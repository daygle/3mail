package com.threemail.android.data.repository

import com.threemail.android.data.local.dao.AccountDao
import com.threemail.android.data.local.entity.AccountEntity
import com.threemail.android.data.security.CredentialStore
import com.threemail.android.domain.model.Account
import com.threemail.android.domain.model.AccountType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AccountRepository @Inject constructor(
    private val accountDao: AccountDao,
    private val credentialStore: CredentialStore
) {

    fun getAccounts(): Flow<List<Account>> =
        accountDao.getAll().map { list -> list.map { it.toDomain() } }

    suspend fun getAccountById(id: Long): Account? =
        accountDao.getById(id)?.toDomain()

    suspend fun getAccountByEmail(email: String): Account? =
        accountDao.getByEmail(email)?.toDomain()

    suspend fun addAccount(account: Account): Long {
        // Persist the password in the encrypted credential store, not the database.
        credentialStore.savePassword(account.email, account.password)
        return accountDao.insert(account.toEntity())
    }

    suspend fun updateAccount(account: Account) {
        credentialStore.savePassword(account.email, account.password)
        accountDao.update(account.toEntity())
    }

    suspend fun deleteAccount(account: Account) {
        credentialStore.deletePassword(account.email)
        accountDao.delete(account.toEntity())
    }

    private fun AccountEntity.toDomain(): Account = Account(
        id = id,
        email = email,
        displayName = displayName,
        accountType = accountType,
        incomingServer = incomingServer,
        incomingPort = incomingPort,
        useEncryption = useEncryption,
        // Hydrate the password from the encrypted store; the DB column stays null.
        password = if (accountType == AccountType.IMAP) credentialStore.getPassword(email) else null,
        isActive = isActive,
        syncEnabled = syncEnabled
    )

    private fun Account.toEntity(): AccountEntity = AccountEntity(
        id = id,
        email = email,
        displayName = displayName,
        accountType = accountType,
        incomingServer = incomingServer,
        incomingPort = incomingPort,
        useEncryption = useEncryption,
        password = null,
        isActive = isActive,
        syncEnabled = syncEnabled
    )
}
