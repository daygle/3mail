package com.threemail.android.domain.model

data class CalendarEvent(
    val id: String,
    val calendarId: String = "primary",
    val title: String,
    val description: String? = null,
    val location: String? = null,
    val start: Long,
    val end: Long,
    val allDay: Boolean = false,
    val organizer: String? = null,
    val attendees: List<String> = emptyList(),
    val htmlLink: String? = null
)
