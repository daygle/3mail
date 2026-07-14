package com.threemail.android.data.remote.calendar

import android.content.Context
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.calendar.Calendar
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
}
