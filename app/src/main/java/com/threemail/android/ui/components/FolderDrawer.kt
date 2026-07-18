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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Drafts
import androidx.compose.material.icons.filled.DragHandle
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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

/**
 * A node in the folder tree, produced by [buildFolderTree]. The flat
 * output list encodes the pre-order traversal so [LazyColumn] receives
 * one stable item per node.
 */
private data class FolderNode(
    val folder: MailFolder,
    val depth: Int,
    val hasChildren: Boolean,
    val isExpanded: Boolean
)

/**
 * Convert a flat folder list into a depth-sorted tree flattened back to
 * pre-order. Parent-child relationships are inferred from `serverId` by
 * detecting the IMAP hierarchy separator (typically `.` or `/`).
 *
 * `expandedServerIds` controls which parent nodes show their children;
 * collapsed parents are emitted as a single node.
 */
private fun buildFolderTree(
    folders: List<MailFolder>,
    expandedServerIds: Set<String>
): List<FolderNode> {
    if (folders.isEmpty()) return emptyList()

    val separator = detectSeparator(folders)

    // Compute parent-child relationships
    val serverIdSet = folders.mapTo(HashSet()) { it.serverId }
    val childrenByParent = mutableMapOf<String, MutableList<MailFolder>>()

    for (folder in folders) {
        val parentId = parentServerId(folder.serverId, separator)
        if (parentId != null && parentId in serverIdSet) {
            childrenByParent.getOrPut(parentId) { mutableListOf() }.add(folder)
        }
    }

    // A folder is a root when its OWN parent does not exist in the folder
    // list (or it has no parent at all).  Importantly this correctly keeps
    // e.g. "INBOX" as a root even though it IS a parent of "INBOX.Sent";
    // the old filter `it.serverId !in allParentIds` removed those folders
    // entirely.
    val rootFolders = folders.filter { folder ->
        parentServerId(folder.serverId, separator) !in serverIdSet
    }.toMutableList()

    // Within each level sort by: special folders (by type ordinal) first,
    // then alphabetically by name, so INBOX always rises to the top.
    val levelComparator = compareBy<MailFolder>(
        { it.type.ordinal },
        { it.name.lowercase() }
    )
    rootFolders.sortWith(levelComparator)
    childrenByParent.values.forEach { it.sortWith(levelComparator) }

    val result = mutableListOf<FolderNode>()

    fun addNode(folder: MailFolder, depth: Int) {
        val hasChildren = childrenByParent.containsKey(folder.serverId)
        val isExpanded = folder.serverId in expandedServerIds
        result.add(FolderNode(folder, depth, hasChildren, isExpanded))
        if (isExpanded && hasChildren) {
            for (child in childrenByParent[folder.serverId].orEmpty()) {
                addNode(child, depth + 1)
            }
        }
    }

    for (root in rootFolders) {
        addNode(root, 0)
    }

    return result
}

/**
 * Detect the IMAP folder hierarchy separator by examining the difference
 * between `serverId` (full path) and `name` (leaf). Falls back to `.`
 * when nothing can be inferred.
 */
private fun detectSeparator(folders: List<MailFolder>): Char {
    for (folder in folders) {
        if (folder.serverId != folder.name && folder.serverId.endsWith(folder.name)) {
            val sep = folder.serverId[folder.serverId.length - folder.name.length - 1]
            return sep
        }
    }
    // Try common IMAP separators
    for (sep in listOf('.', '/', '\\\\', '-', '_')) {
        if (folders.any { it.serverId.contains(sep) }) return sep
    }
    return '.'
}

/**
 * Return the parent's `serverId` by stripping the last path component.
 * `null` means the folder is at the root of the hierarchy.
 */
private fun parentServerId(serverId: String, separator: Char): String? {
    val idx = serverId.lastIndexOf(separator)
    if (idx > 0) {
        return serverId.substring(0, idx)
    }
    return null
}

/**
 * Indent increment per tree depth level (in density-independent pixels).
 * At depth 0 there is no extra indent; depth 1 adds 20.dp, etc.
 */
private val TREE_INDENT_PER_LEVEL = 20.dp

/**
 * Size of the expand/collapse chevron icon (or its spacer placeholder).
 */
