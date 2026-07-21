package com.threemail.android.data.remote.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

class IcsParserTest {

    private val utc: ZoneId = ZoneOffset.UTC

    /** Wide window covering 2026 so single-event tests don't fight the filter. */
    private val windowStart = utcMs(2026, 1, 1)
    private val windowEnd = utcMs(2027, 1, 1)

    private fun utcMs(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Long =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, ZoneOffset.UTC)
            .toInstant().toEpochMilli()

    private fun wrap(vararg vevent: String): String = buildString {
        append("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//test//EN\r\n")
        vevent.forEach { append(it) }
        append("END:VCALENDAR\r\n")
    }

    private fun event(vararg lines: String): String = buildString {
        append("BEGIN:VEVENT\r\n")
        lines.forEach { append(it).append("\r\n") }
        append("END:VEVENT\r\n")
    }

    @Test
    fun `parses a simple UTC event`() {
        val events = IcsParser.parse(
            wrap(
                event(
                    "UID:one@test",
                    "SUMMARY:Team standup",
                    "DTSTART:20260315T090000Z",
                    "DTEND:20260315T093000Z"
                )
            ),
            windowStart, windowEnd, utc
        )
        assertEquals(1, events.size)
        val e = events[0]
        assertEquals("Team standup", e.title)
        assertEquals("one@test", e.uid)
        assertEquals(utcMs(2026, 3, 15, 9, 0), e.startEpochMs)
        assertEquals(utcMs(2026, 3, 15, 9, 30), e.endEpochMs)
        assertEquals(false, e.allDay)
        assertEquals("UTC", e.timezone)
    }

    @Test
    fun `parses an all-day event with exclusive DTEND`() {
        val events = IcsParser.parse(
            wrap(
                event(
                    "SUMMARY:Conference",
                    "DTSTART;VALUE=DATE:20260401",
                    "DTEND;VALUE=DATE:20260403"
                )
            ),
            windowStart, windowEnd, utc
        )
        assertEquals(1, events.size)
        val e = events[0]
        assertTrue(e.allDay)
        assertEquals(utcMs(2026, 4, 1), e.startEpochMs)
        assertEquals(utcMs(2026, 4, 3), e.endEpochMs) // exclusive, 2-day span
    }

    @Test
    fun `all-day event without DTEND spans one day`() {
        val events = IcsParser.parse(
            wrap(event("SUMMARY:Holiday", "DTSTART;VALUE=DATE:20260501")),
            windowStart, windowEnd, utc
        )
        assertEquals(1, events.size)
        assertEquals(utcMs(2026, 5, 2), events[0].endEpochMs)
    }

    @Test
    fun `honours TZID on DTSTART`() {
        val events = IcsParser.parse(
            wrap(
                event(
                    "SUMMARY:Paris lunch",
                    "DTSTART;TZID=Europe/Paris:20260710T120000",
                    "DTEND;TZID=Europe/Paris:20260710T130000"
                )
            ),
            windowStart, windowEnd, utc
        )
        assertEquals(1, events.size)
        // July: Paris is UTC+2, so 12:00 local == 10:00 UTC.
        assertEquals(utcMs(2026, 7, 10, 10, 0), events[0].startEpochMs)
        assertEquals("Europe/Paris", events[0].timezone)
    }

    @Test
    fun `unfolds continuation lines and unescapes text`() {
        val ics = "BEGIN:VCALENDAR\r\n" +
            "BEGIN:VEVENT\r\n" +
            "SUMMARY:A very long su\r\n mmary line\r\n" +
            "DESCRIPTION:Line one\\nLine two\\, with comma\r\n" +
            "DTSTART:20260601T100000Z\r\n" +
            "END:VEVENT\r\n" +
            "END:VCALENDAR\r\n"
        val events = IcsParser.parse(ics, windowStart, windowEnd, utc)
        assertEquals("A very long summary line", events[0].title)
        assertEquals("Line one\nLine two, with comma", events[0].description)
    }

