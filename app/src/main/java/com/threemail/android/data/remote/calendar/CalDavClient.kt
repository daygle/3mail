package com.threemail.android.data.remote.calendar

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import java.net.URI
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Minimal CalDAV (RFC 4791) client covering what the app needs:
 *
 *  - [discoverCalendars]: from a user-supplied URL, find the account's
 *    event-capable calendar collections. Tries, in order: the URL itself
 *    being a calendar; `current-user-principal` → `calendar-home-set` →
 *    Depth-1 listing (the RFC 6764 bootstrapping chain, minus DNS SRV);
 *    and a plain Depth-1 listing of the URL as a fallback.
 *  - [fetchEvents]: `REPORT calendar-query` with a VEVENT time-range
 *    filter, returning each object's href, etag, and raw iCalendar data
 *    (parsed upstream by [IcsParser]).
 *  - [putEvent] / [deleteEvent]: write-back with `If-Match` so a
 *    concurrent server-side edit fails loudly instead of being clobbered.
 *
 * OkHttp rather than HttpURLConnection because the JDK client rejects
 * WebDAV verbs (`PROPFIND`, `REPORT`). Auth is HTTP Basic per request —
 * CalDAV servers (Nextcloud, Fastmail, Radicale, Baïkal…) expect exactly
 * that over TLS, usually with an app password.
 */
@Singleton
class CalDavClient @Inject constructor() {

    open class CalDavException(message: String) : Exception(message)

    /** Distinct so discovery's best-effort fallbacks don't mask a bad login. */
    class CalDavAuthException : CalDavException("Wrong username or password")

    /** One event-capable collection found during discovery. */
    data class DiscoveredCalendar(
        /** Absolute URL of the collection. */
        val url: String,
        val displayName: String,
        /** Apple `calendar-color` hex if the server exposes one. */
        val color: String?
    )

