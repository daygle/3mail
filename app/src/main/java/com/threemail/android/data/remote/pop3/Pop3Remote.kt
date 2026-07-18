package com.threemail.android.data.remote.pop3

import com.threemail.android.data.remote.MailRemote
import com.threemail.android.data.remote.MessageBody
import com.threemail.android.data.remote.OutgoingMessage
import com.threemail.android.data.remote.RemoteCapabilities
import com.threemail.android.data.remote.RemoteFetch
import com.threemail.android.domain.model.Attachment
import com.threemail.android.domain.model.MailFolder
import com.threemail.android.domain.model.MailMessage
import java.io.File

/**
 * [MailRemote] adapter over [Pop3Client]. POP3's limitations are surfaced here
 * as deliberate no-ops so the rest of the app can treat a POP3 account through
 * the same interface as IMAP/Gmail:
 *  - only INBOX exists ([fetchFolders] returns a single folder);
 *  - read/star flags have no server representation ([setSeen]/[setFlagged] are
 *    local-only successes);
 *  - there is nowhere to move a message, so [move] falls back to delete;
 *  - drafts can't be appended server-side ([appendDraft] is a no-op success).
 */
class Pop3Remote(private val client: Pop3Client) : MailRemote {

    private fun number(message: MailMessage): Long = message.remoteId.toLongOrNull() ?: message.uid

    override suspend fun testConnection(): Result<RemoteCapabilities> = client.testConnection()

    override suspend fun fetchFolders(): Result<List<MailFolder>> =
        Result.success(listOf(client.inboxFolder()))

    override suspend fun fetchMessages(folder: MailFolder, sinceCursor: Long, limit: Int): Result<RemoteFetch> =
        client.fetchMessagesSince(sinceCursor, limit)

    override suspend fun fetchBody(folder: MailFolder, message: MailMessage): Result<MessageBody> =
        client.fetchBody(number(message))

    // POP3 has no server-side flags; the local Room write in MailActions is the
    // source of truth, so report success without a server round-trip.
    override suspend fun setSeen(folder: MailFolder, message: MailMessage, seen: Boolean): Result<Unit> =
        Result.success(Unit)

    override suspend fun setFlagged(folder: MailFolder, message: MailMessage, flagged: Boolean): Result<Unit> =
        Result.success(Unit)

    override suspend fun delete(folder: MailFolder, message: MailMessage, trash: MailFolder?): Result<Unit> =
        client.deleteMessage(number(message))

    // No folders to move between on POP3; deleting is the closest equivalent.
    override suspend fun move(from: MailFolder, message: MailMessage, to: MailFolder): Result<Unit> =
        client.deleteMessage(number(message))

    override suspend fun downloadAttachment(
        folder: MailFolder,
        message: MailMessage,
        attachment: Attachment,
        dest: File
    ): Result<File> = client.downloadAttachment(number(message), attachment.fileName, dest)

    override suspend fun send(message: OutgoingMessage): Result<Unit> = client.sendMessage(message)

    // POP3 offers no server-side drafts folder; keep the composer's save path
    // non-disruptive by reporting success (the draft simply isn't stored remotely).
    override suspend fun appendDraft(draftsFolder: MailFolder, message: OutgoingMessage): Result<Unit> =
        Result.success(Unit)
}