    @Test
    fun `skips VALARM nested inside VEVENT`() {
        val events = IcsParser.parse(
            wrap(
                event(
                    "SUMMARY:With alarm",
                    "DTSTART:20260201T080000Z",
                    "BEGIN:VALARM",
                    "TRIGGER:-PT15M",
                    "DESCRIPTION:Reminder blurb",
                    "END:VALARM"
                )
            ),
            windowStart, windowEnd, utc
        )
        assertEquals(1, events.size)
        // The alarm's DESCRIPTION must not leak onto the event.
        assertNull(events[0].description)
    }

    @Test
    fun `drops events outside the window`() {
        val events = IcsParser.parse(
            wrap(
                event("SUMMARY:Old", "DTSTART:20200101T090000Z", "DTEND:20200101T100000Z"),
                event("SUMMARY:Current", "DTSTART:20260601T090000Z", "DTEND:20260601T100000Z")
            ),
            windowStart, windowEnd, utc
        )
        assertEquals(1, events.size)
        assertEquals("Current", events[0].title)
    }

    @Test
    fun `expands a weekly rule with COUNT`() {
        val events = IcsParser.parse(
            wrap(
                event(
                    "SUMMARY:Weekly sync",
                    "DTSTART:20260105T140000Z", // a Monday
                    "DTEND:20260105T150000Z",
                    "RRULE:FREQ=WEEKLY;COUNT=3"
                )
            ),
            windowStart, windowEnd, utc
        )
        assertEquals(3, events.size)
        assertEquals(utcMs(2026, 1, 5, 14, 0), events[0].startEpochMs)
        assertEquals(utcMs(2026, 1, 12, 14, 0), events[1].startEpochMs)
        assertEquals(utcMs(2026, 1, 19, 14, 0), events[2].startEpochMs)
    }

    @Test
    fun `expands a weekly BYDAY rule`() {
        val events = IcsParser.parse(
            wrap(
                event(
                    "SUMMARY:Gym",
                    "DTSTART:20260105T070000Z", // Monday 5 Jan 2026
                    "DTEND:20260105T080000Z",
                    "RRULE:FREQ=WEEKLY;BYDAY=MO,WE;COUNT=4"
                )
            ),
            windowStart, windowEnd, utc
        )
        assertEquals(4, events.size)
        assertEquals(utcMs(2026, 1, 5, 7, 0), events[0].startEpochMs) // Mon
        assertEquals(utcMs(2026, 1, 7, 7, 0), events[1].startEpochMs) // Wed
        assertEquals(utcMs(2026, 1, 12, 7, 0), events[2].startEpochMs) // Mon
        assertEquals(utcMs(2026, 1, 14, 7, 0), events[3].startEpochMs) // Wed
    }

    @Test
    fun `respects UNTIL and EXDATE`() {
        val events = IcsParser.parse(
            wrap(
                event(
                    "SUMMARY:Daily brief",
                    "DTSTART:20260201T090000Z",
                    "DTEND:20260201T091500Z",
                    "RRULE:FREQ=DAILY;UNTIL=20260204T090000Z",
                    "EXDATE:20260202T090000Z"
                )
            ),
            windowStart, windowEnd, utc
        )
        // 1st..4th daily = 4, minus the excluded 2nd = 3.
        assertEquals(3, events.size)
        assertEquals(utcMs(2026, 2, 1, 9, 0), events[0].startEpochMs)
        assertEquals(utcMs(2026, 2, 3, 9, 0), events[1].startEpochMs)
        assertEquals(utcMs(2026, 2, 4, 9, 0), events[2].startEpochMs)
    }

    @Test
    fun `yearly rule with old DTSTART lands in window`() {
        val events = IcsParser.parse(
            wrap(
                event(
                    "SUMMARY:Anniversary",
                    "DTSTART;VALUE=DATE:20100615",
                    "RRULE:FREQ=YEARLY"
                )
            ),
            windowStart, windowEnd, utc
        )
        assertEquals(1, events.size)
        assertEquals(utcMs(2026, 6, 15), events[0].startEpochMs)
        assertTrue(events[0].allDay)
    }

    @Test
    fun `unbounded daily rule with years-old DTSTART reaches the window`() {
        val events = IcsParser.parse(
            wrap(
                event(
                    "SUMMARY:Old daily",
                    "DTSTART:20180101T060000Z",
                    "DTEND:20180101T061000Z",
                    "RRULE:FREQ=DAILY"
                )
            ),
            // Narrow one-week window in 2026 — over 2900 days after DTSTART.
            utcMs(2026, 3, 2), utcMs(2026, 3, 9), utc
        )
        assertEquals(7, events.size)
        assertEquals(utcMs(2026, 3, 2, 6, 0), events[0].startEpochMs)
    }

