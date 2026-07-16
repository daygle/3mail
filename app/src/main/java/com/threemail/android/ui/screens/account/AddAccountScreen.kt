package com.threemail.android.ui.screens.account

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.threemail.android.R
import com.threemail.android.domain.model.AccountType
import com.threemail.android.domain.model.Security

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddAccountScreen(
    viewModel: AddAccountViewModel,
    onNavigateBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val context = LocalContext.current

    LaunchedEffect(state.isSaved) {
        if (state.isSaved) {
            onNavigateBack()
        }
    }

    val recoverableAuthLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        viewModel.onRecoverableAuthHandled()
        viewModel.retryAfterRecoverableAuth()
    }

    LaunchedEffect(state.recoverableAuthIntent) {
        state.recoverableAuthIntent?.let { intent ->
            recoverableAuthLauncher.launch(intent)
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.add_account)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cancel))
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            GoogleSignInButton(
                onClick = { viewModel.signInWithGoogle(context) },
                enabled = !state.isSaving
            )
            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Or add an IMAP account manually",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = state.email,
                onValueChange = viewModel::updateEmail,
                label = { Text(stringResource(R.string.email_address)) },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = state.displayName,
                onValueChange = viewModel::updateDisplayName,
                label = { Text("Display name") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = state.password,
                onValueChange = viewModel::updatePassword,
                label = { Text(stringResource(R.string.password)) },
                // Mask the secret in place and ask the IME for the dedicated
                // password keyboard so users on devices without strong default
                // autofill still see the right key set. The semantic
                // `passwordContentType()` hint is omitted: PasswordVisualTransformation
                // already signals a secret field to TalkBack/IME/autofill for the
                // Compose releases this project targets, and the symbol isn't
                // resolvable on the Material3 BOM pinned in libs.versions.toml.
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = state.server,
                onValueChange = viewModel::updateServer,
                label = { Text(stringResource(R.string.imap_server)) },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = state.port,
                onValueChange = viewModel::updatePort,
                label = { Text(stringResource(R.string.port)) },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = state.outgoingServer,
                onValueChange = viewModel::updateOutgoingServer,
                label = { Text(stringResource(R.string.outgoing_server)) },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = state.outgoingPort,
                onValueChange = viewModel::updateOutgoingPort,
                label = { Text(stringResource(R.string.outgoing_port)) },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            state.upgradeBanner?.let { message ->
                // Non-blocking informational banner. Sits above the
                // security picker so the user sees the explanation
                // before (and while) noticing the chip selection changed
                // under them. The tertiaryContainer tone is the standard
                // Material 3 "informational, not a warning" palette slot.
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
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
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
            Text(
                text = stringResource(R.string.security),
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Three exclusive chips map directly to the Security enum so
                // there is no invalid intermediate state at the UI layer.
                Security.entries.forEach { mode ->
                    val labelRes = when (mode) {
                        Security.NONE -> R.string.security_none
                        Security.STARTTLS -> R.string.security_starttls
                        Security.SSL_TLS -> R.string.security_ssl_tls
                    }
                    FilterChip(
                        selected = state.security == mode,
                        onClick = { viewModel.updateSecurity(mode) },
                        label = { Text(stringResource(labelRes)) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            val securitySubtitleRes = when (state.security) {
                Security.NONE -> R.string.security_none_subtitle
                Security.STARTTLS -> R.string.security_starttls_subtitle
                Security.SSL_TLS -> R.string.security_ssl_tls_subtitle
            }
            Text(
                text = stringResource(securitySubtitleRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = { viewModel.save() },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isSaving
            ) {
                if (state.isSaving) {
                    CircularProgressIndicator()
                } else {
                    Text(stringResource(R.string.save))
                }
            }
            state.error?.let {
                Text(text = it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

@Composable
private fun GoogleSignInButton(
    onClick: () -> Unit,
    enabled: Boolean
) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled
    ) {
        Text(stringResource(R.string.sign_in_with_google))
    }
}
