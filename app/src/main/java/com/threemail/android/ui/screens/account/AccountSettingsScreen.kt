package com.threemail.android.ui.screens.account

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.threemail.android.ui.theme.appTopBarColors
import com.threemail.android.R
import com.threemail.android.domain.model.AccountType
import com.threemail.android.domain.model.FolderType
import com.threemail.android.domain.model.Identity
import com.threemail.android.ui.components.CardDivider
import com.threemail.android.ui.components.SettingsChoice
import com.threemail.android.ui.components.SettingsChoiceDialog
import com.threemail.android.ui.components.SettingsContentRow
import com.threemail.android.ui.components.SettingsGroup
import com.threemail.android.ui.components.SettingsRow
import com.threemail.android.ui.components.SettingsSwitchRow

/** Frequency options offered per account; `0` means "follow the global default". */
private val FREQUENCY_OPTIONS = listOf(0L, 15L, 30L, 60L, 180L)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSettingsScreen(
    viewModel: AccountSettingsViewModel,
    onNavigateBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val account = state.account

    var showFrequencyDialog by remember { mutableStateOf(false) }
    var editingRole by remember { mutableStateOf<FolderType?>(null) }

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

                    PgpKeysSection(state = state, viewModel = viewModel)

                    // IDLE push is IMAP-only; Gmail rides Google's own push
                    // pipeline, so the toggle is meaningless there.
                    if (account.accountType == AccountType.IMAP) {
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
                            // Opt-in extra push folders. INBOX is always watched
                            // and never listed. Only meaningful while push is on.
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
                                    extraFolders.forEach { folder ->
                                        CardDivider()
                                        SettingsSwitchRow(
                                            title = folder.name,
                                            checked = folder.serverId in account.pushFolders,
                                            onCheckedChange = { on ->
                                                viewModel.togglePushFolder(folder.serverId, on)
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        // Folder-role picker is IMAP-only too. Gmail's labels
                        // are fixed by the REST API (INBOX / SENT / DRAFT /
                        // TRASH / SPAM have no per-account aliases), so the
                        // picker would either be a no-op or actively break
                        // Gmail sync.
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
                                // A user override pins a specific folder; with no
                                // override the value shows what auto-detection
                                // resolved to - "Automatic (Junk)" - so the user
                                // can see the mapping the heuristic picked on add,
                                // matching the standard-folders UX. The auto
                                // target is the folder the name heuristic already
                                // classified with this role's type.
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

    editingRole?.let { role ->
        val options = buildList {
            add(SettingsChoice<String?>(null, stringResource(R.string.account_folder_role_automatic)))
            state.folders.forEach { add(SettingsChoice<String?>(it.serverId, it.name)) }
        }
        SettingsChoiceDialog(
            title = stringResource(role.displayNameRes()),
            options = options,
            selected = account?.folderRoles?.get(role),
            dismissLabel = stringResource(R.string.cancel),
            onSelect = { viewModel.setFolderRole(role, it) },
            onDismiss = { editingRole = null }
        )
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
private fun PgpKeysSection(
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
