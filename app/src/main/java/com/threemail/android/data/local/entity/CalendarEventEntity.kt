package com.threemail.android.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "calendar_events",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["accountId", "calendarId", "eventId"], unique = true),
        Index(value = ["accountId", "startEpochMs"]),
        Index(value = ["accountId", "endEpochMs"])
    ]
)
data class CalendarEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val accountId: Long,
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
    val syncedAt: Long = 0
)
