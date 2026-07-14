package com.threemail.android.data.repository

import com.google.api.client.util.DateTime
import com.google.api.services.calendar.model.Event
import com.google.api.services.calendar.model.EventAttendee
import com.google.api.services.calendar.model.EventDateTime
import com.threemail.android.data.local.dao.CalendarEventDao
import com.threemail.android.data.local.entity.CalendarEventEntity
import com.threemail.android.data.remote.calendar.CalendarApiClient
import com.threemail.android.domain.model.Account
import com.threemail.android.domain.model.CalendarEvent
import com.threemail.android.domain.model.CalendarEventStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton
import java.time.Instant

/**
 * Coordinates the locally-cached calendar table with the Google Calendar REST API.
 *
 * - Local Room is the source of truth for the UI.
 * - Sync workers periodically replace the cached range with what the server returns.
 * - User-initiated mutations flow through [createRemote] / [updateRemote] / [deleteRemote]
 *   which upsert into Room after each successful server round-trip.
 */
@Singleton
class CalendarRepository @Inject constructor(
    private val dao: CalendarEventDao,
    private val apiClient: CalendarApiClient
) {

    fun getEventsInRange(accountId: Long, startMs: Long, endMs: Long): Flow<List<CalendarEvent>> =
        dao.getInRange(accountId, startMs, endMs).map { rows -> rows.map { it.toDomain() } }

    /** Convenience wrapper: events that occur on a single day in the device's timezone. */
    fun getEventsForDay(accountId: Long, dayStart: Long, dayEnd: Long): Flow<List<CalendarEvent>> =
        dao.getInRange(accountId, dayStart, dayEnd).map { rows -> rows.map { it.toDomain() } }

    suspend fun getById(id: Long): CalendarEvent? = dao.getById(id)?.toDomain()

    /** Pulls events for [account] covering [startMs, endMs) and replaces local cache for the window. */
    suspend fun syncRange(account: Account, calendarId: String, startMs: Long, endMs: Long) =
        withContext(Dispatchers.IO) {
            val service = apiClient.buildService(account.email)
            val fetched = mutableListOf<CalendarEventEntity>()
            var pageToken: String? = null
            do {
                val response = service.events().list(calendarId)
                    .setTimeMin(DateTime(startMs))
                    .setTimeMax(DateTime(endMs))
                    .setSingleEvents(true)
                    .setOrderBy("startTime")
                    .setMaxResults(250)
                    .setPageToken(pageToken)
                    .execute()
                response.items.orEmpty().forEach { remote ->
                    fetched += remote.toEntity(account.id, calendarId, existingId = null)
                }
                pageToken = response.nextPageToken
            } while (pageToken != null)

            // Best-effort prune of rows that have finished and a full replace of what's in range.
            dao.deleteOlderThan(account.id, endMs)
            if (fetched.isNotEmpty()) dao.insertAll(fetched)
        }

    /** Creates a new remote event and caches the returned server object. */
    suspend fun createRemote(account: Account, calendarId: String, draft: CalendarEvent): CalendarEvent =
        withContext(Dispatchers.IO) {
            val service = apiClient.buildService(account.email)
            val inserted = service.events().insert(calendarId, draft.toGoogleEvent()).execute()
            val entity = inserted.toEntity(account.id, calendarId, existingId = null)
            val id = dao.insert(entity)
            entity.toDomain().copy(id = id)
        }

    /** Updates an existing remote event and refreshes the cached row. */
    suspend fun updateRemote(account: Account, calendarId: String, event: CalendarEvent): CalendarEvent =
        withContext(Dispatchers.IO) {
            require(event.eventId != null) { "Cannot update a remote event without an eventId" }
            val service = apiClient.buildService(account.email)
            val patched = service.events().patch(calendarId, event.eventId, event.toGoogleEvent()).execute()
            val existing = dao.getByRemoteId(account.id, calendarId, event.eventId)
            val entity = patched.toEntity(account.id, calendarId, existingId = existing?.id ?: 0L)
            dao.update(entity)
            entity.toDomain()
        }

    /** Deletes an event locally + remotely (server-first, then local). */
    suspend fun deleteRemote(account: Account, calendarId: String, eventId: String) =
        withContext(Dispatchers.IO) {
            val service = apiClient.buildService(account.email)
            service.events().delete(calendarId, eventId).execute()
            dao.getByRemoteId(account.id, calendarId, eventId)?.let { dao.deleteById(it.id) }
        }

    suspend fun deleteLocal(id: Long) = dao.deleteById(id)
}

/* ---------- Remote -> Entity mapping ---------- */

