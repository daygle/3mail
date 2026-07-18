package com.threemail.android.data.remote.pop3

import com.threemail.android.data.remote.MessageBody
import com.threemail.android.data.remote.MimeBuilder
import com.threemail.android.data.remote.MimeParsing
import com.threemail.android.data.remote.toEmailAddress
import com.threemail.android.data.remote.OutgoingMessage
import com.threemail.android.data.remote.RemoteCapabilities
import com.threemail.android.data.remote.RemoteFetch
import com.threemail.android.domain.model.Account
import com.threemail.android.domain.model.Attachment
import com.threemail.android.domain.model.FolderType
import com.threemail.android.domain.model.MailFolder
import com.threemail.android.domain.model.MailMessage
import com.threemail.android.domain.model.Security
import com.threemail.android.util.ThreadUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Properties
import javax.mail.AuthenticationFailedException
import javax.mail.Flags
import javax.mail.Folder
import javax.mail.Message
import javax.mail.MessagingException
import javax.mail.Session
import javax.mail.Store
import javax.mail.internet.MimeMessage

/**
 * POP3 transport. Unlike IMAP, POP3 has no server-side folders (only the
 * inbox), no server-side flags (read / star are local-only), and no push
 * (IDLE). Outgoing mail still goes over SMTP, shared with the IMAP path via the
 * same [MimeBuilder] + JavaMail transport plumbing.
 *
 * Message addressing: POP3 has no stable numeric UID, so messages are addressed
 * by their 1-based folder message number and the incremental cursor is the
 * highest number seen. This is correct for the common single-client POP3 setup
 * (numbers are stable until a message is deleted); a message deleted directly
 * on the server from another client can shift numbers, which is an accepted
 * POP3 limitation.
 */
class Pop3Client(private val account: Account) {

    private val protocol: String = if (account.security == Security.SSL_TLS) "pop3s" else "pop3"

    @Volatile
    private var _session: Session? = null

    private fun getSession(): Session =
        _session ?: synchronized(this) { _session ?: createSession().also { _session = it } }

    private fun createSession(): Session {
        val isSsl = account.security == Security.SSL_TLS
        val isStartTls = account.security == Security.STARTTLS
        val smtpStartTls = account.security != Security.NONE
        val props = Properties().apply {
            setProperty("mail.store.protocol", protocol)
            setProperty("mail.$protocol.host", incomingServer())
            setProperty("mail.$protocol.port", account.incomingPort.toString())
            setProperty("mail.$protocol.ssl.enable", isSsl.toString())
            setProperty("mail.pop3.starttls.enable", isStartTls.toString())
            if (isStartTls) setProperty("mail.pop3.starttls.required", "true")
            setProperty("mail.$protocol.ssl.checkserveridentity", "true")
            setProperty("mail.$protocol.connectiontimeout", "15000")
            setProperty("mail.$protocol.timeout", "15000")

            // SMTP submission (identical policy to the IMAP client).
            setProperty("mail.smtp.auth", "true")
            setProperty("mail.smtp.starttls.enable", smtpStartTls.toString())
            if (smtpStartTls) setProperty("mail.smtp.starttls.required", "true")
            setProperty("mail.smtp.host", smtpServer())
            setProperty("mail.smtp.port", account.outgoingPort.toString())
            setProperty("mail.smtp.ssl.checkserveridentity", "true")
            setProperty("mail.smtp.connectiontimeout", "15000")
            setProperty("mail.smtp.timeout", "15000")
        }
        return Session.getInstance(props)
    }

    private fun incomingServer(): String = account.incomingServer?.takeIf { it.isNotBlank() }
        ?: when {
            account.email.endsWith("@gmail.com") -> "pop.gmail.com"
            account.email.endsWith("@outlook.com") || account.email.endsWith("@hotmail.com") -> "outlook.office365.com"
            else -> throw IllegalArgumentException("No POP3 server configured for ${account.email}")
        }

    private fun smtpServer(): String = account.outgoingServer?.takeIf { it.isNotBlank() } ?: when {
        account.email.endsWith("@gmail.com") -> "smtp.gmail.com"
        account.email.endsWith("@outlook.com") || account.email.endsWith("@hotmail.com") -> "smtp.office365.com"
        else -> incomingServer().replace("pop", "smtp")
    }

    private fun password(): String =
        account.password ?: throw IllegalStateException("POP3 account requires password")

    private fun connectStore(): Store = getSession().getStore(protocol).apply {
        connect(incomingServer(), account.email, password())
    }

    /** Opens INBOX in [mode], runs [block], and always closes cleanly. */
    private suspend fun <T> withInbox(mode: Int, block: (Folder) -> T): T = withContext(Dispatchers.IO) {
        val store = connectStore()
        try {
            val inbox = store.getFolder("INBOX")
            inbox.open(mode)
            try {
                block(inbox)
            } finally {
                // close(true) expunges DELETED messages; close(false) leaves them.
                runCatching { inbox.close(mode == Folder.READ_WRITE) }
            }
        } finally {
            runCatching { store.close() }
        }
    }