private val CHEVRON_SIZE = 20.dp

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
    // Split folders into favorites + main list.
    val favoriteFolders = remember(folders) { folders.filter { it.isFavorite } }
    val normalFolders = remember(folders) { folders.filterNot { it.isFavorite } }
    val favoriteFoldersState = rememberUpdatedState(favoriteFolders)

    // --- Tree expand/collapse state ---
    val expandedServerIds = remember { mutableStateOf<Set<String>>(emptySet()) }
    val expanded = expandedServerIds.value

    // Initialise: all parent nodes start expanded so the full hierarchy
    // is visible on first open.
    LaunchedEffect(normalFolders) {
        if (expanded.isEmpty() && normalFolders.isNotEmpty()) {
            val sep = detectSeparator(normalFolders)
            expandedServerIds.value = buildSet {
                for (folder in normalFolders) {
                    parentServerId(folder.serverId, sep)?.let { add(it) }
                }
            }
        }
    }

    val treeNodes = remember(normalFolders, expanded) {
        buildFolderTree(normalFolders, expanded)
    }

    val onToggleFolderExpand: (String) -> Unit = { serverId ->
        expandedServerIds.value = if (serverId in expanded) {
            expanded - serverId
        } else {
            expanded + serverId
        }
    }

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

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                // ── Favorites section ──
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
                        key = { _, f -> "fav-${f.serverId}" }
                    ) { index, folder ->
                        var rowHeightPx by remember { mutableStateOf(0f) }
                        val haptics = LocalHapticFeedback.current
                        val dragInfo = remember { mutableStateOf<DragInfo?>(null) }
                        val isDragging by remember {
                            derivedStateOf { dragInfo.value?.fromIndex == index }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .onSizeChanged { rowHeightPx = it.height.toFloat() }
                                .zIndex(if (isDragging) 1f else 0f)
                                .padding(horizontal = 12.dp, vertical = 4.dp)
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
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
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
                                            val final = dragInfo.value ?: return@detectDragGesturesAfterLongPress
                                            val live = favoriteFoldersState.value
                                            if (live.isEmpty()) {
                                                dragInfo.value = null
                                                return@detectDragGesturesAfterLongPress
                                            }
                                            val steps = if (rowHeightPx > 0f) {
                                                (final.accumulatorY / rowHeightPx).toInt()
                                            } else 0
                                            val targetIndex = (final.fromIndex + steps)
                                                .coerceIn(0, live.lastIndex)
                                            if (targetIndex != final.fromIndex) {
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

                // ── Tree folder section ──
                items(treeNodes, key = { "tree-${it.folder.id}" }) { node ->
                    FolderTreeRow(
                        node = node,
                        isSelected = node.folder.id == selectedFolder?.id,
                        onFolderClick = onFolderClick,
                        onToggleExpand = { onToggleFolderExpand(node.folder.serverId) },
                        onToggleFavorite = onToggleFavorite
                    )
                }
            }

            HorizontalDivider()

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

// ─────────────────────────────────────────────────────────────────────────────
//  Tree row composable
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun FolderTreeRow(
    node: FolderNode,
    isSelected: Boolean,
    onFolderClick: (MailFolder) -> Unit,
    onToggleExpand: () -> Unit,
    onToggleFavorite: (MailFolder) -> Unit
) {
    val indentPadding = node.depth * TREE_INDENT_PER_LEVEL
    val containerColor = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else {
        Color.Transparent
    }
    val contentColor = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface
    val iconColor = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
    val badgeColor = if (isSelected) Color.White else MaterialTheme.colorScheme.primary

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            // The indentation sit at the very left, before the row chrome.
            .then(
                if (isSelected) {
                    Modifier
                        .clip(RoundedCornerShape(28.dp))
                        .background(containerColor)
                } else {
                    Modifier.background(containerColor)
                }
            )
            .clickable { onFolderClick(node.folder) }
            .padding(
                start = 8.dp + indentPadding,
                end = 8.dp,
                top = 10.dp,
                bottom = 10.dp
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // ── Chevron (or spacer for alignment) ──
        if (node.hasChildren) {
            Icon(
                imageVector = Icons.Default.ExpandMore,
                contentDescription = if (node.isExpanded) "Collapse" else "Expand",
                modifier = Modifier
                    .size(CHEVRON_SIZE)
                    .clickable { onToggleExpand() }
                    .rotate(if (node.isExpanded) 0f else -90f),
                tint = iconColor.copy(alpha = 0.55f)
            )
        } else {
            Spacer(Modifier.width(CHEVRON_SIZE))
        }
        Spacer(Modifier.width(8.dp))

        // ── Folder type icon ──
        Box(
            modifier = Modifier.size(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = iconFor(node.folder.type),
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(Modifier.width(12.dp))

        // ── Folder name ──
        Text(
            text = node.folder.name,
            style = MaterialTheme.typography.bodyMedium,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        // ── Unread count + favourite star ──
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (node.folder.unreadCount > 0) {
                Text(
                    text = node.folder.unreadCount.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    color = badgeColor
                )
                Spacer(Modifier.width(8.dp))
            }
            IconButton(onClick = { onToggleFavorite(node.folder) }) {
                Icon(
                    imageVector = if (node.folder.isFavorite) Icons.Default.Star
                    else Icons.Outlined.StarBorder,
                    contentDescription = stringResource(
                        if (node.folder.isFavorite) R.string.favorites_remove
                        else R.string.favorites_add
                    ),
                    tint = when {
                        isSelected -> Color.White
                        node.folder.isFavorite -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Footer helpers
// ─────────────────────────────────────────────────────────────────────────────

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
