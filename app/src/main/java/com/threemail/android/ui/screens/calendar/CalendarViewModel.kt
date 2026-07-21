package com.threemail.android.ui.screens.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.threemail.android.data.repository.AccountRepository
import com.threemail.android.data.repository.CalendarRepository
import com.threemail.android.data.repository.CalendarSourceRepository
import com.threemail.android.domain.model.Account
import com.threemail.android.domain.model.AccountType
import com.threemail.android.domain.model.CalendarEvent
import com.threemail.android.domain.model.CalendarSource
import com.threemail.android.sync.SyncScheduler
import com.threemail.android.domain.model.occursOn
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject

/** Six weeks of cells, padded with overflow from the previous/next month. */
private const val GRID_ROWS = 6
private const val GRID_COLUMNS = 7
private const val OVERFLOW_DAYS = 7

/**
 * State holder for the Calendar screen.
 *
 * Aggregates two event feeds onto one 6-week grid window:
 *  - every active Gmail account with `calendarSyncEnabled` (via
 *    [CalendarRepository]), and
 *  - every visible standalone subscription — ICS feeds — (via
 *    [CalendarSourceRepository]).
 *
 * The optional filter narrows to a single account or a single source;
 * `null`/`null` aggregates everything.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CalendarViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
    private val calendarRepository: CalendarRepository,
    private val calendarSourceRepository: CalendarSourceRepository,
    private val syncScheduler: SyncScheduler
) : ViewModel() {

    private val zone: ZoneId = ZoneId.systemDefault()
    private val today: LocalDate = LocalDate.now(zone)

    private val _selectedMonth = MutableStateFlow(YearMonth.from(today))
    val selectedMonth: StateFlow<YearMonth> = _selectedMonth.asStateFlow()

    private val _selectedDay = MutableStateFlow(today)
    val selectedDay: StateFlow<LocalDate> = _selectedDay.asStateFlow()

    /**
     * Optional account filter. `null` means "aggregate everything"; a
     * non-null value narrows both the grid and the day agenda to events from
     * that account. Mutually exclusive with [selectedSourceId].
     */
    private val _selectedAccountId = MutableStateFlow<Long?>(null)
    val selectedAccountId: StateFlow<Long?> = _selectedAccountId.asStateFlow()

    /** Optional standalone-source filter; mutually exclusive with [selectedAccountId]. */
    private val _selectedSourceId = MutableStateFlow<Long?>(null)
    val selectedSourceId: StateFlow<Long?> = _selectedSourceId.asStateFlow()

    val activeAccounts: StateFlow<List<Account>> = accountRepository
        .getAccounts()
        .map { all ->
            all.filter { it.isActive && it.calendarSyncEnabled && it.accountType == AccountType.GMAIL }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Every standalone subscription, for the filter chips and empty-state check. */
    val sources: StateFlow<List<CalendarSource>> = calendarSourceRepository
        .observeSources()
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

    private data class FilterState(
        val accounts: List<Account>,
        val month: YearMonth,
        val accountFilter: Long?,
        val sourceFilter: Long?
    )

    val eventsByDay: StateFlow<Map<LocalDate, List<CalendarEvent>>> = combine(
        activeAccounts,
        selectedMonth,
        selectedAccountId,
        selectedSourceId
    ) { accounts, month, accountFilter, sourceFilter ->
        FilterState(accounts, month, accountFilter, sourceFilter)
    }
        .flatMapLatest { state ->
            val (windowStart, windowEnd) = visibleWindowLocalDates
            val startMs = windowStart.atStartOfDay(zone).toInstant().toEpochMilli()
            val endMsExclusive = windowEnd.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

            val accountFlows: List<Flow<List<CalendarEvent>>> = when {
                state.sourceFilter != null -> emptyList()
                state.accountFilter != null -> state.accounts.filter { it.id == state.accountFilter }
                else -> state.accounts
            }.map { acc -> calendarRepository.getEventsInRange(acc.id, startMs, endMsExclusive) }

            val sourceFlow: Flow<List<CalendarEvent>>? = when {
                state.accountFilter != null -> null
                state.sourceFilter != null -> calendarSourceRepository
                    .getEventsInRangeForSource(state.sourceFilter, startMs, endMsExclusive)
                else -> calendarSourceRepository.getEventsInRange(startMs, endMsExclusive)
            }

            val flows = accountFlows + listOfNotNull(sourceFlow)
            if (flows.isEmpty()) {
                flowOf(emptyMap())
            } else {
                // combine (not merge): every feed contributes to each emission,
                // so one account's update can't wipe the others off the grid.
                combine(flows) { lists ->
                    projectAllToVisibleGrid(lists.flatMap { it }, windowStart, windowEnd)
                }
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

    /** Pass `null` to clear the filter and aggregate everything. */
    fun selectAccount(accountId: Long?) {
        if (accountId == null || activeAccounts.value.any { it.id == accountId }) {
            _selectedAccountId.value = accountId
            _selectedSourceId.value = null
        }
    }

    /** Narrows the grid and agenda to one standalone subscription. */
    fun selectSource(sourceId: Long?) {
        if (sourceId == null || sources.value.any { it.id == sourceId }) {
            _selectedSourceId.value = sourceId
            _selectedAccountId.value = null
        }
    }

    fun refresh() {
        viewModelScope.launch { syncScheduler.schedulePeriodicCalendarSync(replace = true) }
        // Standalone feeds refresh inline: they're cheap single fetches and
        // the user pressed the button expecting the grid to move now.
        viewModelScope.launch { calendarSourceRepository.syncAll() }
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
}
