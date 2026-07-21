package com.threemail.android.ui.screens.account

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import com.threemail.android.R

/** Drilled-out IMAP IDLE push page. See [PushSection]. */
@Composable
fun AccountPushScreen(
    viewModel: AccountSettingsViewModel,
    onNavigateBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    AccountSubPage(
        title = stringResource(R.string.account_push_label),
        onNavigateBack = onNavigateBack
    ) {
        state.account?.let { account ->
            PushSection(account = account, state = state, viewModel = viewModel)
        }
    }
}
