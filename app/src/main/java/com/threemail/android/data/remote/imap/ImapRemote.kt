package com.threemail.android.data.remote.imap

import com.threemail.android.data.remote.MailRemote
import com.threemail.android.data.remote.MessageBody
import com.threemail.android.data.remote.RemoteCapabilities
import com.threemail.android.data.remote.OutgoingMessage
import com.threemail.android.data.remote.RemoteFetch
import com.threemail.android.domain.model.Attachment
import com.threemail.android.domain.model.MailFolder
import com.threemail.android.domain.model.MailMessage
import java.io.File

/** [MailRemote] adapter over the lower-level [ImapClient]. */
class ImapRemote(private val client: ImapClient) : MailRemote {

    private fun uid(message: MailMessage): Long = message.remoteId.toLongOrNull() ?: message.uid

    override suspend fun testConnection(): Result<RemoteCapabilities> = client.testConnection()

    override suspend fun fetchFolders(): Result<List<MailFolder>> = client.fetchFolders()

    override suspend fun fetchMessages(folder: MailFolder, sinceCursor: Long, limit: Int): Result<RemoteFetch> =
        client.fetchMessagesSince(folder.serverId, sinceCursor, limit)

    override suspend fun fetchBody(folder: MailFolder, message: MailMessage): Result<MessageBody> =
        client.fetchBody(folder.serverId, uid(message))

    override suspend fun setSeen(folder: MailFolder, message: MailMessage, seen: Boolean): Result<Unit> =
        client.setSeen(folder.serverId, uid(message), seen)

    override suspend fun setFlagged(folder: MailFolder, message: MailMessage, flagged: Boolean): Result<Unit> =
        client.setFlagged(folder.serverId, uid(message), flagged)

    override suspend fun delete(folder: MailFolder, message: MailMessage, trash: MailFolder?): Result<Unit> =
        client.deleteMessage(folder.serverId, uid(message), trash?.serverId)

    override suspend fun move(from: MailFolder, message: MailMessage, to: MailFolder): Result<Unit> =
        client.moveMessage(from.serverId, uid(message), to.serverId)

    override suspend fun downloadAttachment(
        folder: MailFolder,
        message: MailMessage,
        attachment: Attachment,
        dest: File
    ): Result<File> = client.downloadAttachment(folder.serverId, uid(message), attachment.fileName, dest)

    override suspend fun send(message: OutgoingMessage): Result<Unit> = client.sendMessage(message)

    override suspend fun appendDraft(draftsFolder: MailFolder, message: OutgoingMessage): Result<Unit> =
        client.appendDraft(draftsFolder.serverId, message)

    override suspend fun setSubscribed(folder: MailFolder, subscribed: Boolean): Result<Unit> =
        client.setSubscribed(folder.serverId, subscribed)
}
