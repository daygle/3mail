package com.threemail.android.data.remote.calendar

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

/**
 * Minimal, dependency-free iCalendar (RFC 5545) parser for read-only ICS
 * subscription feeds.
 *
 * Supported:
 *  - line unfolding (CRLF/LF + leading space or tab) and text unescaping
 *  - `VEVENT` components (nested components like `VALARM` are skipped)
 *  - `DTSTART`/`DTEND` in the three RFC forms: UTC (`...Z`), zoned
 *    (`TZID=...`), floating (interpreted in [ZoneId.systemDefault] unless a
 *    default zone is supplied), plus `VALUE=DATE` all-day values
 *  - `DTEND` omitted: all-day events span one day, timed events use
 *    `DURATION` when present (weeks handled manually; `Duration.parse`
 *    covers the rest) or are zero-length
 *  - `RRULE` expansion for `FREQ=DAILY/WEEKLY/MONTHLY/YEARLY` with
 *    `INTERVAL`, `COUNT`, `UNTIL`, and weekly `BYDAY`; `EXDATE` removes
 *    instances
 *
 * Known limitations (documented, acceptable for subscription feeds):
 *  - `BYDAY` with ordinals (e.g. `2SU` in MONTHLY rules), `BYMONTH`,
 *    `BYSETPOS` are not expanded — such rules fall back to their simple
 *    frequency stepping
 *  - `RECURRENCE-ID` per-instance overrides are ignored (the instance
 *    renders at its original slot); `EXDATE`-style cancellations work
 *  - `VTIMEZONE` blocks are skipped; `TZID` values are resolved through
 *    [ZoneId.of] (with a suffix-match fallback for prefixed ids like
 *    `/somevendor/Europe/Paris`), else the default zone
 *
 * Expansion is bounded to a caller-supplied window so an unbounded
 * recurring rule ("every Monday forever") produces only the occurrences
 * the calendar can show.
 */
object IcsParser {

    /** One concrete event occurrence, ready to cache. */
    data class ParsedEvent(
        val uid: String?,
        val title: String,
        val description: String?,
        val location: String?,
        val startEpochMs: Long,
        /** Exclusive. All-day follows the app convention: UTC midnight after the last day. */
        val endEpochMs: Long,
        val allDay: Boolean,
        val timezone: String,
        val status: String,
        val organizer: String?
    )

    /** Hard cap on expanded instances per VEVENT to bound pathological rules. */
    private const val MAX_OCCURRENCES = 1000

    private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val DATETIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

    /**
     * Parses [ics] and returns every event occurrence overlapping the
     * half-open window `[windowStartMs, windowEndMs)`. Recurring events are
     * expanded inside the window; single events outside it are dropped.
     */
    fun parse(
        ics: String,
        windowStartMs: Long,
        windowEndMs: Long,
        defaultZone: ZoneId = ZoneId.systemDefault()
    ): List<ParsedEvent> {
        val events = mutableListOf<ParsedEvent>()
        for (block in extractVEvents(unfoldLines(ics))) {
            runCatching {
                events += expandEvent(block, windowStartMs, windowEndMs, defaultZone)
            }
            // A malformed VEVENT shouldn't sink the whole feed; skip it.
        }
        return events.sortedWith(compareBy({ !it.allDay }, { it.startEpochMs }))
    }

    /**
     * The feed's own display name (`X-WR-CALNAME`, the de-facto standard
     * emitted by Google/Apple/Outlook exports), if present at the calendar
     * level. Used to pre-fill the subscription name.
     */
    fun calendarName(ics: String): String? {
        var inComponent = false
        for (line in unfoldLines(ics)) {
            val upper = line.uppercase()
            when {
                upper.startsWith("BEGIN:") && upper != "BEGIN:VCALENDAR" -> inComponent = true
                upper.startsWith("END:") && upper != "END:VCALENDAR" -> inComponent = false
                !inComponent && upper.startsWith("X-WR-CALNAME") ->
                    return parseProperty(line)?.value?.let { unescapeText(it) }
                        ?.trim()?.ifBlank { null }
            }
        }
        return null
    }

