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

    /**
     * Folds a content line with a leading-space continuation so that no
     * physical line exceeds 75 UTF-8 octets, per RFC 5545 §3.1.
     *
     * Lengths are counted in octets, not chars, and a fold never splits a
     * multi-byte sequence or a surrogate pair - either would corrupt the value
     * for the receiving parser.
     */
    internal fun fold(line: String): String {
        if (line.toByteArray(Charsets.UTF_8).size <= MAX_OCTETS) return line
        val sb = StringBuilder(line.length + line.length / MAX_OCTETS * 3)
        var used = 0
        var continuation = false
        var index = 0
        while (index < line.length) {
            val codePoint = line.codePointAt(index)
            val octets = utf8Octets(codePoint)
            // A continuation line spends one octet on its leading space.
            val limit = if (continuation) MAX_OCTETS - 1 else MAX_OCTETS
            if (used > 0 && used + octets > limit) {
                sb.append("\r\n ")
                used = 0
                continuation = true
                continue
            }
            sb.appendCodePoint(codePoint)
            used += octets
            index += Character.charCount(codePoint)
        }
        return sb.toString()
    }

    /** Maximum octets of content on the first physical line (75 with the fold space). */
    private const val MAX_OCTETS = 74

    /** UTF-8 encoded length of [codePoint], without allocating an array. */
    private fun utf8Octets(codePoint: Int): Int = when {
        codePoint < 0x80 -> 1
        codePoint < 0x800 -> 2
        codePoint < 0x10000 -> 3
        else -> 4
    }
}
