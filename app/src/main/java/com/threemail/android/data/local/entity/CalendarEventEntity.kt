package com.threemail.android.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One cached calendar event. Exactly one of two parents is set:
 *
 *  - `accountId` non-null: the event came from a Google account's calendar
 *    (fetched via the Calendar REST API; `calendarId` is Google's id).
 *  - `sourceId` non-null: the event came from a standalone subscription
 *    ([CalendarSourceEntity], e.g. an ICS feed); `accountId` is NULL and
 *    `calendarId` carries a `source:<id>` marker used only for stable
 *    colour hashing in the UI.
 *
 * Both FKs cascade so removing an account or a source drops its cached
 * events. The `(accountId, calendarId, eventId)` unique index only guards
 * Google rows (SQLite treats NULL as distinct in unique indexes, which is
 * what we want — ICS refreshes replace a source's rows wholesale instead
 * of upserting).
 */
@Entity(
    tableName = "calendar_events",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = CalendarSourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["accountId", "calendarId", "eventId"], unique = true),
        Index(value = ["accountId", "startEpochMs"]),
        Index(value = ["accountId", "endEpochMs"]),
        Index(value = ["sourceId"])
    ]
)
data class CalendarEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val accountId: Long? = null,
    val sourceId: Long? = null,
    val calendarId: String = "primary",
    val eventId: String? = null,
    val iCalUID: String? = null,
    val title: String,
    val description: String? = null,
    val location: String? = null,
    val startEpochMs: Long,
    val endEpochMs: Long,
    val allDay: Boolean,
    val timezone: String,
    val status: String,
    val organizer: String? = null,
    val attendeesJson: String = "[]",
    val htmlLink: String? = null,
    /**
     * CalDAV concurrency token for the backing calendar object. Only set on
     * events from a CALDAV source whose object holds a single instance
     * (recurring expansions are read-only); paired with `eventId`, which for
     * CalDAV rows carries the object's href.
     */
    val etag: String? = null,
    val syncedAt: Long = 0
)
