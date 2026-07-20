package com.threemail.android.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Room-backed representation of one row in the user's calendar list
 * (mirrors the domain [com.threemail.android.domain.model.CalendarEntry]).
 *
 * Why a separate table from `messages_fts`-style side tables:
 *  - Calendar events are keyed by `events.eventId` (server-stable id) inside
 *    [CalendarEventEntity] which already filters by `calendarId`. We need
 *    calendar-level metadata on the calendar_id itself, so a separate row
 *    per (accountId, calendarId) is the natural shape.
 *  - Cross-account calendars share their `calendarId` namespace on Google's
 *    side ("primary" appears for every Gmail login), so the composite
 *    primary key `(accountId, calendarId)` disambiguates them naturally.
 *
 * `isSelected` is the user-controllable visibility flag. The
 * CalendarScreen's filter logic consults this rather than
 * `account.calendarSyncEnabled` so toggling doesn't drop mail sync for
 * the parent account; it just narrows which calendars the calendar screen
 * is willing to render events from.
 *
 * Cascading FK on account matches the rest of the schema: account
 * deletion clears its calendar-row entries. There is intentionally NO FK
 * to `calendar_events` because the events table is a cache the sync
 * worker manages row-wise, and dropping here would lose every event
 * belonging to the just-deleted calendar without giving the worker a
 * chance to clean up its own rows.
 */
@Entity(
    tableName = "calendars",
    primaryKeys = ["accountId", "calendarId"],
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    // Required: Room treats the FK child column as needing an explicit
    // named index (SQLite's composite primary key only creates an implicit
    // b-tree on the leading column which the schema-checksum does NOT
    // accept as a substitute). Same pattern as MessageFlagEntity.
    indices = [Index(value = ["accountId"])]
)
data class CalendarEntryEntity(
    val accountId: Long,
    val calendarId: String,
    val summary: String,
    val description: String? = null,
    val timezone: String? = null,
    val isPrimary: Boolean = false,
    val accessRole: String = "reader",
    val backgroundColor: String? = null,
    val isSelected: Boolean = true,
    val lastSyncedAt: Long? = null
)
