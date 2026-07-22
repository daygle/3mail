package com.threemail.android.ui.screens.calendar

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EditCalendar
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.threemail.android.ui.theme.appTopBarColors
import com.threemail.android.R
import com.threemail.android.domain.model.Account
import com.threemail.android.domain.model.CalendarEntry
import com.threemail.android.domain.model.CalendarSource
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageCalendarsScreen(
    viewModel: ManageCalendarsViewModel = hiltViewModel(),
    /**
     * When `true` the "choose calendar type" chooser dialog opens immediately
     * on first composition - used by the empty-state primary action on the
     * main Calendar page so a user with no calendars at all lands one tap
     * away from the type picker. Existing top-bar / deep-link callers pass
     * `false` (the default) and continue to land on the manage list, with
     * the existing FAB still opening the chooser manually if they want it.
     */
    autoAdd: Boolean = false,
    onNavigateBack: () -> Unit
) {
    val rowsByAccount by viewModel.rowsByAccount.collectAsState()
    val sources by viewModel.sources.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val snackbarMessage by viewModel.snackbar.collectAsState()
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var showAddChooser by remember { mutableStateOf(autoAdd) }
    var showSubscribeDialog by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showAddIcsDialog by remember { mutableStateOf(false) }
    var showAddCalDavDialog by remember { mutableStateOf(false) }
    val calDavDiscovery by viewModel.calDavDiscovery.collectAsState()

    // Resolve the Gmail account we'll subscribe / create under. Picking the
    // first signed-in GMAIL keeps behaviour sensible even after the user
    // signs out of one Google account and signs in to another in the same
    // session; multi-account pickers are out of scope for the MVP.
    val activeAccount = rowsByAccount.keys.firstOrNull()

    // Map snackbar messages to on-screen text. Side-channel for now;
    // a future pass can split the per-status strings into the strings.xml
    // file so translators can localise each path independently.
    LaunchedEffect(snackbarMessage) {
        val msg = snackbarMessage ?: return@LaunchedEffect
        val text = when (msg) {
            is SnackbarMessage.Subscribed -> "Subscribed to ${msg.summary}"
            is SnackbarMessage.Created -> "Created ${msg.summary}"
            is SnackbarMessage.SubscribeFailed -> "Subscribe failed: ${msg.reason}"
            is SnackbarMessage.CreateFailed -> "Create failed: ${msg.reason}"
            is SnackbarMessage.SyncFailed -> "Sync failed for ${msg.accountEmail}"
            SnackbarMessage.SyncSucceeded -> "Calendars refreshed"
            SnackbarMessage.InvalidUrl -> "Enter a valid iCal URL"
            SnackbarMessage.InvalidSummary -> "Enter a calendar name"
            is SnackbarMessage.SourceRemoved -> "Removed ${msg.summary}"
            is SnackbarMessage.CalDavAdded ->
                if (msg.count == 1) "Added 1 calendar" else "Added ${msg.count} calendars"
        }
        scope.launch {
            snackbarHost.showSnackbar(text)
            viewModel.consumeSnackbar()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.manage_calendars_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cancel)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::sync) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.calendar_sync_all)
                        )
                    }
                },
                colors = appTopBarColors()
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) },
        floatingActionButton = {
            // Icon-only FAB: ExtendedFAB's slot-vs-named-arg overloads
            // resolved ambiguously on the project's Compose version;
            // icon-only is unambiguous and reads the same in the corner.
            FloatingActionButton(
                onClick = { showAddChooser = true }
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = stringResource(R.string.manage_calendars_add)
                )
            }
        }
    ) { padding ->
        if (rowsByAccount.isEmpty() && sources.isEmpty()) {
            EmptyCalendarsState(
                onRefresh = viewModel::sync,
                modifier = Modifier.padding(padding)
            )
            return@Scaffold
        }
        ManageCalendarsList(
            rowsByAccount = rowsByAccount,
            sources = sources,
            busy = busy,
            onToggle = { account, entry, flag ->
                viewModel.toggleSelected(account, entry.calendarId, flag)
            },
            onToggleSource = { source, flag ->
                viewModel.toggleSourceVisible(source.id, flag)
            },
            onDeleteSource = viewModel::deleteSource,
            modifier = Modifier.padding(padding)
        )
    }

    if (showAddChooser) {
        AddCalendarChooserDialog(
            hasGoogleAccount = activeAccount != null,
            onDismiss = { showAddChooser = false },
            onPickGoogleSubscribe = {
                showAddChooser = false; showSubscribeDialog = true
            },
            onPickGoogleCreate = {
                showAddChooser = false; showCreateDialog = true
            },
            onPickIcs = {
                showAddChooser = false; showAddIcsDialog = true
            },
            onPickCalDav = {
                showAddChooser = false; showAddCalDavDialog = true
            }
        )
    }

    if (showAddIcsDialog) {
        AddIcsDialog(
            onDismiss = { showAddIcsDialog = false },
            onAdd = { url, name ->
                viewModel.addIcsSource(url, name)
                showAddIcsDialog = false
            }
        )
    }

    if (showAddCalDavDialog) {
        AddCalDavDialog(
            discovering = calDavDiscovery is CalDavDiscovery.Discovering,
            onDismiss = {
                showAddCalDavDialog = false
                viewModel.dismissCalDavDiscovery()
            },
            onFind = { url, user, pass -> viewModel.discoverCalDav(url, user, pass) }
        )
    }

    // Discovery succeeded: swap the credentials dialog for the picker.
    LaunchedEffect(calDavDiscovery) {
        if (calDavDiscovery is CalDavDiscovery.Found) showAddCalDavDialog = false
    }
    (calDavDiscovery as? CalDavDiscovery.Found)?.let { found ->
        CalDavPickDialog(
            calendars = found.calendars,
            onDismiss = viewModel::dismissCalDavDiscovery,
            onAdd = viewModel::addCalDavCalendars
        )
    }

    if (showSubscribeDialog && activeAccount != null) {
        SubscribeDialog(
            onDismiss = { showSubscribeDialog = false },
            onSubscribe = { url ->
                viewModel.subscribeByUrl(activeAccount, url)
                showSubscribeDialog = false
            }
        )
    }

    if (showCreateDialog && activeAccount != null) {
        CreateCalendarDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { summary, timezone ->
                viewModel.createNew(activeAccount, summary, timezone)
                showCreateDialog = false
            },
            onSwitchToSubscribe = {
                showCreateDialog = false
                showSubscribeDialog = true
            }
        )
    }
}

