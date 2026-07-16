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

    suspend fun appendDraft(draftsFolder: MailFolder, message: OutgoingMessage): Result<Unit>
}
