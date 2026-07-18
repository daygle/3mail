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
                    title = "Dynamic color",
                    subtitle = "Match your wallpaper (Android 12+)",
                    icon = Icons.Default.Colorize,
                    checked = settings.useDynamicColor,
                    onCheckedChange = viewModel::setDynamicColor
                )
            }

            SettingsGroup(title = "Sync") {
                SettingsContentRow {
                    Text("Default check frequency", style = MaterialTheme.typography.bodyLarge)
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

            SettingsGroup(title = "Notifications") {
                SettingsSwitchRow(
                    title = "New mail notifications",
                    subtitle = "Notify me when new mail arrives",
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

            SettingsGroup(title = "Signature") {
                SettingsContentRow {
                    OutlinedTextField(
                        value = settings.signature,
                        onValueChange = viewModel::setSignature,
                        label = { Text("Global signature") },
                        placeholder = { Text("Sent from 3mail") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "Used for accounts that don't set their own signature.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