@Composable
private fun ManageCalendarsList(
    rowsByAccount: Map<Account, List<CalendarEntry>>,
    sources: List<CalendarSource>,
    busy: Boolean,
    onToggle: (Account, CalendarEntry, Boolean) -> Unit,
    onToggleSource: (CalendarSource, Boolean) -> Unit,
    onDeleteSource: (CalendarSource) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 12.dp, horizontal = 4.dp)
    ) {
        rowsByAccount.forEach { (account, entries) ->
            item(key = "header-${account.id}") {
                AccountHeaderRow(account)
            }
            // Plain LazyColumn.items overload so the compiler's contentType
            // type-checks against the `String` literal argument we used to
            // pass (the prior `contentType = "row"` was a type error
            // because Compose's overloaded items expects a `(T) -> Any?`
            // lambda there, not a String).
            items(items = entries, key = { it.calendarId }) { entry ->
                CalendarEntryRow(
                    entry = entry,
                    onToggle = { flag -> onToggle(account, entry, flag) }
                )
            }
            item(key = "div-${account.id}") {
                HorizontalDivider(
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                )
            }
        }
        if (sources.isNotEmpty()) {
            item(key = "header-sources") {
                Text(
                    text = stringResource(R.string.manage_calendars_other_section),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(
                        start = 16.dp, top = 8.dp, bottom = 4.dp, end = 16.dp
                    )
                )
            }
            items(items = sources, key = { "source-${it.id}" }) { source ->
                CalendarSourceRow(
                    source = source,
                    onToggle = { flag -> onToggleSource(source, flag) },
                    onDelete = { onDeleteSource(source) }
                )
            }
        }
        // Reserve space for the FAB so the last row doesn't sit underneath it.
        item(key = "bottom-spacer") { Spacer(modifier = Modifier.height(80.dp)) }
    }
}

/**
 * One standalone subscription: colour swatch (stable hash of the source's
 * event `calendarId` marker so it matches the grid), name, either the last
 * sync error (in error colour) or the feed URL as the subtitle, a
 * visibility switch, and delete.
 */
