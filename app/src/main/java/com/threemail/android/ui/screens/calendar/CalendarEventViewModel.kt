package com.threemail.android.ui.screens.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.threemail.android.data.repository.AccountRepository
import com.threemail.android.data.repository.CalendarRepository
import com.threemail.android.data.repository.CalendarSourceRepository
import com.threemail.android.domain.model.CalendarEvent
import com.threemail.android.domain.model.CalendarEventStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import javax.inject.Inject

/**
 * State machine for the create/edit event screen.
 *
 * - In **create** mode ([CalendarFormUiState.isEditing] = false), the user supplies a fresh
 *   `title` and a start/end range - saving calls [CalendarRepository.createRemote].
 * - In **edit** mode, we hydrate from a row in Room (carrying both the local PK and the
 *   remote `eventId`) and saving calls [CalendarRepository.updateRemote]. Deletion calls
 *   [CalendarRepository.deleteRemote].
 *
 * The Save button is disabled unless the title is non-empty and `start <= end`.
 */
@HiltViewModel
class CalendarEventViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
    private val calendarRepository: CalendarRepository,
    private val calendarSourceRepository: CalendarSourceRepository
) : ViewModel() {

    private val zone: ZoneId = ZoneId.systemDefault()

    private val _state = MutableStateFlow(CalendarFormUiState.empty(zone))
    val state: StateFlow<CalendarFormUiState> = _state.asStateFlow()

    private val _saveResult = MutableStateFlow<CalendarSaveResult>(CalendarSaveResult.Idle)
    val saveResult: StateFlow<CalendarSaveResult> = _saveResult.asStateFlow()

    private var initialAccountId: Long = 0L
    private var existingEventId: Long = 0L

    /**
     * The Room row backing edit mode, kept so a CalDAV save can carry the
     * object's href / iCalUID / etag through unchanged — the form only edits
     * the user-visible fields.
     */
    private var loadedEvent: CalendarEvent? = null

    fun bindInitial(accountId: Long, eventId: Long, sourceId: Long = 0L) {
        if (_state.value.isBound) return
        initialAccountId = accountId
        existingEventId = eventId
        viewModelScope.launch {
            val account = if (accountId > 0L) accountRepository.getAccountById(accountId) else null
            if (eventId <= 0L) {
                // Create mode: a positive sourceId targets a CalDAV
                // collection instead of a Google calendar.
                val source = if (sourceId > 0L) {
                    calendarSourceRepository.getSources().firstOrNull { it.id == sourceId }
                } else {
                    null
                }
                _state.value = CalendarFormUiState.empty(zone).copy(
                    accountId = accountId,
                    sourceId = source?.id,
                    accountEmail = source?.displayName ?: account?.email ?: ""
                )
            } else {
                val event = calendarRepository.getById(eventId)
                if (event != null) {
                    loadedEvent = event
                    val sourceName = event.sourceId?.let { sid ->
                        calendarSourceRepository.getSources().firstOrNull { it.id == sid }?.displayName
                    }
                    _state.value = CalendarFormUiState.fromExisting(
                        zone, sourceName ?: account?.email ?: "", event
                    )
                } else {
                    _state.value = CalendarFormUiState.empty(zone).copy(accountId = accountId, accountEmail = account?.email ?: "")
                }
            }
        }
    }

    fun setTitle(value: String) = _state.update { it.copy(title = value) }
    fun setLocation(value: String) = _state.update { it.copy(location = value) }
    fun setAttendees(value: String) = _state.update { it.copy(attendeesRaw = value) }
    fun setDescription(value: String) = _state.update { it.copy(description = value) }

    fun toggleAllDay(allDay: Boolean) = _state.update { current ->
        if (current.allDay == allDay) current
        else if (allDay) {
            val day = LocalDate.ofEpochDay(current.startMs / 86_400_000L)
            val sMs = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            // All-day endEpochMs is exclusive: end-of-day + 1 day in UTC.
            val eMs = day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            current.copy(
                allDay = true,
                startMs = sMs,
                endMs = eMs,
                timezone = "UTC"
            )
        } else {
            // Switching back from all-day: anchor the new timed range to the original date at 9:00–10:00.
            val day = LocalDate.ofEpochDay(current.startMs / 86_400_000L)
            val sMs = day.atTime(LocalTime.of(9, 0)).atZone(zone).toInstant().toEpochMilli()
            val eMs = day.atTime(LocalTime.of(10, 0)).atZone(zone).toInstant().toEpochMilli()
            current.copy(
                allDay = false,
                startMs = sMs,
                endMs = eMs,
                timezone = zone.id
            )
        }
    }

    fun setStartDate(date: LocalDate) = _state.update { current ->
        if (current.allDay) {
            val sMs = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            val span = current.endMs - current.startMs
            current.copy(startMs = sMs, endMs = sMs + span)
        } else {
            val time = java.time.Instant.ofEpochMilli(current.startMs).atZone(zone).toLocalTime()
            val eTime = java.time.Instant.ofEpochMilli(current.endMs).atZone(zone).toLocalTime()
            current.copy(
                startMs = date.atTime(time).atZone(zone).toInstant().toEpochMilli(),
                endMs = date.atTime(eTime).atZone(zone).toInstant().toEpochMilli()
            )
        }
    }

    fun setStartTime(time: LocalTime) = _state.update { current ->
        if (current.allDay) current
        else {
            val date = java.time.Instant.ofEpochMilli(current.startMs).atZone(zone).toLocalDate()
            current.copy(startMs = date.atTime(time).atZone(zone).toInstant().toEpochMilli())
        }
    }

    /**
     * Sets the end date in a way that preserves the same wall-clock duration from the
     * start (so changing only the end date doesn't surprise the user with a 30-minute
     * event becoming a 12-hour one).
     */
    fun setEndDate(date: LocalDate) = _state.update { current ->
        if (current.allDay) {
            // All-day end is exclusive: drop the day after the user picks.
            val eMs = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            current.copy(endMs = eMs)
        } else {
            val time = java.time.Instant.ofEpochMilli(current.endMs).atZone(zone).toLocalTime()
            val newEnd = date.atTime(time).atZone(zone).toInstant().toEpochMilli()
            // Keep duration.
            val duration = current.endMs - current.startMs
            current.copy(endMs = newEnd, startMs = newEnd - duration.coerceAtLeast(0))
        }
    }

    fun setEndTime(time: LocalTime) = _state.update { current ->
        if (current.allDay) current
        else {
            val date = java.time.Instant.ofEpochMilli(current.endMs).atZone(zone).toLocalDate()
            current.copy(endMs = date.atTime(time).atZone(zone).toInstant().toEpochMilli())
        }
    }

    fun save() {
        val snapshot = _state.value
        if (!snapshot.canSave) {
            _saveResult.value = CalendarSaveResult.Invalid
            return
        }
        val sourceId = snapshot.sourceId
        if (sourceId == null && initialAccountId <= 0L) {
            _saveResult.value = CalendarSaveResult.Error("No writable calendar available.")
            return
        }
        _saveResult.value = CalendarSaveResult.Saving
        viewModelScope.launch {
            try {
                val saved = if (sourceId != null) {
                    // CalDAV path: merge the edited fields onto the loaded
                    // row so href / iCalUID / etag survive the round-trip.
                    val draft = snapshot.toDomain(CalendarEvent.NO_ACCOUNT).copy(
                        sourceId = sourceId,
                        calendarId = "source:$sourceId",
                        eventId = loadedEvent?.eventId,
                        iCalUID = loadedEvent?.iCalUID,
                        etag = loadedEvent?.etag
                    )
                    if (snapshot.isEditing) {
                        calendarSourceRepository.updateCalDavEvent(draft)
                    } else {
                        calendarSourceRepository.createCalDavEvent(sourceId, draft)
                    }
                } else {
                    val account = accountRepository.getAccountById(initialAccountId)
                        ?: throw IllegalStateException("Account $initialAccountId not found")
                    val draft = snapshot.toDomain(initialAccountId)
                    if (snapshot.isEditing) {
                        calendarRepository.updateRemote(account, draft.calendarId, draft)
                    } else {
                        calendarRepository.createRemote(account, draft.calendarId, draft)
                    }
                }
                _saveResult.value = CalendarSaveResult.Success(saved.id)
            } catch (e: Exception) {
                _saveResult.value = CalendarSaveResult.Error(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun delete() {
        val snapshot = _state.value
        if (!snapshot.isEditing) {
            _saveResult.value = CalendarSaveResult.Error("Nothing to delete.")
            return
        }
        val remoteId = snapshot.remoteEventId ?: run {
            _saveResult.value = CalendarSaveResult.Error("Missing remote event id.")
            return
        }
        _saveResult.value = CalendarSaveResult.Deleting
        viewModelScope.launch {
            try {
                val sourced = loadedEvent?.takeIf { it.sourceId != null }
                if (sourced != null) {
                    calendarSourceRepository.deleteCalDavEvent(sourced)
                } else {
                    val account = accountRepository.getAccountById(initialAccountId)
                        ?: throw IllegalStateException("Account $initialAccountId not found")
                    calendarRepository.deleteRemote(account, snapshot.calendarId, remoteId)
                }
                _saveResult.value = CalendarSaveResult.Deleted
            } catch (e: Exception) {
                _saveResult.value = CalendarSaveResult.Error(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun consumeResult() {
        _saveResult.value = CalendarSaveResult.Idle
    }

    companion object {
        const val ACCOUNT_COLOR_COUNT = 6
    }
}

/**
 * Immutable snapshot of the event form. The UI renders this and writes through setter
 * calls on [CalendarEventViewModel]. [canSave] is the single source of truth for whether
 * the Save button should be enabled.
 */
data class CalendarFormUiState(
    val isBound: Boolean = false,
    val isEditing: Boolean = false,
    val calendarId: String = "primary",
    val remoteEventId: String? = null,
    val localId: Long = 0L,
    val accountId: Long = 0L,
    /** Non-null when the event lives in a CalDAV source instead of Google. */
    val sourceId: Long? = null,
    /** Display label: the account email, or the source's name for CalDAV. */
    val accountEmail: String = "",
    val title: String = "",
    val description: String = "",
    val location: String = "",
    val attendeesRaw: String = "",
    val allDay: Boolean = false,
    val startMs: Long = 0L,
    val endMs: Long = 0L,
    val timezone: String = ZoneId.systemDefault().id,
    val status: CalendarEventStatus = CalendarEventStatus.CONFIRMED
) {
    val canSave: Boolean
        get() = title.isNotBlank() && startMs <= endMs &&
            (accountId > 0L || sourceId != null) &&
            (!isEditing || remoteEventId != null)

    fun toDomain(accountId: Long): CalendarEvent = CalendarEvent(
        id = localId,
        accountId = accountId,
        calendarId = calendarId,
        eventId = remoteEventId,
        title = title.trim(),
        description = description.takeIf { it.isNotBlank() },
        location = location.takeIf { it.isNotBlank() },
        startEpochMs = startMs,
        endEpochMs = endMs,
        allDay = allDay,
        timezone = timezone,
        status = status,
        attendees = attendeesRaw.split(',').map { it.trim() }.filter { it.isNotBlank() }
    )

    companion object {
        fun empty(zone: ZoneId): CalendarFormUiState {
            val today = LocalDate.now(zone)
            return CalendarFormUiState(
                isBound = true,
                isEditing = false,
                startMs = today.atTime(LocalTime.of(9, 0)).atZone(zone).toInstant().toEpochMilli(),
                endMs = today.atTime(LocalTime.of(10, 0)).atZone(zone).toInstant().toEpochMilli(),
                timezone = zone.id
            )
        }

        fun fromExisting(zone: ZoneId, accountEmail: String, event: CalendarEvent): CalendarFormUiState =
            CalendarFormUiState(
                isBound = true,
                isEditing = true,
                calendarId = event.calendarId,
                remoteEventId = event.eventId,
                localId = event.id,
                accountId = event.accountId,
                sourceId = event.sourceId,
                accountEmail = accountEmail,
                title = event.title,
                description = event.description ?: "",
                location = event.location ?: "",
                attendeesRaw = event.attendees.joinToString(", "),
                allDay = event.allDay,
                startMs = event.startEpochMs,
                endMs = event.endEpochMs,
                timezone = event.timezone,
                status = event.status
            )
    }
}

sealed interface CalendarSaveResult {
    data object Idle : CalendarSaveResult
    data object Saving : CalendarSaveResult
    data object Deleting : CalendarSaveResult
    data class Success(val savedId: Long) : CalendarSaveResult
    data object Deleted : CalendarSaveResult
    data object Invalid : CalendarSaveResult
    data class Error(val message: String) : CalendarSaveResult
}
