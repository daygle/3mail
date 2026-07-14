package com.threemail.android.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Drafts
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Report
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
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
    onCalendar: () -> Unit
) {
    ModalDrawerSheet {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "3mail",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            if (account != null) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(avatarColorFor(account.email)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = account.email.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    Spacer(Modifier.height(0.dp))
                    Column(modifier = Modifier.padding(start = 12.dp)) {
                        Text(account.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                        Text(account.email, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
            }
        }
        HorizontalDivider()
        LazyColumn(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            items(folders, key = { it.id }) { folder ->
                NavigationDrawerItem(
                    icon = { Icon(iconFor(folder.type), contentDescription = null) },
                    label = { Text(folder.name) },
                    badge = { if (folder.unreadCount > 0) Text(folder.unreadCount.toString()) },
                    selected = folder.id == selectedFolder?.id,
                    onClick = { onFolderClick(folder) },
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }
        HorizontalDivider()
        NavigationDrawerItem(
            icon = { Icon(Icons.Default.CalendarMonth, contentDescription = null) },
            label = { Text("Calendar") },
            selected = false,
            onClick = onCalendar,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
        )
        NavigationDrawerItem(
            icon = { Icon(Icons.Default.ManageAccounts, contentDescription = null) },
            label = { Text("Manage accounts") },
            selected = false,
            onClick = onManageAccounts,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
        )
        NavigationDrawerItem(
            icon = { Icon(Icons.Default.Settings, contentDescription = null) },
            label = { Text("Settings") },
            selected = false,
            onClick = onSettings,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
        )
        Spacer(Modifier.height(12.dp))
    }
}

private fun iconFor(type: FolderType): ImageVector = when (type) {
    FolderType.INBOX -> Icons.Default.Inbox
    FolderType.SENT -> Icons.Default.Send
    FolderType.DRAFTS -> Icons.Default.Drafts
    FolderType.TRASH -> Icons.Default.Delete
    FolderType.ARCHIVE, FolderType.ALL_MAIL -> Icons.Default.Archive
    FolderType.SPAM -> Icons.Outlined.Report
    FolderType.STARRED -> Icons.Default.Star
    else -> Icons.AutoMirrored.Filled.Label
}
