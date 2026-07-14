package com.threemail.android.data.remote.imap

import com.sun.mail.imap.IMAPFolder
import com.threemail.android.data.remote.gmail.RecoverableAuthException
import com.threemail.android.domain.model.Account
import com.threemail.android.domain.model.AccountType
import com.threemail.android.domain.model.Attachment
import com.threemail.android.domain.model.EmailAddress
import com.threemail.android.domain.model.FolderType
import com.threemail.android.domain.model.MailFolder
import com.threemail.android.domain.model.MailMessage
import com.threemail.android.util.ThreadUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Date
import java.util.Properties
import javax.activation.DataHandler
import javax.activation.FileDataSource
import javax.mail.Address
import javax.mail.AuthenticationFailedException
import javax.mail.Flags
import javax.mail.Folder
import javax.mail.Message
import javax.mail.MessagingException
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.Session
import javax.mail.Store
import javax.mail.Transport
import javax.mail.UIDFolder
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart

/** Body + attachments extracted from a single message. */
data class MessageBody(
    val html: String?,
    val plain: String?,
    val attachments: List<Attachment>
)

/** Result of an incremental fetch, carrying the highest UID seen for cursoring. */
data class FetchResult(
    val messages: List<MailMessage>,
    val maxUid: Long
)

class ImapClient(
    private val account: Account,
    private val tokenProvider: suspend () -> String? = { null }
) {

    private val session: Session by lazy { createSession() }

    private fun createSession(): Session {
        val isGmail = account.accountType == AccountType.GMAIL
        val props = Properties().apply {
            setProperty("mail.store.protocol", "imaps")
            setProperty("mail.imaps.host", account.incomingServer ?: getDefaultServer())
            setProperty("mail.imaps.port", account.incomingPort.toString())
            setProperty("mail.imaps.ssl.enable", account.useEncryption.toString())
            setProperty("mail.imaps.starttls.enable", "true")
            setProperty("mail.smtp.auth", "true")
            setProperty("mail.smtp.starttls.enable", "true")
            setProperty("mail.smtp.host", getSmtpServer())
            setProperty("mail.smtp.port", getSmtpPort().toString())
            if (isGmail) {
                setProperty("mail.imaps.auth.mechanisms", "XOAUTH2")
                setProperty("mail.smtp.auth.mechanisms", "XOAUTH2")
            }
        }
        return Session.getInstance(props)
    }

    private fun getSmtpServer(): String = when {
        account.email.endsWith("@gmail.com") -> "smtp.gmail.com"
        account.email.endsWith("@outlook.com") || account.email.endsWith("@hotmail.com") -> "smtp.office365.com"
        account.email.endsWith("@icloud.com") -> "smtp.mail.me.com"
        else -> account.incomingServer?.replace("imap", "smtp") ?: "smtp.gmail.com"
    }

    private fun getSmtpPort(): Int = if (account.useEncryption) 587 else 25

    private fun getDefaultServer(): String = when {
        account.email.endsWith("@gmail.com") -> "imap.gmail.com"
        account.email.endsWith("@outlook.com") || account.email.endsWith("@hotmail.com") -> "outlook.office365.com"
        account.email.endsWith("@icloud.com") -> "imap.mail.me.com"
        else -> account.incomingServer ?: throw IllegalArgumentException("Unknown IMAP server for ${account.email}")
    }

    // region connection helpers

    private suspend fun connectStore(): Store {
        val store = session.getStore("imaps")
        val server = account.incomingServer ?: getDefaultServer()
        when (account.accountType) {
            AccountType.GMAIL -> {
                val token = tokenProvider() ?: throw IllegalStateException("Gmail account requires OAuth access token")
                store.connect(server, account.email, token)
            }
            AccountType.IMAP -> {
                val password = account.password ?: throw IllegalStateException("IMAP account requires password")
                store.connect(server, account.email, password)
            }
        }
        return store
    }

    /** Opens [folderServerId] in [mode], runs [block], and always closes cleanly. */
    private suspend fun <T> withFolder(
        folderServerId: String,
        mode: Int,
        block: (IMAPFolder) -> T
    ): T = withContext(Dispatchers.IO) {
        val store = connectStore()
        try {
            val folder = store.getFolder(folderServerId) as IMAPFolder
            if (!folder.isOpen) folder.open(mode)
            try {
                block(folder)
            } finally {
                runCatching { folder.close(mode == Folder.READ_WRITE) }
            }
        } finally {
            runCatching { store.close() }
        }
    }

    // endregion

    suspend fun testConnection(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val store = connectStore()
            runCatching { store.close() }
            Result.success(Unit)
        } catch (e: RecoverableAuthException) {
            throw e
        } catch (e: AuthenticationFailedException) {
            Result.failure(e)
        } catch (e: MessagingException) {
            Result.failure(e)
        }
    }

    suspend fun fetchFolders(): Result<List<MailFolder>> = withContext(Dispatchers.IO) {
        try {
            val store = connectStore()
            try {
                val folders = store.defaultFolder.list("*")
                Result.success(folders.map { folder ->
                    MailFolder(
                        accountId = account.id,
                        serverId = folder.fullName,
                        name = folder.name,
                        type = mapFolderType(folder.name)
                    )
                })
            } finally {
                runCatching { store.close() }
            }
        } catch (e: RecoverableAuthException) {
            throw e
        } catch (e: MessagingException) {
            Result.failure(e)
        }
    }

    suspend fun fetchMessages(folderServerId: String, limit: Int = 50): Result<List<MailMessage>> =
        try {
            withFolder(folderServerId, Folder.READ_ONLY) { folder ->
                val count = folder.messageCount
                if (count == 0) return@withFolder emptyList()
                val start = maxOf(1, count - limit + 1)
                val messages = folder.getMessages(start, count)
                messages.map { it.toHeaderMessage(folder) }
            }.let { Result.success(it) }
        } catch (e: RecoverableAuthException) {
            throw e
        } catch (e: MessagingException) {
            Result.failure(e)
        }

    /**
     * Incrementally fetches messages with UID greater than [sinceUid]. Returns the
     * new messages plus the highest UID observed so callers can persist a cursor.
     */
    suspend fun fetchMessagesSince(folderServerId: String, sinceUid: Long, limit: Int = 100): Result<FetchResult> =
        try {
            withFolder(folderServerId, Folder.READ_ONLY) { folder ->
                val fetched = folder.getMessagesByUID(sinceUid + 1, UIDFolder.LASTUID)
                    ?.filterNotNull()
                    ?.filter { folder.getUID(it) > sinceUid }
                    ?.takeLast(limit)
                    ?: emptyList()
                val mapped = fetched.map { it.toHeaderMessage(folder) }
                val maxUid = mapped.maxOfOrNull { it.uid } ?: sinceUid
                FetchResult(mapped, maxUid)
            }.let { Result.success(it) }
        } catch (e: RecoverableAuthException) {
            throw e
        } catch (e: MessagingException) {
            Result.failure(e)
        }

    /** Fetches the full body (html + plain) and attachment metadata for one message. */
    suspend fun fetchBody(folderServerId: String, uid: Long): Result<MessageBody> =
        try {
            withFolder(folderServerId, Folder.READ_ONLY) { folder ->
                val msg = folder.getMessageByUID(uid) ?: return@withFolder MessageBody(null, null, emptyList())
                val html = StringBuilder()
                val plain = StringBuilder()
                val attachments = mutableListOf<Attachment>()
                extractParts(msg, html, plain, attachments)
                MessageBody(
                    html = html.toString().takeIf { it.isNotBlank() },
                    plain = plain.toString().takeIf { it.isNotBlank() },
                    attachments = attachments
                )
            }.let { Result.success(it) }
        } catch (e: RecoverableAuthException) {
            throw e
        } catch (e: MessagingException) {
            Result.failure(e)
        }

    /** Downloads a single attachment (matched by file name) to [destFile]. */
    suspend fun downloadAttachment(folderServerId: String, uid: Long, fileName: String, destFile: File): Result<File> =
        try {
            withFolder(folderServerId, Folder.READ_ONLY) { folder ->
                val msg = folder.getMessageByUID(uid) ?: throw MessagingException("Message not found")
                val part = findAttachmentPart(msg, fileName)
                    ?: throw MessagingException("Attachment '$fileName' not found")
                destFile.parentFile?.mkdirs()
                part.inputStream.use { input ->
                    destFile.outputStream().use { output -> input.copyTo(output) }
                }
                destFile
            }.let { Result.success(it) }
        } catch (e: RecoverableAuthException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    suspend fun setSeen(folderServerId: String, uid: Long, seen: Boolean): Result<Unit> =
        setFlag(folderServerId, uid, Flags.Flag.SEEN, seen)

    suspend fun setFlagged(folderServerId: String, uid: Long, flagged: Boolean): Result<Unit> =
        setFlag(folderServerId, uid, Flags.Flag.FLAGGED, flagged)

    private suspend fun setFlag(folderServerId: String, uid: Long, flag: Flags.Flag, value: Boolean): Result<Unit> =
        try {
            withFolder(folderServerId, Folder.READ_WRITE) { folder ->
                folder.getMessageByUID(uid)?.setFlag(flag, value)
            }
            Result.success(Unit)
        } catch (e: RecoverableAuthException) {
            throw e
        } catch (e: MessagingException) {
            Result.failure(e)
        }

    /** Moves the message to [trashServerId] if provided, otherwise flags it deleted and expunges. */
    suspend fun deleteMessage(folderServerId: String, uid: Long, trashServerId: String? = null): Result<Unit> {
        if (trashServerId != null && trashServerId != folderServerId) {
            return moveMessage(folderServerId, uid, trashServerId)
        }
        return try {
            withFolder(folderServerId, Folder.READ_WRITE) { folder ->
                folder.getMessageByUID(uid)?.setFlag(Flags.Flag.DELETED, true)
                folder.expunge()
            }
            Result.success(Unit)
        } catch (e: RecoverableAuthException) {
            throw e
        } catch (e: MessagingException) {
            Result.failure(e)
        }
    }

    /**
     * Marks every message in [folderServerId] as DELETED, then expunges the folder.
     * Returns the number of messages expunged (zero when the folder was already empty).
     * Messages are flagged in chunks of [chunkSize] so very large trash folders do not
     * overstay IMAP server command timeouts.
     */
    suspend fun emptyTrashFolder(folderServerId: String, chunkSize: Int = 500): Result<Int> =
        try {
            val expunged = withFolder(folderServerId, Folder.READ_WRITE) { folder ->
                val messages = folder.messages
                var total = 0
                var start = 0
                while (start < messages.size) {
                    val end = minOf(start + chunkSize, messages.size)
                    val chunk = (start until end).map { messages[it] }.toTypedArray()
                    if (chunk.isNotEmpty()) {
                        folder.setFlags(chunk, Flags.Flag.DELETED, true)
                        total += chunk.size
                    }
                    start = end
                }
                if (messages.isNotEmpty()) folder.expunge()
                total
            }
            Result.success(expunged)
        } catch (e: RecoverableAuthException) {
            throw e
        } catch (e: MessagingException) {
            Result.failure(e)
        }

    suspend fun moveMessage(fromServerId: String, uid: Long, toServerId: String): Result<Unit> =
        try {
            withContext(Dispatchers.IO) {
                val store = connectStore()
                try {
                    val source = store.getFolder(fromServerId) as IMAPFolder
                    val dest = store.getFolder(toServerId)
                    source.open(Folder.READ_WRITE)
                    try {
                        val msg = source.getMessageByUID(uid)
                        if (msg != null) {
                            source.copyMessages(arrayOf(msg), dest)
                            msg.setFlag(Flags.Flag.DELETED, true)
                            source.expunge()
                        }
                    } finally {
                        runCatching { source.close(true) }
                    }
                } finally {
                    runCatching { store.close() }
                }
            }
            Result.success(Unit)
        } catch (e: RecoverableAuthException) {
            throw e
        } catch (e: MessagingException) {
            Result.failure(e)
        }

    suspend fun sendMessage(
        to: List<EmailAddress>,
        cc: List<EmailAddress>,
        bcc: List<EmailAddress>,
        subject: String,
        body: String,
        attachments: List<Attachment>,
        inReplyTo: String? = null,
        references: String? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val message = buildMimeMessage(to, cc, bcc, subject, body, attachments, inReplyTo, references)
            val password = credential()
            Transport.send(message, account.email, password)
            Result.success(Unit)
        } catch (e: RecoverableAuthException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Appends a message to the Drafts folder without sending it. */
    suspend fun appendDraft(
        draftsServerId: String,
        to: List<EmailAddress>,
        cc: List<EmailAddress>,
        bcc: List<EmailAddress>,
        subject: String,
        body: String,
        attachments: List<Attachment>
    ): Result<Unit> =
        try {
            withContext(Dispatchers.IO) {
                val store = connectStore()
                try {
                    val drafts = store.getFolder(draftsServerId)
                    if (!drafts.exists()) drafts.create(Folder.HOLDS_MESSAGES)
                    drafts.open(Folder.READ_WRITE)
                    try {
                        val message = buildMimeMessage(to, cc, bcc, subject, body, attachments, null, null).apply {
                            setFlag(Flags.Flag.DRAFT, true)
                        }
                        drafts.appendMessages(arrayOf(message))
                    } finally {
                        runCatching { drafts.close(false) }
                    }
                } finally {
                    runCatching { store.close() }
                }
            }
            Result.success(Unit)
        } catch (e: RecoverableAuthException) {
            throw e
        } catch (e: MessagingException) {
            Result.failure(e)
        }

    private suspend fun credential(): String = when (account.accountType) {
        AccountType.GMAIL -> tokenProvider() ?: throw IllegalStateException("Gmail account requires OAuth access token")
        AccountType.IMAP -> account.password ?: throw IllegalStateException("IMAP account requires password")
    }

    private fun buildMimeMessage(
        to: List<EmailAddress>,
        cc: List<EmailAddress>,
        bcc: List<EmailAddress>,
        subject: String,
        body: String,
        attachments: List<Attachment>,
        inReplyTo: String?,
        references: String?
    ): MimeMessage = MimeMessage(session).apply {
        setFrom(InternetAddress(account.email, account.displayName))
        setRecipients(Message.RecipientType.TO, to.map { InternetAddress(it.address, it.name) }.toTypedArray())
        setRecipients(Message.RecipientType.CC, cc.map { InternetAddress(it.address, it.name) }.toTypedArray())
        setRecipients(Message.RecipientType.BCC, bcc.map { InternetAddress(it.address, it.name) }.toTypedArray())
        setSubject(subject)
        inReplyTo?.let { setHeader("In-Reply-To", "<${it.trim('<', '>')}>") }
        references?.let { setHeader("References", it) }
        if (attachments.isEmpty()) {
            setText(body, "utf-8")
        } else {
            val multipart = MimeMultipart()
            multipart.addBodyPart(MimeBodyPart().apply { setText(body, "utf-8") })
            attachments.forEach { attachment ->
                val path = attachment.localPath ?: return@forEach
                val file = File(path)
                if (!file.exists()) return@forEach
                val part = MimeBodyPart().apply {
                    dataHandler = DataHandler(FileDataSource(file))
                    fileName = attachment.fileName
                }
                multipart.addBodyPart(part)
            }
            setContent(multipart)
        }
        sentDate = Date()
    }

    // region parsing

    private fun Message.toHeaderMessage(folder: IMAPFolder): MailMessage {
        val mime = this as? MimeMessage
        val messageIdHeader = mime?.messageID
        val references = mime?.getHeader("References")?.joinToString(" ")
        val inReplyTo = mime?.getHeader("In-Reply-To")?.joinToString(" ")
        val uid = runCatching { folder.getUID(this) }.getOrDefault(0L)
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
            bodyPreview = extractPreview(this),
            isRead = isSet(Flags.Flag.SEEN),
            isStarred = isSet(Flags.Flag.FLAGGED),
            isDraft = isSet(Flags.Flag.DRAFT),
            uid = uid
        )
    }

    private fun extractParts(
        part: Part,
        html: StringBuilder,
        plain: StringBuilder,
        attachments: MutableList<Attachment>
    ) {
        try {
            val disposition = part.disposition
            val fileName = part.fileName
            val isAttachment = Part.ATTACHMENT.equals(disposition, ignoreCase = true) ||
                (!fileName.isNullOrBlank() && !part.isMimeType("multipart/*"))

            when {
                isAttachment -> attachments.add(
                    Attachment(
                        fileName = fileName ?: "attachment",
                        mimeType = part.contentType?.substringBefore(";")?.trim() ?: "application/octet-stream",
                        size = part.size.toLong().coerceAtLeast(0),
                        remoteId = fileName
                    )
                )
                part.isMimeType("text/html") -> html.append(part.content?.toString().orEmpty())
                part.isMimeType("text/plain") -> plain.append(part.content?.toString().orEmpty())
                part.isMimeType("multipart/*") -> {
                    val multipart = part.content as? Multipart ?: return
                    for (i in 0 until multipart.count) {
                        extractParts(multipart.getBodyPart(i), html, plain, attachments)
                    }
                }
                part.isMimeType("message/rfc822") -> {
                    (part.content as? Part)?.let { extractParts(it, html, plain, attachments) }
                }
            }
        } catch (_: Exception) {
            // Skip parts we cannot decode rather than failing the whole message.
        }
    }

    private fun findAttachmentPart(part: Part, targetName: String): Part? {
        return try {
            if (part.isMimeType("multipart/*")) {
                val multipart = part.content as? Multipart ?: return null
                for (i in 0 until multipart.count) {
                    findAttachmentPart(multipart.getBodyPart(i), targetName)?.let { return it }
                }
                null
            } else if (part.fileName == targetName) {
                part
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun mapFolderType(name: String): FolderType = when (name.lowercase()) {
        "inbox" -> FolderType.INBOX
        "sent", "sent items", "sent mail" -> FolderType.SENT
        "drafts" -> FolderType.DRAFTS
        "trash", "deleted items", "bin" -> FolderType.TRASH
        "archive", "archived" -> FolderType.ARCHIVE
        "spam", "junk" -> FolderType.SPAM
        "all mail", "allmail" -> FolderType.ALL_MAIL
        "important" -> FolderType.IMPORTANT
        "starred", "flagged" -> FolderType.STARRED
        else -> FolderType.CUSTOM
    }

    private fun Address.toEmailAddress(): EmailAddress {
        val internet = this as? InternetAddress
        return EmailAddress(
            name = internet?.personal ?: "",
            address = internet?.address ?: toString()
        )
    }

    private fun extractPreview(message: Message): String = try {
        val sb = StringBuilder()
        val plain = StringBuilder()
        val attachments = mutableListOf<Attachment>()
        extractParts(message, sb, plain, attachments)
        val text = plain.toString().takeIf { it.isNotBlank() }
            ?: com.threemail.android.util.MailText.stripHtml(sb.toString())
        text.replace(Regex("\\s+"), " ").trim().take(200)
    } catch (e: Exception) {
        ""
    }

    // endregion
}
