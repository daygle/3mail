package com.threemail.android.domain.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

data class CalendarEvent(
    val id: Long = 0,
    val accountId: Long,
    val calendarId: String = DEFAULT_CALENDAR_ID,
    val eventId: String? = null,
    val iCalUID: String? = null,
    val title: String,
    val description: String? = null,
    val location: String? = null,
    /** Inclusive start. For all-day, millis at UTC midnight of the start date. */
    val startEpochMs: Long,
    /** Exclusive end. For all-day, millis at UTC midnight of the day AFTER the end date. */
    val endEpochMs: Long,
    val allDay: Boolean,
    val timezone: String = ZoneId.systemDefault().id,
    val status: CalendarEventStatus = CalendarEventStatus.CONFIRMED,
    val organizer: String? = null,
    val attendees: List<String> = emptyList(),
    val htmlLink: String? = null,
    val syncedAt: Long = 0
) {
    val isEditable: Boolean get() = eventId != null || id > 0

    companion object {
        const val DEFAULT_CALENDAR_ID = "primary"
    }
}

enum class CalendarEventStatus {
    CONFIRMED,
    TENTATIVE,
    CANCELLED;

    companion object {
        fun fromApiValue(value: String?): CalendarEventStatus = when (value?.lowercase()) {
            "tentative" -> TENTATIVE
            "cancelled", "canceled" -> CANCELLED
            else -> CONFIRMED
        }
    }
}

/** True if this event occurs on the given local [date] in the device's timezone. */
fun CalendarEvent.occursOn(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Boolean {
    return if (allDay) {
        val start = Instant.ofEpochMilli(startEpochMs).atZone(ZoneOffset.UTC).toLocalDate()
        val end = Instant.ofEpochMilli(endEpochMs - ONE_DAY_MS).atZone(ZoneOffset.UTC).toLocalDate()
        !date.isBefore(start) && !date.isAfter(end)
    } else {
        val start = Instant.ofEpochMilli(startEpochMs).atZone(zone).toLocalDate()
        val end = Instant.ofEpochMilli(endEpochMs).atZone(zone).toLocalDate()
        !date.isBefore(start) && !date.isAfter(end)
    }
}

private const val ONE_DAY_MS = 86_400_000L
