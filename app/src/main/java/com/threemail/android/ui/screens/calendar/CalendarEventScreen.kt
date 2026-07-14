package com.threemail.android.ui.screens.calendar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.threemail.android.R
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DateFieldFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", Locale.getDefault())

private val TimeFieldFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarEventScreen(
    viewModel: CalendarEventViewModel,
    accountId: Long,
    eventId: Long,
    onNavigateBack: () -> Unit
) {
    LaunchedEffect(accountId, eventId) {
        viewModel.bindInitial(accountId, eventId)
    }
    val state by viewModel.state.collectAsState()
    val saveResult by viewModel.saveResult.collectAsState()

    val showDeleteDialog = remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    /**
     * Bridges the VM's one-shot save events to the M3 Snackbar so the user actually
     * sees whether their Save tap succeeded. Success/Delete post a confirmation then
     * navigate back; Error stays on screen so the user can retry.
     */
    LaunchedEffect(saveResult) {
        when (val current = saveResult) {
            is CalendarSaveResult.Success -> {
                snackbarHostState.showSnackbar(
                    message = context.getString(R.string.calendar_event_saved),
                    duration = SnackbarDuration.Short
                )
                viewModel.consumeResult()
                onNavigateBack()
            }
            is CalendarSaveResult.Deleted -> {
                snackbarHostState.showSnackbar(
                    message = context.getString(R.string.calendar_event_deleted),
                    duration = SnackbarDuration.Short
                )
                viewModel.consumeResult()
                onNavigateBack()
            }
            is CalendarSaveResult.Error -> {
                snackbarHostState.showSnackbar(
                    message = context.getString(R.string.calendar_event_save_failed, current.message),
                    duration = SnackbarDuration.Long
                )
                viewModel.consumeResult()
            }
            else -> Unit
        }
    }

    if (state.isBound) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            stringResource(
                                if (state.isEditing) R.string.calendar_edit_event
                                else R.string.calendar_new_event
                            )
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.cancel)
                            )
                        }
                    },
                    actions = {
                        if (state.isEditing) {
                            IconButton(onClick = { showDeleteDialog.value = true }) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = stringResource(R.string.delete)
                                )
                            }
                        }
                        TextButton(
                            onClick = viewModel::save,
                            enabled = state.canSave
                        ) {
                            Text(
                                stringResource(R.string.save),
                                color = if (state.canSave) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        ) { padding ->
            EventFormBody(
                state = state,
                viewModel = viewModel,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            )
        }

        if (showDeleteDialog.value) {
            AlertDialog(
                onDismissRequest = { showDeleteDialog.value = false },
                title = { Text(stringResource(R.string.calendar_delete_title)) },
                text = { Text(stringResource(R.string.calendar_delete_body)) },
                confirmButton = {
                    TextButton(onClick = {
                        showDeleteDialog.value = false
                        viewModel.delete()
                    }) { Text(stringResource(R.string.delete)) }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteDialog.value = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }
    } else {
        Box(
            modifier = Modifier.fillMaxSize().padding(PaddingValues(16.dp)),
            contentAlignment = Alignment.Center
        ) {}
    }
}

@Composable
private fun EventFormBody(
    state: CalendarFormUiState,
    viewModel: CalendarEventViewModel,
    modifier: Modifier = Modifier
) {
    val zone = ZoneId.systemDefault()
    val scrollState = rememberScrollState()
    Column(
        modifier = modifier
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (state.accountEmail.isNotBlank()) {
            Text(
                text = state.accountEmail,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }

        OutlinedTextField(
            value = state.title,
            onValueChange = viewModel::setTitle,
            label = { Text(stringResource(R.string.calendar_event_title_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        AllDayRow(
            allDay = state.allDay,
            onToggle = viewModel::toggleAllDay
        )

        DateTimeRow(
            label = stringResource(R.string.calendar_start),
            dateMs = state.startMs,
            allDay = state.allDay,
            zone = zone,
            onDateChange = viewModel::setStartDate,
            onTimeChange = viewModel::setStartTime
        )
        DateTimeRow(
            label = stringResource(R.string.calendar_end),
            dateMs = state.endMs,
            allDay = state.allDay,
            zone = zone,
            onDateChange = viewModel::setEndDate,
            onTimeChange = viewModel::setEndTime
        )

        OutlinedTextField(
            value = state.location,
            onValueChange = viewModel::setLocation,
            label = { Text(stringResource(R.string.calendar_location)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = state.attendeesRaw,
            onValueChange = viewModel::setAttendees,
            label = { Text(stringResource(R.string.calendar_attendees)) },
            singleLine = false,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = state.description,
            onValueChange = viewModel::setDescription,
            label = { Text(stringResource(R.string.calendar_description)) },
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp),
            maxLines = 6
        )

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun AllDayRow(allDay: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = stringResource(R.string.calendar_all_day),
            style = MaterialTheme.typography.bodyLarge
        )
        Switch(checked = allDay, onCheckedChange = onToggle)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateTimeRow(
    label: String,
    dateMs: Long,
    allDay: Boolean,
    zone: ZoneId,
    onDateChange: (LocalDate) -> Unit,
    onTimeChange: (LocalTime) -> Unit
) {
    var showDateDialog by remember { mutableStateOf(false) }
    var showTimeDialog by remember { mutableStateOf(false) }

    val displayedDate: LocalDate = remember(dateMs, allDay) {
        if (allDay) {
            LocalDate.ofEpochDay(dateMs / 86_400_000L)
        } else {
            Instant.ofEpochMilli(dateMs).atZone(zone).toLocalDate()
        }
    }
    val displayedTime: LocalTime = remember(dateMs, allDay) {
        if (allDay) LocalTime.of(0, 0)
        else Instant.ofEpochMilli(dateMs).atZone(zone).toLocalTime()
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(onClick = { showDateDialog = true }) {
                Icon(Icons.Default.Schedule, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(displayedDate.format(DateFieldFormatter))
            }
            if (!allDay) {
                Spacer(modifier = Modifier.width(8.dp))
                FilledTonalButton(onClick = { showTimeDialog = true }) {
                    Text(displayedTime.format(TimeFieldFormatter))
                }
            }
        }
    }

    if (showDateDialog) {
        val initialMs = displayedDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = initialMs)
        DatePickerDialog(
            onDismissRequest = { showDateDialog = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        val picked = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                        onDateChange(picked)
                    }
                    showDateDialog = false
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = { showDateDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        ) { DatePicker(state = pickerState) }
    }

    if (showTimeDialog) {
        val pickerState = rememberTimePickerState(
            initialHour = displayedTime.hour,
            initialMinute = displayedTime.minute,
            is24Hour = false
        )
        AlertDialog(
            onDismissRequest = { showTimeDialog = false },
            confirmButton = {
                TextButton(onClick = {
                    onTimeChange(LocalTime.of(pickerState.hour, pickerState.minute))
                    showTimeDialog = false
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = { showTimeDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            title = { Text(label) },
            text = { TimePicker(state = pickerState) }
        )
    }
}
