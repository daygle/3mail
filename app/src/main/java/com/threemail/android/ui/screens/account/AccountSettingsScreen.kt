package com.threemail.android.ui.screens.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.threemail.android.R
import com.threemail.android.domain.model.AccountType
import com.threemail.android.domain.model.Identity
import com.threemail.android.ui.components.SettingsContentRow
import com.threemail.android.ui.components.SettingsGroup
import com.threemail.android.ui.components.SettingsSwitchRow

/** Frequency options offered per account; `0` means "follow the global default". */
private val FREQUENCY_OPTIONS = listOf(0L, 15L, 30L, 60L, 180L)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AccountSettingsScreen(
    viewModel: AccountSettingsViewModel,
    onNavigateBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val account = state.account

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
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface
                ),
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
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    SettingsGroup(title = stringResource(R.string.account_settings_general)) {
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

                    SettingsGroup(title = stringResource(R.string.account_settings_signature_section)) {
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

                    SettingsGroup(title = stringResource(R.string.identities_section)) {
                        SettingsContentRow {
                            Text(
                                text = stringResource(R.string.identities_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        account.identities.forEachIndexed { index, identity ->
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
                        SettingsContentRow {
                            AddIdentityForm(onAdd = viewModel::addIdentity)
                        }
                    }

                    SettingsGroup(title = stringResource(R.string.account_settings_sync_section)) {
                        SettingsSwitchRow(
                            title = stringResource(R.string.account_settings_sync_enabled_title),
                            subtitle = stringResource(R.string.account_settings_sync_enabled_subtitle),
                            icon = Icons.Default.Sync,
                            checked = account.syncEnabled,
                            onCheckedChange = viewModel::setSyncEnabled
                        )
                        SettingsContentRow {
                            Text(
                                text = stringResource(R.string.account_settings_frequency_title),
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (account.syncEnabled) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FREQUENCY_OPTIONS.forEach { minutes ->
                                    FilterChip(
                                        selected = account.syncIntervalMinutes == minutes,
                                        enabled = account.syncEnabled,
                                        onClick = { viewModel.setSyncIntervalMinutes(minutes) },
                                        label = {
                                            Text(
                                                if (minutes == 0L) {
                                                    stringResource(
                                                        R.string.account_settings_frequency_default,
                                                        formatFrequency(state.defaultIntervalMinutes)
                                                    )
                                                } else {
                                                    formatFrequency(minutes)
                                                }
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }

                    SettingsGroup(title = stringResource(R.string.account_settings_notifications_section)) {
                        SettingsSwitchRow(
                            title = stringResource(R.string.account_settings_notifications_title),
                            subtitle = stringResource(R.string.account_settings_notifications_subtitle),
                            icon = Icons.Default.Notifications,
                            checked = account.notificationsEnabled,
                            onCheckedChange = viewModel::setNotificationsEnabled
                        )
                    }

                    // IDLE push is IMAP-only; Gmail rides Google's own push
                    // pipeline, so the toggle is meaningless there.
                    if (account.accountType == AccountType.IMAP) {
                        SettingsGroup(title = stringResource(R.string.account_push_label)) {
                            SettingsSwitchRow(
                                title = stringResource(R.string.account_push_label),
                                subtitle = stringResource(R.string.account_push_subtitle),
                                icon = Icons.Default.Badge,
                                checked = account.pushEnabled,
                                onCheckedChange = viewModel::setPushEnabled
                            )
                        }
                    }
                }
            }
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
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
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
