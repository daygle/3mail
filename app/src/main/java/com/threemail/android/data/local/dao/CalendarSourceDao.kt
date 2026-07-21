package com.threemail.android.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.threemail.android.data.local.entity.CalendarSourceEntity
import kotlinx.coroutines.flow.Flow

/**
 * CRUD surface for [CalendarSourceEntity] (standalone ICS / CalDAV
 * calendar subscriptions).
 *
 * Writers:
 *  - [com.threemail.android.data.repository.CalendarSourceRepository.addIcsSource]
 *    inserts a new subscription.
 *  - `syncSource` stamps `lastSyncedAt` / `lastError` after each fetch.
 *  - The Manage Calendars screen flips `isVisible` and deletes rows.
 *
 * Cached events cascade-delete via the `calendar_events.sourceId` FK, so
 * [delete] needs no companion cleanup.
 */
@Dao
interface CalendarSourceDao {

    @Query("SELECT * FROM calendar_sources ORDER BY displayName COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<CalendarSourceEntity>>

    @Query("SELECT * FROM calendar_sources ORDER BY displayName COLLATE NOCASE ASC")
    suspend fun getAll(): List<CalendarSourceEntity>

    @Query("SELECT * FROM calendar_sources WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): CalendarSourceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(source: CalendarSourceEntity): Long

    @Query("UPDATE calendar_sources SET isVisible = :isVisible WHERE id = :id")
    suspend fun setVisible(id: Long, isVisible: Boolean)

    @Query("UPDATE calendar_sources SET syncEnabled = :syncEnabled WHERE id = :id")
    suspend fun setSyncEnabled(id: Long, syncEnabled: Boolean)

    @Query("UPDATE calendar_sources SET displayName = :displayName WHERE id = :id")
    suspend fun setDisplayName(id: Long, displayName: String)

    @Query("UPDATE calendar_sources SET color = :color WHERE id = :id")
    suspend fun setColor(id: Long, color: String?)

    /** Sync bookkeeping: success clears the error, failure records it. */
    @Query("UPDATE calendar_sources SET lastSyncedAt = :syncedAt, lastError = NULL WHERE id = :id")
    suspend fun markSynced(id: Long, syncedAt: Long)

    @Query("UPDATE calendar_sources SET lastError = :error WHERE id = :id")
    suspend fun markSyncError(id: Long, error: String)

    @Query("DELETE FROM calendar_sources WHERE id = :id")
    suspend fun delete(id: Long)
}