private fun Event.toEntity(accountId: Long, calendarId: String, existingId: Long?): CalendarEventEntity {
    val allDay = start.date != null
    val startMs: Long
    val endMs: Long
    val timezone: String
    if (allDay) {
        // Google returns yyyy-MM-dd for `date` fields; end is exclusive (e.g. 2-day event: Aug 1–3).
        val sDate = LocalDate.parse(start.date.toStringRfc3339().take(10))
        val eDate = LocalDate.parse(end.date.toStringRfc3339().take(10))
        startMs = sDate.atStartOfDayUtcMillis()
        endMs = eDate.atStartOfDayUtcMillis()
        timezone = "UTC"
    } else {
        startMs = start.dateTime.value
        endMs = end.dateTime.value
        timezone = start.timeZone ?: ZoneId.systemDefault().id
    }
    return CalendarEventEntity(
        id = existingId ?: 0L,
        accountId = accountId,
        calendarId = calendarId,
        eventId = id,
        iCalUID = iCalUID,
        title = summary ?: "(No title)",
        description = description,
        location = location,
        startEpochMs = startMs,
        endEpochMs = endMs,
        allDay = allDay,
        timezone = timezone,
        status = status ?: "confirmed",
        organizer = organizer?.email,
        attendeesJson = serializeAttendeeEmails(attendees.orEmpty()),
        htmlLink = htmlLink,
        syncedAt = System.currentTimeMillis()
    )
}

/* ---------- Domain -> Entity mapping ---------- */

internal fun CalendarEvent.toEntity(): CalendarEventEntity = CalendarEventEntity(
    id = id,
    accountId = accountId,
    calendarId = calendarId,
    eventId = eventId,
    iCalUID = iCalUID,
    title = title,
    description = description,
    location = location,
    startEpochMs = startEpochMs,
    endEpochMs = endEpochMs,
    allDay = allDay,
    timezone = timezone,
    status = status.name.lowercase(),
    organizer = organizer,
    attendeesJson = serializeAttendeeEmailsRaw(attendees),
    htmlLink = htmlLink,
    syncedAt = syncedAt
)

/* ---------- Entity -> Domain mapping ---------- */

private fun CalendarEventEntity.toDomain(): CalendarEvent = CalendarEvent(
    id = id,
    accountId = accountId,
    calendarId = calendarId,
    eventId = eventId,
    iCalUID = iCalUID,
    title = title,
    description = description,
    location = location,
    startEpochMs = startEpochMs,
    endEpochMs = endEpochMs,
    allDay = allDay,
    timezone = timezone,
    status = CalendarEventStatus.fromApiValue(status),
    organizer = organizer,
    attendees = parseAttendees(attendeesJson),
    htmlLink = htmlLink,
    syncedAt = syncedAt
)

/* ---------- Domain -> Remote mapping (creates fresh; ignores id / etag) ---------- */

private fun CalendarEvent.toGoogleEvent(): Event {
    val event = Event()
        .setSummary(title)
        .setDescription(description)
        .setLocation(location)
        .setStatus(status.name.lowercase())
    val startEdt: EventDateTime
    val endEdt: EventDateTime
    if (allDay) {
        val sDate = LocalDate.ofEpochDay(startEpochMs / MILLIS_PER_DAY)
        val eDate = LocalDate.ofEpochDay(endEpochMs / MILLIS_PER_DAY)
        startEdt = EventDateTime().setDate(DateTime(true, sDate.atStartOfDayUtcMillis(), 0))
        endEdt = EventDateTime().setDate(DateTime(true, eDate.atStartOfDayUtcMillis(), 0))
    } else {
        val zone = runCatching { ZoneId.of(timezone) }.getOrDefault(ZoneId.systemDefault())
        startEdt = EventDateTime()
            .setDateTime(DateTime(startEpochMs))
            .setTimeZone(zone.id)
        endEdt = EventDateTime()
            .setDateTime(DateTime(endEpochMs))
            .setTimeZone(zone.id)
    }
    event.start = startEdt
    event.end = endEdt
    if (attendees.isNotEmpty()) {
        event.attendees = attendees.map { EventAttendee().setEmail(it) }
    }
    return event
}

/* ---------- helpers ---------- */

private fun serializeAttendeeEmails(attendees: List<EventAttendee>): String {
    val array = JSONArray()
    attendees.forEach { array.put(it.email) }
    return array.toString()
}

private fun serializeAttendeeEmailsRaw(emails: List<String>): String {
    val array = JSONArray()
    emails.forEach { array.put(it) }
    return array.toString()
}

private fun parseAttendees(json: String): List<String> = try {
    val array = JSONArray(json)
    (0 until array.length()).map { array.getString(it) }
} catch (e: Exception) {
    emptyList()
}

private fun LocalDate.atStartOfDayUtcMillis(): Long =
    atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private const val MILLIS_PER_DAY: Long = 86_400_000L
