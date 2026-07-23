package com.threemail.android.data.remote.gmail

import android.util.Base64
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException
import com.google.api.services.gmail.Gmail
import com.google.api.services.gmail.model.Draft
import com.google.api.services.gmail.model.Label
import com.google.api.services.gmail.model.MessagePart
import com.google.api.services.gmail.model.ModifyMessageRequest
import com.threemail.android.data.remote.MailRemote
import com.threemail.android.data.remote.MessageBody
import com.threemail.android.data.remote.RemoteCapabilities
import com.threemail.android.data.remote.MimeBuilder
import com.threemail.android.data.remote.OutgoingMessage
import com.threemail.android.data.remote.RemoteFetch
import com.threemail.android.domain.model.Account
import com.threemail.android.domain.model.Attachment
import com.threemail.android.domain.model.EmailAddress
import com.threemail.android.domain.model.FolderType
import com.threemail.android.domain.model.MailFolder
import com.threemail.android.domain.model.MailMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import javax.mail.internet.InternetAddress
import com.google.api.services.gmail.model.Message as GmailMessage

/**
 * [MailRemote] backed by the native Gmail REST API. Unlike the IMAP path this
 * gives real Gmail labels (as folders) and server-side thread ids, and performs
 * read/star/delete/move as label mutations.
 */
