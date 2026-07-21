package com.threemail.android.ui.screens.account

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.input.KeyboardType
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.threemail.android.ui.theme.appTopBarColors
import com.threemail.android.R
import com.threemail.android.domain.model.Account
import com.threemail.android.domain.model.AccountType
import com.threemail.android.domain.model.FolderType
import com.threemail.android.domain.model.Identity
import com.threemail.android.domain.model.Security
import com.threemail.android.ui.components.CardDivider
import com.threemail.android.ui.components.FolderNode
import com.threemail.android.ui.components.SettingsChoice
import com.threemail.android.ui.components.SettingsChoiceDialog
import com.threemail.android.ui.components.SettingsContentRow
import com.threemail.android.ui.components.SettingsGroup
import com.threemail.android.ui.components.SettingsRow
import com.threemail.android.ui.components.SettingsSwitchRow
import com.threemail.android.ui.components.buildFolderTree
import com.threemail.android.ui.components.iconFor

/** Frequency options offered per account; `0` means "follow the global default". */
private val FREQUENCY_OPTIONS = listOf(0L, 15L, 30L, 60L, 180L)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSettingsScreen(
    viewModel: AccountSettingsViewModel,
    onNavigateBack: () -> Unit,
    onOpenIdentities: () -> Unit = {},
    onOpenServer: () -> Unit = {},
    onOpenFolderRoles: () -> Unit = {},
    onOpenPush: () -> Unit = {},
    onOpenPgp: () -> Unit = {}
) {
    val state by viewModel.uiState.collectAsState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val account = state.account

    var showFrequencyDialog by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = {
                    Text(account?.email ?: stringResource(R.string.account_settings_title))
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cancel)
                        )
                    }
                },
                colors = appTopBarColors(),
                scrollBehavior = scrollBehavior
            )
        }
    ) { padding ->
        when {
            state.isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            account == null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.account_not_found),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            else -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    SettingsGroup(
                        title = stringResource(R.string.account_settings_general),
                        icon = Icons.Default.Badge
                    ) {
                        SettingsContentRow {
                            OutlinedTextField(
                                value = account.displayName,
                                onValueChange = viewModel::setDisplayName,
                                label = { Text(stringResource(R.string.display_name)) },
                                supportingText = {
                                    Text(stringResource(R.string.account_settings_display_name_subtitle))
                                },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    SettingsGroup(
                        title = stringResource(R.string.account_settings_signature_section),
                        icon = Icons.Default.Draw
                    ) {
                        SettingsContentRow {
                            OutlinedTextField(
                                value = account.signature,
                                onValueChange = viewModel::setSignature,
                                label = { Text(stringResource(R.string.account_settings_signature_label)) },
                                placeholder = { Text(stringResource(R.string.account_settings_signature_placeholder)) },
                                supportingText = {
                                    Text(stringResource(R.string.account_settings_signature_hint))
                                },
                                minLines = 3,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    SettingsGroup(
                        title = stringResource(R.string.account_settings_sync_section),
                        icon = Icons.Default.Sync
                    ) {
                        SettingsSwitchRow(
                            title = stringResource(R.string.account_settings_sync_enabled_title),
                            subtitle = stringResource(R.string.account_settings_sync_enabled_subtitle),
                            checked = account.syncEnabled,
                            onCheckedChange = viewModel::setSyncEnabled
                        )
                        CardDivider()
                        SettingsRow(
                            title = stringResource(R.string.account_settings_frequency_title),
                            value = accountFrequencyLabel(
                                account.syncIntervalMinutes,
                                state.defaultIntervalMinutes
                            ),
                            enabled = account.syncEnabled,
                            onClick = { showFrequencyDialog = true }
                        )
                    }

                    SettingsGroup(
                        title = stringResource(R.string.account_settings_notifications_section),
                        icon = Icons.Default.Notifications
                    ) {
                        SettingsSwitchRow(
                            title = stringResource(R.string.account_settings_notifications_title),
                            subtitle = stringResource(R.string.account_settings_notifications_subtitle),
                            checked = account.notificationsEnabled,
                            onCheckedChange = viewModel::setNotificationsEnabled
                        )
                    }

                    // Calendar sync is Google-only: the Calendar tab is backed by
                    // the Google Calendar API and reads accounts where this flag is
                    // set, so the toggle is meaningless for IMAP/POP3 accounts.
                    if (account.accountType == AccountType.GMAIL) {
                        SettingsGroup(
                            title = stringResource(R.string.account_settings_calendar_section),
                            icon = Icons.Default.CalendarMonth
                        ) {
                            SettingsSwitchRow(
                                title = stringResource(R.string.account_settings_calendar_title),
                                subtitle = stringResource(R.string.account_settings_calendar_subtitle),
                                checked = account.calendarSyncEnabled,
                                onCheckedChange = viewModel::setCalendarSyncEnabled
                            )
                        }
                    }

                    // Drill-in rows for the heavier sections; each opens its own
                    // focused sub-page instead of stacking on this scroll.
                    SettingsGroup(
                        title = stringResource(R.string.account_settings_manage_section),
                        icon = Icons.Default.Tune
                    ) {
                        SettingsRow(
                            title = stringResource(R.string.identities_section),
                            onClick = onOpenIdentities
                        )
                        if (account.accountType != AccountType.GMAIL) {
                            CardDivider()
                            SettingsRow(
                                title = stringResource(R.string.account_settings_server_section),
                                onClick = onOpenServer
                            )
                        }
                        if (account.accountType == AccountType.IMAP) {
                            CardDivider()
                            SettingsRow(
                                title = stringResource(R.string.account_folder_roles_section),
                                onClick = onOpenFolderRoles
                            )
                            CardDivider()
                            SettingsRow(
                                title = stringResource(R.string.account_push_label),
                                onClick = onOpenPush
                            )
                        }
                        CardDivider()
                        SettingsRow(
                            title = stringResource(R.string.pgp_keys_section),
                            onClick = onOpenPgp
                        )
                    }
                }
            }
        }
    }

    if (showFrequencyDialog && account != null) {
        SettingsChoiceDialog(
            title = stringResource(R.string.account_settings_frequency_title),
            options = FREQUENCY_OPTIONS.map {
                SettingsChoice(it, accountFrequencyLabel(it, state.defaultIntervalMinutes))
            },
            selected = account.syncIntervalMinutes,
            dismissLabel = stringResource(R.string.cancel),
            onSelect = viewModel::setSyncIntervalMinutes,
            onDismiss = { showFrequencyDialog = false }
        )
    }
}

/**
 * Scaffold shared by the drilled-out account sub-pages: a collapsing top bar
 * with the given [title] and a back arrow, plus a scrolling content column.
 * The content lambda supplies one or more [SettingsGroup]s.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AccountSubPage(
    title: String,
    onNavigateBack: () -> Unit,
    content: @Composable () -> Unit
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cancel)
                        )
                    }
                },
                colors = appTopBarColors(),
                scrollBehavior = scrollBehavior
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            content()
        }
    }
}

/**
 * Send-as identities: the description, the existing identities with a remove
 * action, and an inline add form.
 */
@Composable
internal fun IdentitiesSection(
    account: Account,
    viewModel: AccountSettingsViewModel
) {
    SettingsGroup(
        title = stringResource(R.string.identities_section),
        icon = Icons.Default.AlternateEmail
    ) {
        SettingsContentRow {
            Text(
                text = stringResource(R.string.identities_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        account.identities.forEachIndexed { index, identity ->
            CardDivider()
            SettingsContentRow {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = identity.displayName.ifBlank { identity.email },
                            style = MaterialTheme.typography.bodyLarge
                        )
                        if (identity.displayName.isNotBlank()) {
                            Text(
                                text = identity.email,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    IconButton(onClick = { viewModel.removeIdentity(index) }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource(R.string.identity_remove),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
        CardDivider()
        SettingsContentRow {
            AddIdentityForm(onAdd = viewModel::addIdentity)
        }
    }
}

/**
 * IMAP folder-role overrides. Each role shows what it currently resolves to
 * (a user override, the auto-detected folder, or plain "Automatic") and opens
 * a picker to pin a specific folder. Owns its own picker state.
 */
@Composable
internal fun FolderRolesSection(
    account: Account,
    state: AccountSettingsViewModel.UiState,
    viewModel: AccountSettingsViewModel
) {
    var editingRole by remember { mutableStateOf<FolderType?>(null) }

    SettingsGroup(
        title = stringResource(R.string.account_folder_roles_section),
        icon = Icons.Default.Folder
    ) {
        SettingsContentRow {
            Text(
                text = stringResource(R.string.account_folder_roles_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        FOLDER_ROLES.forEach { role ->
            // A user override pins a specific folder; with no override the value
            // shows what auto-detection resolved to - "Automatic (Junk)" - so the
            // user can see the mapping the heuristic picked on add. The auto
            // target is the folder the name heuristic already classified with
            // this role's type.
            val overrideFolder = state.folders.firstOrNull {
                it.serverId == account.folderRoles[role]
            }
            val autoFolder = state.folders.firstOrNull {
                it.type == role
            }
            val valueText = when {
                overrideFolder != null -> overrideFolder.name
                autoFolder != null -> stringResource(
                    R.string.account_folder_role_automatic_named,
                    autoFolder.name
                )
                else -> stringResource(R.string.account_folder_role_automatic)
            }
            CardDivider()
            SettingsRow(
                title = stringResource(role.displayNameRes()),
                value = valueText,
                onClick = { editingRole = role }
            )
        }
    }

    editingRole?.let { role ->
        val options = buildList {
            add(SettingsChoice<String?>(null, stringResource(R.string.account_folder_role_automatic)))
            state.folders.forEach { add(SettingsChoice<String?>(it.serverId, it.name)) }
        }
        SettingsChoiceDialog(
            title = stringResource(role.displayNameRes()),
            options = options,
            selected = account.folderRoles[role],
            dismissLabel = stringResource(R.string.cancel),
            onSelect = { viewModel.setFolderRole(role, it) },
            onDismiss = { editingRole = null }
        )
    }
}

/**
 * IMAP IDLE push: the enable toggle and, when on, the opt-in extra push-folder
 * tree. INBOX is always watched and never listed.
 */
@Composable
internal fun PushSection(
    account: Account,
    state: AccountSettingsViewModel.UiState,
    viewModel: AccountSettingsViewModel
) {
    SettingsGroup(
        title = stringResource(R.string.account_push_label),
        icon = Icons.Default.Bolt
    ) {
        SettingsSwitchRow(
            title = stringResource(R.string.account_push_label),
            subtitle = stringResource(R.string.account_push_subtitle),
            checked = account.pushEnabled,
            onCheckedChange = viewModel::setPushEnabled
        )
        // Opt-in extra push folders. INBOX is always watched and never listed.
        // Only meaningful while push is on.
        if (account.pushEnabled) {
            CardDivider()
            SettingsContentRow {
                Text(
                    text = stringResource(R.string.account_push_folders_title),
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = stringResource(R.string.account_push_folders_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            val extraFolders = state.folders.filter {
                it.type != FolderType.Inbox
            }
            if (extraFolders.isEmpty()) {
                SettingsContentRow {
                    Text(
                        text = stringResource(R.string.account_push_folders_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                // Same IMAP-hierarchy tree the drawer/move picker use, but each
                // row carries a push toggle. Rendered inline (not Lazy) since the
                // whole settings screen is a scroll.
                var pushExpanded by remember(account.id) {
                    mutableStateOf(
                        extraFolders.mapTo(HashSet()) { it.serverId } as Set<String>
                    )
                }
                val pushNodes = remember(extraFolders, pushExpanded) {
                    buildFolderTree(extraFolders, pushExpanded)
                }
                pushNodes.forEach { node ->
                    CardDivider()
                    PushFolderTreeRow(
                        node = node,
                        checked = node.folder.serverId in account.pushFolders,
                        onToggleExpand = { serverId ->
                            pushExpanded = if (serverId in pushExpanded) {
                                pushExpanded - serverId
                            } else {
                                pushExpanded + serverId
                            }
                        },
                        onCheckedChange = { on ->
                            viewModel.togglePushFolder(node.folder.serverId, on)
                        }
                    )
                }
            }
        }
    }
}

/**
 * Editable incoming ("Fetching Mail") and outgoing ("Sending Mail") server
 * settings for IMAP/POP3 accounts. Fields are edited as a local draft and only
 * persisted via [AccountSettingsViewModel.testAndSaveConnection], which probes
 * the server first so a bad host/port can't silently break sync. Connection
 * security is a single setting shared by both directions (matching the account
 * model), shown under Fetching Mail.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ConnectionSettingsSections(
    account: Account,
    viewModel: AccountSettingsViewModel
) {
    val connectionState by viewModel.connectionState.collectAsState()

    // Local draft, re-seeded from the account only when the account identity
    // changes (never on this screen), so unrelated write-through updates to
    // other settings don't reset a half-typed host.
    var incomingServer by remember(account.id) { mutableStateOf(account.incomingServer.orEmpty()) }
    var incomingPort by remember(account.id) { mutableStateOf(account.incomingPort.toString()) }
    var outgoingServer by remember(account.id) { mutableStateOf(account.outgoingServer.orEmpty()) }
    var outgoingPort by remember(account.id) { mutableStateOf(account.outgoingPort.toString()) }
    var security by remember(account.id) { mutableStateOf(account.security) }

    // Any edit invalidates a prior Saved/Failed banner.
    val onEdited: () -> Unit = { viewModel.clearConnectionState() }

    val incomingServerLabel = if (account.accountType == AccountType.POP3) {
        R.string.pop3_server
    } else {
        R.string.imap_server
    }

    SettingsGroup(
        title = stringResource(R.string.account_fetching_section),
        icon = Icons.Default.CloudDownload
    ) {
        SettingsContentRow {
            OutlinedTextField(
                value = incomingServer,
                onValueChange = { incomingServer = it; onEdited() },
                label = { Text(stringResource(incomingServerLabel)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = incomingPort,
                onValueChange = { incomingPort = it.filter(Char::isDigit); onEdited() },
                label = { Text(stringResource(R.string.port)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = stringResource(R.string.security),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 8.dp)
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Security.entries.forEach { mode ->
                    FilterChip(
                        selected = security == mode,
                        onClick = { security = mode; onEdited() },
                        label = { Text(stringResource(securityLabelRes(mode))) }
                    )
                }
            }
            Text(
                text = stringResource(R.string.account_connection_security_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    SettingsGroup(
        title = stringResource(R.string.account_sending_section),
        icon = Icons.Default.CloudUpload
    ) {
        SettingsContentRow {
            OutlinedTextField(
                value = outgoingServer,
                onValueChange = { outgoingServer = it; onEdited() },
                label = { Text(stringResource(R.string.outgoing_server)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = outgoingPort,
                onValueChange = { outgoingPort = it.filter(Char::isDigit); onEdited() },
                label = { Text(stringResource(R.string.outgoing_port)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            when (val s = connectionState) {
                is AccountSettingsViewModel.ConnectionSettingsState.Saved -> Text(
                    text = stringResource(R.string.account_connection_saved),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp)
                )
                is AccountSettingsViewModel.ConnectionSettingsState.Failed -> Text(
                    text = stringResource(R.string.account_connection_failed, s.message),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp)
                )
                else -> Unit
            }
            val testing = connectionState is AccountSettingsViewModel.ConnectionSettingsState.Testing
            Button(
                onClick = {
                    viewModel.testAndSaveConnection(
                        incomingServer = incomingServer.trim(),
                        incomingPort = incomingPort.toIntOrNull() ?: account.incomingPort,
                        outgoingServer = outgoingServer.trim(),
                        outgoingPort = outgoingPort.toIntOrNull() ?: account.outgoingPort,
                        security = security
                    )
                },
                enabled = !testing,
                modifier = Modifier.padding(top = 8.dp)
            ) {
                if (testing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.account_connection_testing))
                } else {
                    Text(stringResource(R.string.account_connection_test_and_save))
                }
            }
        }
    }
}

private fun securityLabelRes(mode: Security): Int = when (mode) {
    Security.NONE -> R.string.security_none
    Security.STARTTLS -> R.string.security_starttls
    Security.SSL_TLS -> R.string.security_ssl_tls
}

/** One folder row in the push-folders tree: indent + chevron + icon + toggle. */
@Composable
private fun PushFolderTreeRow(
    node: FolderNode,
    checked: Boolean,
    onToggleExpand: (String) -> Unit,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = 16.dp + 20.dp * node.depth,
                end = 16.dp,
                top = 8.dp,
                bottom = 8.dp
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (node.hasChildren) {
            Icon(
                imageVector = Icons.Default.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(20.dp)
                    .rotate(if (node.isExpanded) 0f else -90f)
                    .clickable { onToggleExpand(node.folder.serverId) }
            )
        } else {
            Spacer(Modifier.size(20.dp))
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            imageVector = iconFor(node.folder.type),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = node.folder.name,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * Per-account OpenPGP key management: the account's own key fingerprint
 * (with a WKD export action), the cached peer keys with fingerprints and
 * removal, and a manual import form. The WKD export stages its payload in
 * the ViewModel, launches a SAF create-document picker named after the
 * spec-mandated zbase32 hash, and streams the binary key to whatever
 * location the user picks.
 */
@Composable
internal fun PgpKeysSection(
    state: AccountSettingsViewModel.UiState,
    viewModel: AccountSettingsViewModel
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val export = viewModel.uiState.value.wkdExport
        if (uri != null && export != null) {
            scope.launch(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(export.keyBytes) }
                }
            }
        }
        viewModel.clearWkdExport()
    }
    // A staged export means the user tapped the button; hand the payload's
    // filename to SAF. The launcher callback clears the staging either way.
    LaunchedEffect(state.wkdExport) {
        state.wkdExport?.let { exportLauncher.launch(it.fileName) }
    }

    SettingsGroup(
        title = stringResource(R.string.pgp_keys_section),
        icon = Icons.Default.Key
    ) {
        SettingsContentRow {
            Text(
                text = stringResource(R.string.pgp_keys_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        CardDivider()
        SettingsContentRow {
            Text(
                text = stringResource(R.string.pgp_own_key_title),
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = state.ownKeyFingerprint ?: "…",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 4.dp)
            )
            Text(
                text = stringResource(R.string.pgp_own_key_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            OutlinedButton(
                onClick = viewModel::prepareWkdExport,
                enabled = state.ownKeyFingerprint != null,
                modifier = Modifier.padding(top = 8.dp)
            ) {
                Text(stringResource(R.string.pgp_export_wkd_button))
            }
            Text(
                text = stringResource(R.string.pgp_export_wkd_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        CardDivider()
        SettingsContentRow {
            Text(
                text = stringResource(R.string.pgp_peer_keys_title),
                style = MaterialTheme.typography.bodyLarge
            )
            if (state.peerKeys.isEmpty()) {
                Text(
                    text = stringResource(R.string.pgp_peer_keys_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
        state.peerKeys.forEach { (email, fingerprint) ->
            CardDivider()
            SettingsContentRow {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = email, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = fingerprint
                                ?: stringResource(R.string.pgp_peer_key_unparseable),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = if (fingerprint != null) FontFamily.Monospace else null,
                            color = if (fingerprint != null) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.error
                            }
                        )
                    }
                    IconButton(onClick = { viewModel.removePeerKey(email) }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource(R.string.pgp_peer_key_remove),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
        CardDivider()
        SettingsContentRow {
            ImportPeerKeyForm(
                actionMessage = state.keyActionMessage,
                onImport = { email, keyData ->
                    viewModel.clearKeyActionMessage()
                    viewModel.importPeerKey(email, keyData)
                }
            )
        }
    }
}

/**
 * Inline form for manually importing a contact's public key. Clears its
 * fields only on a successful import (the [actionMessage] flips to
 * [AccountSettingsViewModel.KeyActionMessage.Imported]) so a typo doesn't
 * throw away a long pasted key block.
 */
@Composable
private fun ImportPeerKeyForm(
    actionMessage: AccountSettingsViewModel.KeyActionMessage?,
    onImport: (String, String) -> Unit
) {
    var email by remember { mutableStateOf("") }
    var keyData by remember { mutableStateOf("") }
    var showEmailError by remember { mutableStateOf(false) }

    LaunchedEffect(actionMessage) {
        if (actionMessage is AccountSettingsViewModel.KeyActionMessage.Imported) {
            email = ""
            keyData = ""
        }
    }

    Column {
        Text(
            text = stringResource(R.string.pgp_import_title),
            style = MaterialTheme.typography.bodyLarge
        )
        OutlinedTextField(
            value = email,
            onValueChange = { email = it; showEmailError = false },
            label = { Text(stringResource(R.string.pgp_import_email_label)) },
            isError = showEmailError,
            supportingText = if (showEmailError) {
                { Text(stringResource(R.string.pgp_import_email_required)) }
            } else null,
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = keyData,
            onValueChange = { keyData = it },
            label = { Text(stringResource(R.string.pgp_import_keydata_label)) },
            supportingText = { Text(stringResource(R.string.pgp_import_keydata_hint)) },
            minLines = 3,
            modifier = Modifier.fillMaxWidth()
        )
        when (actionMessage) {
            is AccountSettingsViewModel.KeyActionMessage.Imported -> Text(
                text = stringResource(R.string.pgp_import_success, actionMessage.fingerprint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp)
            )
            is AccountSettingsViewModel.KeyActionMessage.Failed -> Text(
                text = stringResource(R.string.pgp_import_failure, actionMessage.reason),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp)
            )
            null -> Unit
        }
        Button(
            onClick = {
                if (email.isBlank()) {
                    showEmailError = true
                } else {
                    onImport(email.trim(), keyData)
                }
            },
            enabled = keyData.isNotBlank(),
            modifier = Modifier.padding(top = 8.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.pgp_import_button))
        }
    }
}

/**
 * Inline form to add a new send-as identity. Keeps its own draft state so the
 * fields clear after a successful add. An identity requires a non-blank email.
 */
@Composable
private fun AddIdentityForm(onAdd: (Identity) -> Unit) {
    var displayName by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var signature by remember { mutableStateOf("") }
    var showError by remember { mutableStateOf(false) }

    Column {
        OutlinedTextField(
            value = displayName,
            onValueChange = { displayName = it },
            label = { Text(stringResource(R.string.identity_display_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = email,
            onValueChange = { email = it; showError = false },
            label = { Text(stringResource(R.string.identity_email)) },
            isError = showError,
            supportingText = if (showError) {
                { Text(stringResource(R.string.identity_email_required)) }
            } else null,
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = signature,
            onValueChange = { signature = it },
            label = { Text(stringResource(R.string.identity_signature)) },
            minLines = 2,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = {
                if (email.isBlank()) {
                    showError = true
                } else {
                    onAdd(
                        Identity(
                            displayName = displayName.trim(),
                            email = email.trim(),
                            signature = signature
                        )
                    )
                    displayName = ""
                    email = ""
                    signature = ""
                }
            },
            modifier = Modifier.padding(top = 8.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.identity_add))
        }
    }
}

/** Renders a minutes value as a compact "15m" / "1h" label. */
@Composable
private fun formatFrequency(minutes: Long): String =
    if (minutes < 60) {
        stringResource(R.string.frequency_minutes, minutes.toInt())
    } else {
        stringResource(R.string.frequency_hours, (minutes / 60).toInt())
    }

/**
 * Row/dialog label for a per-account check frequency. `0` follows the global
 * default, shown as "Default (15m)"; other values render compactly.
 */
@Composable
private fun accountFrequencyLabel(minutes: Long, defaultMinutes: Long): String =
    if (minutes == 0L) {
        stringResource(R.string.account_settings_frequency_default, formatFrequency(defaultMinutes))
    } else {
        formatFrequency(minutes)
    }

/** Roles exposed in the per-account picker, in display order. */
private val FOLDER_ROLES = listOf(
    FolderType.Inbox,
    FolderType.SENT,
    FolderType.DRAFTS,
    FolderType.TRASH,
    FolderType.SPAM,
    FolderType.ARCHIVE
)

private fun FolderType.displayNameRes(): Int = when (this) {
    FolderType.Inbox -> R.string.account_folder_role_inbox
    FolderType.SENT -> R.string.account_folder_role_sent
    FolderType.DRAFTS -> R.string.account_folder_role_drafts
    FolderType.TRASH -> R.string.account_folder_role_trash
    FolderType.SPAM -> R.string.account_folder_role_spam
    FolderType.ARCHIVE -> R.string.account_folder_role_archive
    else -> R.string.account_folder_role_auto_detected
}
