package com.threemail.android.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.threemail.android.data.crypto.MailPgpOutbound
import com.threemail.android.data.local.dao.MessageFlagDao
import com.threemail.android.data.local.entity.MessageFlagEntity
import com.threemail.android.data.remote.MailRemoteFactory
import com.threemail.android.data.remote.MimeBuilder
import com.threemail.android.data.remote.gmail.RecoverableAuthException
import com.threemail.android.data.repository.AccountRepository
import com.threemail.android.data.repository.OutboxRepository
import com.threemail.android.domain.model.Account
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.mail.Message
import javax.mail.Session
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart
import javax.mail.util.ByteArrayDataSource

/**
 * Drains the outbox queue. Runs whenever Compose enqueues a message (and on
 * WorkManager's exponential backoff after a failure), so a send survives
 * network loss and process death instead of being lost when the immediate call
 * fails.
 *
 * Each message is deleted on success. On failure the attempt is recorded and
 * the worker asks to be retried, unless the message has exceeded [MAX_ATTEMPTS]
 * - at which point it stays in the outbox with its last error rather than
 * retrying forever (e.g. a permanently bad recipient).
 *
 * # Opportunistic encryption
 *
 * The dispatcher delegates to [MailPgpOutbound] before each send. Decision
 * rule is **strict**: encryption is the chosen path only when **every**
 * recipient's public key has been resolved (cache or WKD). If `unresolvable`
 * is non-empty we fall back to plaintext because a single multipart/encrypted
 * wire body has no way to address the unreadable recipient - sending
 * ciphertext to a group where one member lacks the key would silently lock
 * them out of the thread, so the cryptographic-correctness answer is
 * "all-or-nothing, fall back to plaintext when any one is unresolvable".
 *
 * On a successful encrypted send the Message-ID of the outgoing message is
 * recorded in [MessageFlagDao] so the Sent folder row carries the "Sent
 * encrypted" badge even after a subsequent REPLACE-style server sync re-fetches
 * the same row.
 *
 * Manually constructed by [ThreeMailWorkerFactory]. See that class doc for
 * the rationale (androidx.hilt 1.4.0 silently skips generating @HiltWorker
 * AssistedFactory bindings under KSP2).
 */
