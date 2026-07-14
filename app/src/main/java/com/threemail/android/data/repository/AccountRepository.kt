package com.threemail.android.data.repository

import com.threemail.android.data.local.dao.AccountDao
import com.threemail.android.data.local.entity.AccountEntity
import com.threemail.android.domain.model.Account
import com.threemail.android.domain.model.AccountType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AccountRepository @Inject constructor(
    private val accountDao: AccountDao
) {

    fun getAccounts(): Flow<List<Account>> =
        accountDao.getAll().map { list -> list.map { it.toDomain() } }

    suspend fun getAccountById(id: Long): Account? =
        accountDao.getById(id)?.toDomain()

    suspend fun getAccountByEmail(email: String): Account? =
        accountDao.getByEmail(email)?.toDomain()

    suspend fun addAccount(account: Account): Long {
        return accountDao.insert(account.toEntity())
    }

    suspend fun updateAccount(account: Account) {
        accountDao.update(account.toEntity())
    }

    suspend fun deleteAccount(account: Account) {
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
        password = password,
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
        password = password,
        isActive = isActive,
        syncEnabled = syncEnabled
    )
}
