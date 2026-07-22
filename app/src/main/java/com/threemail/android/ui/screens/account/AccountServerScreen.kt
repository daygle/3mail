package com.threemail.android.ui.screens.account

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import com.threemail.android.R

/** Drilled-out incoming (fetching) server settings. See [IncomingServerSection]. */
@Composable
fun AccountIncomingServerScreen(
    viewModel: AccountSettingsViewModel,
    onNavigateBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    AccountSubPage(
        title = stringResource(R.string.account_incoming_server_section),
        onNavigateBack = onNavigateBack
    ) {
        state.account?.let { account ->
            IncomingServerSection(account = account, viewModel = viewModel)
        }
    }
}

/** Drilled-out outgoing (sending) server settings. See [OutgoingServerSection]. */
@Composable
fun AccountOutgoingServerScreen(
    viewModel: AccountSettingsViewModel,
    onNavigateBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    AccountSubPage(
        title = stringResource(R.string.account_outgoing_server_section),
        onNavigateBack = onNavigateBack
    ) {
        state.account?.let { account ->
            OutgoingServerSection(account = account, viewModel = viewModel)
        }
    }
}
