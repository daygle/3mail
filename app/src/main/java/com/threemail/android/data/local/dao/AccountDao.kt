package com.threemail.android.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.threemail.android.data.local.entity.AccountEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {

    @Query("SELECT * FROM accounts WHERE isActive = 1 ORDER BY email ASC")
    fun getAll(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts WHERE isActive = 1 ORDER BY email ASC")
    suspend fun getAllOnce(): List<AccountEntity>

    @Query("SELECT * FROM accounts WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): AccountEntity?

    @Query("SELECT * FROM accounts WHERE email = :email LIMIT 1")
    suspend fun getByEmail(email: String): AccountEntity?

    @Query("UPDATE accounts SET pushEnabled = :enabled WHERE id = :id")
    suspend fun setPushEnabled(id: Long, enabled: Boolean)

    @Query("UPDATE accounts SET displayName = :displayName WHERE id = :id")
    suspend fun setDisplayName(id: Long, displayName: String)

    @Query("UPDATE accounts SET signature = :signature WHERE id = :id")
    suspend fun setSignature(id: Long, signature: String)

    @Query("UPDATE accounts SET syncIntervalMinutes = :minutes WHERE id = :id")
    suspend fun setSyncIntervalMinutes(id: Long, minutes: Long)

    @Query("UPDATE accounts SET syncEnabled = :enabled WHERE id = :id")
    suspend fun setSyncEnabled(id: Long, enabled: Boolean)

    @Query("UPDATE accounts SET notificationsEnabled = :enabled WHERE id = :id")
    suspend fun setNotificationsEnabled(id: Long, enabled: Boolean)

    @Query("UPDATE accounts SET identitiesJson = :identitiesJson WHERE id = :id")
    suspend fun setIdentitiesJson(id: Long, identitiesJson: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(account: AccountEntity): Long

    @Update
    suspend fun update(account: AccountEntity)

    @Delete
    suspend fun delete(account: AccountEntity)
}
