package com.threemail.android.ui.screens.calendar

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.threemail.android.data.remote.calendar.CalDavClient
import com.threemail.android.data.repository.CalendarRepository
import com.threemail.android.data.repository.CalendarSourceRepository
import com.threemail.android.domain.model.Account
import com.threemail.android.domain.model.AccountType
import com.threemail.android.domain.model.CalendarEntry
import com.threemail.android.domain.model.CalendarSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * State holder for the Manage Calendars screen.
 *
 * Three reactive inputs:
 *  - the user's [CalendarEntry] rows from [com.threemail.android.data.local.dao.CalendarListDao]
 *    (one row per (accountId, calendarId) the user has access to)
 *  - the [Account] list, used to bucket calendar rows by their parent
 *    Google account so the UI can show "Account email" sticky headers
 *  - the in-flight subscription / creation results, surfaced as
 *    [SnackbarMessage] events so the user sees success / failure feedback
 *
 * The user toggles a row's `isSelected` via [toggleSelected] which writes
 * to Room (so the CalendarScreen filter reads it instantly) and
 * best-effort mirrors the change back to Google's
 * [com.threemail.android.data.remote.calendar.CalendarApiClient.setSelectedRemote].
 * A failure on the remote push is swallowed: the local flag wins for
 * the current device, and the next [sync] call will fold the change in
 * if Google eventually agrees.
 */