    suspend fun testConnection(): Result<RemoteCapabilities> = withContext(Dispatchers.IO) {
        try {
            connectStore().use { /* connect handshake only */ }
            Result.success(RemoteCapabilities(emptyList()))
        } catch (e: AuthenticationFailedException) {
            Result.failure(e)
        } catch (e: MessagingException) {
            Result.failure(e)
        }
    }

    /** POP3 exposes a single INBOX; there are no server folders to enumerate. */
    fun inboxFolder(): MailFolder = MailFolder(
        accountId = account.id,
        serverId = "INBOX",
        name = "Inbox",
        type = FolderType.INBOX
    )

    /**
     * Fetches messages whose 1-based number is greater than [sinceNumber],
     * capped to the most recent [limit]. Returns the new messages plus the
     * highest number observed as the next cursor.
     */
    suspend fun fetchMessagesSince(sinceNumber: Long, limit: Int = 100): Result<RemoteFetch> =
        try {
            withInbox(Folder.READ_ONLY) { inbox ->
                val count = inbox.messageCount
                if (count == 0 || sinceNumber >= count) {
                    return@withInbox RemoteFetch(emptyList(), maxOf(sinceNumber, count.toLong()))
                }
                val start = maxOf((sinceNumber + 1).toInt(), count - limit + 1, 1)
                val messages = inbox.getMessages(start, count)
                val mapped = messages.map { it.toHeaderMessage() }
                RemoteFetch(mapped, count.toLong())
            }.let { Result.success(it) }
        } catch (e: MessagingException) {
            Result.failure(e)
        }

    /** Downloads the full body (POP3 always retrieves the whole message). */
    suspend fun fetchBody(number: Long): Result<MessageBody> =
        try {
            withInbox(Folder.READ_ONLY) { inbox ->
                val msg = messageByNumber(inbox, number) ?: return@withInbox MessageBody(null, null, emptyList())
                val html = StringBuilder()
                val plain = StringBuilder()
                val attachments = mutableListOf<Attachment>()
                MimeParsing.extractParts(msg, html, plain, attachments)
                MessageBody(
                    html = html.toString().takeIf { it.isNotBlank() },
                    plain = plain.toString().takeIf { it.isNotBlank() },
                    attachments = attachments
                )
            }.let { Result.success(it) }
        } catch (e: MessagingException) {
            Result.failure(e)
        }

    suspend fun downloadAttachment(number: Long, fileName: String, destFile: File): Result<File> =
        try {
            withInbox(Folder.READ_ONLY) { inbox ->
                val msg = messageByNumber(inbox, number) ?: throw MessagingException("Message not found")
                val part = MimeParsing.findAttachmentPart(msg, fileName)
                    ?: throw MessagingException("Attachment '$fileName' not found")
                destFile.parentFile?.mkdirs()
                part.inputStream.use { input ->
                    destFile.outputStream().use { output -> input.copyTo(output) }
                }
                destFile
            }.let { Result.success(it) }
        } catch (e: Exception) {
            Result.failure(e)
        }

    /** Deletes a message: flag DELETED, then expunge on folder close. */
    suspend fun deleteMessage(number: Long): Result<Unit> =
        try {
            withInbox(Folder.READ_WRITE) { inbox ->
                messageByNumber(inbox, number)?.setFlag(Flags.Flag.DELETED, true)
            }
            Result.success(Unit)
        } catch (e: MessagingException) {
            Result.failure(e)
        }

    suspend fun sendMessage(message: OutgoingMessage): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val mime = MimeBuilder.build(account.email, account.displayName, message)
            val transport = getSession().getTransport("smtp")
            try {
                transport.connect(smtpServer(), account.outgoingPort, account.email, password())
                transport.sendMessage(mime, mime.allRecipients)
            } finally {
                runCatching { transport.close() }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun messageByNumber(inbox: Folder, number: Long): Message? {
        val n = number.toInt()
        if (n < 1 || n > inbox.messageCount) return null
        return inbox.getMessage(n)
    }

    private fun Message.toHeaderMessage(): MailMessage {
        val mime = this as? MimeMessage
        val messageIdHeader = mime?.messageID
        val references = mime?.getHeader("References")?.joinToString(" ")
        val inReplyTo = mime?.getHeader("In-Reply-To")?.joinToString(" ")
        val number = messageNumber.toLong()
        return MailMessage(
            accountId = account.id,
            folderId = 0,
            messageId = messageIdHeader ?: messageNumber.toString(),
            threadId = ThreadUtil.deriveThreadId(messageIdHeader, references, inReplyTo),
            subject = subject ?: "(no subject)",
            from = from?.map { it.toEmailAddress() } ?: emptyList(),
            to = getRecipients(Message.RecipientType.TO)?.map { it.toEmailAddress() } ?: emptyList(),
            cc = getRecipients(Message.RecipientType.CC)?.map { it.toEmailAddress() } ?: emptyList(),
            bcc = getRecipients(Message.RecipientType.BCC)?.map { it.toEmailAddress() } ?: emptyList(),
            date = sentDate?.time ?: receivedDate?.time ?: System.currentTimeMillis(),
            // POP3 has no server-side flags; read / star are tracked locally.
            isRead = false,
            isStarred = false,
            isDraft = false,
            uid = number,
            remoteId = number.toString()
        )
    }

    private companion object {
        private const val TAG = "Pop3Client"
    }
}