    @Test
    fun `monthly rule skips short months for day 31`() {
        val events = IcsParser.parse(
            wrap(
                event(
                    "SUMMARY:Rent due",
                    "DTSTART;VALUE=DATE:20260131",
                    "RRULE:FREQ=MONTHLY;COUNT=4"
                )
            ),
            windowStart, windowEnd, utc
        )
        // Jan 31, (Feb skipped), Mar 31, (Apr skipped), May 31, (Jun skipped), Jul 31
        assertEquals(4, events.size)
        assertEquals(utcMs(2026, 1, 31), events[0].startEpochMs)
        assertEquals(utcMs(2026, 3, 31), events[1].startEpochMs)
        assertEquals(utcMs(2026, 5, 31), events[2].startEpochMs)
        assertEquals(utcMs(2026, 7, 31), events[3].startEpochMs)
    }

    @Test
    fun `timed event without DTEND uses DURATION`() {
        val events = IcsParser.parse(
            wrap(
                event(
                    "SUMMARY:Long haul",
                    "DTSTART:20260901T080000Z",
                    "DURATION:PT2H30M"
                )
            ),
            windowStart, windowEnd, utc
        )
        assertEquals(utcMs(2026, 9, 1, 10, 30), events[0].endEpochMs)
    }

    @Test
    fun `week duration is parsed manually`() {
        assertEquals(7L * 24 * 60 * 60 * 1000, IcsParser.parseDuration("P1W")!!.toMillis())
    }

    @Test
    fun `organizer mailto prefix is stripped`() {
        val events = IcsParser.parse(
            wrap(
                event(
                    "SUMMARY:Meet",
                    "ORGANIZER;CN=Jane:mailto:jane@example.org",
                    "DTSTART:20260501T100000Z"
                )
            ),
            windowStart, windowEnd, utc
        )
        assertEquals("jane@example.org", events[0].organizer)
    }

    @Test
    fun `quoted TZID parameter with colon does not break the property split`() {
        val prop = IcsParser.parseProperty(
            "DTSTART;TZID=\"weird:zone\":20260101T000000"
        )
        assertEquals("DTSTART", prop!!.name)
        assertEquals("weird:zone", prop.params["TZID"])
        assertEquals("20260101T000000", prop.value)
    }

    @Test
    fun `malformed VEVENT does not sink the rest of the feed`() {
        val events = IcsParser.parse(
            wrap(
                event("SUMMARY:No start at all"),
                event("SUMMARY:Fine", "DTSTART:20260601T090000Z")
            ),
            windowStart, windowEnd, utc
        )
        assertEquals(1, events.size)
        assertEquals("Fine", events[0].title)
    }

    @Test
    fun `floating time uses the supplied default zone`() {
        val events = IcsParser.parse(
            wrap(event("SUMMARY:Floating", "DTSTART:20260801T120000")),
            windowStart, windowEnd, ZoneId.of("Australia/Sydney")
        )
        // August: Sydney is UTC+10, so 12:00 local == 02:00 UTC.
        val expected = ZonedDateTime.of(2026, 8, 1, 12, 0, 0, 0, ZoneId.of("Australia/Sydney"))
            .toInstant().toEpochMilli()
        assertEquals(expected, events[0].startEpochMs)
        assertEquals(
            Instant.ofEpochMilli(utcMs(2026, 8, 1, 2, 0)),
            Instant.ofEpochMilli(events[0].startEpochMs)
        )
    }

    @Test
    fun `webcal url is normalized to https`() {
        assertEquals(
            "https://example.org/cal.ics",
            IcsCalendarClient.normalizeUrl("webcal://example.org/cal.ics")
        )
        assertEquals(
            "https://example.org/cal.ics",
            IcsCalendarClient.normalizeUrl("  webcals://example.org/cal.ics ")
        )
        assertEquals(
            "https://example.org/cal.ics",
            IcsCalendarClient.normalizeUrl("https://example.org/cal.ics")
        )
    }
}
