package com.threemail.android.data.remote.imap

import com.threemail.android.data.remote.gmail.RecoverableAuthException
import com.threemail.android.domain.model.Account
import com.threemail.android.domain.model.AccountType
import com.threemail.android.domain.model.Attachment
import com.threemail.android.domain.model.EmailAddress
import com.threemail.android.domain.model.FolderType
import com.threemail.android.domain.model.MailFolder
import com.threemail.android.domain.model.MailMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Date
import java.util.Properties
import javax.mail.Address
import javax.mail.AuthenticationFailedException
import javax.mail.Flags
import javax.mail.Folder
import javax.mail.Message
import javax.mail.MessagingException
import javax.mail.Session
import javax.mail.Store
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart

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

    private fun getSmtpServer(): String {
        return when {
            account.email.endsWith("@gmail.com") -> "smtp.gmail.com"
            account.email.endsWith("@outlook.com") || account.email.endsWith("@hotmail.com") -> "smtp.office365.com"
            account.email.endsWith("@icloud.com") -> "smtp.mail.me.com"
            else -> account.incomingServer?.replace("imap", "smtp") ?: "smtp.gmail.com"
        }
    }

    private fun getSmtpPort(): Int {
        return if (account.useEncryption) 587 else 25
    }

    private fun getDefaultServer(): String {
        return when {
            account.email.endsWith("@gmail.com") -> "imap.gmail.com"
            account.email.endsWith("@outlook.com") || account.email.endsWith("@hotmail.com") -> "outlook.office365.com"
            account.email.endsWith("@icloud.com") -> "imap.mail.me.com"
            else -> account.incomingServer ?: throw IllegalArgumentException("Unknown IMAP server for ${account.email}")
        }
    }

    suspend fun testConnection(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val store = connectStore()
            try {
                store.close()
                Result.success(Unit)
            } finally {
                runCatching { store.close() }
            }
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
                val defaultFolder = store.defaultFolder
                val folders = defaultFolder.list("*")
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

    suspend fun fetchMessages(folderServerId: String, limit: Int = 50): Result<List<MailMessage>> = withContext(Dispatchers.IO) {
        try {
            val store = connectStore()
            try {
                val folder = store.getFolder(folderServerId) as? Folder ?: run {
                    runCatching { store.close() }
                    return@withContext Result.success(emptyList<MailMessage>())
                }
                if (!folder.isOpen) folder.open(Folder.READ_ONLY)

                val count = folder.messageCount
                if (count == 0) {
                    folder.close(false)
                    runCatching { store.close() }
                    return@withContext Result.success(emptyList<MailMessage>())
                }

                val start = maxOf(1, count - limit + 1)
                val end = maxOf(start, count)
                val messages = folder.getMessages(start, end)

                val result = messages.map { msg ->
                    MailMessage(
                        accountId = account.id,
                        folderId = 0,
                        messageId = msg.messageNumber.toString(),
                        threadId = null,
                        subject = msg.subject ?: "",
                        from = msg.from?.map { it.toEmailAddress() } ?: emptyList(),
                        to = msg.getRecipients(Message.RecipientType.TO)?.map { it.toEmailAddress() } ?: emptyList(),
                        cc = msg.getRecipients(Message.RecipientType.CC)?.map { it.toEmailAddress() } ?: emptyList(),
                        bcc = msg.getRecipients(Message.RecipientType.BCC)?.map { it.toEmailAddress() } ?: emptyList(),
                        date = msg.sentDate?.time ?: msg.receivedDate?.time ?: System.currentTimeMillis(),
                        bodyPreview = extractPreview(msg),
                        isRead = msg.isSet(Flags.Flag.SEEN),
                        isStarred = msg.isSet(Flags.Flag.FLAGGED),
                        uid = folder.getUID(msg)
                    )
                }
                folder.close(false)
                runCatching { store.close() }
                Result.success(result)
            } catch (e: Throwable) {
                runCatching { store.close() }
                throw e
            }
        } catch (e: RecoverableAuthException) {
            throw e
        } catch (e: MessagingException) {
            Result.failure(e)
        }
    }

    suspend fun sendMessage(
        to: List<EmailAddress>,
        cc: List<EmailAddress>,
        bcc: List<EmailAddress>,
        subject: String,
        body: String,
        attachments: List<Attachment>
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val message = MimeMessage(session).apply {
                setFrom(InternetAddress(account.email, account.displayName))
                setRecipients(Message.RecipientType.TO, to.map { InternetAddress(it.address, it.name) }.toTypedArray())
                setRecipients(Message.RecipientType.CC, cc.map { InternetAddress(it.address, it.name) }.toTypedArray())
                setRecipients(Message.RecipientType.BCC, bcc.map { InternetAddress(it.address, it.name) }.toTypedArray())
                setSubject(subject)
                if (attachments.isEmpty()) {
                    setText(body)
                } else {
                    val multipart = MimeMultipart()
                    val textPart = MimeBodyPart().apply { setText(body) }
                    multipart.addBodyPart(textPart)
                    attachments.forEach { _ ->
                        // Attachment handling omitted for brevity; load file bytes and add part
                    }
                    setContent(multipart)
                }
                sentDate = Date()
            }
            val password = when (account.accountType) {
                AccountType.GMAIL -> tokenProvider() ?: throw IllegalStateException("Gmail account requires OAuth access token")
                AccountType.IMAP -> account.password ?: throw IllegalStateException("IMAP account requires password")
            }
            Transport.send(message, account.email, password)
            Result.success(Unit)
        } catch (e: RecoverableAuthException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

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

    private fun mapFolderType(name: String): FolderType {
        return when (name.lowercase()) {
            "inbox" -> FolderType.INBOX
            "sent", "sent items", "sent mail" -> FolderType.SENT
            "drafts" -> FolderType.DRAFTS
            "trash", "deleted items" -> FolderType.TRASH
            "archive", "archived" -> FolderType.ARCHIVE
            "spam", "junk" -> FolderType.SPAM
            "all mail", "allmail" -> FolderType.ALL_MAIL
            "important" -> FolderType.IMPORTANT
            "starred", "flagged" -> FolderType.STARRED
            else -> FolderType.CUSTOM
        }
    }

    private fun Address.toEmailAddress(): EmailAddress {
        val internet = this as? InternetAddress
        return EmailAddress(
            name = internet?.personal ?: "",
            address = internet?.address ?: toString()
        )
    }

    private fun extractPreview(message: Message): String {
        return try {
            message.content?.toString()?.take(200)?.replace("\n", " ") ?: ""
        } catch (e: Exception) {
            ""
        }
    }
}
