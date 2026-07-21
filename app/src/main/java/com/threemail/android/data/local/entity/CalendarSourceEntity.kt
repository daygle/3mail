package com.threemail.android.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room row for one standalone calendar source — a calendar the user
 * subscribed to that is NOT backed by a signed-in mail account (mirrors the
 * domain [com.threemail.android.domain.model.CalendarSource]).
 *
 * Why a separate table from `calendars`:
 *  - `calendars` rows are keyed `(accountId, calendarId)` with a cascading
 *    FK onto `accounts`; a public ICS feed or a CalDAV collection has no
 *    parent account, so shoehorning it in would need a fake account row.
 *  - Sources carry fetch state (`url`, `lastError`, credentials for CalDAV)
 *    that Google-backed calendar rows never need.
 *
 * `type` is the storage form of
 * [com.threemail.android.domain.model.CalendarSourceType] ("ICS"/"CALDAV").
 * `password` never leaves the data layer: the domain model deliberately
 * omits it, and only the CalDAV client reads it back.
 *
 * Events fetched for a source land in `calendar_events` with `sourceId`
 * set (and `accountId` NULL); the FK there cascades so deleting a source
 * drops its cached events with it.
 */
@Entity(tableName = "calendar_sources")
data class CalendarSourceEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val type: String,
    val url: String,
    val displayName: String,
    val color: String? = null,
    val username: String? = null,
    val password: String? = null,
    val isVisible: Boolean = true,
    val syncEnabled: Boolean = true,
    val lastSyncedAt: Long? = null,
    val lastError: String? = null
)
