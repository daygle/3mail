package com.threemail.android.data.remote.calendar

import com.threemail.android.domain.model.CalendarEvent
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Serialises one [CalendarEvent] into an iCalendar object suitable for a
 * CalDAV `PUT`. Timed events are written in UTC (`...Z`) so no `VTIMEZONE`
 * block is required; all-day events use `VALUE=DATE` with the app's
 * exclusive-end convention, which matches RFC 5545's exclusive `DTEND`.
 * Output lines are CRLF-terminated and folded at 74 octets per RFC 5545 §3.1.
 */
object IcsWriter {

    private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC)
    private val UTC_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

    fun writeEvent(event: CalendarEvent, uid: String): String {
        val lines = mutableListOf(
            "BEGIN:VCALENDAR",
            "VERSION:2.0",
            "PRODID:-//3mail//3mail Android//EN",
            "BEGIN:VEVENT",
            "UID:$uid",
            "DTSTAMP:${UTC_FORMAT.format(Instant.now())}"
        )
        if (event.allDay) {
            lines += "DTSTART;VALUE=DATE:${DATE_FORMAT.format(Instant.ofEpochMilli(event.startEpochMs))}"
            lines += "DTEND;VALUE=DATE:${DATE_FORMAT.format(Instant.ofEpochMilli(event.endEpochMs))}"
        } else {
            lines += "DTSTART:${UTC_FORMAT.format(Instant.ofEpochMilli(event.startEpochMs))}"
            lines += "DTEND:${UTC_FORMAT.format(Instant.ofEpochMilli(event.endEpochMs))}"
        }
        lines += "SUMMARY:${escapeText(event.title)}"
        event.description?.takeIf { it.isNotBlank() }?.let {
            lines += "DESCRIPTION:${escapeText(it)}"
        }
        event.location?.takeIf { it.isNotBlank() }?.let {
            lines += "LOCATION:${escapeText(it)}"
        }
        lines += "STATUS:${event.status.name}"
        lines += "END:VEVENT"
        lines += "END:VCALENDAR"
        return lines.joinToString("") { fold(it) + "\r\n" }
    }

    /** RFC 5545 §3.3.11 TEXT escaping (inverse of [IcsParser.unescapeText]). */
    internal fun escapeText(value: String): String = buildString(value.length) {
        for (c in value) {
            when (c) {
                '\\' -> append("\\\\")
                ';' -> append("\\;")
                ',' -> append("\\,")
                '\n' -> append("\\n")
                '\r' -> Unit
                else -> append(c)
            }
        }
    }

    /** Folds a content line at 74 chars with a leading-space continuation. */
    internal fun fold(line: String): String {
        if (line.length <= 74) return line
        val sb = StringBuilder()
        var index = 0
        while (index < line.length) {
            val chunk = line.substring(index, minOf(index + 74, line.length))
            if (index > 0) sb.append("\r\n ")
            sb.append(chunk)
            index += 74
        }
        return sb.toString()
    }
}
