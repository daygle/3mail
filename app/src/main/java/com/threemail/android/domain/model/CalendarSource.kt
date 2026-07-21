package com.threemail.android.domain.model

/**
 * A calendar the user subscribed to that is NOT backed by a signed-in mail
 * account. Two kinds today:
 *
 *  - [CalendarSourceType.ICS]: a read-only internet calendar fetched from a
 *    URL (`.ics` / `webcal://`) — holidays, sports fixtures, a shared iCloud
 *    or Outlook "public link". Events are parsed and cached locally; there is
 *    no write-back.
 *  - [CalendarSourceType.CALDAV]: a two-way CalDAV collection (Nextcloud,
 *    iCloud, Fastmail, …). Read/write. (Handled by a later phase; the model
 *    carries the fields it needs so the storage layer is stable.)
 *
 * Unlike [CalendarEntry] (which hangs off a Gmail account id), a source is a
 * first-class row with its own generated [id]. The Calendar screen overlays a
 * source's events alongside every Google account's events, and [isVisible]
 * gates whether they render — the standalone equivalent of
 * [CalendarEntry.isSelected].
 *
 * The credential ([CalendarSourceType.CALDAV] username/password) is
 * intentionally kept out of this UI-facing model; only the storage entity
 * holds it. [color] is a hex string (e.g. "#3B82F6") so the model stays
 * pure-Kotlin — Compose `Color` lives in the UI layer.
 */
data class CalendarSource(
    val id: Long = 0,
    val type: CalendarSourceType,
    val url: String,
    val displayName: String,
    val color: String? = null,
    val username: String? = null,
    val isVisible: Boolean = true,
    val syncEnabled: Boolean = true,
    val lastSyncedAt: Long? = null,
    val lastError: String? = null
)

enum class CalendarSourceType {
    ICS,
    CALDAV;

    companion object {
        fun fromStorage(value: String?): CalendarSourceType = when (value?.uppercase()) {
            "CALDAV" -> CALDAV
            else -> ICS
        }
    }
}
