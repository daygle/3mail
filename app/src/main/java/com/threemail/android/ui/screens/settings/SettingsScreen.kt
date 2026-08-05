package com.threemail.android.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.threemail.android.ui.theme.appTopBarColors
import com.threemail.android.R
import com.threemail.android.data.settings.AfterDeleteNavigation
import com.threemail.android.data.settings.MessageDensity
import com.threemail.android.data.settings.SwipeAction
import com.threemail.android.data.settings.ThemeMode
import com.threemail.android.ui.components.CardDivider
import com.threemail.android.ui.components.SettingsChoice
import com.threemail.android.ui.components.SettingsChoiceDialog
import com.threemail.android.ui.components.SettingsContentRow
import com.threemail.android.ui.components.SettingsGroup
import com.threemail.android.ui.components.SettingsRow
import com.threemail.android.ui.components.SettingsSwitchRow

/** Which single-choice picker dialog, if any, is currently open. */
private enum class SettingsDialog {
    None, Theme, SyncFrequency, SwipeRight, SwipeLeft, Density, PreviewLines, AfterAction, InboxLimit, ImageAllowlist
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit,
    /**
     * Invoked when the user taps the "Top Bar" settings row at the bottom
     * of this screen. The TopBarCustomisationScreen is a separate destination
     * because the per-screen visibility list runs long enough that it earned
     * its own scroll surface.
     */
    onNavigateToTopBarSettings: () -> Unit = {}
) {
    val settings by viewModel.settings.collectAsState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var dialog by remember { mutableStateOf(SettingsDialog.None) }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
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
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // -- Appearance --
            SettingsGroup(title = stringResource(R.string.theme_label), icon = Icons.Default.Palette) {
                SettingsRow(
                    title = stringResource(R.string.theme_label),
                    value = themeLabel(settings.themeMode),
                    onClick = { dialog = SettingsDialog.Theme }
                )
                CardDivider()
                SettingsSwitchRow(
                    title = stringResource(R.string.dynamic_color_title),
                    subtitle = stringResource(R.string.dynamic_color_subtitle),
                    checked = settings.useDynamicColor,
                    onCheckedChange = viewModel::setDynamicColor
                )
            }

            // -- Inbox & Reading --
            SettingsGroup(title = stringResource(R.string.display_section), icon = Icons.Default.Tune) {
                SettingsRow(
                    title = stringResource(R.string.density_label),
                    value = stringResource(densityLabel(settings.messageDensity)),
                    onClick = { dialog = SettingsDialog.Density }
                )
                CardDivider()
                SettingsRow(
                    title = stringResource(R.string.preview_lines_label),
                    value = previewLinesLabel(settings.previewLines),
                    onClick = { dialog = SettingsDialog.PreviewLines }
                )
                CardDivider()
                SettingsRow(
                    title = stringResource(R.string.swipe_right_label),
                    value = stringResource(swipeActionLabel(settings.swipeRightAction)),
                    onClick = { dialog = SettingsDialog.SwipeRight }
                )
                CardDivider()
                SettingsRow(
                    title = stringResource(R.string.swipe_left_label),
                    value = stringResource(swipeActionLabel(settings.swipeLeftAction)),
                    onClick = { dialog = SettingsDialog.SwipeLeft }
                )
                SettingsRow(
                    title = stringResource(R.string.reading_after_delete_row_title),
                    subtitle = stringResource(R.string.reading_after_delete_subtitle),
                    value = stringResource(afterDeleteLabel(settings.afterDeleteNavigation)),
                    onClick = { dialog = SettingsDialog.AfterAction }
                )
                CardDivider()
                SettingsRow(
                    title = stringResource(R.string.inbox_limit_label),
                    subtitle = stringResource(R.string.inbox_limit_subtitle),
                    value = inboxLimitLabel(settings.inboxLimit),
                    onClick = { dialog = SettingsDialog.InboxLimit }
                )
                CardDivider()
                SettingsRow(
                    title = stringResource(R.string.top_bar_settings_title),
                    subtitle = stringResource(R.string.top_bar_settings_subtitle),
                    onClick = onNavigateToTopBarSettings
                )
            }

            // -- Delivery & Sync --
            SettingsGroup(title = stringResource(R.string.sync_section), icon = Icons.Default.Sync) {
                SettingsRow(
                    title = stringResource(R.string.default_check_frequency_title),
                    subtitle = stringResource(R.string.default_check_frequency_subtitle),
                    value = frequencyLabel(settings.syncIntervalMinutes),
                    onClick = { dialog = SettingsDialog.SyncFrequency }
                )
                CardDivider()
                SettingsSwitchRow(
                    title = stringResource(R.string.new_mail_notifications_title),
                    subtitle = stringResource(R.string.new_mail_notifications_subtitle),
                    checked = settings.notificationsEnabled,
                    onCheckedChange = viewModel::setNotificationsEnabled
                )
            }

            // -- Privacy --
            SettingsGroup(title = stringResource(R.string.images_section_title), icon = Icons.Default.Security) {
                SettingsSwitchRow(
                    title = stringResource(R.string.biometric_lock_title),
                    subtitle = stringResource(R.string.biometric_lock_subtitle),
                    checked = settings.biometricLockEnabled,
                    onCheckedChange = viewModel::setBiometricLockEnabled
                )
                CardDivider()
                SettingsSwitchRow(
                    title = stringResource(R.string.images_setting_label),
                    subtitle = stringResource(R.string.images_setting_subtitle),
                    checked = settings.loadImages,
                    onCheckedChange = viewModel::setLoadImages
                )
                CardDivider()
                SettingsRow(
                    title = stringResource(R.string.image_allowlist_title),
                    subtitle = stringResource(R.string.image_allowlist_subtitle),
                    value = settings.imageAllowlist.size.toString(),
                    onClick = { dialog = SettingsDialog.ImageAllowlist }
                )
                CardDivider()
                SettingsSwitchRow(
                    title = stringResource(R.string.shrink_to_fit_title),
                    subtitle = stringResource(R.string.shrink_to_fit_subtitle),
                    checked = settings.shrinkEmailToFit,
                    onCheckedChange = viewModel::setShrinkEmailToFit
                )
            }

        }
    }

    when (dialog) {
        SettingsDialog.Theme -> SettingsChoiceDialog(
            title = stringResource(R.string.theme_label),
            options = ThemeMode.entries.map { SettingsChoice(it, themeLabel(it)) },
            selected = settings.themeMode,
            dismissLabel = stringResource(R.string.cancel),
            onSelect = viewModel::setThemeMode,
            onDismiss = { dialog = SettingsDialog.None }
        )
        SettingsDialog.SyncFrequency -> SettingsChoiceDialog(
            title = stringResource(R.string.default_check_frequency_title),
            options = SYNC_FREQUENCY_OPTIONS.map { SettingsChoice(it, frequencyLabel(it)) },
            selected = settings.syncIntervalMinutes,
            dismissLabel = stringResource(R.string.cancel),
            onSelect = viewModel::setSyncInterval,
            onDismiss = { dialog = SettingsDialog.None }
        )
        SettingsDialog.SwipeRight -> SettingsChoiceDialog(
            title = stringResource(R.string.swipe_right_label),
            options = SwipeAction.entries.map { SettingsChoice(it, stringResource(swipeActionLabel(it))) },
            selected = settings.swipeRightAction,
            dismissLabel = stringResource(R.string.cancel),
            onSelect = viewModel::setSwipeRightAction,
            onDismiss = { dialog = SettingsDialog.None }
        )
        SettingsDialog.SwipeLeft -> SettingsChoiceDialog(
            title = stringResource(R.string.swipe_left_label),
            options = SwipeAction.entries.map { SettingsChoice(it, stringResource(swipeActionLabel(it))) },
            selected = settings.swipeLeftAction,
            dismissLabel = stringResource(R.string.cancel),
            onSelect = viewModel::setSwipeLeftAction,
            onDismiss = { dialog = SettingsDialog.None }
        )
        SettingsDialog.Density -> SettingsChoiceDialog(
            title = stringResource(R.string.density_label),
            options = MessageDensity.entries.map { SettingsChoice(it, stringResource(densityLabel(it))) },
            selected = settings.messageDensity,
            dismissLabel = stringResource(R.string.cancel),
            onSelect = viewModel::setMessageDensity,
            onDismiss = { dialog = SettingsDialog.None }
        )
        SettingsDialog.PreviewLines -> SettingsChoiceDialog(
            title = stringResource(R.string.preview_lines_label),
            options = (0..3).map { SettingsChoice(it, previewLinesLabel(it)) },
            selected = settings.previewLines,
            dismissLabel = stringResource(R.string.cancel),
            onSelect = viewModel::setPreviewLines,
            onDismiss = { dialog = SettingsDialog.None }
        )
        SettingsDialog.AfterAction -> SettingsChoiceDialog(
            title = stringResource(R.string.reading_after_delete_section),
            options = AfterDeleteNavigation.entries.map { SettingsChoice(it, stringResource(afterDeleteLabel(it))) },
            selected = settings.afterDeleteNavigation,
            dismissLabel = stringResource(R.string.cancel),
            onSelect = viewModel::setAfterDeleteNavigation,
            onDismiss = { dialog = SettingsDialog.None }
        )
        SettingsDialog.InboxLimit -> SettingsChoiceDialog(
            title = stringResource(R.string.inbox_limit_label),
            options = INBOX_LIMIT_OPTIONS.map { SettingsChoice(it, inboxLimitLabel(it)) },
            selected = settings.inboxLimit,
            dismissLabel = stringResource(R.string.cancel),
            onSelect = viewModel::setInboxLimit,
            onDismiss = { dialog = SettingsDialog.None }
        )
        SettingsDialog.ImageAllowlist -> {
            if (settings.imageAllowlist.isEmpty()) {
                AlertDialog(
                    onDismissRequest = { dialog = SettingsDialog.None },
                    title = { Text(stringResource(R.string.image_allowlist_title)) },
                    text = { Text(stringResource(R.string.image_allowlist_empty)) },
                    confirmButton = {
                        TextButton(onClick = { dialog = SettingsDialog.None }) {
                            Text(stringResource(R.string.headers_close))
                        }
                    }
                )
            } else {
                AlertDialog(
                    onDismissRequest = { dialog = SettingsDialog.None },
                    title = { Text(stringResource(R.string.image_allowlist_title)) },
                    text = {
                        LazyColumn {
                            items(settings.imageAllowlist.toList().sorted()) { entry ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = entry,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(
                                        onClick = {
                                            viewModel.removeFromImageAllowlist(entry)
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = stringResource(R.string.remove),
                                            modifier = Modifier.size(18.dp),
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { dialog = SettingsDialog.None }) {
                            Text(stringResource(R.string.headers_close))
                        }
                    }
                )
            }
        }
        SettingsDialog.None -> Unit
    }
}

/** Default-frequency options offered globally, in minutes. */
private val SYNC_FREQUENCY_OPTIONS = listOf(15L, 30L, 60L, 180L)

private val INBOX_LIMIT_OPTIONS = listOf(50, 100, 250, 500, 1000, 0)

@Composable
private fun themeLabel(mode: ThemeMode): String = stringResource(
    when (mode) {
        ThemeMode.SYSTEM -> R.string.theme_system
        ThemeMode.LIGHT -> R.string.theme_light
        ThemeMode.DARK -> R.string.theme_dark
    }
)

@Composable
private fun frequencyLabel(minutes: Long): String =
    if (minutes < 60) "${minutes}m" else "${minutes / 60}h"

@Composable
private fun previewLinesLabel(lines: Int): String =
    if (lines == 0) stringResource(R.string.preview_lines_none)
    else stringResource(R.string.preview_lines_count, lines)

@Composable
private fun inboxLimitLabel(limit: Int): String =
    if (limit <= 0) stringResource(R.string.inbox_limit_unlimited)
    else limit.toString()

private fun swipeActionLabel(action: SwipeAction): Int = when (action) {
    SwipeAction.NONE -> R.string.swipe_action_none
    SwipeAction.ARCHIVE -> R.string.swipe_action_archive
    SwipeAction.DELETE -> R.string.swipe_action_delete
    SwipeAction.TOGGLE_READ -> R.string.swipe_action_toggle_read
    SwipeAction.MARK_SPAM -> R.string.swipe_action_mark_spam
    SwipeAction.MOVE -> R.string.swipe_action_move
}

private fun densityLabel(density: MessageDensity): Int = when (density) {
    MessageDensity.COMFORTABLE -> R.string.density_comfortable
    MessageDensity.COMPACT -> R.string.density_compact
    MessageDensity.EXTRA_COMPACT -> R.string.density_extra_compact
}

private fun afterDeleteLabel(value: AfterDeleteNavigation): Int = when (value) {
    AfterDeleteNavigation.RETURN_TO_LIST -> R.string.reading_after_delete_return_to_list
    AfterDeleteNavigation.PREVIOUS_EMAIL -> R.string.reading_after_delete_previous_email
    AfterDeleteNavigation.NEXT_EMAIL -> R.string.reading_after_delete_next_email
}