@Composable
private fun CalendarSourceRow(
    source: CalendarSource,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .background(getEventColor("source:${source.id}"), CircleShape)
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = source.displayName,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
            Text(
                text = source.lastError ?: source.url,
                style = MaterialTheme.typography.bodySmall,
                color = if (source.lastError != null) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1
            )
        }
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = source.isVisible,
            onCheckedChange = onToggle
        )
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Default.Delete,
                contentDescription = stringResource(R.string.manage_calendars_remove_source),
                tint = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun AccountHeaderRow(account: Account) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 8.dp, bottom = 4.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(
                    color = avatarColorFor(account.email),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = account.email.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                style = MaterialTheme.typography.titleSmall,
                color = Color.White
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = account.displayName.ifBlank { account.email.substringAfter("@") },
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = account.email,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CalendarEntryRow(
    entry: CalendarEntry,
    onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .background(parseHexColor(entry.backgroundColor), CircleShape)
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.summary,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
            if (!entry.description.isNullOrBlank()) {
                Text(
                    text = entry.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = entry.isSelected,
            onCheckedChange = onToggle
        )
    }
}

/**
 * Pure helper. Returns a static mid-grey fallback for malformed input
 * so the swatch always renders *something*. Reading `MaterialTheme.*`
 * from inside a helper would force the helper to be `@Composable`,
 * which propagates composable context through every call site; the
 * static fallback keeps the helper plain. The mid-grey matches the
 * project's empty-state icon tint so the row reads consistently when
 * the remote hex is missing.
 */
private fun parseHexColor(hex: String?): Color {
    if (hex.isNullOrBlank()) return Color(0xFF9E9E9E.toInt())
    val cleaned = hex.removePrefix("#")
    val asLong = runCatching { java.lang.Long.parseLong(cleaned, 16) }.getOrNull()
        ?: return Color(0xFF9E9E9E.toInt())
    return when (cleaned.length) {
        6 -> Color(
            red = ((asLong shr 16) and 0xFF) / 255f,
            green = ((asLong shr 8) and 0xFF) / 255f,
            blue = (asLong and 0xFF) / 255f,
            alpha = 1f
        )
        8 -> Color(
            red = ((asLong shr 16) and 0xFF) / 255f,
            green = ((asLong shr 8) and 0xFF) / 255f,
            blue = (asLong and 0xFF) / 255f,
            alpha = ((asLong shr 24) and 0xFF) / 255f
        )
        else -> Color(0xFF9E9E9E.toInt())
    }
}

@Composable
private fun EmptyCalendarsState(onRefresh: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.EditCalendar,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.manage_calendars_empty_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.manage_calendars_empty_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onRefresh) { Text(stringResource(R.string.calendar_sync_all)) }
    }
}

/**
 * First step of the add flow: pick where the new calendar lives. Google
 * options need a signed-in Google account; an internet (ICS) subscription
 * is standalone and always available.
 */
