package com.threemail.android.data.remote.calendar

import android.content.Context
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.calendar.Calendar
import com.google.api.services.calendar.model.Calendar as ModelCalendar
import com.google.api.services.calendar.model.CalendarListEntry
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CalendarApiClient @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        /** Full read/write Calendar scope. Must be requested at Google Sign-In. */
        const val SCOPE: String = "https://www.googleapis.com/auth/calendar"
        const val PRIMARY_CALENDAR: String = "primary"
    }

    /**
     * Builds a [Calendar] service bound to the user's Google account. The credential
     * handles token refresh transparently per request.
     */
    fun buildService(accountEmail: String): Calendar {
        val credential = GoogleAccountCredential.usingOAuth2(
            context,
            listOf(SCOPE)
        )
        credential.selectedAccountName = accountEmail

        return Calendar.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        )
            .setApplicationName("3mail")
            .build()
    }

    /**
     * Lists every calendar the user has access to via Google's
     * `calendarList().list(...)` endpoint. Walks pagination transparently so a
     * Gmail user with many subscriptions doesn't lose entries after the first
     * 250-row page.
     *
     * Result is a flat [List] of [CalendarListEntry]; the
     * [com.threemail.android.data.repository.CalendarRepository] translates
     * each into a [com.threemail.android.domain.model.CalendarEntry] for
     * Room upsert.
     */
    suspend fun listCalendars(accountEmail: String): List<CalendarListEntry> {
        val service = buildService(accountEmail)
        val out = mutableListOf<CalendarListEntry>()
        var pageToken: String? = null
        do {
            val request = service.calendarList().list().setMaxResults(250)
            if (pageToken != null) request.pageToken = pageToken
            val response = request.execute()
            response.items.orEmpty().forEach(out::add)
            pageToken = response.nextPageToken
        } while (pageToken != null)
        return out
    }

    /**
     * Subscribes to a public / iCal calendar via Google's `calendarList().insert`.
     * The `id` parameter is the iCal URL encoded into a Google calendar id
     * (Google accepts arbitrary ids on insert). On success the entry is
     * selectable immediately and starts pulling events on the next sync.
     */
    suspend fun subscribeByUrl(
        accountEmail: String,
        calendarId: String,
        summary: String? = null,
        backgroundColor: String? = null
    ): CalendarListEntry {
        val service = buildService(accountEmail)
        val entry = CalendarListEntry().apply {
            id = calendarId
            // `selected = true` so the new subscription shows up in the
            // user's calendar view immediately; matches the
            // `is_selected=1` default for fresh rows we ship in Room.
            selected = java.lang.Boolean.TRUE
            if (summary != null) this.summary = summary
            if (backgroundColor != null) this.backgroundColor = backgroundColor
        }
        return service.calendarList().insert(entry).execute()
    }

    /**
     * Creates a new calendar owned by the user. Two-step on Google's
     * Calendar REST: first POST to `calendars()` to allocate the
     * calendar id and writable ACL, then POST to `calendarList()` to
     * subscribe the *same* user to it (otherwise the freshly-created
     * calendar won't appear in the user's own calendarList). Returns
     * the resulting [CalendarListEntry] so the repository can upsert
     * metadata into Room.
     */
    suspend fun createNewCalendar(
        accountEmail: String,
        summary: String,
        description: String? = null,
        timezone: String = java.util.TimeZone.getDefault().id
    ): CalendarListEntry {
        val service = buildService(accountEmail)
        val newCalendar = ModelCalendar().apply {
            this.summary = summary
            this.description = description
            this.timeZone = timezone
        }
        val created = service.calendars().insert(newCalendar).execute()
        val subscription = CalendarListEntry().apply {
            id = created.id
            selected = java.lang.Boolean.TRUE
        }
        return service.calendarList().insert(subscription).execute()
    }

    /**
     * Updates Google's `selected` flag for a calendar entry. We treat the
     * value as a mirror of Room's `calendars.is_selected` so a future
     * background sync can pull remote `selected` back; the local flag wins
     * for the current user via [com.threemail.android.data.repository.CalendarRepository.setSelected].
     */
    suspend fun setSelectedRemote(
        accountEmail: String,
        calendarId: String,
        selected: Boolean
    ): CalendarListEntry {
        val service = buildService(accountEmail)
        val entry = CalendarListEntry().apply {
            id = calendarId
            this.selected = java.lang.Boolean.valueOf(selected)
        }
        return service.calendarList().patch(calendarId, entry).execute()
    }
}
