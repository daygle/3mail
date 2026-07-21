package com.threemail.android.data.remote.calendar

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches raw iCalendar payloads for standalone ICS subscriptions.
 *
 * `webcal://` (and `webcals://`) links — the de-facto "subscribe to this
 * calendar" scheme used by Apple, Outlook, sports-fixture sites, etc. —
 * are plain HTTPS underneath, so they're rewritten before fetching.
 * Responses are capped at [MAX_BODY_BYTES] so a mistyped URL pointing at
 * some huge binary can't balloon memory.
 */
@Singleton
class IcsCalendarClient @Inject constructor() {

    class FetchException(message: String) : Exception(message)

    /**
     * Downloads the feed at [url] and returns the raw ICS text.
     * Throws [FetchException] with a user-presentable reason on failure.
     */
    suspend fun fetch(url: String): String = withContext(Dispatchers.IO) {
        val normalized = normalizeUrl(url)
        val parsed = runCatching { URL(normalized) }.getOrNull()
            ?: throw FetchException("Invalid URL")
        if (parsed.protocol != "https" && parsed.protocol != "http") {
            throw FetchException("Only http(s) and webcal URLs are supported")
        }
        val conn = (parsed.openConnection() as? HttpURLConnection)
            ?: throw FetchException("Invalid URL")
        conn.requestMethod = "GET"
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("Accept", "text/calendar, text/plain, */*")
        conn.setRequestProperty("User-Agent", "3mail/1.0 (+ics)")
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw FetchException("Server returned HTTP $code")
            val body = conn.inputStream.use { input ->
                val buffer = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val read = input.read(chunk)
                    if (read < 0) break
                    total += read
                    if (total > MAX_BODY_BYTES) {
                        throw FetchException("Calendar file is too large")
                    }
                    buffer.write(chunk, 0, read)
                }
                buffer.toString("UTF-8")
            }
            if (!body.contains("BEGIN:VCALENDAR")) {
                throw FetchException("Not an iCalendar file")
            }
            body
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val MAX_BODY_BYTES = 20 * 1024 * 1024 // 20 MiB

        /** `webcal(s)://` → `https://`; anything else passes through trimmed. */
        fun normalizeUrl(url: String): String {
            val trimmed = url.trim()
            return when {
                trimmed.startsWith("webcals://", ignoreCase = true) ->
                    "https://" + trimmed.substring("webcals://".length)
                trimmed.startsWith("webcal://", ignoreCase = true) ->
                    "https://" + trimmed.substring("webcal://".length)
                else -> trimmed
            }
        }
    }
}
