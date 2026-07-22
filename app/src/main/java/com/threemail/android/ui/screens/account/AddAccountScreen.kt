package com.threemail.android.ui.screens.account

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.threemail.android.ui.theme.appTopBarColors
import com.threemail.android.R
import com.threemail.android.data.remote.autoconfig.MailProviders
import com.threemail.android.data.remote.autoconfig.ProviderAuth
import com.threemail.android.data.remote.autoconfig.ProviderPreset
import com.threemail.android.domain.model.AccountType
import com.threemail.android.domain.model.Security
import com.threemail.android.ui.components.SettingsContentRow
import com.threemail.android.ui.components.SettingsGroup

/** Two-step add-account flow: pick a provider, then fill in the details. */
private enum class AddStep { ChooseProvider, Form }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddAccountScreen(
    viewModel: AddAccountViewModel,
    onNavigateBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val context = LocalContext.current

    var step by remember { mutableStateOf(AddStep.ChooseProvider) }
    // The chosen built-in provider; null means the manual "Other" path.
    var provider by remember { mutableStateOf<ProviderPreset?>(null) }

    LaunchedEffect(state.isSaved) {
        if (state.isSaved) onNavigateBack()
    }

    val recoverableAuthLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        viewModel.onRecoverableAuthHandled()
        viewModel.retryAfterRecoverableAuth()
    }
    LaunchedEffect(state.recoverableAuthIntent) {
        state.recoverableAuthIntent?.let { intent -> recoverableAuthLauncher.launch(intent) }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (step == AddStep.ChooseProvider) R.string.add_account
                            else R.string.add_account_details_title
                        )
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        // Form step backs out to the provider chooser first.
                        if (step == AddStep.Form) step = AddStep.ChooseProvider else onNavigateBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cancel))
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
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when (step) {
                AddStep.ChooseProvider -> ProviderChooser(
                    isBusy = state.isSaving,
                    error = state.error,
                    onGoogle = { viewModel.signInWithGoogle(context) },
                    onProvider = { picked ->
                        viewModel.applyProvider(picked)
                        provider = picked
                        step = AddStep.Form
                    },
                    onOther = {
                        provider = null
                        viewModel.updateError(null)
                        viewModel.updateAccountType(AccountType.IMAP)
                        step = AddStep.Form
                    }
                )
                AddStep.Form -> AccountForm(
                    viewModel = viewModel,
                    state = state,
                    provider = provider
                )
            }
        }
    }
}

