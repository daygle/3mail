package com.threemail.android.data.remote

import com.threemail.android.domain.model.Attachment
import com.threemail.android.domain.model.MailFolder
import com.threemail.android.domain.model.MailMessage
import java.io.File

/**
 * Provider-agnostic mail operations. Implemented by [com.threemail.android.data.remote.imap.ImapRemote]
 * for IMAP/SMTP accounts and [com.threemail.android.data.remote.gmail.GmailRemote] for Gmail
 * accounts (native Gmail REST API). Call sites work against this interface so the
 * rest of the app never branches on account type.
 *
 * The [sinceCursor] passed to [fetchMessages] is a monotonic value each provider
 * defines for itself (IMAP: highest UID; Gmail: latest internalDate in millis) and
 * round-trips through [RemoteFetch.nextCursor].
 */
interface MailRemote {

    /**
     * Probe the account's mail server. Returns the parsed CAPABILITY list
     * alongside a connect-success / connect-failure signal so callers can
     * honor opportunistic TLS upgrades (e.g. [com.threemail.android.ui.screens.account.AddAccountViewModel]
     * auto-bumps Security.NONE -> Security.STARTTLS when STARTTLS is in
     * the banner).
     *
     * Result.success is returned iff the connect handshake itself succeeded;
     * the inner `capabilities` list may still be empty when the server did
     * not advertise its capabilities.
     */
    suspend fun testConnection(): Result<RemoteCapabilities>

    suspend fun fetchFolders(): Result<List<MailFolder>>

    suspend fun fetchMessages(folder: MailFolder, sinceCursor: Long, limit: Int): Result<RemoteFetch>

    suspend fun fetchBody(folder: MailFolder, message: MailMessage): Result<MessageBody>

    /**
     * Fetch the message's full raw RFC 5322 header block as text (one
     * "Name: value" line per header). Default returns an empty string for any
     * transport that can't supply it, so callers can invoke it uniformly.
     */
    suspend fun fetchRawHeaders(folder: MailFolder, message: MailMessage): Result<String> =
        Result.success("")

    suspend fun setSeen(folder: MailFolder, message: MailMessage, seen: Boolean): Result<Unit>

    suspend fun setFlagged(folder: MailFolder, message: MailMessage, flagged: Boolean): Result<Unit>

    suspend fun delete(folder: MailFolder, message: MailMessage, trash: MailFolder?): Result<Unit>

    suspend fun move(from: MailFolder, message: MailMessage, to: MailFolder): Result<Unit>

    suspend fun downloadAttachment(
        folder: MailFolder,
        message: MailMessage,
        attachment: Attachment,
        dest: File
    ): Result<File>

    suspend fun send(message: OutgoingMessage): Result<Unit>

    /**
     * Send a pre-built RFC 5322 wire-format message (e.g. a
     * multipart/encrypted envelope from
     * [com.threemail.android.data.crypto.MailPgpOutbound]). Bypasses
     * [com.threemail.android.data.remote.MimeBuilder.build] entirely -
     * the caller already has the full wire form, so we just parse +
     * send. Used by the opportunistic-encryption send path.
     */
    suspend fun sendRaw(messageBytes: ByteArray): Result<Unit>

    suspend fun appendDraft(draftsFolder: MailFolder, message: OutgoingMessage): Result<Unit>

    /**
     * Subscribe / unsubscribe a folder on the server (IMAP LSUB). Controls
     * whether other clients list the folder by default. No-op success for
     * transports without a subscription concept (Gmail REST, POP3), so callers
     * can invoke it uniformly.
     */
    suspend fun setSubscribed(folder: MailFolder, subscribed: Boolean): Result<Unit> = Result.success(Unit)
}