    /* ---------- lexing ---------- */

    /** RFC 5545 §3.1: a line starting with SPACE/HTAB continues the previous line. */
    internal fun unfoldLines(ics: String): List<String> {
        val out = mutableListOf<StringBuilder>()
        for (raw in ics.split("\r\n", "\n")) {
            if (raw.isEmpty() && out.isEmpty()) continue
            if ((raw.startsWith(" ") || raw.startsWith("\t")) && out.isNotEmpty()) {
                out.last().append(raw.substring(1))
            } else {
                out.add(StringBuilder(raw))
            }
        }
        return out.map { it.toString() }.filter { it.isNotBlank() }
    }

    /** One property line, split into name, params, and raw value. */
    internal data class Property(
        val name: String,
        val params: Map<String, String>,
        val value: String
    )

    /**
     * Splits `NAME;PARAM=V;PARAM="quoted:v":value` respecting quoted param
     * values (a `:` inside quotes doesn't end the prelude).
     */
    internal fun parseProperty(line: String): Property? {
        var inQuotes = false
        var colonAt = -1
        for (i in line.indices) {
            when (line[i]) {
                '"' -> inQuotes = !inQuotes
                ':' -> if (!inQuotes) {
                    colonAt = i; break
                }
            }
        }
        if (colonAt <= 0) return null
        val prelude = line.substring(0, colonAt)
        val value = line.substring(colonAt + 1)
        val parts = splitRespectingQuotes(prelude, ';')
        val name = parts.firstOrNull()?.uppercase() ?: return null
        val params = mutableMapOf<String, String>()
        for (p in parts.drop(1)) {
            val eq = p.indexOf('=')
            if (eq > 0) {
                params[p.substring(0, eq).uppercase()] =
                    p.substring(eq + 1).trim('"')
            }
        }
        return Property(name, params, value)
    }

