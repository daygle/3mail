package com.threemail.android.ui.screens.account

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import com.threemail.android.R

/** Drilled-out IMAP folder-role overrides. See [FolderRolesSection]. */
@Composable
fun AccountFolderRolesScreen(
    viewModel: AccountSettingsViewModel,
    onNavigateBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    AccountSubPage(
        title = stringResource(R.string.account_folder_roles_section),
        onNavigateBack = onNavigateBack
    ) {
        state.account?.let { account ->
            FolderRolesSection(account = account, state = state, viewModel = viewModel)
        }
    }
}
