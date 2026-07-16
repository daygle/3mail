package com.threemail.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Drafts
import androidx.compose.material.icons.filled.DragHandle
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.threemail.android.R
import com.threemail.android.domain.model.Account
import com.threemail.android.domain.model.FolderType
import com.threemail.android.domain.model.MailFolder
import com.threemail.android.ui.theme.avatarColorFor

/**
 * Per-row drag state for the favourites drag-reorder gesture. Lifted to
 * file scope so the data class stays private to this composable - no
 * other screen cares about it. `accumulatorY` is the running sum of
 * pointer dragAmount pixels since the gesture began; the row's
 * graphicsLayer translates against this so dragging never causes the
 * rest of the drawer to recompose.
 */
private data class DragInfo(
    val fromIndex: Int,
    val fromServerId: String,
    val accumulatorY: Float = 0f
)

@Composable
fun FolderDrawerContent(
    account: Account?,
    folders: List<MailFolder>,
    selectedFolder: MailFolder?,
    onFolderClick: (MailFolder) -> Unit,
    onToggleFavorite: (MailFolder) -> Unit,
    onReorderFavorite: (accountId: Long, serverIds: List<String>) -> Unit,
    onManageAccounts: () -> Unit,
    onSettings: () -> Unit,
    onCalendar: () -> Unit,
    onSync: () -> Unit
) {
    // Split folders into favorites + main list. The drawer renders favorites
    // first (the user's mental model: a "pinned" shortcut list at the top)
    // so the action targets for the real folder picker stay grouped below.
    // `remember(folders)` keeps the partitioning stable across recompositions
    // not driven by `folders`.
    val favoriteFolders = remember(folders) { folders.filter { it.isFavorite } }
    val normalFolders = remember(folders) { folders.filterNot { it.isFavorite } }

    // Live reference to the favourites list. The drag gesture's `onDragEnd`
    // closure captures this Compose state (which always reads the latest
    // value) instead of the original `favoriteFolders` parameter - a mid-
    // drag reorder that changed the list would otherwise be invisible to
    // the closure, and the user could see their reorder "stick" against a
    // stale snapshot.
    val favoriteFoldersState = rememberUpdatedState(favoriteFolders)

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
            //   1. Favorites header + long-press-draggable pinned rows
            //      (only when there is an account AND at least one favorite)
            //   2. The main folder list with star toggles on each row
            // Both share a single LazyColumn so the scroll position behaves
            // naturally and there is no divider chrome breaking the feel.
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
                            modifier = Modifier.padding(start = 32.dp, top = 4.dp, bottom = 4.dp)
                        )
                    }

                    itemsIndexed(
                        favoriteFolders,
                        // ServerId is the natural cross-table identifier;
                        // using folder.id would force a full re-key on
                        // every server sync that REPLACES folders.
                        key = { _, f -> "fav-${f.serverId}" }
                    ) { index, folder ->
                        // Capture the row's actual measured height so the
                        // per-row "row height" used to compute the drop
                        // index matches whatever's currently laid out
                        // (long folder names wrap, padding differs, etc.).
                        // The default is `0f` because `Dp.toPx()` is
                        // `@Composable` and cannot be called inside the
                        // `remember { ... }` lambda (which is not a
                        // composable context) - any non-zero literal
                        // would be density-dependent pixels (e.g. `48f`
                        // on a 3x display is ~16dp, far from the ~48dp
                        // the original `48.dp.toPx()` produced). Using
                        // `0f` lets the `rowHeightPx > 0f` guard in
                        // `onDragEnd` be the single source of truth: a
                        // drag that fires before `onSizeChanged` reports
                        // the real measured height is treated as a
                        // no-op rather than snapping to a wrong index.
                        var rowHeightPx by remember { mutableStateOf(0f) }
                        val haptics = LocalHapticFeedback.current
                        val dragInfo = remember { mutableStateOf<DragInfo?>(null) }
                        // derivedStateOf so the boolean only flips on drag
                        // start/stop edge, not on every pixel of accumulatorY
                        // movement. Reading dragInfo.value directly in
                        // `.zIndex(...)` would re-compose the whole Row on
                        // every drag step; derivedStateOf collapses the
                        // high-frequency reads to a single recomposition per
                        // edge so the rest of the row stays still.
                        val isDragging by remember {
                            derivedStateOf { dragInfo.value?.fromIndex == index }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .onSizeChanged { rowHeightPx = it.height.toFloat() }
                                // Lift the dragged row above siblings so it
                                // paints over the divider and the next
                                // favourite instead of sliding under them.
                                .zIndex(if (isDragging) 1f else 0f)
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                                // Read dragInfo.value ONLY inside graphicsLayer
                                // - reading it in the composition phase would
                                // re-compose the whole Row on every pixel of
                                // movement; inside the layer only the cheap
                                // graphicsLayer apply runs.
                                .graphicsLayer {
                                    val current = dragInfo.value
                                    if (current?.fromIndex == index) {
                                        translationY = current.accumulatorY
                                        shadowElevation = 6.dp.toPx()
                                        alpha = 0.92f
                                    } else {
                                        translationY = 0f
                                        shadowElevation = 0f
                                        alpha = 1f
                                    }
                                }
                                .pointerInput(favoriteFolders.size) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            // Tactile cue that drag mode has
                                            // engaged (long-press passed).
                                            haptics.performHapticFeedback(
                                                HapticFeedbackType.LongPress
                                            )
                                            dragInfo.value = DragInfo(
                                                fromIndex = index,
                                                fromServerId = folder.serverId,
                                                accumulatorY = 0f
                                            )
                                        },
                                        onDrag = { _, dragAmount ->
                                            val cur = dragInfo.value
                                                ?: return@detectDragGesturesAfterLongPress
                                            dragInfo.value = cur.copy(
                                                accumulatorY = cur.accumulatorY + dragAmount.y
                                            )
                                        },
                                        onDragEnd = {
                                            val final = dragInfo.value ?: run {
                                                return@detectDragGesturesAfterLongPress
                                            }
                                            // Read the LIVE list, not the
                                            // close-over snapshot. If favourites
                                            // shrank to empty mid-drag, drop
                                            // the reorder cleanly.
                                            val live = favoriteFoldersState.value
                                            if (live.isEmpty()) {
                                                dragInfo.value = null
                                                return@detectDragGesturesAfterLongPress
                                            }
                                            val steps = if (rowHeightPx > 0f) {
                                                (final.accumulatorY / rowHeightPx).toInt()
                                            } else {
                                                0
                                            }
                                            // Bound the target so the user
                                            // cannot drag the dragged row
                                            // beyond either end of the
                                            // favourites list. From index K,
                                            // valid new indices are 0..N-1.
                                            val targetIndex = (final.fromIndex + steps)
                                                .coerceIn(0, live.lastIndex)
                                            if (targetIndex != final.fromIndex && account != null) {
                                                val newOrder = live.toMutableList()
                                                val moved = newOrder.removeAt(final.fromIndex)
                                                newOrder.add(
                                                    targetIndex.coerceAtMost(newOrder.size),
                                                    moved
                                                )
                                                onReorderFavorite(
                                                    account.id,
                                                    newOrder.map { it.serverId }
                                                )
                                            }
                                            dragInfo.value = null
                                        },
                                        onDragCancel = { dragInfo.value = null }
                                    )
                                },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Drag-handle icon: low-alpha visual contract
                            // that says "this row reorders on long-press".
                            // Kept low-alpha so it doesn't compete with
                            // the star for attention.
                            Icon(
                                imageVector = Icons.Default.DragHandle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    .copy(alpha = 0.5f)
                            )
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = stringResource(R.string.favorites_remove),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 4.dp)
                            )
                            Spacer(Modifier.width(16.dp))
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
                        // new mail") AND the star toggle for favoriting.
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
                                        // folders where priority order would
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