class GmailRemote(
    private val account: Account,
    private val apiClient: GmailApiClient
) : MailRemote {

    private companion object {
        const val USER = "me"
        val METADATA_HEADERS = listOf("Subject", "From", "To", "Cc", "Date", "Message-ID")
    }

    private fun service(): Gmail = apiClient.buildService(account)

    private suspend fun <T> gmail(block: (Gmail) -> T): Result<T> = withContext(Dispatchers.IO) {
        try {
            Result.success(block(service()))
        } catch (e: UserRecoverableAuthIOException) {
            throw RecoverableAuthException(e.intent, "Google consent required for mail access")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Gmail authenticates via OAuth XOAUTH2 against the REST API - it never
     * goes through IMAP, so there is no CAPABILITY list to read here. We
     * return success with an empty capabilities list to signal "no IMAP
     * TLS upgrade possible" without making the upstream caller's
     * auto-upgrade logic Gmail-aware.
     */
    override suspend fun testConnection(): Result<RemoteCapabilities> =
        gmail { it.users().getProfile(USER).execute() }.map { RemoteCapabilities(emptyList()) }

    override suspend fun fetchFolders(): Result<List<MailFolder>> = gmail { svc ->
        val labels = svc.users().labels().list(USER).execute().labels ?: emptyList<Label>()
        labels.map { label ->
            MailFolder(
                accountId = account.id,
                serverId = label.id,
                name = displayName(label),
                type = labelType(label.id)
            )
        }
    }

    override suspend fun fetchMessages(folder: MailFolder, sinceCursor: Long, limit: Int): Result<RemoteFetch> =
        gmail { svc ->
            val request = svc.users().messages().list(USER)
                .setLabelIds(listOf(folder.serverId))
                .setMaxResults(limit.toLong())
            if (sinceCursor > 0) request.q = "after:${sinceCursor / 1000}"
            val refs = request.execute().messages ?: emptyList<GmailMessage>()

            val messages = refs.mapNotNull { ref ->
                runCatching {
                    val full = svc.users().messages().get(USER, ref.id)
                        .setFormat("metadata")
                        .setMetadataHeaders(METADATA_HEADERS)
                        .execute()
                    toHeaderMessage(full)
                }.getOrNull()
            }
            val nextCursor = messages.maxOfOrNull { it.date } ?: sinceCursor
            RemoteFetch(messages, nextCursor)
        }

    override suspend fun fetchBody(folder: MailFolder, message: MailMessage): Result<MessageBody> = gmail { svc ->
        val full = svc.users().messages().get(USER, message.remoteId).setFormat("full").execute()
        val html = StringBuilder()
        val plain = StringBuilder()
        val attachments = mutableListOf<Attachment>()
        full.payload?.let { collect(it, html, plain, attachments) }
        MessageBody(
            html = html.toString().takeIf { it.isNotBlank() },
            plain = plain.toString().takeIf { it.isNotBlank() },
            attachments = attachments
        )
    }

    override suspend fun fetchRawHeaders(folder: MailFolder, message: MailMessage): Result<String> = gmail { svc ->
        val meta = svc.users().messages().get(USER, message.remoteId).setFormat("metadata").execute()
        meta.payload?.headers
            ?.joinToString("\r\n") { "${it.name}: ${it.value}" }
            ?: ""
    }

    override suspend fun setSeen(folder: MailFolder, message: MailMessage, seen: Boolean): Result<Unit> =
        gmail { svc ->
            val req = ModifyMessageRequest().apply {
                if (seen) removeLabelIds = listOf("UNREAD") else addLabelIds = listOf("UNREAD")
            }
            svc.users().messages().modify(USER, message.remoteId, req).execute()
        }.map { }

    override suspend fun setFlagged(folder: MailFolder, message: MailMessage, flagged: Boolean): Result<Unit> =
        gmail { svc ->
            val req = ModifyMessageRequest().apply {
                if (flagged) addLabelIds = listOf("STARRED") else removeLabelIds = listOf("STARRED")
            }
            svc.users().messages().modify(USER, message.remoteId, req).execute()
        }.map { }

    override suspend fun delete(folder: MailFolder, message: MailMessage, trash: MailFolder?): Result<Unit> =
        gmail { svc ->
            if (folder.type == FolderType.TRASH || trash == null) {
                // Permanent delete (also used when emptying trash).
                svc.users().messages().delete(USER, message.remoteId).execute()
            } else {
                svc.users().messages().trash(USER, message.remoteId).execute()
            }
        }.map { }

    override suspend fun move(from: MailFolder, message: MailMessage, to: MailFolder): Result<Unit> =
        gmail { svc ->
            val req = ModifyMessageRequest().apply {
                addLabelIds = listOf(to.serverId)
                removeLabelIds = listOf(from.serverId)
            }
            svc.users().messages().modify(USER, message.remoteId, req).execute()
        }.map { }

    override suspend fun downloadAttachment(
        folder: MailFolder,
        message: MailMessage,
        attachment: Attachment,
        dest: File
    ): Result<File> = gmail { svc ->
        val attachmentId = attachment.remoteId ?: throw IllegalStateException("Missing attachment id")
        val body = svc.users().messages().attachments().get(USER, message.remoteId, attachmentId).execute()
        val bytes = Base64.decode(body.data, Base64.URL_SAFE or Base64.NO_WRAP)
        dest.parentFile?.mkdirs()
        dest.outputStream().use { it.write(bytes) }
        dest
    }

    override suspend fun send(message: OutgoingMessage): Result<Unit> = gmail { svc ->
        svc.users().messages().send(USER, GmailMessage().setRaw(rawOf(message))).execute()
    }.map { }

    override suspend fun sendRaw(messageBytes: ByteArray): Result<Unit> = gmail { svc ->
        val raw = android.util.Base64.encodeToString(
            messageBytes,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
        )
        svc.users().messages().send(USER, GmailMessage().setRaw(raw)).execute()
    }.map { }

    override suspend fun appendDraft(draftsFolder: MailFolder, message: OutgoingMessage): Result<Unit> = gmail { svc ->
        val draft = Draft().setMessage(GmailMessage().setRaw(rawOf(message)))
        svc.users().drafts().create(USER, draft).execute()
    }.map { }

    // region helpers

    private fun rawOf(message: OutgoingMessage): String {
        val mime = MimeBuilder.build(account.email, account.displayName, message)
        val out = ByteArrayOutputStream()
        mime.writeTo(out)
        return Base64.encodeToString(out.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun toHeaderMessage(msg: GmailMessage): MailMessage {
        val headers = msg.payload?.headers.orEmpty().associate { it.name.lowercase() to it.value }
        val labelIds = msg.labelIds ?: emptyList()
        return MailMessage(
            accountId = account.id,
            folderId = 0,
            messageId = headers["message-id"] ?: msg.id,
            threadId = msg.threadId,
            subject = headers["subject"] ?: "(no subject)",
            from = parseAddresses(headers["from"]),
            to = parseAddresses(headers["to"]),
            cc = parseAddresses(headers["cc"]),
            date = msg.internalDate ?: System.currentTimeMillis(),
            bodyPreview = msg.snippet.orEmpty(),
            isRead = !labelIds.contains("UNREAD"),
            isStarred = labelIds.contains("STARRED"),
            isDraft = labelIds.contains("DRAFT"),
            remoteId = msg.id
        )
    }

    private fun collect(
        part: MessagePart,
        html: StringBuilder,
        plain: StringBuilder,
        attachments: MutableList<Attachment>
    ) {
        val filename = part.filename
        val mimeType = part.mimeType.orEmpty()
        when {
            !filename.isNullOrBlank() -> {
                val attachmentId = part.body?.attachmentId
                attachments.add(
                    Attachment(
                        fileName = filename,
                        mimeType = mimeType.substringBefore(";").ifBlank { "application/octet-stream" },
                        size = part.body?.size?.toLong() ?: 0L,
                        remoteId = attachmentId
                    )
                )
            }
            mimeType == "text/html" -> part.body?.data?.let { html.append(decode(it)) }
            mimeType == "text/plain" -> part.body?.data?.let { plain.append(decode(it)) }
            part.parts != null -> part.parts.forEach { collect(it, html, plain, attachments) }
        }
    }

    private fun decode(data: String): String =
        runCatching { String(Base64.decode(data, Base64.URL_SAFE or Base64.NO_WRAP)) }.getOrDefault("")

    private fun parseAddresses(header: String?): List<EmailAddress> {
        if (header.isNullOrBlank()) return emptyList()
        return runCatching {
            InternetAddress.parse(header, false).map {
                EmailAddress(name = it.personal ?: "", address = it.address ?: it.toString())
            }
        }.getOrDefault(emptyList())
    }

    private fun displayName(label: Label): String {
        val name = label.name ?: label.id
        // System labels come back upper-cased (e.g. "INBOX"); title-case them.
        return if (label.id == name && name == name.uppercase()) {
            name.lowercase().replaceFirstChar { it.uppercase() }
        } else name
    }

    private fun labelType(labelId: String): FolderType = when (labelId.uppercase()) {
        "INBOX" -> FolderType.INBOX
        "SENT" -> FolderType.SENT
        "DRAFT" -> FolderType.DRAFTS
        "TRASH" -> FolderType.TRASH
        "SPAM" -> FolderType.SPAM
        "STARRED" -> FolderType.STARRED
        "IMPORTANT" -> FolderType.IMPORTANT
        "ALL_MAIL" -> FolderType.ALL_MAIL
        else -> FolderType.CUSTOM
    }

    // endregion
}