    private fun splitRespectingQuotes(text: String, delimiter: Char): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        for (c in text) {
            when {
                c == '"' -> {
                    inQuotes = !inQuotes; current.append(c)
                }
                c == delimiter && !inQuotes -> {
                    parts.add(current.toString()); current.clear()
                }
                else -> current.append(c)
            }
        }
        parts.add(current.toString())
        return parts
    }

    /** Groups the unfolded lines into top-level VEVENT blocks, skipping nested components. */
    private fun extractVEvents(lines: List<String>): List<List<Property>> {
        val events = mutableListOf<List<Property>>()
        var current: MutableList<Property>? = null
        var nestedDepth = 0
        for (line in lines) {
            val upper = line.uppercase()
            when {
                upper == "BEGIN:VEVENT" -> {
                    current = mutableListOf(); nestedDepth = 0
                }
                upper == "END:VEVENT" -> {
                    current?.let { events.add(it) }; current = null
                }
                current != null && upper.startsWith("BEGIN:") -> nestedDepth++
                current != null && upper.startsWith("END:") -> {
                    if (nestedDepth > 0) nestedDepth--
                }
                current != null && nestedDepth == 0 ->
                    parseProperty(line)?.let { current.add(it) }
            }
        }
        return events
    }

    /* ---------- value decoding ---------- */

    /** RFC 5545 §3.3.11 TEXT unescaping. */
    internal fun unescapeText(value: String): String {
        val sb = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                when (val next = value[i + 1]) {
                    'n', 'N' -> sb.append('\n')
                    else -> sb.append(next)
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    private fun resolveZone(tzid: String?, defaultZone: ZoneId): ZoneId {
        if (tzid == null) return defaultZone
        runCatching { return ZoneId.of(tzid) }
        // Vendor-prefixed ids like "/freeassociation.sourceforge.net/Europe/Paris":
        // try the trailing Region/City pair.
        val segments = tzid.trim('/').split('/')
        if (segments.size >= 2) {
            val candidate = segments.takeLast(2).joinToString("/")
            runCatching { return ZoneId.of(candidate) }
        }
        return defaultZone
    }

    /** A decoded DTSTART/DTEND: either an all-day date or a zoned instant. */
    internal data class IcsTime(
        val date: LocalDate?,
        val dateTime: LocalDateTime?,
        val zone: ZoneId,
        val isUtc: Boolean
    ) {
        val isDate: Boolean get() = date != null

        fun toEpochMs(): Long = if (date != null) {
            date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        } else {
            dateTime!!.atZone(if (isUtc) ZoneOffset.UTC else zone).toInstant().toEpochMilli()
        }
    }

    internal fun parseTime(prop: Property, defaultZone: ZoneId): IcsTime? {
        val raw = prop.value.trim()
        val zone = resolveZone(prop.params["TZID"], defaultZone)
        return when {
            prop.params["VALUE"] == "DATE" || (raw.length == 8 && raw.all(Char::isDigit)) ->
                runCatching {
                    IcsTime(LocalDate.parse(raw, DATE_FORMAT), null, zone, isUtc = false)
                }.getOrNull()
            raw.endsWith("Z") ->
                runCatching {
                    IcsTime(
                        null,
                        LocalDateTime.parse(raw.dropLast(1), DATETIME_FORMAT),
                        ZoneOffset.UTC,
                        isUtc = true
                    )
                }.getOrNull()
            else ->
                runCatching {
                    IcsTime(null, LocalDateTime.parse(raw, DATETIME_FORMAT), zone, isUtc = false)
                }.getOrNull()
        }
    }

    /** `P1W`/`P2DT3H` style durations; weeks aren't supported by [Duration.parse]. */
    internal fun parseDuration(value: String): Duration? {
        val trimmed = value.trim().removePrefix("+")
        val weekMatch = Regex("^P(\\d+)W$").find(trimmed)
        if (weekMatch != null) {
            return Duration.ofDays(weekMatch.groupValues[1].toLong() * 7)
        }
        return runCatching { Duration.parse(trimmed) }.getOrNull()
    }

    /* ---------- expansion ---------- */

    private data class RecurrenceRule(
        val freq: String,
        val interval: Int,
        val count: Int?,
        val untilMs: Long?,
        val byDay: List<java.time.DayOfWeek>
    )

    private fun parseRRule(value: String, defaultZone: ZoneId): RecurrenceRule? {
        val parts = value.split(';').mapNotNull {
            val eq = it.indexOf('=')
            if (eq > 0) it.substring(0, eq).uppercase() to it.substring(eq + 1) else null
        }.toMap()
        val freq = parts["FREQ"]?.uppercase() ?: return null
        if (freq !in setOf("DAILY", "WEEKLY", "MONTHLY", "YEARLY")) return null
        val interval = parts["INTERVAL"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val count = parts["COUNT"]?.toIntOrNull()
        val untilMs = parts["UNTIL"]?.let { raw ->
            parseTime(Property("UNTIL", emptyMap(), raw), defaultZone)?.let { t ->
                if (t.isDate) {
                    // Inclusive date: cover the whole final day.
                    t.date!!.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() - 1
                } else {
                    t.toEpochMs()
                }
            }
        }
        val byDay = if (freq == "WEEKLY") {
            parts["BYDAY"]?.split(',')?.mapNotNull { dayCodeToDayOfWeek(it.trim()) } ?: emptyList()
        } else {
            emptyList()
        }
        return RecurrenceRule(freq, interval, count, untilMs, byDay)
    }

    private fun dayCodeToDayOfWeek(code: String): java.time.DayOfWeek? = when (code.uppercase()) {
        "MO" -> java.time.DayOfWeek.MONDAY
        "TU" -> java.time.DayOfWeek.TUESDAY
        "WE" -> java.time.DayOfWeek.WEDNESDAY
        "TH" -> java.time.DayOfWeek.THURSDAY
        "FR" -> java.time.DayOfWeek.FRIDAY
        "SA" -> java.time.DayOfWeek.SATURDAY
        "SU" -> java.time.DayOfWeek.SUNDAY
        else -> null // ordinal codes like "2SU" unsupported
    }

    private fun expandEvent(
        props: List<Property>,
        windowStartMs: Long,
        windowEndMs: Long,
        defaultZone: ZoneId
    ): List<ParsedEvent> {
        val byName = props.groupBy { it.name }
        fun single(name: String): Property? = byName[name]?.firstOrNull()

        val dtStartProp = single("DTSTART") ?: return emptyList()
        val start = parseTime(dtStartProp, defaultZone) ?: return emptyList()

        val uid = single("UID")?.value?.trim()
        val title = single("SUMMARY")?.let { unescapeText(it.value) }?.ifBlank { null }
            ?: "(No title)"
        val description = single("DESCRIPTION")?.let { unescapeText(it.value) }?.ifBlank { null }
        val location = single("LOCATION")?.let { unescapeText(it.value) }?.ifBlank { null }
        val status = single("STATUS")?.value?.trim()?.lowercase() ?: "confirmed"
        val organizer = single("ORGANIZER")?.value?.trim()
            ?.removePrefix("mailto:")?.removePrefix("MAILTO:")?.ifBlank { null }

        val allDay = start.isDate
        val startMs = start.toEpochMs()
        val endMs = single("DTEND")?.let { parseTime(it, defaultZone)?.toEpochMs() }
            ?: single("DURATION")?.let { parseDuration(it.value) }
                ?.let { startMs + it.toMillis() }
            ?: if (allDay) startMs + ONE_DAY_MS else startMs
        val durationMs = (endMs - startMs).coerceAtLeast(0)

        val timezone = if (allDay) "UTC" else if (start.isUtc) "UTC" else start.zone.id

        val exdates = byName["EXDATE"].orEmpty().flatMap { prop ->
            prop.value.split(',').mapNotNull { raw ->
                parseTime(Property("EXDATE", prop.params, raw.trim()), defaultZone)?.toEpochMs()
            }
        }.toHashSet()

        val rrule = single("RRULE")?.let { parseRRule(it.value, defaultZone) }

        fun occurrence(occStartMs: Long): ParsedEvent = ParsedEvent(
            uid = uid,
            title = title,
            description = description,
            location = location,
            startEpochMs = occStartMs,
            endEpochMs = occStartMs + durationMs,
            allDay = allDay,
            timezone = timezone,
            status = status,
            organizer = organizer
        )

        fun overlapsWindow(s: Long): Boolean =
            (s + durationMs) > windowStartMs && s < windowEndMs

        if (rrule == null) {
            return if (overlapsWindow(startMs) && startMs !in exdates) {
                listOf(occurrence(startMs))
            } else {
                emptyList()
            }
        }

        // Step occurrences in the event's own zone so local wall-time stays
        // stable across DST changes (all-day steps are pure date math).
        val zone: ZoneId = if (allDay) ZoneOffset.UTC else if (start.isUtc) ZoneOffset.UTC else start.zone
        val startZdt = Instant.ofEpochMilli(startMs).atZone(zone)
        val out = mutableListOf<ParsedEvent>()
        var emittedOrSkipped = 0

        // For unbounded rules (no COUNT) we can skip whole intervals that end
        // before the window instead of stepping one occurrence at a time —
        // otherwise a daily event with a years-old DTSTART would burn the
        // MAX_OCCURRENCES budget before reaching today. COUNT rules must step
        // from DTSTART because every generated instance consumes the count.
        fun fastForwardWholeIntervals(
            from: java.time.ZonedDateTime,
            unitMs: Long
        ): java.time.ZonedDateTime {
            if (rrule.count != null) return from
            val firstRelevant = windowStartMs - durationMs
            val behindMs = firstRelevant - from.toInstant().toEpochMilli()
            if (behindMs <= 0) return from
            val stepMs = unitMs * rrule.interval
            // One interval short of the window so the loop lands cleanly.
            val jumps = (behindMs / stepMs - 1).coerceAtLeast(0)
            return when (rrule.freq) {
                "DAILY" -> from.plusDays(jumps * rrule.interval)
                else -> from.plusWeeks(jumps * rrule.interval) // WEEKLY
            }
        }

        if (rrule.freq == "WEEKLY" && rrule.byDay.isNotEmpty()) {
            // Week-by-week: each interval-week emits the BYDAY days at the
            // DTSTART wall time. Occurrences before DTSTART don't count.
            var weekAnchor = fastForwardWholeIntervals(startZdt, ONE_WEEK_MS)
                .with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
            while (emittedOrSkipped < MAX_OCCURRENCES) {
                var doneByCount = false
                for (day in rrule.byDay.sortedBy { it.value }) {
                    val occ = weekAnchor.with(TemporalAdjusters.nextOrSame(day))
                    if (occ.isBefore(startZdt)) continue
                    val occMs = occ.toInstant().toEpochMilli()
                    if (rrule.untilMs != null && occMs > rrule.untilMs) {
                        doneByCount = true; break
                    }
                    emittedOrSkipped++
                    if (rrule.count != null && emittedOrSkipped > rrule.count) {
                        doneByCount = true; break
                    }
                    if (occMs !in exdates && overlapsWindow(occMs)) out.add(occurrence(occMs))
                    if (occMs >= windowEndMs && rrule.count == null) {
                        doneByCount = true; break
                    }
                }
                if (doneByCount) break
                weekAnchor = weekAnchor.plusWeeks(rrule.interval.toLong())
            }
            return out
        }

        var cursor = when (rrule.freq) {
            "DAILY" -> fastForwardWholeIntervals(startZdt, ONE_DAY_MS)
            "WEEKLY" -> fastForwardWholeIntervals(startZdt, ONE_WEEK_MS)
            else -> startZdt // MONTHLY/YEARLY step cheaply; no jump needed
        }
        while (emittedOrSkipped < MAX_OCCURRENCES) {
            val occMs = cursor.toInstant().toEpochMilli()
            if (rrule.untilMs != null && occMs > rrule.untilMs) break
            emittedOrSkipped++
            if (rrule.count != null && emittedOrSkipped > rrule.count) break
            if (occMs !in exdates && overlapsWindow(occMs)) out.add(occurrence(occMs))
            if (occMs >= windowEndMs && rrule.count == null) break
            cursor = when (rrule.freq) {
                "DAILY" -> cursor.plusDays(rrule.interval.toLong())
                "WEEKLY" -> cursor.plusWeeks(rrule.interval.toLong())
                "MONTHLY" -> nextMonthlyOccurrence(cursor, startZdt.dayOfMonth, rrule.interval)
                else -> cursor.plusYears(rrule.interval.toLong()) // YEARLY
            }
        }
        return out
    }

    /**
     * Monthly stepping that honours the original day-of-month: from a cursor
     * that may have been clamped (e.g. Jan 31 -> Feb 28), advance by
     * [interval] months from the cursor's month and re-apply [targetDay],
     * skipping months too short to hold it (RFC behaviour: an invalid
     * BYMONTHDAY date is skipped, not clamped).
     */
    private fun nextMonthlyOccurrence(
        cursor: java.time.ZonedDateTime,
        targetDay: Int,
        interval: Int
    ): java.time.ZonedDateTime {
        var monthCursor = cursor.withDayOfMonth(1)
        repeat(48) {
            monthCursor = monthCursor.plusMonths(interval.toLong())
            if (monthCursor.toLocalDate().lengthOfMonth() >= targetDay) {
                return monthCursor.withDayOfMonth(targetDay)
            }
        }
        return cursor.plusMonths(interval.toLong()) // give up; clamped fallback
    }

    private const val ONE_DAY_MS = 86_400_000L
    private const val ONE_WEEK_MS = 7 * ONE_DAY_MS
}
