package com.threemail.android.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.threemail.android.ui.theme.appTopBarColors
import com.threemail.android.R
import com.threemail.android.data.settings.AppSettings
import com.threemail.android.data.settings.TopBarItemId
import com.threemail.android.ui.components.CardDivider
import com.threemail.android.ui.components.SettingsGroup
import com.threemail.android.ui.components.SettingsSwitchRow

/**
 * Per-screen top-bar visibility controls. Each supported screen (Inbox,
 * Message Detail, Compose) gets its own grouped section of toggles. Hiding
 * an entry moves the action from its TopAppBar IconButton into the bar's
 * MoreVert overflow menu so the underlying feature is still reachable.
 *
 * Persistence is a single shared list of hidden IDs in [AppSettings.hiddenTopBarItems].
 * The screen reads it via [SettingsViewModel.settings] and writes through
 * [SettingsViewModel.setTopBarItemHidden]. The "Reset to defaults" affordance
 * clears the list in one call.
 *
 * Required actions (drawer hamburger / back arrow / selection close) are
 * NOT exposed here - hiding them would strand the user.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopBarCustomisationScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit
) {
    val settings by viewModel.settings.collectAsState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.top_bar_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cancel)
                        )
                    }
                },
                actions = {
                    TextButton(onClick = { viewModel.resetTopBarDefaults() }) {
                        Icon(
                            Icons.Default.Restore,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 6.dp)
                        )
                        Text(stringResource(R.string.top_bar_settings_reset))
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
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.top_bar_settings_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp)
            )

            SettingsGroup(title = stringResource(R.string.top_bar_settings_inbox_header), icon = Icons.Default.Inbox) {
                TopBarToggleRow(
                    item = TopBarItemId.INBOX_SEARCH,
                    label = stringResource(R.string.search),
                    settings = settings,
                    onToggle = viewModel::setTopBarItemHidden
                )
                CardDivider()
                TopBarToggleRow(
                    item = TopBarItemId.INBOX_SYNC,
                    label = stringResource(R.string.sync),
                    settings = settings,
                    onToggle = viewModel::setTopBarItemHidden
                )
                CardDivider()
                TopBarToggleRow(
                    item = TopBarItemId.INBOX_EMPTY_TRASH,
                    label = stringResource(R.string.empty_trash_action),
                    settings = settings,
                    onToggle = viewModel::setTopBarItemHidden,
                    subtitle = stringResource(R.string.top_bar_item_trash_subtitle)
                )
            }

            SettingsGroup(title = stringResource(R.string.top_bar_settings_detail_header), icon = Icons.Default.Mail) {
                TopBarToggleRow(
                    item = TopBarItemId.DETAIL_MARK_UNREAD,
                    label = stringResource(R.string.mark_as_unread),
                    settings = settings,
                    onToggle = viewModel::setTopBarItemHidden
                )
                CardDivider()
                TopBarToggleRow(
                    item = TopBarItemId.DETAIL_ARCHIVE,
                    label = stringResource(R.string.archive),
                    settings = settings,
                    onToggle = viewModel::setTopBarItemHidden
                )
                CardDivider()
                TopBarToggleRow(
                    item = TopBarItemId.DETAIL_DELETE,
                    label = stringResource(R.string.delete),
                    settings = settings,
                    onToggle = viewModel::setTopBarItemHidden
                )
            }

            SettingsGroup(title = stringResource(R.string.top_bar_settings_compose_header), icon = Icons.Default.Edit) {
                TopBarToggleRow(
                    item = TopBarItemId.COMPOSE_INSERT_IMAGE,
                    label = stringResource(R.string.insert_image),
                    settings = settings,
                    onToggle = viewModel::setTopBarItemHidden
                )
                CardDivider()
                TopBarToggleRow(
                    item = TopBarItemId.COMPOSE_ATTACH,
                    label = stringResource(R.string.attach),
                    settings = settings,
                    onToggle = viewModel::setTopBarItemHidden
                )
                CardDivider()
                TopBarToggleRow(
                    item = TopBarItemId.COMPOSE_SAVE_DRAFT,
                    label = stringResource(R.string.save_draft),
                    settings = settings,
                    onToggle = viewModel::setTopBarItemHidden
                )
            }
        }
    }
}

/**
 * Single settings row for one customisable top-bar action. The subtitle
 * (if provided) is reserved for explanatory notes specific to one item,
 * e.g. clarifying that "Empty Trash" only matters while viewing Trash.
 */
@Composable
private fun TopBarToggleRow(
    item: TopBarItemId,
    label: String,
    settings: AppSettings,
    onToggle: (TopBarItemId, Boolean) -> Unit,
    subtitle: String? = null
) {
    SettingsSwitchRow(
        title = label,
        // A switched-OFF toggle means the action is hidden from the bar -
        // the user-facing verb is "Show" so we invert the binary input here
        // rather than at every call site.
        checked = item !in settings.hiddenTopBarItems,
        onCheckedChange = { visible -> onToggle(item, !visible) },
        subtitle = subtitle
    )
}
