package com.threemail.android.ui.screens.account

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import com.threemail.android.R

/** Drilled-out incoming/outgoing server settings. See [ConnectionSettingsSections]. */
@Composable
fun AccountServerScreen(
    viewModel: AccountSettingsViewModel,
    onNavigateBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    AccountSubPage(
        title = stringResource(R.string.account_settings_server_section),
        onNavigateBack = onNavigateBack
    ) {
        state.account?.let { account ->
            ConnectionSettingsSections(account = account, viewModel = viewModel)
        }
    }
}