@HiltViewModel
class ManageCalendarsViewModel @Inject constructor(
    private val calendarRepository: CalendarRepository,
    private val calendarSourceRepository: CalendarSourceRepository,
    private val accountRepository: com.threemail.android.data.repository.AccountRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    /** Standalone subscriptions (ICS feeds), independent of any account. */
    val sources: StateFlow<List<CalendarSource>> = calendarSourceRepository
        .observeSources()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** One Shot-style message stream. The screen collects these into a Snackbar. */
    private val _snackbar = MutableStateFlow<SnackbarMessage?>(null)
    val snackbar: StateFlow<SnackbarMessage?> = _snackbar.asStateFlow()

    /** True while a subscribe / create round-trip is in flight. UI dims FAB. */
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /**
     * Account bucket -> ordered list of [CalendarEntry] for that account.
     * The Manage Calendars screen renders one section header per account
     * followed by the rows; the VM doesn't enforce sticky-header logic
     * (Compose `LazyColumn with key(...)` handles it in pure UI).
     */
    val rowsByAccount: StateFlow<Map<Account, List<CalendarEntry>>> = combine(
        calendarRepository.observeCalendarList(),
        accountRepository.getAccounts()
    ) { rows, accounts ->
        val gmailAccounts = accounts.filter { it.accountType == AccountType.GMAIL }
        val accountById = gmailAccounts.associateBy { it.id }
        rows.groupBy { entry -> accountById[entry.accountId] }
            .filterKeys { it != null }
            .mapKeys { it.key!! }
            .mapValues { (_, entries) ->
                entries.sortedWith(compareBy({ !it.isPrimary }, { it.summary.lowercase() }))
            }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    fun sync() {
        viewModelScope.launch {
            accountRepository.getAccounts().first().filter {
                it.accountType == AccountType.GMAIL
            }.forEach { account ->
                runCatching { calendarRepository.syncCalendarList(account) }
                    .onFailure { _snackbar.value = SnackbarMessage.SyncFailed(account.email) }
            }
            calendarSourceRepository.syncAll()
            _snackbar.value = SnackbarMessage.SyncSucceeded
        }
    }

    /* ---------- standalone sources ---------- */

    fun addIcsSource(url: String, displayName: String?) {
        if (url.isBlank()) {
            _snackbar.value = SnackbarMessage.InvalidUrl
            return
        }
        viewModelScope.launch {
            _busy.value = true
            runCatching { calendarSourceRepository.addIcsSource(url, displayName) }
                .onSuccess { _snackbar.value = SnackbarMessage.Subscribed(it.displayName) }
                .onFailure {
                    _snackbar.value = SnackbarMessage.SubscribeFailed(it.message ?: "Unknown error")
                }
            _busy.value = false
        }
    }

    fun toggleSourceVisible(id: Long, isVisible: Boolean) {
        viewModelScope.launch { calendarSourceRepository.setVisible(id, isVisible) }
    }

    /* ---------- CalDAV connect flow ---------- */

    /**
     * Two-step connect: [discoverCalDav] probes the server and lands in
     * [CalDavDiscovery.Found] (keeping the credentials so the add step can
     * reuse them), then [addCalDavCalendars] subscribes the picked
     * collections. [dismissCalDavDiscovery] abandons the flow.
     */
    private val _calDavDiscovery = MutableStateFlow<CalDavDiscovery>(CalDavDiscovery.Idle)
    val calDavDiscovery: StateFlow<CalDavDiscovery> = _calDavDiscovery.asStateFlow()

    fun discoverCalDav(url: String, username: String, password: String) {
        if (url.isBlank()) {
            _snackbar.value = SnackbarMessage.InvalidUrl
            return
        }
        _calDavDiscovery.value = CalDavDiscovery.Discovering
        viewModelScope.launch {
            runCatching { calendarSourceRepository.discoverCalDav(url, username, password) }
                .onSuccess { calendars ->
                    if (calendars.isEmpty()) {
                        _calDavDiscovery.value = CalDavDiscovery.Idle
                        _snackbar.value = SnackbarMessage.SubscribeFailed("No calendars found")
                    } else {
                        _calDavDiscovery.value =
                            CalDavDiscovery.Found(calendars, username, password)
                    }
                }
                .onFailure {
                    _calDavDiscovery.value = CalDavDiscovery.Idle
                    _snackbar.value =
                        SnackbarMessage.SubscribeFailed(it.message ?: "Unknown error")
                }
        }
    }

    fun addCalDavCalendars(selected: List<CalDavClient.DiscoveredCalendar>) {
        val found = _calDavDiscovery.value as? CalDavDiscovery.Found ?: return
        _calDavDiscovery.value = CalDavDiscovery.Idle
        if (selected.isEmpty()) return
        viewModelScope.launch {
            _busy.value = true
            runCatching {
                calendarSourceRepository.addCalDavSources(selected, found.username, found.password)
            }
                .onSuccess { _snackbar.value = SnackbarMessage.CalDavAdded(it.size) }
                .onFailure {
                    _snackbar.value = SnackbarMessage.SubscribeFailed(it.message ?: "Unknown error")
                }
            _busy.value = false
        }
    }

    fun dismissCalDavDiscovery() {
        _calDavDiscovery.value = CalDavDiscovery.Idle
    }

    fun deleteSource(source: CalendarSource) {
        viewModelScope.launch {
            calendarSourceRepository.delete(source.id)
            _snackbar.value = SnackbarMessage.SourceRemoved(source.displayName)
        }
    }

    fun toggleSelected(account: Account, calendarId: String, isSelected: Boolean) {
        viewModelScope.launch {
            calendarRepository.setSelected(account, calendarId, isSelected)
        }
    }

    fun subscribeByUrl(account: Account, url: String) {
        if (url.isBlank()) {
            _snackbar.value = SnackbarMessage.InvalidUrl
            return
        }
        viewModelScope.launch {
            _busy.value = true
            runCatching { calendarRepository.subscribeByUrl(account, url) }
                .onSuccess { _snackbar.value = SnackbarMessage.Subscribed(it.summary) }
                .onFailure { _snackbar.value = SnackbarMessage.SubscribeFailed(it.message ?: "Unknown error") }
            _busy.value = false
        }
    }

    fun createNew(account: Account, summary: String, timezone: String) {
        if (summary.isBlank()) {
            _snackbar.value = SnackbarMessage.InvalidSummary
            return
        }
        viewModelScope.launch {
            _busy.value = true
            runCatching { calendarRepository.createNewCalendar(account, summary, timezone = timezone) }
                .onSuccess { _snackbar.value = SnackbarMessage.Created(it.summary) }
                .onFailure { _snackbar.value = SnackbarMessage.CreateFailed(it.message ?: "Unknown error") }
            _busy.value = false
        }
    }

    fun consumeSnackbar() {
        _snackbar.value = null
    }
}

/** Snackbar payloads the screen renders into M3 Snackbar messages. */
sealed interface SnackbarMessage {
    data object InvalidUrl : SnackbarMessage
    data object InvalidSummary : SnackbarMessage
    data class Subscribed(val summary: String) : SnackbarMessage
    data class Created(val summary: String) : SnackbarMessage
    data class SubscribeFailed(val reason: String) : SnackbarMessage
    data class CreateFailed(val reason: String) : SnackbarMessage
    data class SyncFailed(val accountEmail: String) : SnackbarMessage
    data object SyncSucceeded : SnackbarMessage
    data class SourceRemoved(val summary: String) : SnackbarMessage
    data class CalDavAdded(val count: Int) : SnackbarMessage
}

/** State machine for the CalDAV connect dialog flow. */
sealed interface CalDavDiscovery {
    data object Idle : CalDavDiscovery
    data object Discovering : CalDavDiscovery
    data class Found(
        val calendars: List<CalDavClient.DiscoveredCalendar>,
        val username: String,
        val password: String
    ) : CalDavDiscovery
}