class SendMailWorker(
    context: Context,
    params: WorkerParameters,
    private val outboxRepository: OutboxRepository,
    private val accountRepository: AccountRepository,
    private val mailRemoteFactory: MailRemoteFactory,
    private val mailPgpOutbound: MailPgpOutbound,
    private val messageFlagDao: MessageFlagDao
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val pending = outboxRepository.pending()
        if (pending.isEmpty()) return Result.success()

        var shouldRetry = false
        for (entry in pending) {
            val account = accountRepository.getAccountById(entry.accountId)
            if (account == null) {
                // The account was removed while this message sat in the queue;
                // drop the orphan so it doesn't block the queue forever.
                outboxRepository.delete(entry.id)
                continue
            }

            val error: String? = try {
                val result = sendOutgoing(account, entry)
                if (result.isSuccess) {
                    outboxRepository.delete(entry.id)
                    null
                } else {
                    result.exceptionOrNull()?.message ?: "Send failed"
                }
            } catch (e: RecoverableAuthException) {
                // Needs interactive re-auth we can't perform from a worker; leave
                // it queued and let a later foreground re-auth unblock it.
                "Authentication required"
            } catch (e: Exception) {
                e.message ?: "Send failed"
            }

            if (error != null) {
                val attempts = entry.attemptCount + 1
                outboxRepository.recordFailure(entry.id, attempts, error)
                if (attempts < MAX_ATTEMPTS) shouldRetry = true
            }
        }

        return if (shouldRetry) Result.retry() else Result.success()
    }

    /**
     * Decide between encrypted (strict, all-recipients-resolved) and
     * plaintext paths for a single outbox entry. Carries the
     * opportunistic-encryption logic out of [doWork] so the loop above
     * stays flat.
     *
     * The encrypted path:
     *  1. Build the plaintext MimeMessage via [MimeBuilder] so headers
     *     (From, To, Subject, Message-ID, Date, etc.) are generated exactly
     *     as the plaintext path would, then serialize to bytes.
     *  2. Feed those bytes to [MailPgpOutbound.compose] which signs +
     *     encrypts the body and wraps the cipher in a
     *     `multipart/encrypted` envelope.
     *  3. Re-wrap the envelope in an outer MimeMessage so the wire form
     *     is a fully-qualified RFC 5322 message (SMTP `MAIL FROM` /
     *     `RCPT TO` derive from `From` / `To` headers; Gmail REST needs
     *     those headers too).
     *  4. Send via [com.threemail.android.data.remote.MailRemote.sendRaw].
     *  5. On success, write a [MessageFlagEntity] row keyed by the
     *     outgoing `Message-ID` so the Sent-folder cache flags the row
     *     encrypted even after REPLACE from the next server sync.
     */
    private suspend fun sendOutgoing(account: Account, entry: OutboxEntry): Result<Unit> {
        val plaintextMime = MimeBuilder.build(account.email, account.displayName, entry.message)
        val plaintextBytes = ByteArrayOutputStream().also { plaintextMime.writeTo(it) }.toByteArray()

        val recipients = buildList {
            entry.message.to.forEach { add(it.address) }
            entry.message.cc.forEach { add(it.address) }
            entry.message.bcc.forEach { add(it.address) }
        }
        val outcome = mailPgpOutbound.compose(account.id, plaintextBytes, recipients).getOrElse {
            return Result.failure(it)
        }
        val envelopeBytes = outcome.envelopeBytes
        // Strict-mode is "all-or-nothing". Both `unresolvable` (we
        // couldn't FIND a key for this address) and `unparseable` (we
        // FOUND keydata but it didn't decode) block the encrypted
        // path: failing the second case would silently shrink the
        // wire body's recipient set, which is the cryptographic
        // correctness issue the strict rule is meant to prevent.
        val allResolved = outcome.unresolvable.isEmpty() && outcome.unparseable.isEmpty()

        val remote = mailRemoteFactory.create(account)
        return if (envelopeBytes != null && allResolved) {
            // Strict-mode encrypted path.
            val outerMime = buildOuterEncryptedMime(plaintextMime, envelopeBytes)
            val outerBytes = ByteArrayOutputStream().also { outerMime.writeTo(it) }.toByteArray()
            val result = remote.sendRaw(outerBytes)
            val outgoingMessageId = plaintextMime.messageID
                ?.trim()?.removePrefix("<")?.removeSuffix(">")?.orEmpty()
            if (result.isSuccess && outgoingMessageId.isNotBlank()) {
                runCatching {
                    messageFlagDao.insertOrIgnore(
                        MessageFlagEntity(
                            accountId = account.id,
                            messageId = outgoingMessageId,
                            isEncrypted = true
                        )
                    )
                }
            }
            result
        } else {
            // Plaintext fallback: any unresolved recipient -> can't form a
            // single wire body that works for everyone, so send unencrypted.
            remote.send(entry.message)
        }
    }

    /**
     * Build the outer RFC 5322 MimeMessage that wraps the
     * `multipart/encrypted` envelope produced by [MailPgpOutbound]. We
     * carry over the From / To / Subject / Date / Message-ID / threading
     * headers from the plaintext MimeMessage so the envelope has the
     * right SMTP envelope + recipient routing; everything else below
     * the headers comes from the encrypted envelope verbatim.
     *
     * Uses [javax.mail.util.ByteArrayDataSource] to parse the
     * multipart boundary / part headers so we don't have to re-implement
     * MIME parsing by hand.
     */
    private fun buildOuterEncryptedMime(plaintextMime: MimeMessage, envelopeBytes: ByteArray): MimeMessage {
        val session = Session.getInstance(java.util.Properties())
        val outer = MimeMessage(session)
        // Carry From + recipients + threading headers verbatim from the
        // plaintext so the SMTP MAIL FROM / RCPT TO derive correctly.
        plaintextMime.from?.firstOrNull()?.let { outer.setFrom(it as InternetAddress) }
        plaintextMime.getRecipients(Message.RecipientType.TO)?.let {
            outer.setRecipients(Message.RecipientType.TO, it)
        }
        plaintextMime.getRecipients(Message.RecipientType.CC)?.let {
            outer.setRecipients(Message.RecipientType.CC, it)
        }
        plaintextMime.getRecipients(Message.RecipientType.BCC)?.let {
            outer.setRecipients(Message.RecipientType.BCC, it)
        }
        outer.subject = plaintextMime.subject
        plaintextMime.getHeader("In-Reply-To")?.firstOrNull()?.let {
            outer.setHeader("In-Reply-To", it)
        }
        plaintextMime.getHeader("References")?.firstOrNull()?.let {
            outer.setHeader("References", it)
        }
        // Preserve Message-ID so the side-table key matches the message
        // the receiver threads us back on. Falls back to whatever
        // JavaMail auto-assigns if the plaintext didn't have one.
        plaintextMime.messageID?.let { outer.setHeader("Message-ID", it) }
        outer.sentDate = plaintextMime.sentDate ?: java.util.Date()
        // Body is the multipart/encrypted envelope. DataSource-based
        // parsing so the inner Content-Type / boundary headers ride
        // through cleanly.
        val dataSource = ByteArrayDataSource(
            ByteArrayInputStream(envelopeBytes),
            "multipart/encrypted; protocol=\"application/pgp-encrypted\""
        )
        outer.setContent(MimeMultipart(dataSource))
        outer.saveChanges()
        return outer
    }

    private companion object {
        const val MAX_ATTEMPTS = 10
    }
}