@Composable
private fun ProviderChooser(
    isBusy: Boolean,
    error: String?,
    onGoogle: () -> Unit,
    onProvider: (ProviderPreset) -> Unit,
    onOther: () -> Unit
) {
    Text(
        text = stringResource(R.string.add_account_choose_provider),
        style = MaterialTheme.typography.titleMedium
    )
    // While a provider sign-in (currently only Google's Credential Manager
    // flow) is in flight, show a spinner and disable the cards so a second
    // tap can't kick off a parallel request.
    if (isBusy) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.add_account_connecting),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
    MailProviders.ALL.forEach { preset ->
        ProviderCard(
            label = preset.displayName,
            enabled = !isBusy,
            onClick = {
                if (preset.auth == ProviderAuth.OAUTH_GOOGLE) onGoogle() else onProvider(preset)
            }
        )
    }
    ProviderCard(
        label = stringResource(R.string.add_account_other),
        enabled = !isBusy,
        onClick = onOther
    )
    // Surface sign-in failures here too - the provider chooser is a distinct
    // step from the details form, and an error raised during Google sign-in
    // (e.g. an unconfigured OAuth client id, or the user dismissing the sheet)
    // would otherwise be invisible because the form's error text never shows.
    error?.let {
        Text(
            text = it,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun ProviderCard(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onClick() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccountForm(
    viewModel: AddAccountViewModel,
    state: AddAccountViewModel.UiState,
    provider: ProviderPreset?
) {
    // Known providers keep server settings collapsed (pre-filled); the manual
    // "Other" path shows them by default.
    var showAdvanced by remember(provider?.id) { mutableStateOf(provider == null) }

    if (provider != null) {
        Text(
            text = stringResource(R.string.add_account_setup_title, provider.displayName),
            style = MaterialTheme.typography.titleMedium
        )
        if (provider.needsAppPassword) {
            InfoBanner(stringResource(R.string.add_account_app_password_hint))
        }
    }

    SettingsGroup(title = stringResource(R.string.add_account_credentials)) {
        SettingsContentRow {
            OutlinedTextField(
                value = state.email,
                onValueChange = viewModel::updateEmail,
                label = { Text(stringResource(R.string.email_address)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = state.displayName,
                onValueChange = viewModel::updateDisplayName,
                label = { Text(stringResource(R.string.display_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = state.password,
                onValueChange = viewModel::updatePassword,
                label = { Text(stringResource(R.string.password)) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    if (provider == null) {
        // Manual path: offer autoconfig discovery before the server fields.
        Button(
            onClick = { viewModel.discoverAutoconfig() },
            enabled = !state.isDiscovering,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (state.isDiscovering) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(stringResource(R.string.add_account_detect))
        }
        state.discoveryMessage?.let { InfoBanner(it) }
        ProtocolPicker(state = state, onSelect = viewModel::updateAccountType)
        IncomingServerFields(viewModel = viewModel, state = state)
        OutgoingServerFields(viewModel = viewModel, state = state)
    } else {
        // Known provider: server settings tucked behind an expander.
        TextButton(onClick = { showAdvanced = !showAdvanced }) {
            Text(
                stringResource(
                    if (showAdvanced) R.string.add_account_hide_advanced
                    else R.string.add_account_advanced
                )
            )
        }
        if (showAdvanced) {
            IncomingServerFields(viewModel = viewModel, state = state)
            OutgoingServerFields(viewModel = viewModel, state = state)
        }
    }

    state.upgradeBanner?.let { InfoBanner(it) }

    Button(
        onClick = { viewModel.save() },
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        enabled = !state.isSaving
    ) {
        if (state.isSaving) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp))
        } else {
            Text(stringResource(R.string.save))
        }
    }
    state.error?.let {
        Text(text = it, color = MaterialTheme.colorScheme.error)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProtocolPicker(
    state: AddAccountViewModel.UiState,
    onSelect: (AccountType) -> Unit
) {
    SettingsGroup(title = stringResource(R.string.protocol_label)) {
        SettingsContentRow {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(AccountType.IMAP, AccountType.POP3).forEach { type ->
                    FilterChip(
                        selected = state.accountType == type,
                        onClick = { onSelect(type) },
                        label = {
                            Text(
                                stringResource(
                                    if (type == AccountType.IMAP) R.string.protocol_imap
                                    else R.string.protocol_pop3
                                )
                            )
                        }
                    )
                }
            }
            Text(
                text = stringResource(
                    if (state.accountType == AccountType.POP3) R.string.protocol_pop3_subtitle
                    else R.string.protocol_imap_subtitle
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IncomingServerFields(
    viewModel: AddAccountViewModel,
    state: AddAccountViewModel.UiState
) {
    SettingsGroup(title = stringResource(R.string.add_account_incoming_section)) {
        SettingsContentRow {
            OutlinedTextField(
                value = state.server,
                onValueChange = viewModel::updateServer,
                label = {
                    Text(
                        stringResource(
                            if (state.accountType == AccountType.POP3) R.string.pop3_server
                            else R.string.imap_server
                        )
                    )
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = state.port,
                onValueChange = viewModel::updatePort,
                label = { Text(stringResource(R.string.port)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = state.incomingUsername,
                onValueChange = viewModel::updateIncomingUsername,
                label = { Text(stringResource(R.string.incoming_username)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = stringResource(R.string.incoming_username_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SecurityChips(
                title = stringResource(R.string.security_incoming_title),
                selected = state.security,
                onSelect = viewModel::updateSecurity
            )
        }
    }
}

@Composable
private fun OutgoingServerFields(
    viewModel: AddAccountViewModel,
    state: AddAccountViewModel.UiState
) {
    SettingsGroup(title = stringResource(R.string.add_account_outgoing_section)) {
        SettingsContentRow {
            OutlinedTextField(
                value = state.outgoingServer,
                onValueChange = viewModel::updateOutgoingServer,
                label = { Text(stringResource(R.string.outgoing_server)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = state.outgoingPort,
                onValueChange = viewModel::updateOutgoingPort,
                label = { Text(stringResource(R.string.outgoing_port)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = state.outgoingUsername,
                onValueChange = viewModel::updateOutgoingUsername,
                label = { Text(stringResource(R.string.outgoing_username)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = state.outgoingPassword,
                onValueChange = viewModel::updateOutgoingPassword,
                label = { Text(stringResource(R.string.outgoing_password)) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = stringResource(R.string.outgoing_credentials_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SecurityChips(
                title = stringResource(R.string.security_outgoing_title),
                selected = state.outgoingSecurity,
                onSelect = viewModel::updateOutgoingSecurity
            )
        }
    }
}

/** A titled row of security-mode chips plus the selected mode's subtitle. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SecurityChips(
    title: String,
    selected: Security,
    onSelect: (Security) -> Unit
) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(top = 8.dp)
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Security.entries.forEach { mode ->
            val labelRes = when (mode) {
                Security.NONE -> R.string.security_none
                Security.STARTTLS -> R.string.security_starttls
                Security.SSL_TLS -> R.string.security_ssl_tls
            }
            FilterChip(
                selected = selected == mode,
                onClick = { onSelect(mode) },
                label = { Text(stringResource(labelRes)) }
            )
        }
    }
    val subtitleRes = when (selected) {
        Security.NONE -> R.string.security_none_subtitle
        Security.STARTTLS -> R.string.security_starttls_subtitle
        Security.SSL_TLS -> R.string.security_ssl_tls_subtitle
    }
    Text(
        text = stringResource(subtitleRes),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** Small informational banner (tertiaryContainer) used for hints and results. */
@Composable
private fun InfoBanner(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(imageVector = Icons.Default.Info, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(text = message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
