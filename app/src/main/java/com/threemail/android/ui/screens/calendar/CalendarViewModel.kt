package com.threemail.android.ui.screens.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.threemail.android.data.repository.AccountRepository
import com.threemail.android.data.repository.CalendarRepository
import com.threemail.android.domain.model.Account
import com.threemail.android.domain.model.AccountType
import com.threemail.android.domain.model.CalendarEvent
import com.threemail.android.sync.SyncScheduler
import com.threemail.android.domain.model.occursOn
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import javax.inject.Inject

/** Six weeks of cells, padded with overflow from the previous/next month. */
private const val GRID_ROWS = 6
private const val GRID_COLUMNS = 7
private const val OVERFLOW_DAYS = 7

/**
 * State holder for the Calendar screen.
 *
 * Observes every active Gmail account that has `calendarSyncEnabled`, then projects the
 * combined flow onto a 6-week grid window. The UI is intentionally a single VM rather than
 * a Compose state object so the data layer can fan-in multiple Google Calendar accounts
 * for users with multiple Google identities.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CalendarViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
    private val calendarRepository: CalendarRepository,
    private val syncScheduler: SyncScheduler
) : ViewModel() {

    private val zone: ZoneId = ZoneId.systemDefault()
    private val today: LocalDate = LocalDate.now(zone)

    private val _selectedMonth = MutableStateFlow(YearMonth.from(today))
    val selectedMonth: StateFlow<YearMonth> = _selectedMonth.asStateFlow()

    private val _selectedDay = MutableStateFlow(today)
    val selectedDay: StateFlow<LocalDate> = _selectedDay.asStateFlow()

    /**
     * Optional account filter. `null` means "aggregate every active Gmail account"; a
     * non-null value narrows both the grid and the day agenda to events from that
     * account. Persists across month changes within the same ViewModel lifetime.
     */
    private val _selectedAccountId = MutableStateFlow<Long?>(null)
    val selectedAccountId: StateFlow<Long?> = _selectedAccountId.asStateFlow()

    val activeAccounts: StateFlow<List<Account>> = accountRepository
        .getAccounts()
        .map { all ->
            all.filter { it.isActive && it.calendarSyncEnabled && it.accountType == AccountType.GMAIL }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** The first day of the visible 6-week grid, plus one week of overflow on each side. */
    private val visibleWindowLocalDates: Pair<LocalDate, LocalDate>
        get() {
            val firstOfMonth = _selectedMonth.value.atDay(1)
            // Roll back to Sunday of the week containing the first-of-month.
            val gridStart = firstOfMonth.minusDays(firstOfMonth.dayOfWeek.value.toLong() % 7).minusDays(OVERFLOW_DAYS.toLong())
            val gridEnd = gridStart.plusDays((GRID_ROWS * GRID_COLUMNS + OVERFLOW_DAYS * 2 - 1).toLong())
            return gridStart to gridEnd
        }

    val eventsByDay: StateFlow<Map<LocalDate, List<CalendarEvent>>> = combine(
        activeAccounts,
        selectedMonth,
        selectedAccountId
    ) { accounts, month, filter -> Triple(accounts, month, filter) }
        .flatMapLatest { (accounts, _, filter) ->
            val (windowStart, windowEnd) = visibleWindowLocalDates
            val startMs = windowStart.atStartOfDay(zone).toInstant().toEpochMilli()
            val endMsExclusive = windowEnd.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val filtered = if (filter == null) accounts else accounts.filter { it.id == filter }
            if (filtered.isEmpty()) {
                flowOf(emptyMap())
            } else {
                filtered.map { acc -> calendarRepository.getEventsInRange(acc.id, startMs, endMsExclusive) }
                    .merge()
                    .map { all -> projectAllToVisibleGrid(all, windowStart, windowEnd) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** The events that happen on [selectedDay], ordered all-day-first then timed ascending. */
    val selectedDayEvents: StateFlow<List<CalendarEvent>> = combine(
        eventsByDay,
        selectedDay
    ) { map, day -> map[day].orEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun goToNextMonth() {
        _selectedMonth.value = _selectedMonth.value.plusMonths(1)
    }

    fun goToPreviousMonth() {
        _selectedMonth.value = _selectedMonth.value.minusMonths(1)
    }

    fun goToToday() {
        _selectedMonth.value = YearMonth.from(today)
        _selectedDay.value = today
    }

    fun selectDay(day: LocalDate) {
        _selectedDay.value = day
        // Bring the visible month in line if the user picked a day from the overflow band.
        val ym = YearMonth.from(day)
        if (ym != _selectedMonth.value) _selectedMonth.value = ym
    }

    /** Pass `null` to clear the filter and aggregate every active Gmail account. */
    fun selectAccount(accountId: Long?) {
        if (accountId == null || activeAccounts.value.any { it.id == accountId }) {
            _selectedAccountId.value = accountId
        }
    }

    fun refresh() {
        viewModelScope.launch { syncScheduler.schedulePeriodicCalendarSync(replace = true) }
    }

    private fun projectAllToVisibleGrid(
        all: List<CalendarEvent>,
        windowStart: LocalDate,
        windowEnd: LocalDate
    ): Map<LocalDate, List<CalendarEvent>> {
        val byDay = LinkedHashMap<LocalDate, MutableList<CalendarEvent>>(windowEnd.toEpochDay().toInt() - windowStart.toEpochDay().toInt() + 1)
        var cursor = windowStart
        while (!cursor.isAfter(windowEnd)) {
            byDay[cursor] = mutableListOf()
            cursor = cursor.plusDays(1)
        }
        all.forEach { event ->
            var day = windowStart
            while (!day.isAfter(windowEnd)) {
                if (event.occursOn(day, zone)) byDay[day]?.add(event)
                day = day.plusDays(1)
            }
        }
        return byDay.mapValues { (_, value) ->
            value.sortedWith(compareBy({ !it.allDay }, { it.startEpochMs }))
        }
    }

    /**
     * Mappers from `Event` rows to a deterministic, low-overlap indicator "color" derived
     * from the event's calendar id (or its title when no calendar id is set).
     * Stable across recompositions to avoid pill color thrash.
     */
    @Suppress("unused")
    private fun accountColorIndex(event: CalendarEvent): Int =
        ((event.calendarId.hashCode() and 0x7FFFFFFF) % ACCOUNT_COLOR_COUNT)

    companion object {
        const val ACCOUNT_COLOR_COUNT = 6

        @Suppress("unused")
        private fun epochToLocalDate(epochMs: Long, zone: ZoneId): LocalDate =
            Instant.ofEpochMilli(epochMs).atZone(if (epochMs % 86_400_000 == 0L) ZoneOffset.UTC else zone).toLocalDate()
    }
}
