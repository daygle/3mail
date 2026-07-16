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
import androidx.compose.foundation.layout.width
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Report
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.threemail.android.R
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
    onToggleFavorite: (MailFolder) -> Unit,
    onManageAccounts: () -> Unit,
    onSettings: () -> Unit,
    onCalendar: () -> Unit,
    onSync: () -> Unit
) {
    // Split folders into favorites + main list. The drawer renders favorites
    // first as non-interactive rows (the user's mental model: a "pinned"
    // shortcut list at the top) so the action targets for the real folder
    // picker stay grouped below. `remember(folders)` keeps the partitioning
    // stable across recompositions not driven by `folders`.
    val favoriteFolders = remember(folders) { folders.filter { it.isFavorite } }
    val normalFolders = remember(folders) { folders.filterNot { it.isFavorite } }

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
                            contentDescription = stringResource(R.string.accounts),
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

            // Folder List (Scrollable). Emits three blocks:
            //   1. Favorites header + disabled rows (only when there is an
            //      account AND at least one favorited folder)
            //   2. The main folder list with star toggles on each row
            // Both blocks share a single LazyColumn so the scroll position
            // behaves naturally and the divider above is the only chrome
            // between sections.
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                if (account != null && favoriteFolders.isNotEmpty()) {
                    item(key = "favorites-header") {
                        Text(
                            text = stringResource(R.string.favorites_header),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            // Sit the label inline with the folder-row text gutter
                            // (NavigationDrawerItem's label is offset by the icon
                            // width; 32.dp approximates that gutter for the list
                            // density this drawer uses).
                            modifier = Modifier.padding(start = 32.dp, top = 4.dp, bottom = 4.dp)
                        )
                    }
                    items(favoriteFolders, key = { "fav-${it.id}" }) { folder ->
                        // Non-interactive pinned shortcut. Rendered as a plain
                        // Row rather than a NavigationDrawerItem because
                        // NavigationDrawerItem (`enabled` was added in a later
                        // Material3 than this project pins, and on this version
                        // the icon stays clickable even with `onClick = {}`)
                        // and our pinned rows must NOT be tappable: tapping
                        // them would clear the selection on the same-named
                        // entry in the main list below, which is confusing.
                        // The star + dimmed-onSurface color combination is the
                        // visual contract that says "shortcut, not a button".
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                            Spacer(Modifier.width(20.dp))
                            Text(
                                text = folder.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    item(key = "favorites-divider") {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 6.dp),
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                        )
                    }
                }

                items(normalFolders, key = { it.id }) { folder ->
                    val isSelected = folder.id == selectedFolder?.id
                    NavigationDrawerItem(
                        icon = { Icon(iconFor(folder.type), contentDescription = null) },
                        label = { Text(folder.name) },
                        // Badge slot hosts BOTH the unread count (preserves the
                        // pre-favoriteFolders visual cue for "this folder has
                        // new mail") AND the star toggle for favoriting. The
                        // Row shares the gutter reserved for the badge area;
                        // the IconButton keeps its default 48dp tap target so
                        // accessibility (TalkBack long-press, overshoot) is
                        // preserved even in this dense compact list.
                        badge = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (folder.unreadCount > 0) {
                                    Text(
                                        text = folder.unreadCount.toString(),
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Medium,
                                        color = if (isSelected) Color.White
                                                else MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(Modifier.width(8.dp))
                                }
                                IconButton(onClick = { onToggleFavorite(folder) }) {
                                    Icon(
                                        imageVector = if (folder.isFavorite) Icons.Default.Star else Icons.Outlined.StarBorder,
                                        contentDescription = stringResource(
                                            if (folder.isFavorite) R.string.favorites_remove else R.string.favorites_add
                                        ),
                                        // Color chain: `when` (not chained `if`)
                                        // so the selected state ALWAYS paints
                                        // white-on-primary, even for favorited
                                        // folders where the priority order would
                                        // otherwise be primary-on-primary (invisible).
                                        tint = when {
                                            isSelected -> Color.White
                                            folder.isFavorite -> MaterialTheme.colorScheme.primary
                                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                }
                            }
                        },
                        selected = isSelected,
                        onClick = { onFolderClick(folder) },
                        colors = NavigationDrawerItemDefaults.colors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedIconColor = Color.White,
                            selectedTextColor = Color.White,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurface
                        ),
                        shape = CircleShape,
                        modifier = Modifier
                            .padding(NavigationDrawerItemDefaults.ItemPadding)
                            .padding(vertical = 2.dp)
                    )
                }
            }

            HorizontalDivider()

            // Footer. Refresh and Manage Folders are scoped to a configured
            // account, so we hide them when there isn't one - tapping either
            // without an account leads to confusing empty-state flows. Settings
            // stays visible regardless so the user can still reach app-wide
            // preferences even on a fresh install.
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (account != null) {
                    FooterItem(
                        icon = Icons.Default.Refresh,
                        label = stringResource(R.string.footer_refresh),
                        onClick = onSync
                    )
                    FooterItem(
                        icon = Icons.Default.FolderOpen,
                        label = stringResource(R.string.footer_manage_folders),
                        onClick = onManageAccounts
                    )
                }
                FooterItem(
                    icon = Icons.Default.Settings,
                    label = stringResource(R.string.footer_settings),
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
