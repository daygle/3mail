package com.threemail.android.data.repository

import com.threemail.android.data.local.dao.AccountDao
import com.threemail.android.data.local.entity.AccountEntity
import com.threemail.android.data.security.CredentialStore
import com.threemail.android.domain.model.Account
import com.threemail.android.domain.model.AccountType
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
        // Persist the password in the encrypted credential store, not the database.
        credentialStore.savePassword(account.email, account.password)
        return accountDao.insert(account.toEntity())
    }

    open suspend fun updateAccount(account: Account) {
        credentialStore.savePassword(account.email, account.password)
        accountDao.update(account.toEntity())
    }

    open suspend fun deleteAccount(account: Account) {
        credentialStore.deletePassword(account.email)
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

    suspend fun setNotificationsEnabled(id: Long, enabled: Boolean) =
        accountDao.setNotificationsEnabled(id, enabled)

    suspend fun setIdentities(id: Long, identities: List<Identity>) =
        accountDao.setIdentitiesJson(id, serializeIdentities(identities))

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
        // Hydrate the password from the encrypted store; the DB column stays
        // null. Both IMAP and POP3 authenticate with a stored password (only
        // Gmail uses OAuth), so hydrate for every non-Gmail account.
        password = if (accountType != AccountType.GMAIL) credentialStore.getPassword(email) else null,
        isActive = isActive,
        syncEnabled = syncEnabled,
        calendarSyncEnabled = calendarSyncEnabled,
        pushEnabled = pushEnabled,
        signature = signature,
        syncIntervalMinutes = syncIntervalMinutes,
        notificationsEnabled = notificationsEnabled,
        identities = parseIdentities(identitiesJson)
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
        password = null,
        isActive = isActive,
        syncEnabled = syncEnabled,
        calendarSyncEnabled = calendarSyncEnabled,
        pushEnabled = pushEnabled,
        signature = signature,
        syncIntervalMinutes = syncIntervalMinutes,
        notificationsEnabled = notificationsEnabled,
        identitiesJson = serializeIdentities(identities)
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
}
