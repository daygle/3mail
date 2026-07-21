package com.threemail.android.ui.screens.account

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import com.threemail.android.R

/** Drilled-out OpenPGP key management page. See [PgpKeysSection]. */
@Composable
fun AccountPgpScreen(
    viewModel: AccountSettingsViewModel,
    onNavigateBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    AccountSubPage(
        title = stringResource(R.string.pgp_keys_section),
        onNavigateBack = onNavigateBack
    ) {
        PgpKeysSection(state = state, viewModel = viewModel)
    }
}
