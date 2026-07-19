package com.threemail.android.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.threemail.android.data.local.entity.MessageFlagEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for [MessageFlagEntity]. Defaults to INSERT OR IGNORE so writes
 * are idempotent: a second call with the same `(accountId, messageId)`
 * simply preserves the existing row, which is what we want when the
 * SendMailWorker re-runs after a worker retry. UPDATEs deliberately
 * absent - a row is either "is" or "isn't" encrypted, never flipped.
 */
@Dao
interface MessageFlagDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(flag: MessageFlagEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnoreAll(flags: List<MessageFlagEntity>): List<Long>

    /**
     * Reactive snapshot of every flag row for [accountId]. Joined at
     * read time against the main [MessageDao] by the repository.
     */
    @Query("SELECT * FROM message_flags WHERE accountId = :accountId")
    fun observeByAccount(accountId: Long): Flow<List<MessageFlagEntity>>

    /**
     * Reactive snapshot of every flag row across every account. Used by
     * the repository when joining against folder-scoped message flows
     * (the folder row alone doesn't carry an `accountId` join hint).
     * The flag table is small - one row per flagged message, regardless
     * of how many messages exist - so streaming the whole thing every
     * time the list re-emits is acceptable.
     */
    @Query("SELECT * FROM message_flags")
    fun observeAll(): Flow<List<MessageFlagEntity>>

    @Query("SELECT * FROM message_flags WHERE accountId = :accountId AND messageId = :messageId LIMIT 1")
    suspend fun getOne(accountId: Long, messageId: String): MessageFlagEntity?

    @Query("DELETE FROM message_flags WHERE accountId = :accountId AND messageId = :messageId")
    suspend fun deleteOne(accountId: Long, messageId: String)
}