    /** One calendar object (an .ics resource) inside a collection. */
    data class RemoteObject(
        /** Absolute URL of the object. */
        val url: String,
        val etag: String?,
        val icsData: String
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun discoverCalendars(
        baseUrl: String,
        username: String,
        password: String
    ): List<DiscoveredCalendar> = withContext(Dispatchers.IO) {
        val base = normalize(baseUrl)
        val auth = Credentials.basic(username, password)

        // Fallbacks may probe URLs that legitimately 404/405, but a bad
        // login fails every step identically — surface it immediately.
        fun <T> Result<T>.orNullUnlessAuth(): T? =
            getOrElse { if (it is CalDavAuthException) throw it else null }

        // 1) The URL may itself be a calendar collection.
        val selfDoc = runCatching { propfind(base, auth, depth = 0) }.orNullUnlessAuth()
        if (selfDoc != null) {
            val self = parseCalendars(selfDoc, base)
            if (self.isNotEmpty()) return@withContext self
        }

        // 2) RFC 6764 chain: principal -> calendar home -> children.
        val principal = selfDoc?.let { firstHref(it, NS_DAV, "current-user-principal") }
            ?: runCatching { propfind(resolve(base, "/.well-known/caldav"), auth, depth = 0) }
                .orNullUnlessAuth()?.let { firstHref(it, NS_DAV, "current-user-principal") }
        if (principal != null) {
            val principalUrl = resolve(base, principal)
            val homeDoc = runCatching { propfind(principalUrl, auth, depth = 0) }.orNullUnlessAuth()
            val home = homeDoc?.let { firstHref(it, NS_CALDAV, "calendar-home-set") }
            if (home != null) {
                val homeUrl = resolve(base, home)
                val listing = runCatching { propfind(homeUrl, auth, depth = 1) }.orNullUnlessAuth()
                val found = listing?.let { parseCalendars(it, homeUrl) }.orEmpty()
                if (found.isNotEmpty()) return@withContext found
            }
        }

        // 3) Fallback: the URL may be a calendar home; list its children.
        val listing = runCatching { propfind(base, auth, depth = 1) }.orNullUnlessAuth()
        val found = listing?.let { parseCalendars(it, base) }.orEmpty()
        if (found.isEmpty()) {
            throw CalDavException("No calendars found at this address")
        }
        found
    }

    suspend fun fetchEvents(
        collectionUrl: String,
        username: String,
        password: String,
        startMs: Long,
        endMs: Long
    ): List<RemoteObject> = withContext(Dispatchers.IO) {
        val auth = Credentials.basic(username, password)
        val body = """
            <c:calendar-query xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
              <d:prop><d:getetag/><c:calendar-data/></d:prop>
              <c:filter>
                <c:comp-filter name="VCALENDAR">
                  <c:comp-filter name="VEVENT">
                    <c:time-range start="${UTC_FORMAT.format(Instant.ofEpochMilli(startMs))}" end="${UTC_FORMAT.format(Instant.ofEpochMilli(endMs))}"/>
                  </c:comp-filter>
                </c:comp-filter>
              </c:filter>
            </c:calendar-query>
        """.trimIndent()
        val request = Request.Builder()
            .url(collectionUrl)
            .method("REPORT", body.toRequestBody(XML_MEDIA_TYPE))
            .header("Authorization", auth)
            .header("Depth", "1")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw CalDavException("Calendar query failed: HTTP ${response.code}")
            }
            val doc = parseXml(response.body?.string() ?: "")
            val out = mutableListOf<RemoteObject>()
            eachResponse(doc) { href, propstat ->
                val data = firstText(propstat, NS_CALDAV, "calendar-data") ?: return@eachResponse
                out += RemoteObject(
                    url = resolve(collectionUrl, href),
                    etag = firstText(propstat, NS_DAV, "getetag"),
                    icsData = data
                )
            }
            out
        }
    }

    /**
     * Creates or updates one calendar object. Pass [etag] null for a create
     * (`If-None-Match: *` guards against overwriting an existing object) or
     * the cached etag for an update (`If-Match` detects concurrent edits).
     * Returns the new etag when the server includes one; callers should
     * trigger a sync otherwise.
     */
    suspend fun putEvent(
        objectUrl: String,
        username: String,
        password: String,
        icsData: String,
        etag: String?
    ): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(objectUrl)
            .put(icsData.toRequestBody(ICS_MEDIA_TYPE))
            .header("Authorization", Credentials.basic(username, password))
            .apply {
                if (etag != null) header("If-Match", etag)
                else header("If-None-Match", "*")
            }
            .build()
        http.newCall(request).execute().use { response ->
            when {
                response.code == 412 -> throw CalDavException(
                    "The event changed on the server; refresh and try again"
                )
                !response.isSuccessful -> throw CalDavException(
                    "Saving failed: HTTP ${response.code}"
                )
            }
            response.header("ETag")
        }
    }

    suspend fun deleteEvent(
        objectUrl: String,
        username: String,
        password: String,
        etag: String?
    ): Unit = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(objectUrl)
            .delete()
            .header("Authorization", Credentials.basic(username, password))
            .apply { if (etag != null) header("If-Match", etag) }
            .build()
        http.newCall(request).execute().use { response ->
            when {
                response.code == 412 -> throw CalDavException(
                    "The event changed on the server; refresh and try again"
                )
                // 404: already gone — deleting twice shouldn't error out.
                !response.isSuccessful && response.code != 404 -> throw CalDavException(
                    "Deleting failed: HTTP ${response.code}"
                )
            }
        }
    }

    /* ---------- WebDAV plumbing ---------- */

    private fun propfind(url: String, auth: String, depth: Int): Document {
        val body = """
            <d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav" xmlns:ic="http://apple.com/ns/ical/">
              <d:prop>
                <d:resourcetype/>
                <d:displayname/>
                <d:current-user-principal/>
                <c:calendar-home-set/>
                <c:supported-calendar-component-set/>
                <ic:calendar-color/>
              </d:prop>
            </d:propfind>
        """.trimIndent()
        val request = Request.Builder()
            .url(url)
            .method("PROPFIND", body.toRequestBody(XML_MEDIA_TYPE))
            .header("Authorization", auth)
            .header("Depth", depth.toString())
            .build()
        http.newCall(request).execute().use { response ->
            if (response.code == 401) throw CalDavAuthException()
            if (!response.isSuccessful) throw CalDavException("HTTP ${response.code}")
            return parseXml(response.body?.string() ?: "")
        }
    }

    /**
     * Walks `d:multistatus/d:response` entries, invoking [block] with each
     * response's href and its 2xx `propstat/prop` element.
     */
    private fun eachResponse(doc: Document, block: (href: String, prop: Element) -> Unit) {
        val responses = doc.getElementsByTagNameNS(NS_DAV, "response")
        for (i in 0 until responses.length) {
            val response = responses.item(i) as? Element ?: continue
            val href = directChildText(response, NS_DAV, "href") ?: continue
            val propstats = response.getElementsByTagNameNS(NS_DAV, "propstat")
            for (j in 0 until propstats.length) {
                val propstat = propstats.item(j) as? Element ?: continue
                val status = directChildText(propstat, NS_DAV, "status") ?: ""
                if (!status.contains(" 200 ")) continue
                val prop = propstat.getElementsByTagNameNS(NS_DAV, "prop")
                    .item(0) as? Element ?: continue
                block(href, prop)
            }
        }
    }

    /** Collections whose resourcetype includes caldav:calendar and that hold VEVENTs. */
    private fun parseCalendars(doc: Document, baseUrl: String): List<DiscoveredCalendar> {
        val out = mutableListOf<DiscoveredCalendar>()
        eachResponse(doc) { href, prop ->
            val resourceType = prop.getElementsByTagNameNS(NS_DAV, "resourcetype")
                .item(0) as? Element ?: return@eachResponse
            val isCalendar = resourceType.getElementsByTagNameNS(NS_CALDAV, "calendar").length > 0
            if (!isCalendar) return@eachResponse
            // No supported-calendar-component-set → assume events (some
            // servers omit it); with one, require VEVENT.
            val compSet = prop.getElementsByTagNameNS(NS_CALDAV, "supported-calendar-component-set")
                .item(0) as? Element
            if (compSet != null) {
                var hasVEvent = false
                val comps = compSet.getElementsByTagNameNS(NS_CALDAV, "comp")
                for (i in 0 until comps.length) {
                    val comp = comps.item(i) as? Element ?: continue
                    if (comp.getAttribute("name").equals("VEVENT", ignoreCase = true)) {
                        hasVEvent = true
                    }
                }
                if (!hasVEvent) return@eachResponse
            }
            val name = firstText(prop, NS_DAV, "displayname")
                ?: href.trimEnd('/').substringAfterLast('/')
            out += DiscoveredCalendar(
                url = resolve(baseUrl, href),
                displayName = name,
                color = firstText(prop, NS_APPLE, "calendar-color")
            )
        }
        return out
    }

    /* ---------- XML helpers ---------- */

    /**
     * Namespace-aware DocumentBuilder hardened against XXE — the XML comes
     * from a server the user is only beginning to trust.
     */
    private fun parseXml(xml: String): Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        return runCatching {
            factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        }.getOrElse { throw CalDavException("Server sent an unreadable response") }
    }

    private fun firstText(scope: Element, ns: String, local: String): String? {
        val list = scope.getElementsByTagNameNS(ns, local)
        for (i in 0 until list.length) {
            val text = list.item(i)?.textContent?.trim()
            if (!text.isNullOrEmpty()) return text
        }
        return null
    }

    /** `<x><d:href>` nested inside a property (principal / home-set answers). */
    private fun firstHref(doc: Document, ns: String, local: String): String? {
        val list = doc.getElementsByTagNameNS(ns, local)
        for (i in 0 until list.length) {
            val el = list.item(i) as? Element ?: continue
            val href = firstText(el, NS_DAV, "href")
            if (href != null) return href
        }
        return null
    }

    private fun directChildText(parent: Element, ns: String, local: String): String? {
        var child = parent.firstChild
        while (child != null) {
            if (child is Element && child.localName == local && child.namespaceURI == ns) {
                return child.textContent?.trim()?.ifBlank { null }
            }
            child = child.nextSibling
        }
        return null
    }

    /* ---------- URL helpers ---------- */

    private fun normalize(url: String): String {
        val trimmed = url.trim()
        return if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) {
            trimmed
        } else {
            "https://$trimmed"
        }
    }

    /** Resolves a Multistatus href (absolute URL or server-absolute path) against [base]. */
    internal fun resolve(base: String, href: String): String =
        runCatching { URI(base).resolve(href).toString() }.getOrDefault(href)

    companion object {
        private const val NS_DAV = "DAV:"
        private const val NS_CALDAV = "urn:ietf:params:xml:ns:caldav"
        private const val NS_APPLE = "http://apple.com/ns/ical/"
        private val XML_MEDIA_TYPE = "application/xml; charset=utf-8".toMediaType()
        private val ICS_MEDIA_TYPE = "text/calendar; charset=utf-8".toMediaType()
        private val UTC_FORMAT = DateTimeFormatter
            .ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    }
}
