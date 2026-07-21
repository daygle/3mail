package com.threemail.android.data.remote.calendar

import com.threemail.android.domain.model.CalendarEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime

class IcsWriterTest {

    private fun utcMs(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Long =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, ZoneOffset.UTC)
            .toInstant().toEpochMilli()

    @Test
    fun `written timed event round-trips through the parser`() {
        val event = CalendarEvent(
            accountId = CalendarEvent.NO_ACCOUNT,
            sourceId = 7L,
            title = "Board meeting; agenda, notes",
            description = "Line one\nLine two",
            location = "Room 4",
            startEpochMs = utcMs(2026, 9, 10, 14, 0),
            endEpochMs = utcMs(2026, 9, 10, 15, 30),
            allDay = false,
            timezone = "UTC"
        )
        val ics = IcsWriter.writeEvent(event, uid = "abc-123")
        assertTrue(ics.contains("UID:abc-123"))

        val parsed = IcsParser.parse(ics, utcMs(2026, 1, 1), utcMs(2027, 1, 1), ZoneOffset.UTC)
        assertEquals(1, parsed.size)
        val p = parsed[0]
        assertEquals("Board meeting; agenda, notes", p.title)
        assertEquals("Line one\nLine two", p.description)
        assertEquals("Room 4", p.location)
        assertEquals(event.startEpochMs, p.startEpochMs)
        assertEquals(event.endEpochMs, p.endEpochMs)
        assertEquals(false, p.allDay)
        assertEquals("abc-123", p.uid)
    }

    @Test
    fun `written all-day event round-trips with exclusive end`() {
        val event = CalendarEvent(
            accountId = CalendarEvent.NO_ACCOUNT,
            sourceId = 7L,
            title = "Offsite",
            startEpochMs = utcMs(2026, 5, 4),
            endEpochMs = utcMs(2026, 5, 6), // two days
            allDay = true,
            timezone = "UTC"
        )
        val ics = IcsWriter.writeEvent(event, uid = "u1")
        val parsed = IcsParser.parse(ics, utcMs(2026, 1, 1), utcMs(2027, 1, 1), ZoneOffset.UTC)
        assertEquals(1, parsed.size)
        assertTrue(parsed[0].allDay)
        assertEquals(event.startEpochMs, parsed[0].startEpochMs)
        assertEquals(event.endEpochMs, parsed[0].endEpochMs)
    }

    @Test
    fun `long lines are folded and lines use CRLF`() {
        val longTitle = "T".repeat(200)
        val ics = IcsWriter.writeEvent(
            CalendarEvent(
                accountId = CalendarEvent.NO_ACCOUNT,
                title = longTitle,
                startEpochMs = utcMs(2026, 1, 1, 9, 0),
                endEpochMs = utcMs(2026, 1, 1, 10, 0),
                allDay = false
            ),
            uid = "u2"
        )
        // Raw physical lines must respect the 75-octet cap (74 + fold space).
        ics.split("\r\n").forEach { line ->
            assertTrue("line too long: $line", line.length <= 75)
        }
        // And the parser must reassemble the folded title.
        val parsed = IcsParser.parse(ics, utcMs(2026, 1, 1), utcMs(2027, 1, 1), ZoneOffset.UTC)
        assertEquals(longTitle, parsed[0].title)
    }
}
