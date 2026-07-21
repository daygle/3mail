package com.threemail.android.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.threemail.android.data.local.entity.CalendarEventEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CalendarEventDao {

    /**
     * All events that overlap the half-open range [startMs, endMs).
     * An event with start at 9:00 and end at 10:00 is included in a query window of 9:00–9:30,
     * and a multi-day event's start/end are inclusive/exclusive on its actual boundaries.
     */
    @Query(
        "SELECT * FROM calendar_events " +
            "WHERE accountId = :accountId " +
            "AND endEpochMs > :startMs " +
            "AND startEpochMs < :endMs " +
            "ORDER BY allDay DESC, startEpochMs ASC"
    )
    fun getInRange(accountId: Long, startMs: Long, endMs: Long): Flow<List<CalendarEventEntity>>

    /**
     * Events from every *visible* standalone source overlapping the
     * half-open range. The JOIN keeps the flow reactive to both the events
     * cache and the user's per-source visibility toggles.
     */
    @Query(
        "SELECT calendar_events.* FROM calendar_events " +
            "INNER JOIN calendar_sources ON calendar_events.sourceId = calendar_sources.id " +
            "WHERE calendar_sources.isVisible = 1 " +
            "AND endEpochMs > :startMs " +
            "AND startEpochMs < :endMs " +
            "ORDER BY allDay DESC, startEpochMs ASC"
    )
    fun getInRangeForVisibleSources(startMs: Long, endMs: Long): Flow<List<CalendarEventEntity>>

    @Query(
        "SELECT * FROM calendar_events " +
            "WHERE sourceId = :sourceId " +
            "AND endEpochMs > :startMs " +
            "AND startEpochMs < :endMs " +
            "ORDER BY allDay DESC, startEpochMs ASC"
    )
    fun getInRangeForSource(sourceId: Long, startMs: Long, endMs: Long): Flow<List<CalendarEventEntity>>

    @Query("SELECT * FROM calendar_events WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): CalendarEventEntity?

    @Query(
        "SELECT * FROM calendar_events " +
            "WHERE accountId = :accountId AND calendarId = :calendarId AND eventId = :eventId " +
            "LIMIT 1"
    )
    suspend fun getByRemoteId(accountId: Long, calendarId: String, eventId: String): CalendarEventEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: CalendarEventEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(events: List<CalendarEventEntity>): List<Long>

    @Update
    suspend fun update(event: CalendarEventEntity)

    @Delete
    suspend fun delete(event: CalendarEventEntity)

    @Query("DELETE FROM calendar_events WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM calendar_events WHERE accountId = :accountId AND endEpochMs < :cutoffMs")
    suspend fun deleteOlderThan(accountId: Long, cutoffMs: Long)

    /** ICS refreshes replace a source's cache wholesale rather than upserting. */
    @Query("DELETE FROM calendar_events WHERE sourceId = :sourceId")
    suspend fun deleteBySource(sourceId: Long)

    /** Atomic wholesale swap so observers never see a half-refreshed source. */
    @Transaction
    suspend fun replaceForSource(sourceId: Long, events: List<CalendarEventEntity>) {
        deleteBySource(sourceId)
        if (events.isNotEmpty()) insertAll(events)
    }
}
