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
import com.threemail.android.data.remote.MimeParsing
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
import java.io.ByteArrayInputStream
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
        // The outgoing (SMTP) server has its own security mode, independent of
        // the incoming one: SSL_TLS uses implicit TLS (e.g. 465), STARTTLS
        // upgrades a cleartext submission connection (e.g. 587), NONE is plain.
        val isSsl = account.security == Security.SSL_TLS
        val isStartTls = account.security == Security.STARTTLS
        val outgoingSecurity = account.outgoingSecurity
        val smtpSsl = outgoingSecurity == Security.SSL_TLS
        val smtpStartTls = outgoingSecurity == Security.STARTTLS
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
            // Implicit TLS (SSL_TLS) vs opportunistic upgrade (STARTTLS) are
            // mutually exclusive JavaMail flags; drive them from the outgoing
            // security so a 465/SSL submission host works as well as 587/STARTTLS.
            setProperty("mail.smtp.ssl.enable", smtpSsl.toString())
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
                store.connect(server, account.incomingLogin, password)
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
        var store: Store? = null
        try {
            store = connectStore()
            // `hasCapability` lives on `IMAPStore`, not the base `Store`,
            // so cast safely. `connectStore()` always returns an
            // `IMAPStore` today; the null branch keeps us safe if a
            // non-IMAP adapter is ever swapped in.
            (store as? IMAPStore)?.hasCapability("IDLE") ?: false
        } catch (e: Exception) {
            false
        } finally {
            runCatching { store?.close() }
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
                    val type = resolveFolderType(account, folder)
                    MailFolder(
                        accountId = account.id,
                        serverId = folder.fullName,
                        // IMAP servers return the inbox leaf as "INBOX" per RFC 3501,
                        // but every modern email client titles-cases it as "Inbox" in
                        // the UI. Normalize the displayed name here so the change is
                        // a single-source fix (drawer, folder management, favourites,
                        // move-to-folder dialogs, etc.) without touching the
                        // server-command `serverId`, which must remain the canonical
                        // "INBOX" uppercase to pass `SELECT`/`EXAMINE` on every
                        // server we talk to. Other folder names pass through
                        // untouched (e.g. "Sent", "Drafts", "[Gmail]/All Mail").
                        name = if (folder.name.equals("INBOX", ignoreCase = true)) "Inbox" else folder.name,
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

    /**
     * Returns the subset of [uids] that STILL EXIST (are not expunged) in
     * [folderServerId] on the server. Sync uses this to detect messages a
     * user deleted from another client: any locally-cached uid the server
     * no longer returns has been removed remotely and should be dropped
     * from the local cache.
     *
     * The check is a direct `UID FETCH` of exactly the supplied uids (chunked
     * so a large cache doesn't build one enormous command), NOT a re-scan of
     * the synced window, so even old cached messages are reconciled. An empty
     * input short-circuits without opening the folder. If the folder can't be
     * addressed by UID we return the input set unchanged (assume everything
     * still exists) so a non-UID server never triggers a mass local delete.
     */
    suspend fun existingUids(folderServerId: String, uids: List<Long>): Result<Set<Long>> =
        try {
            if (uids.isEmpty()) {
                Result.success(emptySet())
            } else {
                withFolder(folderServerId, Folder.READ_ONLY) { folder ->
                    existingUidsIn(folder, uids)
                }.let { Result.success(it) }
            }
        } catch (e: RecoverableAuthException) {
            throw e
        } catch (e: MessagingException) {
            Result.failure(e)
        }

    /**
     * Batch counterpart to [existingUids]: probe several folders' cached uids
     * over a SINGLE store connection instead of reconnecting per folder, which
     * is what the periodic deletion-reconcile sweep needs when it checks every
     * folder the user has opened. Returns a map from serverId to its surviving
     * uid set.
     *
     * Per-folder resilience: a folder that can't be opened (e.g. it was deleted
     * on the server) is simply OMITTED from the result rather than failing the
     * whole batch, so one vanished folder never blocks reconciling the others
     * and never gets its own cache wrongly cleared (the caller only prunes
     * folders present in the map). If the store connection itself fails, the
     * whole call fails and nothing is reconciled. Folders with an empty uid
     * list map to an empty set without opening them.
     */
    suspend fun existingUidsBatch(folderUids: Map<String, List<Long>>): Result<Map<String, Set<Long>>> =
        withContext(Dispatchers.IO) {
            try {
                val store = connectStore()
                try {
                    val out = HashMap<String, Set<Long>>(folderUids.size)
                    for ((serverId, uids) in folderUids) {
                        if (uids.isEmpty()) {
                            out[serverId] = emptySet()
                            continue
                        }
                        // Open each folder inside the shared store; a failure
                        // for one folder (missing/expunged) omits it, leaving
                        // its cache untouched, without tearing down the batch.
                        runCatching {
                            val folder = store.getFolder(serverId)
                            if (!folder.isOpen) folder.open(Folder.READ_ONLY)
                            try {
                                existingUidsIn(folder, uids)
                            } finally {
                                runCatching { folder.close(false) }
                            }
                        }.onSuccess { out[serverId] = it }
                    }
                    Result.success(out)
                } finally {
                    runCatching { store.close() }
                }
            } catch (e: RecoverableAuthException) {
                throw e
            } catch (e: MessagingException) {
                Result.failure(e)
            }
        }

    /**
     * Core existence probe shared by [existingUids] and [existingUidsBatch]:
     * chunked `UID FETCH` of exactly [uids] against an already-open [folder],
     * returning the subset the server still has. A non-UID folder yields the
     * input set unchanged so a non-UID server never triggers a mass delete.
     */
    private fun existingUidsIn(folder: Folder, uids: List<Long>): Set<Long> {
        val uidFolder = folder as? UIDFolder ?: return uids.toSet()
        val existing = HashSet<Long>(uids.size)
        uids.chunked(UID_EXISTENCE_CHUNK).forEach { chunk ->
            // getMessagesByUID(long[]) returns an array the same length as the
            // input, with a null slot for every uid the server no longer has -
            // exactly the expunged set.
            val messages = uidFolder.getMessagesByUID(chunk.toLongArray())
            for (msg in messages) {
                if (msg != null && !msg.isExpunged) {
                    val uid = uidFolder.getUID(msg)
                    if (uid > 0L) existing.add(uid)
                }
            }
        }
        return existing
    }

    /**
     * Fetches only the envelope headers (RFC 5322 §3.6.1 plus any MIME
     * extensions like `Autocrypt` / `Autocrypt-Gossip`). Cheaper than
     * [fetchBody] for the opportunistic Autocrypt-key-learning path
     * because headers are delivered as one block per message and are
     * typically tens of KB - the entire MIME body is never read.
     *
     * JavaMail exposes headers as a flat list of `name: value` lines
     * via [javax.mail.Message.getAllHeaders]. Multi-value headers
     * (References, Received) are split on newline into a list so the
     * caller doesn't have to re-parse.
     *
     * Returns an empty map when the message is not found so callers
     * can iterate `fetchMessages*` results and synchronously call this
     * helper without a per-UID exception path.
     */
    suspend fun fetchMessageHeaders(folderServerId: String, uid: Long): Result<Map<String, List<String>>> =
        fetchMessagesHeaders(folderServerId, listOf(uid)).map { it[uid] ?: emptyMap() }

    /**
     * Batch version of [fetchMessageHeaders]. Uses `FETCH <range> (FLAGS ENVELOPE)`
     * or similar via JavaMail's `FetchProfile` to read only the metadata for
     * multiple UIDs in a single network round-trip.
     */
    suspend fun fetchMessagesHeaders(
        folderServerId: String,
        uids: List<Long>
    ): Result<Map<Long, Map<String, List<String>>>> = try {
        withFolder(folderServerId, Folder.READ_ONLY) { folder ->
            if (uids.isEmpty()) return@withFolder emptyMap()
            
            val uidFolder = folder as? UIDFolder 
                ?: return@withFolder emptyMap()
            
            val messages = uidFolder.getMessagesByUID(uids.toLongArray())
            val fp = javax.mail.FetchProfile().apply {
                add(javax.mail.FetchProfile.Item.ENVELOPE)
                add(javax.mail.FetchProfile.Item.CONTENT_INFO)
                // Add any extra headers we need for Autocrypt/Gossip.
                add("Autocrypt")
                add("Autocrypt-Gossip")
                add("Message-ID")
                add("References")
                add("In-Reply-To")
            }
            folder.fetch(messages, fp)

            messages.filterNotNull().associate { msg ->
                val uid = uidFolder.getUID(msg)
                val headersMap = mutableMapOf<String, MutableList<String>>()
                val enumeration = msg.allHeaders
                while (enumeration != null && enumeration.hasMoreElements()) {
                    val header = enumeration.nextElement() as javax.mail.Header
                    val values = header.value.split('\n')
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                    headersMap.getOrPut(header.name) { mutableListOf() }.addAll(values)
                }
                uid to headersMap
            }
        }.let { Result.success(it) }
    } catch (e: RecoverableAuthException) {
        throw e
    } catch (e: Exception) {
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

    /** Fetches the full header block (every header, as "Name: value") for one message. */
    suspend fun fetchRawHeaders(folderServerId: String, uid: Long): Result<String> =
        try {
            withFolder(folderServerId, Folder.READ_ONLY) { folder ->
                val msg = folder.getMessageByUID(uid) ?: return@withFolder ""
                MimeParsing.buildHeaderText(msg)
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

    /**
     * Rename (or reparent) a folder by changing its full path from
     * [oldServerId] to [newServerId]. IMAP's RENAME command relocates the
     * folder and all of its subfolders in one step, so no per-descendant
     * work is needed here. Fails if the source is missing or a folder already
     * exists at the destination (so we never clobber an unrelated mailbox).
     */
    suspend fun renameFolder(oldServerId: String, newServerId: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val store = connectStore()
                try {
                    val source = store.getFolder(oldServerId)
                    if (!source.exists()) {
                        return@withContext Result.failure(
                            MessagingException("Folder no longer exists: $oldServerId")
                        )
                    }
                    val dest = store.getFolder(newServerId)
                    if (dest.exists()) {
                        return@withContext Result.failure(
                            MessagingException("A folder already exists at: $newServerId")
                        )
                    }
                    if (source.isOpen) runCatching { source.close(false) }
                    if (source.renameTo(dest)) {
                        Result.success(Unit)
                    } else {
                        Result.failure(MessagingException("Server rejected rename of $oldServerId"))
                    }
                } finally {
                    runCatching { store.close() }
                }
            } catch (e: RecoverableAuthException) {
                throw e
            } catch (e: MessagingException) {
                Result.failure(e)
            }
        }

    /**
     * The server's folder-hierarchy separator, read from the default (root)
     * folder. Authoritative for building rename/move target paths, so we don't
     * have to guess it from folder names.
     */
    suspend fun folderSeparator(): Result<Char> =
        withContext(Dispatchers.IO) {
            try {
                val store = connectStore()
                try {
                    Result.success(store.defaultFolder.separator)
                } finally {
                    runCatching { store.close() }
                }
            } catch (e: RecoverableAuthException) {
                throw e
            } catch (e: MessagingException) {
                Result.failure(e)
            }
        }

    /**
     * Delete a folder and its subfolders from the server. Treated as a
     * successful no-op when the folder is already gone (idempotent), so a
     * retried delete doesn't surface a spurious error.
     */
    suspend fun deleteFolder(serverId: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val store = connectStore()
                try {
                    val folder = store.getFolder(serverId)
                    if (!folder.exists()) return@withContext Result.success(Unit)
                    if (folder.isOpen) runCatching { folder.close(false) }
                    // recurse = true so subfolders are removed alongside the parent.
                    if (folder.delete(true)) {
                        Result.success(Unit)
                    } else {
                        Result.failure(MessagingException("Server rejected delete of $serverId"))
                    }
                } finally {
                    runCatching { store.close() }
                }
            } catch (e: RecoverableAuthException) {
                throw e
            } catch (e: MessagingException) {
                Result.failure(e)
            }
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
                transport.connect(getSmtpServer(), getSmtpPort(), account.outgoingLogin, credential)
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

    /**
     * Sends a pre-built RFC 5322 wire-format message (e.g. a
     * multipart/encrypted envelope from [com.threemail.android.data.crypto.MailPgpOutbound])
     * over the same SMTP transport as [sendMessage]. The bytes are parsed
     * into a [MimeMessage] via an empty [Properties] session so JavaMail
     * stays out of header rewriting and trusts the wire form verbatim;
     * the SMTP transport itself is this client's configured session
     * (STARTTLS / SSL_TLS / ssl.checkserveridentity are unchanged).
     *
     * Anything that needs to bypass [MimeBuilder.build] - because the
     * outbound payload is already encrypted Mime - goes here.
     */
    suspend fun sendEncryptedMessage(messageBytes: ByteArray): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val mime = javax.mail.internet.MimeMessage(
                javax.mail.Session.getInstance(java.util.Properties()),
                ByteArrayInputStream(messageBytes)
            )
            val credential = credential()
            val transport = getSession().getTransport("smtp")
            try {
                transport.connect(getSmtpServer(), getSmtpPort(), account.outgoingLogin, credential)
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

    // The SMTP credential: for password accounts this is the outgoing secret,
    // which falls back to the incoming password when no separate outgoing
    // password is configured (Account.outgoingSecret).
    private suspend fun credential(): String = when (account.accountType) {
        AccountType.GMAIL -> tokenProvider() ?: throw IllegalStateException("Gmail account requires OAuth access token")
        AccountType.IMAP, AccountType.POP3 -> account.outgoingSecret ?: throw IllegalStateException("IMAP account requires password")
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

    private fun resolveFolderType(
        account: Account,
        folder: javax.mail.Folder
    ): FolderType {
        // User-set override wins: if this folder's fullName is mapped to any
        // role in the account's override JSON, use that role exactly.
        account.folderRoles.entries.firstOrNull { it.value == folder.fullName }
            ?.let { return it.key }
        // Otherwise ask the name heuristic, but make sure the heuristic
        // doesn't pre-empt a role that's already been claimed by the user
        // for a different server folder (avoids two folders both tagged as
        // Inbox, etc.).
        val heuristic = mapFolderType(folder.name)
        return if (heuristic in account.folderRoles.keys) FolderType.CUSTOM else heuristic
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
         * Max uids per `UID FETCH` when probing for expunged messages. JavaMail
         * compresses consecutive uids into ranges, so this only bounds the
         * worst case (a sparse cache) and keeps a single command reasonable.
         */
        private const val UID_EXISTENCE_CHUNK = 1000

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
