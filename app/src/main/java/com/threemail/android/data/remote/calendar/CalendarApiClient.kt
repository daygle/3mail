package com.threemail.android.data.remote.calendar

import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.client.util.DateTime
import com.google.api.services.calendar.Calendar
import com.google.api.services.calendar.CalendarScopes
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.services.calendar.model.Event
import com.google.api.services.calendar.model.EventDateTime
import com.threemail.android.data.remote.gmail.RecoverableAuthException
import com.threemail.android.domain.model.CalendarEvent
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin wrapper over the Google Calendar REST API for the signed-in Google account.
 * Operations run on IO and surface consent requirements as [RecoverableAuthException].
 */
@Singleton
class CalendarApiClient @Inject constructor(
    @ApplicationContext private val context: android.content.Context
) {

    private fun service(accountEmail: String): Calendar {
        val credential = GoogleAccountCredential.usingOAuth2(context, listOf(CalendarScopes.CALENDAR))
        credential.selectedAccountName = accountEmail
        return Calendar.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance(), credential)
            .setApplicationName("3mail")
            .build()
    }

    suspend fun listEvents(accountEmail: String, timeMinMs: Long, timeMaxMs: Long): Result<List<CalendarEvent>> =
        run(accountEmail) { svc ->
            val response = svc.events().list("primary")
                .setTimeMin(DateTime(timeMinMs))
                .setTimeMax(DateTime(timeMaxMs))
                .setOrderBy("startTime")
                .setSingleEvents(true)
                .setMaxResults(250)
                .execute()
            response.items.orEmpty().map { it.toDomain() }
        }

    suspend fun createEvent(accountEmail: String, event: CalendarEvent): Result<CalendarEvent> =
        run(accountEmail) { svc ->
            val gEvent = Event().apply {
                summary = event.title
                description = event.description
                location = event.location
                start = event.start.toEventDateTime(event.allDay)
                end = event.end.toEventDateTime(event.allDay)
            }
            svc.events().insert("primary", gEvent).execute().toDomain()
        }

    suspend fun deleteEvent(accountEmail: String, eventId: String): Result<Unit> =
        run(accountEmail) { svc -> svc.events().delete("primary", eventId).execute(); Unit }

    private suspend fun <T> run(accountEmail: String, block: (Calendar) -> T): Result<T> =
        withContext(Dispatchers.IO) {
            try {
                Result.success(block(service(accountEmail)))
            } catch (e: UserRecoverableAuthIOException) {
                throw RecoverableAuthException(e.intent, "Google consent required for calendar access")
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private fun Event.toDomain(): CalendarEvent {
        val startMs = start?.dateTime?.value ?: start?.date?.value ?: 0L
        val endMs = end?.dateTime?.value ?: end?.date?.value ?: startMs
        val allDay = start?.dateTime == null && start?.date != null
        return CalendarEvent(
            id = id ?: "",
            title = summary ?: "(no title)",
            description = description,
            location = location,
            start = startMs,
            end = endMs,
            allDay = allDay,
            organizer = organizer?.email,
            attendees = attendees.orEmpty().mapNotNull { it.email },
            htmlLink = htmlLink
        )
    }

    private fun Long.toEventDateTime(allDay: Boolean): EventDateTime =
        if (allDay) {
            EventDateTime().setDate(DateTime(true, this, null))
        } else {
            EventDateTime()
                .setDateTime(DateTime(this))
                .setTimeZone(TimeZone.getDefault().id)
        }
}
