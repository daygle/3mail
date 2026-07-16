package com.threemail.android.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.threemail.android.data.local.entity.OutboxMessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface OutboxDao {

    @Insert
    suspend fun insert(message: OutboxMessageEntity): Long

    /** Oldest first, so the queue drains in the order messages were composed. */
    @Query("SELECT * FROM outbox_messages ORDER BY createdAt ASC")
    suspend fun getAll(): List<OutboxMessageEntity>

    @Query("SELECT COUNT(*) FROM outbox_messages")
    fun observeCount(): Flow<Int>

    @Query("DELETE FROM outbox_messages WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query(
        "UPDATE outbox_messages SET attemptCount = :attemptCount, " +
            "lastAttemptAt = :lastAttemptAt, lastError = :lastError WHERE id = :id"
    )
    suspend fun recordAttempt(id: Long, attemptCount: Int, lastAttemptAt: Long, lastError: String?)
}
