package com.threemail.android.ui.screens.calendar

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.threemail.android.domain.model.CalendarEvent
import com.threemail.android.ui.components.EmptyState
import com.threemail.android.ui.components.LoadingIndicator
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    viewModel: CalendarViewModel,
    onNavigateBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    var showCreate by remember { mutableStateOf(false) }

    val recoverableAuthLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        viewModel.onRecoverableAuthHandled()
        viewModel.retryAfterRecoverableAuth()
    }
    LaunchedEffect(state.recoverableAuthIntent) {
        state.recoverableAuthIntent?.let { recoverableAuthLauncher.launch(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Calendar") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        floatingActionButton = {
            if (state.accountEmail != null) {
                FloatingActionButton(onClick = { showCreate = true }) {
                    Icon(Icons.Default.Add, contentDescription = "New event")
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when {
                state.isLoading && state.events.isEmpty() -> LoadingIndicator()
                state.accountEmail == null -> EmptyState(
                    title = "No Google account",
                    subtitle = "Add a Google account to connect your calendar."
                )
                state.events.isEmpty() -> EmptyState(
                    title = "No upcoming events",
                    subtitle = "You're all clear for the next 60 days."
                )
                else -> AgendaList(events = state.events, onDelete = viewModel::deleteEvent)
            }
        }
    }

    if (showCreate) {
        NewEventDialog(
            onDismiss = { showCreate = false },
            onCreate = { title, start, end, allDay ->
                showCreate = false
                viewModel.createEvent(title, start, end, allDay)
            }
        )
    }
}

@Composable
private fun AgendaList(events: List<CalendarEvent>, onDelete: (String) -> Unit) {
    val grouped = events.groupBy { dayKey(it.start) }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        grouped.forEach { (day, dayEvents) ->
            item(key = "header-$day") {
                Text(
                    text = day,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)
                )
            }
            items(dayEvents.size) { index ->
                EventRow(event = dayEvents[index], onDelete = { onDelete(dayEvents[index].id) })
            }
        }
    }
}

@Composable
private fun EventRow(event: CalendarEvent, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.size(width = 64.dp, height = 40.dp)) {
            Text(
                text = if (event.allDay) "All day" else timeFormat.format(Date(event.start)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!event.allDay) {
                Text(
                    text = timeFormat.format(Date(event.end)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(event.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            event.location?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.DeleteOutline, contentDescription = "Delete", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun NewEventDialog(
    onDismiss: () -> Unit,
    onCreate: (title: String, start: Long, end: Long, allDay: Boolean) -> Unit
) {
    val context = LocalContext.current
    var title by remember { mutableStateOf("") }
    val startCal = remember { Calendar.getInstance().apply { add(Calendar.HOUR_OF_DAY, 1); set(Calendar.MINUTE, 0) } }
    var startLabel by remember { mutableStateOf(dateTimeFormat.format(startCal.time)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New event") },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = {
                    DatePickerDialog(
                        context,
                        { _, y, m, d ->
                            startCal.set(Calendar.YEAR, y); startCal.set(Calendar.MONTH, m); startCal.set(Calendar.DAY_OF_MONTH, d)
                            TimePickerDialog(
                                context,
                                { _, h, min ->
                                    startCal.set(Calendar.HOUR_OF_DAY, h); startCal.set(Calendar.MINUTE, min)
                                    startLabel = dateTimeFormat.format(startCal.time)
                                },
                                startCal.get(Calendar.HOUR_OF_DAY), startCal.get(Calendar.MINUTE), false
                            ).show()
                        },
                        startCal.get(Calendar.YEAR), startCal.get(Calendar.MONTH), startCal.get(Calendar.DAY_OF_MONTH)
                    ).show()
                }) {
                    Text("Starts: $startLabel")
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank(),
                onClick = {
                    val start = startCal.timeInMillis
                    onCreate(title, start, start + 60L * 60 * 1000, false)
                }
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
private val dateTimeFormat = SimpleDateFormat("EEE, MMM d 'at' h:mm a", Locale.getDefault())
private val dayHeaderFormat = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault())

private fun dayKey(timestamp: Long): String = dayHeaderFormat.format(Date(timestamp))
