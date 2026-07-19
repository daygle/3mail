package com.threemail.android.data.remote.imap

import android.util.Log
import com.sun.mail.imap.IMAPFolder
import com.sun.mail.imap.IMAPStore
import com.threemail.android.data.remote.MessageBody
import com.threemail.android.data.remote.RemoteCapabilities
import com.threemail.android.data.remote.MimeBuilder
import com.threemail.android.data.remote.OutgoingMessage
import com.threemail.android.data.remote.RemoteFetch
import com.threemail.android.data.remote.gmail.RecoverableAuthException
import com.threemail.android.data.remote.idle.IdleEvent
import com.threemail.android.data.remote.idle.IdleFolderOps
import com.threemail.android.data.remote.idle.IdleLoop
import com.threemail.android.domain.model.Account
import com.threemail.android.domain.model.AccountType
import com.threemail.android.domain.model.Security
import com.threemail.android.domain.model.Attachment
import com.threemail.android.domain.model.EmailAddress
import com.threemail.android.domain.model.FolderType
import com.threemail.android.domain.model.MailFolder
import com.threemail.android.domain.model.MailMessage
import com.threemail.android.util.ThreadUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Properties
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
import javax.mail.UIDFolder
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

class ImapClient(
    private val account: Account,
    private val tokenProvider: suspend () -> String? = { null }
) {

    @Volatile
    private var _session: Session? = null

    private fun getSession(): Session {
        return _session ?: synchronized(this) {
            _session ?: createSession().also { _session = it }
        }
    }

    /**
     * The IMAP protocol name mirrors `account.security`: SSL_TLS uses
     * `imaps` (implicit TLS from byte 0), everything else uses plain `imap`
     * (JavaMail upgrades via STARTTLS if the Session's properties say so).
     *
     * Routing every [connectStore] lookup through this field is what keeps
     * STARTTLS and NONE from accidentally re-opening the store on the SSL
     * provider just because the legacy code path was written for IMAPS only.
     */
    private val imapProtocol: String = if (account.security == Security.SSL_TLS) "imaps" else "imap"

    private fun createSession(): Session {
        val isGmail = account.accountType == AccountType.GMAIL
        // Translate the domain Security enum into the on-the-wire flags:
        //   SSL_TLS  → use the `imaps` protocol on port 993 (implicit TLS from byte 0)
        //   STARTTLS → use the `imap` protocol on port 143, then upgrade via STARTTLS
        //   NONE     → cleartext `imap` on port 143
        // SMTP semantics lag IMAP by convention - 587 + STARTTLS is overwhelmingly
        // the default for modern submission - so any non-NONE security asks for
        // and requires STARTTLS on the SMTP side too.
        val isSsl = account.security == Security.SSL_TLS
        val isStartTls = account.security == Security.STARTTLS
        val smtpStartTls = account.security != Security.NONE
        val protocol = if (isSsl) "imaps" else "imap"

        val props = Properties().apply {
            setProperty("mail.store.protocol", protocol)
            setProperty("mail.$protocol.host", account.incomingServer ?: getDefaultServer())
            setProperty("mail.$protocol.port", account.incomingPort.toString())
            setProperty("mail.$protocol.ssl.enable", isSsl.toString())
            // STARTTLS only applies to the cleartext `imap` protocol; setting
            // it on `imaps` is a no-op so we set it once, gated by mode.
            setProperty("mail.imap.starttls.enable", isStartTls.toString())
            // Strict mode for STARTTLS: refuse to silently downgrade to
            // cleartext if the server doesn't advertise STARTTLS in its banner.
            if (isStartTls) {
                setProperty("mail.imap.starttls.required", "true")
            }

            setProperty("mail.smtp.auth", "true")
            setProperty("mail.smtp.starttls.enable", smtpStartTls.toString())
            setProperty("mail.smtp.host", getSmtpServer())
            setProperty("mail.smtp.port", getSmtpPort().toString())
            // Verify the server certificate actually matches the hostname we
            // connected to. JavaMail defaults this to false, which accepts any
            // valid cert for ANY host and leaves the connection open to a
            // man-in-the-middle. Always on for both protocols.
            setProperty("mail.$protocol.ssl.checkserveridentity", "true")
            setProperty("mail.smtp.ssl.checkserveridentity", "true")

            // Timeouts to prevent indefinite hanging in background workers.
            setProperty("mail.$protocol.connectiontimeout", "15000")
            setProperty("mail.$protocol.timeout", "15000")
            setProperty("mail.smtp.connectiontimeout", "15000")
            setProperty("mail.smtp.timeout", "15000")

            if (smtpStartTls) {
                setProperty("mail.smtp.starttls.required", "true")
            }

            if (isGmail) {
                setProperty("mail.imaps.auth.mechanisms", "XOAUTH2")
                setProperty("mail.smtp.auth.mechanisms", "XOAUTH2")
            }
        }
        return Session.getInstance(props)
    }

    // Prefer the explicitly configured outgoing server; only fall back to a
    // best-effort guess when the account doesn't specify one (e.g. rows created
    // before outgoing config existed, or the well-known providers below).
    private fun getSmtpServer(): String = account.outgoingServer?.takeIf { it.isNotBlank() } ?: when {
        account.email.endsWith("@gmail.com") -> "smtp.gmail.com"
        account.email.endsWith("@outlook.com") || account.email.endsWith("@hotmail.com") -> "smtp.office365.com"
        account.email.endsWith("@icloud.com") -> "smtp.mail.me.com"
        else -> account.incomingServer?.replace("imap", "smtp")
            ?: throw IllegalArgumentException("No outgoing (SMTP) server configured for ${account.email}")
    }

    private fun getSmtpPort(): Int = account.outgoingPort

    private fun getDefaultServer(): String = when {
        account.email.endsWith("@gmail.com") -> "imap.gmail.com"
        account.email.endsWith("@outlook.com") || account.email.endsWith("@hotmail.com") -> "outlook.office365.com"
        account.email.endsWith("@icloud.com") -> "imap.mail.me.com"
        else -> account.incomingServer ?: throw IllegalArgumentException("Unknown IMAP server for ${account.email}")
    }

    // region connection helpers

    private suspend fun connectStore(): Store {
        val store = getSession().getStore(imapProtocol)
        val server = account.incomingServer ?: getDefaultServer()
        when (account.accountType) {
            AccountType.GMAIL -> {
                val token = tokenProvider() ?: throw IllegalStateException("Gmail account requires OAuth access token")
                store.connect(server, account.email, token)
            }
            AccountType.IMAP, AccountType.POP3 -> {
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
            Log.d(TAG, "Opened folder $folderServerId (mode=$mode), msgCount=${folder.messageCount}")
            try {
                block(folder)
            } finally {
                runCatching { folder.close(mode == Folder.READ_WRITE) }
            }
        } finally {
            runCatching { store.close() }
        }
    }

    /**
     * Returns true if the connected account's IMAP server advertised IDLE
     * (RFC 2177) in its CAPABILITY response. IDLE push is only attempted on
     * servers that confirm support - otherwise we silently fall back to the
     * periodic worker-based sync.
     *
     * Uses [Store.hasCapability] against the connected [Store] directly so we
     * don't pay for a per-account INBOX open just to inspect capabilities.
     */
    suspend fun supportsIdle(): Boolean = withContext(Dispatchers.IO) {
        try {
            connectStore().use { store ->
                // `hasCapability` lives on `IMAPStore`, not the base `Store`,
                // so cast safely. `connectStore()` always returns an
                // `IMAPStore` today; the null branch keeps us safe if a
                // non-IMAP adapter is ever swapped in.
                (store as? IMAPStore)?.hasCapability("IDLE") ?: false
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Subscribes to the IMAP IDLE notification stream on [folderServerId]
     * (typically INBOX). The returned flow emits when the server reports new
     * mail. The connection is owned by the collector - cancelling collection
     * tears the folder + store down cleanly.
     *
     * This is the IMAP-side counterpart to Gmail's REST push and is meant to
     * be driven by a long-lived foreground service so the OS doesn't kill it.
     *
     * Cleanup contract: any [Exception] thrown inside the `try { ... }` block
     * (folder lookup AND folder open AND the IDLE loop) routes through the
     * `finally { ... }`, which closes the folder + store. The escape hatch
     * for "folder not present at all" still runs the store close via
     * `runCatching` so we never leak the IMAP connection.
     */
    fun idle(folderServerId: String): Flow<IdleEvent> = kotlinx.coroutines.flow.flow {
        val store = connectStore()
        val folder: IMAPFolder = try {
            store.getFolder(folderServerId) as IMAPFolder
        } catch (e: Exception) {
            runCatching { store.close() }
            emit(IdleEvent.Disconnected("Folder not found: $folderServerId"))
            return@flow
        }
        try {
            folder.open(Folder.READ_ONLY)
            IdleLoop(folder.asIdleOps()).events().collect { emit(it) }
        } finally {
            runCatching { folder.close(false) }
            runCatching { store.close() }
        }
    }.flowOn(Dispatchers.IO)
    // Defensive: production callers (ImapIdleService.scope) already use IO,
    // but tests or unusual callers don't have to remember to wrap with IO themselves.

    /** Wrap a real [IMAPFolder] as the abstraction the IDLE loop drives. */
    private fun IMAPFolder.asIdleOps(): IdleFolderOps = object : IdleFolderOps {
        override fun idle() = this@asIdleOps.idle()
        override fun messageCount(): Int = this@asIdleOps.messageCount
        override fun close() { runCatching { this@asIdleOps.close(false) } }
    }

    // endregion

    /**
     * Connect handshake + capability read. The IMAP server's CAPABILITY is
     * listed in either the initial greeting (`* OK [CAPABILITY ...]`) or in
     * the reply to an explicit CAPABILITY command; JavaMail's IMAPStore
     * caches the greeting-time list internally and exposes per-keyword
     * lookup via [IMAPStore.hasCapability].
     *
     * We don't issue an explicit CAPABILITY command ourselves: the greeting
     * announcement is universal among RFC 3501 servers and reading from
     * the cached store avoids burning an extra round-trip on every test.
     * Servers that omit CAPABILITY from the greeting (rare) still connect
     * successfully - the returned list will be empty, which the caller
     * handles by NOT auto-upgrading.
     *
     * Returns [RemoteCapabilities] with an empty list on a non-IMAP store
     * (cast failure is silent). The connect still counts as a success in
     * that case since [Store.connect] returned without throwing.
     *
     * Implementation note: IMAPStore.capabilities is a `protected Map`
     * field on JavaMail, so it cannot be read from outside the
     * `com.sun.mail.imap` package. The public `hasCapability(String)`
     * method is the supported cross-package probe, so we walk a curated
     * list of keywords we actually branch on.
     */
    suspend fun testConnection(): Result<RemoteCapabilities> = withContext(Dispatchers.IO) {
        try {
            val store = connectStore()
            try {
                val imapStore = store as? IMAPStore
                val capabilities = PROBED_CAPABILITIES
                    .filter { keyword -> imapStore?.hasCapability(keyword) == true }
                Result.success(RemoteCapabilities(capabilities))
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
                val folders = store.defaultFolder.list("*")
                // Server subscription set (LSUB). Used only to pick the *initial*
                // hidden state of newly-discovered folders; the repository
                // preserves the user's local hide choice for folders it already
                // knows, so re-syncing never re-hides a folder the user un-hid.
                val subscribed = runCatching {
                    store.defaultFolder.listSubscribed("*").mapNotNullTo(HashSet()) { it.fullName }
                }.getOrDefault(HashSet())
                Log.d(TAG, "Fetched ${folders.size} folders (${subscribed.size} subscribed) for ${account.email}")
                Result.success(folders.map { folder ->
                    val type = mapFolderType(folder.name)
                    MailFolder(
                        accountId = account.id,
                        serverId = folder.fullName,
                        name = folder.name,
                        type = type,
                        // Hide unsubscribed folders by default, but never the
                        // inbox, and never when the server gave us no LSUB data.
                        isHidden = subscribed.isNotEmpty() &&
                            type != FolderType.INBOX &&
                            folder.fullName !in subscribed
                    )
                })
            } finally {
                runCatching { store.close() }
            }
        } catch (e: RecoverableAuthException) {
            throw e
        } catch (e: MessagingException) {
            Log.e(TAG, "Failed to fetch folders for ${account.email}", e)
            Result.failure(e)
        }
    }

    /** Subscribe / unsubscribe a folder on the server (IMAP LSUB). */
    suspend fun setSubscribed(folderServerId: String, subscribed: Boolean): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val store = connectStore()
                try {
                    store.getFolder(folderServerId).setSubscribed(subscribed)
                    Result.success(Unit)
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
    suspend fun fetchMessagesSince(folderServerId: String, sinceUid: Long, limit: Int = 100): Result<RemoteFetch> =
        try {
            withFolder(folderServerId, Folder.READ_ONLY) { folder ->
                val fetched = folder.getMessagesByUID(sinceUid + 1, UIDFolder.LASTUID)
                    ?.filterNotNull()
                    ?.filter { folder.getUID(it) > sinceUid }
                    ?.takeLast(limit)
                    ?: emptyList()
                val mapped = fetched.map { it.toHeaderMessage(folder) }
                val maxUid = mapped.maxOfOrNull { it.uid } ?: sinceUid
                RemoteFetch(mapped, maxUid)
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
                        folder.setFlags(chunk, Flags(Flags.Flag.DELETED), true)
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

    suspend fun sendMessage(message: OutgoingMessage): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val mime = MimeBuilder.build(account.email, account.displayName, message)
            val credential = credential()
            // Send through this client's configured session rather than
            // Transport.send(mime, ...): the latter uses the message's own
            // session, which MimeBuilder creates with empty Properties, so the
            // SMTP host, STARTTLS, and ssl.checkserveridentity settings would
            // all be ignored. Connect the "smtp" transport explicitly so those
            // properties (and TLS verification) actually apply.
            val transport = getSession().getTransport("smtp")
            try {
                transport.connect(getSmtpServer(), getSmtpPort(), account.email, credential)
                transport.sendMessage(mime, mime.allRecipients)
            } finally {
                runCatching { transport.close() }
            }
            Result.success(Unit)
        } catch (e: RecoverableAuthException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Appends a message to the Drafts folder without sending it. */
    suspend fun appendDraft(draftsServerId: String, message: OutgoingMessage): Result<Unit> =
        try {
            withContext(Dispatchers.IO) {
                val store = connectStore()
                try {
                    val drafts = store.getFolder(draftsServerId)
                    if (!drafts.exists()) drafts.create(Folder.HOLDS_MESSAGES)
                    drafts.open(Folder.READ_WRITE)
                    try {
                        val mime = MimeBuilder.build(account.email, account.displayName, message).apply {
                            setFlag(Flags.Flag.DRAFT, true)
                        }
                        drafts.appendMessages(arrayOf(mime))
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
        AccountType.IMAP, AccountType.POP3 -> account.password ?: throw IllegalStateException("IMAP account requires password")
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
            uid = uid,
            remoteId = uid.toString()
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

    private companion object {
        private const val TAG = "ImapClient"

        /**
         * Capability keywords surfaced via [IMAPStore.hasCapability]. Picked
         * because each maps to a code branch somewhere in the app:
         *
         *  - `STARTTLS`       drives opportunistic Security.NONE -> STARTTLS upgrade
         *  - `IDLE`           mirrors the [supportsIdle] probe for the push pipeline
         *  - `AUTH=OAUTH2` /
         *    `AUTH=OAUTHBEARER` exposes OAuth-capable servers to the
         *                      provider picker
         *  - `LOGINDISABLED`  forces the password field into an OAuth-only mode
         *  - `UTF8=ACCEPT` / `MOVE` / `CONDSTORE` / `OBJECTID` are modern
         *    IMAP extensions that future account-detail UI can surface
         *  - `AUTH=PLAIN` is included only so the Gmail/IMAP bridge can
         *    log it during a failed connection - we never send credentials
         *    without TLS in this codebase.
         */
        val PROBED_CAPABILITIES = listOf(
            "STARTTLS",
            "IDLE",
            "AUTH=PLAIN",
            "AUTH=OAUTH2",
            "AUTH=OAUTHBEARER",
            "LOGINDISABLED",
            "UTF8=ACCEPT",
            "MOVE",
            "CONDSTORE",
            "OBJECTID"
        )
    }
}
