package com.threemail.android.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.threemail.android.data.local.entity.CalendarEntryEntity
import kotlinx.coroutines.flow.Flow

/**
 * Reactive + bulk read/write surface for [CalendarEntryEntity].
 *
 * Three writers today:
 *  - [com.threemail.android.data.repository.CalendarRepository.syncCalendarList]
 *    replaces the rows for one (or all) Google accounts with what Google
 *    just returned; uses [insertAll] with REPLACE strategy keyed on the
 *    composite PK.
 *  - [com.threemail.android.data.repository.CalendarRepository.subscribeByUrl]
 *    and `createNew` insert a single freshly created calendar; the
 *    rest of the user's list is untouched.
 *  - [com.threemail.android.data.repository.CalendarRepository.setSelected]
 *    toggles the user-facing visibility flag without rewiring metadata.
 *
 * Read paths:
 *  - [com.threemail.android.ui.screens.calendar.ManageCalendarsViewModel]
 *    consumes [observeAll] for the per-account grouped list.
 *  - The next-phase CalendarScreen filter will consult
 *    `getSelectedCalendarIdsByAccount` to scope event fetches.
 */
@Dao
interface CalendarListDao {

    /**
     * Reactive feed of every calendar row across every account. The popup
     * menu in Manage Calendars groups by account id locally; the calendar
     * screen filter has the per-account subset query below.
     */
    @Query("SELECT * FROM calendars ORDER BY accountId ASC, isPrimary DESC, summary COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<CalendarEntryEntity>>

    @Query(
        "SELECT * FROM calendars WHERE accountId = :accountId " +
            "ORDER BY isPrimary DESC, summary COLLATE NOCASE ASC"
    )
    fun observeByAccount(accountId: Long): Flow<List<CalendarEntryEntity>>

    /**
     * For calendar-fetch scoping: returns the calendar ids the user has
     * marked visible for [accountId]. Used by [CalendarSyncWorker] /
     * [com.threemail.android.ui.screens.calendar.CalendarViewModel] when
     * the per-calendar visibility filter is wired (Phase 2 - hook lives
     * here so the contract is in place even before the consumer reads it).
     */
    @Query("SELECT calendarId FROM calendars WHERE accountId = :accountId AND isSelected = 1")
    suspend fun getSelectedCalendarIdsByAccount(accountId: Long): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<CalendarEntryEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: CalendarEntryEntity): Long

    /**
     * Visibility flag flip. Doesn't touch summary / tz / colour / role;
     * a `syncCalendarList` roundtrip should leave `isSelected` alone on
     * rows that already exist - the upsert keeps the user's local flag.
     */
    @Query(
        "UPDATE calendars SET isSelected = :isSelected " +
            "WHERE accountId = :accountId AND calendarId = :calendarId"
    )
    suspend fun setSelected(accountId: Long, calendarId: String, isSelected: Boolean)

    /**
     * Drop everything refresh-by-refresh: used by sync when the API
     * returned an empty page (caller still has to walk all visible
     * accounts). Defensive: a user who loses ALL their subscriptions would
     * otherwise never re-enter the snapshot.
     */
    @Query("DELETE FROM calendars WHERE accountId = :accountId")
    suspend fun deleteByAccount(accountId: Long)

    /** Test / migration helper: trim the table to the supplied id set. */
    @Query("DELETE FROM calendars WHERE accountId = :accountId AND calendarId NOT IN (:keepIds)")
    suspend fun deleteExcluding(accountId: Long, keepIds: List<String>)
}
