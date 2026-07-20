package com.threemail.android.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.threemail.android.R
import com.threemail.android.data.settings.MessageDensity
import com.threemail.android.data.settings.SwipeAction
import com.threemail.android.data.settings.ThemeMode
import com.threemail.android.ui.components.SettingsContentRow
import com.threemail.android.ui.components.SettingsGroup
import com.threemail.android.ui.components.SettingsSwitchRow

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit
) {
    val settings by viewModel.settings.collectAsState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            SettingsGroup(title = "Appearance") {
                SettingsContentRow {
                    Text("Theme", style = MaterialTheme.typography.bodyLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThemeMode.entries.forEach { mode ->
                            FilterChip(
                                selected = settings.themeMode == mode,
                                onClick = { viewModel.setThemeMode(mode) },
                                label = { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) }
                            )
                        }
                    }
                }
                SettingsSwitchRow(
                    title = "Dynamic Color",
                    subtitle = "Match your wallpaper (Android 12+).",
                    icon = Icons.Default.Colorize,
                    checked = settings.useDynamicColor,
                    onCheckedChange = viewModel::setDynamicColor
                )
            }

            SettingsGroup(title = stringResource(R.string.images_section_title)) {
                SettingsSwitchRow(
                    title = stringResource(R.string.images_setting_label),
                    subtitle = stringResource(R.string.images_setting_subtitle),
                    icon = Icons.Default.Image,
                    checked = settings.loadImages,
                    onCheckedChange = viewModel::setLoadImages
                )
            }

            SettingsGroup(title = "Sync") {
                SettingsContentRow {
                    Text("Default Check Frequency", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = "Applies to accounts without their own frequency. Set a per-account override in Accounts.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(15L, 30L, 60L, 180L).forEach { minutes ->
                            FilterChip(
                                selected = settings.syncIntervalMinutes == minutes,
                                onClick = { viewModel.setSyncInterval(minutes) },
                                label = { Text(if (minutes < 60) "${minutes}m" else "${minutes / 60}h") }
                            )
                        }
                    }
                }
            }

            SettingsGroup(title = stringResource(R.string.display_section)) {
                SettingsContentRow {
                    Text(stringResource(R.string.swipe_right_label), style = MaterialTheme.typography.bodyLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SwipeAction.entries.forEach { action ->
                            FilterChip(
                                selected = settings.swipeRightAction == action,
                                onClick = { viewModel.setSwipeRightAction(action) },
                                label = { Text(stringResource(swipeActionLabel(action))) }
                            )
                        }
                    }
                }
                SettingsContentRow {
                    Text(stringResource(R.string.swipe_left_label), style = MaterialTheme.typography.bodyLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SwipeAction.entries.forEach { action ->
                            FilterChip(
                                selected = settings.swipeLeftAction == action,
                                onClick = { viewModel.setSwipeLeftAction(action) },
                                label = { Text(stringResource(swipeActionLabel(action))) }
                            )
                        }
                    }
                }
                SettingsContentRow {
                    Text(stringResource(R.string.density_label), style = MaterialTheme.typography.bodyLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MessageDensity.entries.forEach { density ->
                            FilterChip(
                                selected = settings.messageDensity == density,
                                onClick = { viewModel.setMessageDensity(density) },
                                label = {
                                    Text(
                                        stringResource(
                                            when (density) {
                                                MessageDensity.COMFORTABLE -> R.string.density_comfortable
                                                MessageDensity.COMPACT -> R.string.density_compact
                                                MessageDensity.EXTRA_COMPACT -> R.string.density_extra_compact
                                            }
                                        )
                                    )
                                }
                            )
                        }
                    }
                }
                SettingsContentRow {
                    Text(stringResource(R.string.preview_lines_label), style = MaterialTheme.typography.bodyLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (0..3).forEach { lines ->
                            FilterChip(
                                selected = settings.previewLines == lines,
                                onClick = { viewModel.setPreviewLines(lines) },
                                label = { Text(lines.toString()) }
                            )
                        }
                    }
                }
            }

            SettingsGroup(title = "Notifications") {
                SettingsSwitchRow(
                    title = "New Mail Notifications",
                    subtitle = "Notify me when new mail arrives.",
                    icon = Icons.Default.Notifications,
                    checked = settings.notificationsEnabled,
                    onCheckedChange = viewModel::setNotificationsEnabled
                )
            }

            SettingsGroup(title = stringResource(R.string.trash_settings_section)) {
                SettingsContentRow {
                    Text(
                        text = stringResource(R.string.trash_settings_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                SettingsSwitchRow(
                    title = stringResource(R.string.empty_trash_on_launch_title),
                    subtitle = stringResource(R.string.empty_trash_on_launch_subtitle),
                    icon = Icons.Default.DeleteSweep,
                    checked = settings.emptyTrashOnLaunch,
                    onCheckedChange = viewModel::setEmptyTrashOnLaunch
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.empty_trash_on_quit_title),
                    subtitle = stringResource(R.string.empty_trash_on_quit_subtitle),
                    icon = Icons.Default.DeleteSweep,
                    checked = settings.emptyTrashOnQuit,
                    onCheckedChange = viewModel::setEmptyTrashOnQuit
                )
            }
        }
    }
}

private fun swipeActionLabel(action: SwipeAction): Int = when (action) {
    SwipeAction.NONE -> R.string.swipe_action_none
    SwipeAction.ARCHIVE -> R.string.swipe_action_archive
    SwipeAction.DELETE -> R.string.swipe_action_delete
    SwipeAction.TOGGLE_READ -> R.string.swipe_action_toggle_read
    SwipeAction.MARK_SPAM -> R.string.swipe_action_mark_spam
}
