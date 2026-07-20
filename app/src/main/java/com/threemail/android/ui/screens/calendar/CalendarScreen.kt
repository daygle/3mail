package com.threemail.android.ui.screens.calendar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.threemail.android.R
import com.threemail.android.ui.components.EmptyState
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val MonthHeaderFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    viewModel: CalendarViewModel,
    onNavigateBack: () -> Unit,
    onCreateEvent: (accountId: Long) -> Unit,
    onEditEvent: (accountId: Long, eventId: Long) -> Unit,
    onNavigateToManageCalendars: () -> Unit = {}
) {
    val selectedMonth by viewModel.selectedMonth.collectAsState()
    val selectedDay by viewModel.selectedDay.collectAsState()
    val eventsByDay by viewModel.eventsByDay.collectAsState()
    val selectedDayEvents by viewModel.selectedDayEvents.collectAsState()
    val activeAccounts by viewModel.activeAccounts.collectAsState()
    val selectedAccountId by viewModel.selectedAccountId.collectAsState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(selectedMonth.format(MonthHeaderFormatter)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cancel)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::goToToday) {
                        Icon(
                            imageVector = Icons.Default.Today,
                            contentDescription = stringResource(R.string.calendar_today)
                        )
                    }
                    IconButton(onClick = viewModel::goToPreviousMonth) {
                        Icon(
                            imageVector = Icons.Default.ChevronLeft,
                            contentDescription = stringResource(R.string.calendar_prev_month)
                        )
                    }
                    IconButton(onClick = viewModel::goToNextMonth) {
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = stringResource(R.string.calendar_next_month)
                        )
                    }
                    IconButton(onClick = onNavigateToManageCalendars) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = stringResource(R.string.manage_calendars_open)
                        )
                    }
                    IconButton(onClick = viewModel::refresh) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.sync)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface
                ),
                scrollBehavior = scrollBehavior
            )
        },
        floatingActionButton = {
            val fabAccountId = activeAccounts.firstOrNull()?.id ?: 0L
            ExtendedFloatingActionButton(
                onClick = {
                    if (fabAccountId > 0L) onCreateEvent(fabAccountId)
                },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.calendar_new_event)) },
                expanded = true
            )
        }
    ) { padding ->
        if (activeAccounts.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                EmptyState(
                    title = stringResource(R.string.calendar_no_account_title),
                    subtitle = stringResource(R.string.calendar_no_account_subtitle)
                )
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (activeAccounts.size > 1) {
                AccountFilterRow(
                    accounts = activeAccounts,
                    selectedAccountId = selectedAccountId,
                    onSelectAccount = viewModel::selectAccount
                )
                Spacer(modifier = Modifier.height(4.dp))
            }
            CalendarBody(
                selectedMonth = selectedMonth,
                selectedDay = selectedDay,
                today = LocalDate.now(ZoneId.systemDefault()),
                eventsByDay = eventsByDay,
                selectedDayEvents = selectedDayEvents,
                onSelectDay = viewModel::selectDay,
                onEventClick = { event ->
                    if (event.eventId != null) onEditEvent(event.accountId, event.id)
                },
                onCreateEvent = {
                    val accountId = selectedAccountId ?: activeAccounts.firstOrNull()?.id ?: 0L
                    if (accountId > 0L) onCreateEvent(accountId)
                },
                onRefresh = viewModel::refresh
            )
        }
    }
}

@Composable
private fun CalendarBody(
    selectedMonth: YearMonth,
    selectedDay: LocalDate,
    today: LocalDate,
    eventsByDay: Map<LocalDate, List<com.threemail.android.domain.model.CalendarEvent>>,
    selectedDayEvents: List<com.threemail.android.domain.model.CalendarEvent>,
    onSelectDay: (LocalDate) -> Unit,
    onEventClick: (com.threemail.android.domain.model.CalendarEvent) -> Unit,
    onCreateEvent: () -> Unit,
    onRefresh: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column {
            MonthGrid(
                visibleMonth = selectedMonth,
                eventsByDay = eventsByDay,
                selectedDay = selectedDay,
                today = today,
                onDayClick = onSelectDay,
                onEventClick = onEventClick,
                modifier = Modifier.padding(8.dp)
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(modifier = Modifier)
        Text(
            text = if (selectedDay == today) {
                stringResource(R.string.calendar_today)
            } else {
                ""
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = 16.dp)
        )
    }
    DayAgenda(
        day = selectedDay,
        events = selectedDayEvents,
        onEventClick = onEventClick,
        onCreateClick = onCreateEvent,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize()
    )
    // Padding: ensure content sits above the system bottom inset / FAB by accounting
    // for any insets not otherwise consumed.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp)
    )
}

/**
 * Horizontally-scrollable chip row shown above the grid when the user has more than
 * one Gmail account with calendar sync enabled. An "All" chip aggregates; tapping a
 * specific account scopes both the grid and the day agenda to that account.
 */
@Composable
private fun AccountFilterRow(
    accounts: List<com.threemail.android.domain.model.Account>,
    selectedAccountId: Long?,
    onSelectAccount: (Long?) -> Unit
) {
    val accountLabelFor = remember(accounts) { accounts.associate { it.id to it.email } }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilterChip(
            selected = selectedAccountId == null,
            onClick = { onSelectAccount(null) },
            label = { Text(stringResource(R.string.calendar_filter_all)) },
            modifier = Modifier.padding(end = 8.dp)
        )
        accounts.forEach { account ->
            FilterChip(
                selected = selectedAccountId == account.id,
                onClick = { onSelectAccount(account.id) },
                label = { Text(accountLabelFor[account.id] ?: account.email) },
                modifier = Modifier.padding(end = 8.dp)
            )
        }
    }
}