@Composable
private fun AddCalendarChooserDialog(
    hasGoogleAccount: Boolean,
    onDismiss: () -> Unit,
    onPickGoogleSubscribe: () -> Unit,
    onPickGoogleCreate: () -> Unit,
    onPickIcs: () -> Unit,
    onPickCalDav: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.manage_calendars_add)) },
        text = {
            Column {
                TextButton(onClick = onPickIcs, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.manage_calendars_add_choice_ics))
                }
                TextButton(onClick = onPickCalDav, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.manage_calendars_add_choice_caldav))
                }
                if (hasGoogleAccount) {
                    TextButton(
                        onClick = onPickGoogleSubscribe,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.manage_calendars_add_choice_google_subscribe))
                    }
                    TextButton(
                        onClick = onPickGoogleCreate,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.manage_calendars_add_choice_google_create))
                    }
                } else {
                    Text(
                        text = stringResource(R.string.manage_calendars_add_google_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

/** Subscribe to a standalone internet calendar (ICS / webcal URL). */
@Composable
private fun AddIcsDialog(
    onDismiss: () -> Unit,
    onAdd: (url: String, name: String?) -> Unit
) {
    var url by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.manage_calendars_add_ics_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.manage_calendars_add_ics_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it.trim() },
                    label = { Text(stringResource(R.string.manage_calendars_subscribe_url_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    leadingIcon = {
                        Icon(Icons.Default.Public, contentDescription = null)
                    }
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.manage_calendars_add_ics_name_label)) },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(url, name.trim().ifBlank { null }) },
                enabled = url.isNotBlank()
            ) {
                Text(stringResource(R.string.manage_calendars_subscribe_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

/**
 * CalDAV connect step 1: server + sign-in details. "Find Calendars" runs
 * discovery; on success the caller swaps this for [CalDavPickDialog].
 */
@Composable
private fun AddCalDavDialog(
    discovering: Boolean,
    onDismiss: () -> Unit,
    onFind: (url: String, username: String, password: String) -> Unit
) {
    var url by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.manage_calendars_caldav_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.manage_calendars_caldav_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it.trim() },
                    label = { Text(stringResource(R.string.manage_calendars_caldav_url_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    leadingIcon = { Icon(Icons.Default.Public, contentDescription = null) }
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it.trim() },
                    label = { Text(stringResource(R.string.manage_calendars_caldav_username_label)) },
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.password)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = PasswordVisualTransformation()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onFind(url, username, password) },
                enabled = url.isNotBlank() && username.isNotBlank() && !discovering
            ) {
                Text(
                    stringResource(
                        if (discovering) {
                            R.string.manage_calendars_caldav_finding
                        } else {
                            R.string.manage_calendars_caldav_find
                        }
                    )
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

/** CalDAV connect step 2: tick the discovered collections to subscribe. */
@Composable
private fun CalDavPickDialog(
    calendars: List<com.threemail.android.data.remote.calendar.CalDavClient.DiscoveredCalendar>,
    onDismiss: () -> Unit,
    onAdd: (List<com.threemail.android.data.remote.calendar.CalDavClient.DiscoveredCalendar>) -> Unit
) {
    var selectedUrls by remember(calendars) {
        mutableStateOf(calendars.map { it.url }.toSet())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.manage_calendars_caldav_pick_title)) },
        text = {
            Column {
                calendars.forEach { calendar ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = calendar.url in selectedUrls,
                            onCheckedChange = { checked ->
                                selectedUrls = if (checked) {
                                    selectedUrls + calendar.url
                                } else {
                                    selectedUrls - calendar.url
                                }
                            }
                        )
                        Text(
                            text = calendar.displayName,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(calendars.filter { it.url in selectedUrls }) },
                enabled = selectedUrls.isNotEmpty()
            ) {
                Text(stringResource(R.string.manage_calendars_caldav_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubscribeDialog(
    onDismiss: () -> Unit,
    onSubscribe: (String) -> Unit
) {
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.manage_calendars_subscribe_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.manage_calendars_subscribe_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it.trim() },
                    label = { Text(stringResource(R.string.manage_calendars_subscribe_url_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    leadingIcon = {
                        Icon(Icons.Default.Public, contentDescription = null)
                    }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubscribe(url) }, enabled = url.isNotBlank()) {
                Text(stringResource(R.string.manage_calendars_subscribe_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateCalendarDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String) -> Unit,
    onSwitchToSubscribe: () -> Unit
) {
    var summary by remember { mutableStateOf("") }
    val defaultTz = remember { java.util.TimeZone.getDefault().id }
    var timezone by remember { mutableStateOf(defaultTz) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.manage_calendars_create_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.manage_calendars_create_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = summary,
                    onValueChange = { summary = it },
                    label = { Text(stringResource(R.string.calendar_event_title_label)) },
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = timezone,
                    onValueChange = { timezone = it.trim() },
                    label = { Text(stringResource(R.string.manage_calendars_create_tz_label)) },
                    singleLine = true
                )
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onSwitchToSubscribe) {
                    Text(stringResource(R.string.manage_calendars_subscribe_action))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(summary.trim(), timezone) },
                enabled = summary.isNotBlank()
            ) {
                Text(stringResource(R.string.manage_calendars_create_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

/**
 * Stable, low-overlap indicator color for the account-chip dot. Reuses
 * the inbox row's `avatarColorFor` so the chip color matches the rest
 * of the app's account chrome.
 */
private fun avatarColorFor(email: String): Color =
    com.threemail.android.ui.theme.avatarColorFor(email)
