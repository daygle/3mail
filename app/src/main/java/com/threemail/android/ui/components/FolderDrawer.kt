package com.threemail.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Drafts
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.filled.Outbox
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Report
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.threemail.android.domain.model.Account
import com.threemail.android.domain.model.FolderType
import com.threemail.android.domain.model.MailFolder
import com.threemail.android.ui.theme.avatarColorFor

@Composable
fun FolderDrawerContent(
    account: Account?,
    folders: List<MailFolder>,
    selectedFolder: MailFolder?,
    onFolderClick: (MailFolder) -> Unit,
    onManageAccounts: () -> Unit,
    onSettings: () -> Unit,
    onCalendar: () -> Unit,
    onSync: () -> Unit
) {
    ModalDrawerSheet {
        Column(modifier = Modifier.fillMaxHeight()) {
            // Header
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                if (account != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onManageAccounts() },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(avatarColorFor(account.email)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = account.email.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                                color = Color.White,
                                style = MaterialTheme.typography.titleLarge
                            )
                        }
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 16.dp)
                        ) {
                            val accountName = account.email.substringAfter("@")
                            Text(
                                text = accountName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = account.email,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.ExpandMore,
                            contentDescription = "Switch account",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    Text(
                        text = "3mail",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            HorizontalDivider()

            // Folder List (Scrollable)
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                items(folders, key = { it.id }) { folder ->
                    val isSelected = folder.id == selectedFolder?.id
                    NavigationDrawerItem(
                        icon = { Icon(iconFor(folder.type), contentDescription = null) },
                        label = { Text(folder.name) },
                        badge = {
                            if (folder.unreadCount > 0) {
                                Text(
                                    text = folder.unreadCount.toString(),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        },
                        selected = isSelected,
                        onClick = { onFolderClick(folder) },
                        colors = NavigationDrawerItemDefaults.colors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedIconColor = Color.White,
                            selectedTextColor = Color.White,
                            selectedBadgeColor = Color.White,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurface
                        ),
                        shape = CircleShape,
                        modifier = Modifier
                            .padding(NavigationDrawerItemDefaults.ItemPadding)
                            .padding(vertical = 2.dp)
                    )
                }

                // Add Outbox as shown in screenshot if not in folders
                if (folders.none { it.type == FolderType.SENT }) {
                    item {
                        NavigationDrawerItem(
                            icon = { Icon(Icons.Default.Outbox, contentDescription = null) },
                            label = { Text("Outbox") },
                            selected = false,
                            onClick = { /* TODO */ },
                            shape = CircleShape,
                            modifier = Modifier
                                .padding(NavigationDrawerItemDefaults.ItemPadding)
                                .padding(vertical = 2.dp)
                        )
                    }
                }
            }

            HorizontalDivider()

            // Footer
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                FooterItem(
                    icon = Icons.Default.Refresh,
                    label = "Sync account",
                    onClick = onSync
                )
                FooterItem(
                    icon = Icons.Default.FolderOpen,
                    label = "Manage folders",
                    onClick = onManageAccounts
                )
                FooterItem(
                    icon = Icons.Default.Settings,
                    label = "Settings",
                    onClick = onSettings
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun FooterItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    NavigationDrawerItem(
        icon = { Icon(icon, contentDescription = null) },
        label = { Text(label) },
        selected = false,
        onClick = onClick,
        colors = NavigationDrawerItemDefaults.colors(
            unselectedContainerColor = Color.Transparent,
            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unselectedTextColor = MaterialTheme.colorScheme.onSurface
        ),
        shape = CircleShape,
        modifier = Modifier
            .padding(NavigationDrawerItemDefaults.ItemPadding)
            .padding(vertical = 2.dp)
    )
}

private fun iconFor(type: FolderType): ImageVector = when (type) {
    FolderType.INBOX -> Icons.Default.Inbox
    FolderType.SENT -> Icons.AutoMirrored.Filled.Send
    FolderType.DRAFTS -> Icons.Default.Drafts
    FolderType.TRASH -> Icons.Default.Delete
    FolderType.ARCHIVE, FolderType.ALL_MAIL -> Icons.Default.Archive
    FolderType.SPAM -> Icons.Outlined.Report
    FolderType.STARRED -> Icons.Default.Star
    else -> Icons.AutoMirrored.Filled.Label
}
