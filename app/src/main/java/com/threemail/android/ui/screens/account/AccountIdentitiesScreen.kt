package com.threemail.android.ui.screens.account

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import com.threemail.android.R

/** Drilled-out send-as identities page. See [IdentitiesSection]. */
@Composable
fun AccountIdentitiesScreen(
    viewModel: AccountSettingsViewModel,
    onNavigateBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    AccountSubPage(
        title = stringResource(R.string.identities_section),
        onNavigateBack = onNavigateBack
    ) {
        state.account?.let { account ->
            IdentitiesSection(account = account, viewModel = viewModel)
        }
    }
}
