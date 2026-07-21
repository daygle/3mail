package com.threemail.android.data.repository

import com.threemail.android.data.local.dao.CalendarEventDao
import com.threemail.android.data.local.dao.CalendarSourceDao
import com.threemail.android.data.local.entity.CalendarEventEntity
import com.threemail.android.data.local.entity.CalendarSourceEntity
import com.threemail.android.data.remote.calendar.IcsCalendarClient
import com.threemail.android.data.remote.calendar.IcsParser
import com.threemail.android.domain.model.CalendarEvent
import com.threemail.android.domain.model.CalendarSource
import com.threemail.android.domain.model.CalendarSourceType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Standalone calendar subscriptions (ICS feeds today, CalDAV later) —
 * the calendars that exist without a signed-in mail account.
 *
 * Mirrors [CalendarRepository]'s local-first shape: Room is the source of
 * truth for the UI, and [syncSource] replaces a source's cached window with
 * what the feed currently says. Sync bookkeeping (`lastSyncedAt` /
 * `lastError`) is recorded on the source row so Manage Calendars can show
 * per-feed health without extra queries.
 */
@Singleton
class CalendarSourceRepository @Inject constructor(
    private val sourceDao: CalendarSourceDao,
    private val eventDao: CalendarEventDao,
    private val icsClient: IcsCalendarClient
) {

    fun observeSources(): Flow<List<CalendarSource>> =
        sourceDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    suspend fun getSources(): List<CalendarSource> =
        sourceDao.getAll().map { it.toDomain() }

    /**
     * Events from every *visible* source overlapping `[startMs, endMs)`.
     * Reactive to both cached events and per-source visibility flips.
     */
    fun getEventsInRange(startMs: Long, endMs: Long): Flow<List<CalendarEvent>> =
        eventDao.getInRangeForVisibleSources(startMs, endMs)
            .map { rows -> rows.map { it.toDomain() } }

    /** One source's cached events, regardless of its visibility flag. */
    fun getEventsInRangeForSource(sourceId: Long, startMs: Long, endMs: Long): Flow<List<CalendarEvent>> =
        eventDao.getInRangeForSource(sourceId, startMs, endMs)
            .map { rows -> rows.map { it.toDomain() } }

    /**
     * Subscribes to an ICS/webcal feed: fetches it once up front (so a bad
     * URL fails here, before anything is persisted), names the subscription
     * from the feed's own `X-WR-CALNAME` when the user left the name blank,
     * inserts the source row, and caches the default event window.
     */
    suspend fun addIcsSource(url: String, displayName: String? = null): CalendarSource {
        val normalizedUrl = IcsCalendarClient.normalizeUrl(url)
        val body = icsClient.fetch(normalizedUrl)
        val name = displayName?.trim()?.ifBlank { null }
            ?: IcsParser.calendarName(body)
            ?: normalizedUrl.substringAfterLast('/').substringBefore('?').ifBlank { "Calendar" }
        val id = sourceDao.insert(
            CalendarSourceEntity(
                type = CalendarSourceType.ICS.name,
                url = normalizedUrl,
                displayName = name
            )
        )
        val (windowStart, windowEnd) = defaultWindow()
        cacheParsedEvents(id, body, windowStart, windowEnd)
        sourceDao.markSynced(id, System.currentTimeMillis())
        return sourceDao.getById(id)!!.toDomain()
    }

    suspend fun setVisible(id: Long, isVisible: Boolean) = sourceDao.setVisible(id, isVisible)

    suspend fun setSyncEnabled(id: Long, enabled: Boolean) = sourceDao.setSyncEnabled(id, enabled)

    suspend fun rename(id: Long, displayName: String) {
        displayName.trim().ifBlank { null }?.let { sourceDao.setDisplayName(id, it) }
    }

    /** Cached events cascade-delete via the `calendar_events.sourceId` FK. */
    suspend fun delete(id: Long) = sourceDao.delete(id)

    /**
     * Refreshes one source's cached window. Failures are recorded on the row
     * (`lastError`) and returned rather than thrown so a broken feed can't
     * sink a multi-source sync loop.
     */
    suspend fun syncSource(
        source: CalendarSource,
        windowStartMs: Long,
        windowEndMs: Long
    ): Result<Unit> = runCatching {
        val body = icsClient.fetch(source.url)
        cacheParsedEvents(source.id, body, windowStartMs, windowEndMs)
        sourceDao.markSynced(source.id, System.currentTimeMillis())
    }.onFailure { e ->
        runCatching { sourceDao.markSyncError(source.id, e.message ?: "Sync failed") }
    }

    /** Refreshes every sync-enabled source over the default window. */
    suspend fun syncAll() {
        val (windowStart, windowEnd) = defaultWindow()
        getSources().filter { it.syncEnabled }.forEach { source ->
            syncSource(source, windowStart, windowEnd)
        }
    }

    private suspend fun cacheParsedEvents(
        sourceId: Long,
        body: String,
        windowStartMs: Long,
        windowEndMs: Long
    ) {
        val now = System.currentTimeMillis()
        val entities = IcsParser.parse(body, windowStartMs, windowEndMs).map { parsed ->
            CalendarEventEntity(
                accountId = null,
                sourceId = sourceId,
                // Marker id: stable per-source colour hashing in the UI.
                calendarId = "source:$sourceId",
                // eventId deliberately null — it is the "remotely editable"
                // signal throughout the UI, and ICS caches are read-only.
                eventId = null,
                iCalUID = parsed.uid,
                title = parsed.title,
                description = parsed.description,
                location = parsed.location,
                startEpochMs = parsed.startEpochMs,
                endEpochMs = parsed.endEpochMs,
                allDay = parsed.allDay,
                timezone = parsed.timezone,
                status = parsed.status,
                organizer = parsed.organizer,
                syncedAt = now
            )
        }
        eventDao.replaceForSource(sourceId, entities)
    }

    /** Same window the calendar sync worker uses for Google accounts. */
    private fun defaultWindow(): Pair<Long, Long> {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val start = today.minusDays(WINDOW_BACK_DAYS).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = today.plusDays(WINDOW_FORWARD_DAYS + 1).atStartOfDay(zone).toInstant().toEpochMilli()
        return start to end
    }

    companion object {
        private const val WINDOW_BACK_DAYS = 30L
        private const val WINDOW_FORWARD_DAYS = 84L
    }
}

/* ---------- Entity <-> Domain mapping ---------- */

private fun CalendarSourceEntity.toDomain(): CalendarSource = CalendarSource(
    id = id,
    type = CalendarSourceType.fromStorage(type),
    url = url,
    displayName = displayName,
    color = color,
    username = username,
    isVisible = isVisible,
    syncEnabled = syncEnabled,
    lastSyncedAt = lastSyncedAt,
    lastError = lastError
)
