package com.threemail.android.domain.model

/**
 * One calendar the user has access to (subscribed, owned, or pre-loaded
 * by Google server-side). Modelled after Google's `CalendarListEntry` so
 * the API mapping in [com.threemail.android.data.remote.calendar.CalendarApiClient]
 * is a straight field copy.
 *
 * `isSelected` is the user-controllable visibility flag. The CalendarScreen
 * filter-by-active-calendars logic consults this rather than
 * [com.threemail.android.domain.model.Account.calendarSyncEnabled] so an
 * opt-in/out is per-calendar; toggling doesn't drop mail sync for the
 * parent account.
 *
 * `accessRole` mirrors Google's permissioning enum:
 *  - `owner`            : calendar the user owns (can create events)
 *  - `writer`          : can edit events but no ACL changes
 *  - `reader`          : read-only access
 *  - `freeBusyReader`  : can only see free/busy
 *
 * `backgroundColor` is the hex string Google surfaces (e.g. "#9fc6e7") so
 * the Manage Calendars list can render a colour swatch per row matching
 * Google's web UI; the data layer keeps it as a String so the model
 * stays pure-Kotlin (Compose `Color` lives in the UI layer only).
 *
 * `lastSyncedAt` is the last successful [com.threemail.android.data.repository.CalendarRepository.syncCalendarList]
 * run for this (accountId, calendarId); lets UI show staleness badges
 * without a separate per-row query.
 */
data class CalendarEntry(
    val accountId: Long,
    val calendarId: String,
    val summary: String,
    val description: String? = null,
    val timezone: String? = null,
    val isPrimary: Boolean = false,
    val accessRole: String = "reader",
    val backgroundColor: String? = null,
    val isSelected: Boolean = true,
    val lastSyncedAt: Long? = null
)
