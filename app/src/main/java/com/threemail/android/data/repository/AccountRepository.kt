package com.threemail.android.data.repository

import com.threemail.android.data.local.dao.AccountDao
import com.threemail.android.data.local.entity.AccountEntity
import com.threemail.android.data.security.CredentialStore
import com.threemail.android.domain.model.Account
import com.threemail.android.domain.model.AccountType
import com.threemail.android.domain.model.FolderType
import com.threemail.android.domain.model.Identity
import com.threemail.android.domain.model.Security
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
/**
 * `open` so unit tests in `AddAccountViewModelTest` can subclass it and
 * capture the [Account] passed to [addAccount] without pulling in mockito.
 * Production subclasses are not expected - Hilt always wires this
 * concrete class.
 */
open class AccountRepository @Inject constructor(
    private val accountDao: AccountDao,
    private val credentialStore: CredentialStore
) {

    fun getAccounts(): Flow<List<Account>> = flow {
        accountDao.getAll().collect { list ->
            emit(list.map { it.toDomain() })
        }
    }

    suspend fun getAccountById(id: Long): Account? =
        accountDao.getById(id)?.toDomain()

    suspend fun getAccountByEmail(email: String): Account? =
        accountDao.getByEmail(email)?.toDomain()

    open suspend fun addAccount(account: Account): Long {
        // Persist the passwords in the encrypted credential store, not the
        // database. The outgoing password is stored separately; a null/blank
        // value clears the slot so the outgoing side falls back to the
        // incoming password at connect time.
        credentialStore.savePassword(account.email, account.password)
        credentialStore.saveOutgoingPassword(account.email, account.outgoingPassword)
        return accountDao.insert(account.toEntity())
    }

    open suspend fun updateAccount(account: Account) {
        credentialStore.savePassword(account.email, account.password)
        credentialStore.saveOutgoingPassword(account.email, account.outgoingPassword)
        accountDao.update(account.toEntity())
    }

    open suspend fun deleteAccount(account: Account) {
        credentialStore.deletePassword(account.email)
        credentialStore.deleteOutgoingPassword(account.email)
        accountDao.delete(account.toEntity())
    }

    /**
     * Flips the per-account IDLE push flag without round-tripping through the
     * full Account object. Callers (e.g. AccountViewModel) are expected to
     * notify the push system separately so an open IDLE connection can be torn
     * down or brought up without waiting for the next refresh tick.
     */
    suspend fun setPushEnabled(id: Long, enabled: Boolean) {
        accountDao.setPushEnabled(id, enabled)
    }

    /**
     * Targeted per-account settings updates. These bypass [updateAccount] on
     * purpose: that path re-saves the password into the encrypted credential
     * store and rebuilds the whole entity, which is unnecessary (and would
     * reset `createdAt`) when the user is only tweaking a single preference on
     * the account settings screen.
     */
    suspend fun setDisplayName(id: Long, displayName: String) =
        accountDao.setDisplayName(id, displayName)

    suspend fun setSignature(id: Long, signature: String) =
        accountDao.setSignature(id, signature)

    suspend fun setSyncIntervalMinutes(id: Long, minutes: Long) =
        accountDao.setSyncIntervalMinutes(id, minutes)

    suspend fun setSyncEnabled(id: Long, enabled: Boolean) =
        accountDao.setSyncEnabled(id, enabled)

    suspend fun setCalendarSyncEnabled(id: Long, enabled: Boolean) =
        accountDao.setCalendarSyncEnabled(id, enabled)

    suspend fun setAccountColor(id: Long, color: Int?) =
        accountDao.setAccountColor(id, color)

    /**
     * Updates the full incoming/outgoing server connection settings in one
     * write: hosts, ports, per-direction security (each mapped back onto its
     * stored (useEncryption, useStartTls) pair), and per-direction login
     * usernames. Passwords are written to the encrypted credential store (keyed
     * by [email]) rather than the DB - a null/blank outgoing password clears
     * that slot so the outgoing side falls back to the incoming password.
     *
     * Usernames are normalised so a blank field is stored as `null` ("use the
     * account email" for incoming, "use the incoming login" for outgoing).
     */
    suspend fun setConnectionSettings(
        id: Long,
        email: String,
        incomingServer: String?,
        incomingPort: Int,
        incomingSecurity: Security,
        incomingUsername: String?,
        incomingPassword: String?,
        outgoingServer: String?,
        outgoingPort: Int,
        outgoingSecurity: Security,
        outgoingUsername: String?,
        outgoingPassword: String?
    ) {
        credentialStore.savePassword(email, incomingPassword)
        credentialStore.saveOutgoingPassword(email, outgoingPassword)
        accountDao.setConnectionSettings(
            id = id,
            incomingServer = incomingServer,
            incomingPort = incomingPort,
            useEncryption = incomingSecurity == Security.SSL_TLS,
            useStartTls = incomingSecurity == Security.STARTTLS,
            incomingUsername = incomingUsername?.takeIf { it.isNotBlank() },
            outgoingServer = outgoingServer,
            outgoingPort = outgoingPort,
            outgoingUseEncryption = outgoingSecurity == Security.SSL_TLS,
            outgoingUseStartTls = outgoingSecurity == Security.STARTTLS,
            outgoingUsername = outgoingUsername?.takeIf { it.isNotBlank() }
        )
    }

    suspend fun setNotificationsEnabled(id: Long, enabled: Boolean) =
        accountDao.setNotificationsEnabled(id, enabled)

    suspend fun setIdentities(id: Long, identities: List<Identity>) =
        accountDao.setIdentitiesJson(id, serializeIdentities(identities))

    /**
     * Replaces the per-account folder-role override map. Pass an empty map to
     * clear all overrides and return to the heuristic-only path.
     */
    suspend fun setFolderRoles(id: Long, folderRoles: Map<FolderType, String>) =
        accountDao.setFolderRolesJson(id, serializeFolderRoles(folderRoles))

    /**
     * Replaces the per-account extra-push-folders list (IMAP `serverId`s watched
     * via IDLE beyond INBOX). Pass an empty list to return to INBOX-only push.
     */
    suspend fun setPushFolders(id: Long, pushFolders: List<String>) =
        accountDao.setPushFoldersJson(id, serializeStringList(pushFolders))

    /**
     * Persist a per-peer OpenPGP public key exchange over Autocrypt
     * (RFC 8180) or WKD (RFC 9582). Storage: a single JSON-encoded map
     * of `Map<String, String>` (lowercased email -> base64-blocked keydata)
     * kept on the AccountEntity in a sister column. Same JSON-in-column
     * pattern as `identitiesJson` and `folderRolesJson` so we avoid
     * adding a separate Room table for a small monotonic key cache.
     *
     * The cache lives per-account on purpose: keys you trust on
     * `alice@example.com` for `account1` are independent from the same
     * address seen on `account2` (different identities, different
     * authenticity). Each [loadAutocryptPeerKeys] call returns a
     * snapshot; callers that want to write should batch through
     * [replaceAutocryptPeerKeys] rather than mutate the underlying JSON.
     */
    suspend fun loadAutocryptPeerKeys(accountId: Long): Map<String, String> {
        val raw = accountDao.getAutocryptKeysJson(accountId) ?: "{}"
        return parsePeerKeysJson(raw)
    }

    suspend fun replaceAutocryptPeerKeys(accountId: Long, keys: Map<String, String>) {
        accountDao.setAutocryptKeysJson(accountId, serializePeerKeysJson(keys))
    }

    /** One-shot snapshot of active accounts (used by schedulers, not the UI). */
    suspend fun getAccountsOnce(): List<Account> =
        accountDao.getAllOnce().map { it.toDomain() }

    private fun AccountEntity.toDomain(): Account = Account(
        id = id,
        email = email,
        displayName = displayName,
        accountType = accountType,
        incomingServer = incomingServer,
        incomingPort = incomingPort,
        outgoingServer = outgoingServer,
        outgoingPort = outgoingPort,
        // Reconcile the (useEncryption, useStartTls) pair into a single
        // domain Security enum. STARTTLS wins so a user who explicitly turned
        // it on cannot silently fall back to SSL_TLS just because the legacy
        // column was also true.
        security = when {
            useStartTls -> Security.STARTTLS
            useEncryption -> Security.SSL_TLS
            else -> Security.NONE
        },
        // Outgoing (SMTP) security is stored as its own (outgoingUseEncryption,
        // outgoingUseStartTls) pair, reconciled the same way as the incoming
        // side so each direction is independent.
        outgoingSecurity = when {
            outgoingUseStartTls -> Security.STARTTLS
            outgoingUseEncryption -> Security.SSL_TLS
            else -> Security.NONE
        },
        incomingUsername = incomingUsername,
        outgoingUsername = outgoingUsername,
        // Hydrate the passwords from the encrypted store; the DB column stays
        // null. Both IMAP and POP3 authenticate with a stored password (only
        // Gmail uses OAuth), so hydrate for every non-Gmail account. The
        // outgoing password may be null - the outgoing side then falls back to
        // the incoming password via Account.outgoingSecret.
        password = if (accountType != AccountType.GMAIL) credentialStore.getPassword(email) else null,
        outgoingPassword = if (accountType != AccountType.GMAIL) credentialStore.getOutgoingPassword(email) else null,
        isActive = isActive,
        syncEnabled = syncEnabled,
        calendarSyncEnabled = calendarSyncEnabled,
        pushEnabled = pushEnabled,
        signature = signature,
        syncIntervalMinutes = syncIntervalMinutes,
        notificationsEnabled = notificationsEnabled,
        identities = parseIdentities(identitiesJson),
        folderRoles = parseFolderRoles(folderRolesJson),
        peerKeys = parsePeerKeysJson(autocryptKeysJson),
        pushFolders = parseStringList(pushFoldersJson),
        color = color
    )

    private fun Account.toEntity(): AccountEntity = AccountEntity(
        id = id,
        email = email,
        displayName = displayName,
        accountType = accountType,
        incomingServer = incomingServer,
        incomingPort = incomingPort,
        outgoingServer = outgoingServer,
        outgoingPort = outgoingPort,
        // Map the single Security enum back onto the on-disk (useEncryption,
        // useStartTls) pair. The previous boolean column is preserved for the
        // legacy migration backfill; new writes always set exactly one of
        // the two to true (or both false for Security.NONE).
        useEncryption = security == Security.SSL_TLS,
        useStartTls = security == Security.STARTTLS,
        outgoingUseEncryption = outgoingSecurity == Security.SSL_TLS,
        outgoingUseStartTls = outgoingSecurity == Security.STARTTLS,
        incomingUsername = incomingUsername?.takeIf { it.isNotBlank() },
        outgoingUsername = outgoingUsername?.takeIf { it.isNotBlank() },
        password = null,
        isActive = isActive,
        syncEnabled = syncEnabled,
        calendarSyncEnabled = calendarSyncEnabled,
        pushEnabled = pushEnabled,
        signature = signature,
        syncIntervalMinutes = syncIntervalMinutes,
        notificationsEnabled = notificationsEnabled,
        identitiesJson = serializeIdentities(identities),
        folderRolesJson = serializeFolderRoles(folderRoles),
        autocryptKeysJson = serializePeerKeysJson(peerKeys),
        pushFoldersJson = serializeStringList(pushFolders),
        color = color
    )

    private fun serializeIdentities(identities: List<Identity>): String {
        val json = JSONArray()
        identities.forEach {
            json.put(
                JSONObject()
                    .put("displayName", it.displayName)
                    .put("email", it.email)
                    .put("signature", it.signature)
            )
        }
        return json.toString()
    }

    private fun parseIdentities(json: String): List<Identity> = try {
        val array = JSONArray(json)
        (0 until array.length()).mapNotNull {
            val obj = array.getJSONObject(it)
            val email = obj.optString("email", "")
            if (email.isBlank()) null
            else Identity(
                displayName = obj.optString("displayName", ""),
                email = email,
                signature = obj.optString("signature", "")
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    /**
     * JSON shape: flat object `{ "Inbox": "INBOX", "SENT": "Sent Items", ... }`.
     * Keys are FolderType enum names (`FolderType.valueOf`); values are the
     * IMAP `folder.fullName` chosen to occupy that role. Unrecognised keys
     * are skipped (forward-compat with new FolderType values).
     */
    private fun serializeFolderRoles(roles: Map<FolderType, String>): String {
        val json = JSONObject()
        roles.forEach { (role, serverId) -> json.put(role.name, serverId) }
        return json.toString()
    }

    private fun parseFolderRoles(json: String): Map<FolderType, String> = try {
        val obj = JSONObject(json)
        buildMap {
            obj.keys().forEach { key ->
                val value = obj.optString(key, "")
                if (value.isBlank()) return@forEach
                runCatching { FolderType.valueOf(key) }
                    .getOrNull()
                    ?.let { put(it, value) }
            }
        }
    } catch (e: Exception) {
        emptyMap()
    }

    /**
     * JSON shape: `{"user@example.com": "<base64 keydata>"}` - both sides
     * are stored verbatim so the importer can decode them without a regex
     * hunt. We deliberately do NOT parse the keys into PGPPublicKey objects
     * here; that work belongs to the importer, not the entity layer.
     */
    /** JSON array of strings, e.g. IMAP folder serverIds: `["INBOX.Important","Work"]`. */
    private fun serializeStringList(values: List<String>): String {
        val json = JSONArray()
        values.forEach { json.put(it) }
        return json.toString()
    }

    private fun parseStringList(json: String): List<String> = try {
        val array = JSONArray(json)
        (0 until array.length()).mapNotNull { i ->
            array.optString(i, "").takeIf { it.isNotBlank() }
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun serializePeerKeysJson(keys: Map<String, String>): String {
        val obj = JSONObject()
        keys.forEach { (email, keydata) -> obj.put(email.lowercase(), keydata) }
        return obj.toString()
    }

    private fun parsePeerKeysJson(json: String): Map<String, String> = try {
        val obj = JSONObject(json)
        buildMap {
            obj.keys().forEach { key ->
                val value = obj.optString(key, "")
                if (value.isBlank()) return@forEach
                put(key.lowercase(), value)
            }
        }
    } catch (e: Exception) {
        emptyMap()
    }
}
