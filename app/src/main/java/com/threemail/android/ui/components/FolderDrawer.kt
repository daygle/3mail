package com.threemail.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.filled.AllInbox
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Drafts
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Report
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.painterResource
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
    for (sep in listOf('.', '/', '\\', '-', '_')) {
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
    accounts: List<Account>,
    folders: List<MailFolder>,
    selectedFolder: MailFolder?,
    unifiedSelected: Boolean = false,
    onUnifiedInbox: () -> Unit = {},
    onFolderClick: (MailFolder) -> Unit,
    onSelectAccount: (Account) -> Unit,
    onToggleFavorite: (MailFolder) -> Unit,
    onReorderFavorite: (accountId: Long, serverIds: List<String>) -> Unit,
    onManageAccounts: () -> Unit,
    onManageFolders: () -> Unit = {},
    onSettings: () -> Unit,
    onCalendar: () -> Unit,
    onSync: () -> Unit
) {
    // Two columns of the same source data:
    //  - `favoriteFolders`: the user's pinned shortcuts, in the order
    //    they've dragged them to. Drawn at the top of the drawer.
    //  - `treeFolders`: every visible folder laid out as an IMAP-hierarchy
    //    tree. **Includes** favorites, so starring a folder pins it at the
    //    top *and* keeps it in the canonical list (the user can still find
    //    it nested under its parent). Hidden folders are omitted from both.
    val visibleFolders = remember(folders) { folders.filterNot { it.isHidden } }
    val favoriteFolders = remember(visibleFolders) { visibleFolders.filter { it.isFavorite } }
    val treeFolders = remember(visibleFolders) { visibleFolders }
    val favoriteFoldersState = rememberUpdatedState(favoriteFolders)
    // Capture `account` through rememberUpdatedState so any closure that
    // fires while the user is mid-account-switch (after a new account
    // composes but before the LaunchedEffect below resets the edit-mode
    // flag) uses the *current* account.id rather than the one bound
    // at the closure's original composition.
    val accountState = rememberUpdatedState(account)

    // Whether the header is expanded to show the configured-account list
    // (tapping the header / its chevron toggles this) instead of folders.
    var showAccounts by remember { mutableStateOf(false) }

    // Local-only UI affordance: while true, every favorite row swaps
    // its drag handle for explicit ↑ / ↓ IconButtons so reorder is
    // reachable without the long-press drag gesture. The chip in the
    // FAVORITES header reads "Edit" when false and "Done" when true.
    // Reset on account change so a half-finished reorder doesn't carry
    // over to the next account the user opens.
    var isEditingFavorites by remember { mutableStateOf(false) }
    LaunchedEffect(account?.id) {
        isEditingFavorites = false
    }

    // --- Tree expand/collapse state ---
    val expandedServerIds = remember { mutableStateOf<Set<String>>(emptySet()) }
    val expanded = expandedServerIds.value

    // Initialise: all parent nodes start expanded so the full hierarchy
    // is visible on first open.
    LaunchedEffect(treeFolders) {
        if (expanded.isEmpty() && treeFolders.isNotEmpty()) {
            val sep = detectSeparator(treeFolders)
            expandedServerIds.value = buildSet {
                for (folder in treeFolders) {
                    parentServerId(folder.serverId, sep)?.let { add(it) }
                }
            }
        }
    }

    val treeNodes = remember(treeFolders, expanded) {
        buildFolderTree(treeFolders, expanded)
    }

    // Lookup set keyed by folder id. The tree section needs to know which
    // of its rows are duplicated in the favorites shortcut above so it can
    // suppress the primary "pill" highlight on the duplicated row (the
    // favorites chip already carries the one true highlight).
    val favoriteFolderIds = remember(favoriteFolders) {
        favoriteFolders.mapTo(HashSet()) { it.id }
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
                            .clickable { showAccounts = !showAccounts },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (showAccounts) {
                            // Expanded state: header collapses to a plain
                            // "Account list" title, matching the account picker.
                            Text(
                                text = stringResource(R.string.account_list),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(avatarColorFor(account.email)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_launcher_foreground),
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(28.dp)
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
                        }
                        Icon(
                            imageVector = Icons.Default.ExpandMore,
                            contentDescription = stringResource(R.string.accounts),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.rotate(if (showAccounts) 180f else 0f)
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

            if (showAccounts) {
                // ── Account switcher list ──
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    items(accounts, key = { "acct-${it.id}" }) { acct ->
                        AccountRow(
                            account = acct,
                            isSelected = acct.id == account?.id,
                            onClick = {
                                if (acct.id != account?.id) onSelectAccount(acct)
                                showAccounts = false
                            }
                        )
                    }
                }
            } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                // ── Unified inbox (only meaningful with more than one account) ──
                if (accounts.size > 1) {
                    item(key = "unified-inbox") {
                        UnifiedInboxRow(
                            isSelected = unifiedSelected,
                            onClick = onUnifiedInbox
                        )
                    }
                    item(key = "unified-divider") {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 6.dp),
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                        )
                    }
                }

                // ── Favorites section ──
                if (account != null && favoriteFolders.isNotEmpty()) {
                    item(key = "favorites-header") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 32.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.favorites_header),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            // "Edit" / "Done" chip. Edit enters reorder
                            // mode (each row surfaces ↑ / ↓ buttons);
                            // Done exits and restores the drag handle.
                            // UI-only state - never persisted.
                            TextButton(onClick = { isEditingFavorites = !isEditingFavorites }) {
                                Text(
                                    text = stringResource(
                                        if (isEditingFavorites) R.string.favorites_done_action
                                        else R.string.favorites_edit_action
                                    )
                                )
                            }
                        }
                    }

                    itemsIndexed(
                        favoriteFolders,
                        // Include account.id in the key so the per-row menu
                        // state and drag accumulator cannot leak across
                        // account switches (two accounts can legitimately have
                        // the same serverId, e.g. a shared INBOX).
                        key = { _, f -> "fav-${account.id}-${f.serverId}" }
                    ) { index, folder ->
                        var rowHeightPx by remember { mutableStateOf(0f) }
                        val haptics = LocalHapticFeedback.current
                        val dragInfo = remember { mutableStateOf<DragInfo?>(null) }
                        val isDragging by remember {
                            derivedStateOf { dragInfo.value?.fromIndex == index }
                        }
                        // Mirror the tree-row selected pill so a favorite folder
                        // that happens to be the current mailbox is also
                        // highlighted here - users can otherwise lose track of
                        // which row corresponds to the currently-open folder.
                        val isSelected = folder.id == selectedFolder?.id
                        val contentTint = if (isSelected) {
                            Color.White
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        // Per-row overflow state. Long-press on the row body
                        // opens the "Remove from favourites" menu - mirrors
                        // FolderTreeRow's pattern two sections below so the
                        // two list types feel consistent.
                        var showMenu by remember { mutableStateOf(false) }

                        Box {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .onSizeChanged { rowHeightPx = it.height.toFloat() }
                                    .zIndex(if (isDragging) 1f else 0f)
                                    .padding(horizontal = 12.dp, vertical = 4.dp)
                                    .then(
                                        if (isSelected) {
                                            Modifier
                                                .clip(RoundedCornerShape(28.dp))
                                                .background(MaterialTheme.colorScheme.primary)
                                        } else {
                                            Modifier
                                        }
                                    )
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
                                    // Tap selects; long-press opens the
                                    // remove-favourite menu. Drag-reorder
                                    // lives on the DragHandle icon below.
                                    .combinedClickable(
                                        onClick = { onFolderClick(folder) },
                                        onLongClick = {
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            showMenu = true
                                        }
                                    ),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Edit mode swaps the drag handle for an
                                // inert spacer of equal width so the folder
                                // name stays aligned across modes; explicit
                                // ↑ / ↓ buttons render at the row's end.
                                if (isEditingFavorites) {
                                    Spacer(modifier = Modifier.size(CHEVRON_SIZE))
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.DragHandle,
                                        contentDescription = stringResource(R.string.favorites_drag_handle),
                                        tint = contentTint.copy(alpha = 0.5f),
                                        // Pin the drag handle to CHEVRON_SIZE so
                                        // folder names stay byte-identical across
                                        // modes; the inert Spacer in edit mode is
                                        // the same constant.
                                        modifier = Modifier
                                            .size(CHEVRON_SIZE)
                                            // Tap is absorbed here so it doesn't
                                            // fall through to the Row's selection
                                            // (a tap on the icon would otherwise
                                            // navigate into the folder). The
                                            // ripple marks the icon as
                                            // drag-only.
                                            .clickable {}
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
                                            }
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    text = folder.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = contentTint,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                if (isEditingFavorites) {
                                    // Look up the row's live index every
                                    // recomposition so successive taps still
                                    // move against the post-reorder order,
                                    // not the original `index` captured by
                                    // itemsIndexed.
                                    val live = favoriteFoldersState.value
                                    val liveIndex = live.indexOfFirst { it.serverId == folder.serverId }
                                    val canMoveUp = liveIndex > 0
                                    val canMoveDown = liveIndex < live.lastIndex
                                    IconButton(
                                        onClick = {
                                            val current = favoriteFoldersState.value
                                            val currentIndex = current.indexOfFirst { it.serverId == folder.serverId }
                                            if (currentIndex > 0) {
                                                val newOrder = current.toMutableList()
                                                val moved = newOrder.removeAt(currentIndex)
                                                newOrder.add(currentIndex - 1, moved)
                                                onReorderFavorite(
                                                    accountState.value?.id ?: return@IconButton,
                                                    newOrder.map { it.serverId }
                                                )
                                            }
                                        },
                                        enabled = canMoveUp
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.KeyboardArrowUp,
                                            contentDescription = stringResource(R.string.favorites_move_up)
                                        )
                                    }
                                    Spacer(Modifier.width(4.dp))
                                    IconButton(
                                        onClick = {
                                            val current = favoriteFoldersState.value
                                            val currentIndex = current.indexOfFirst { it.serverId == folder.serverId }
                                            if (currentIndex < current.lastIndex) {
                                                val newOrder = current.toMutableList()
                                                val moved = newOrder.removeAt(currentIndex)
                                                newOrder.add(currentIndex + 1, moved)
                                                onReorderFavorite(
                                                    accountState.value?.id ?: return@IconButton,
                                                    newOrder.map { it.serverId }
                                                )
                                            }
                                        },
                                        enabled = canMoveDown
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.KeyboardArrowDown,
                                            contentDescription = stringResource(R.string.favorites_move_down)
                                        )
                                    }
                                }
                            }
                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Text(stringResource(R.string.favorites_remove))
                                    },
                                    onClick = {
                                        showMenu = false
                                        onToggleFavorite(folder)
                                    }
                                )
                            }
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
                    // Favorited folders render twice (pinned shortcut above
                    // AND this tree row). When the user has one open, both
                    // rows would otherwise paint the primary "pill"
                    // highlight at the same time - a TalkBack user hears
                    // "selected" twice and a sighted user reads two
                    // selected items. The favorites chip already conveys
                    // "this is the active folder", so suppress the tree
                    // row's pill when the folder is in the favorites set;
                    // the pinned shortcut above carries the one true
                    // highlight instead.
                    val isFavorited = node.folder.id in favoriteFolderIds
                    val showPillHighlight = node.folder.id == selectedFolder?.id && !isFavorited
                    // Per-row state for the overflow menu surfaced by long-
                    // press. Each item gets its own SnapshotState, so opening
                    // a menu on one row never opens one on its neighbours.
                    var showMenu by remember { mutableStateOf(false) }
                    Box {
                        FolderTreeRow(
                            node = node,
                            isSelected = showPillHighlight,
                            onFolderClick = onFolderClick,
                            onLongClick = { showMenu = true },
                            onToggleExpand = { onToggleFolderExpand(node.folder.serverId) }
                        )
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(
                                            if (node.folder.isFavorite) R.string.favorites_remove
                                            else R.string.favorites_add
                                        )
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    onToggleFavorite(node.folder)
                                }
                            )
                        }
                    }
                }
            }
            }

            HorizontalDivider()

            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (showAccounts) {
                    // Account-list footer mirrors the account picker: sync
                    // everything at once, or jump to the add-account flow.
                    FooterItem(
                        icon = Icons.Default.Refresh,
                        label = stringResource(R.string.footer_sync_all),
                        onClick = onSync
                    )
                    FooterItem(
                        icon = Icons.Default.ManageAccounts,
                        label = stringResource(R.string.add_account),
                        onClick = onManageAccounts
                    )
                } else if (account != null) {
                    FooterItem(
                        icon = Icons.Default.Refresh,
                        label = stringResource(R.string.footer_refresh),
                        onClick = onSync
                    )
                    FooterItem(
                        icon = Icons.Default.FolderOpen,
                        label = stringResource(R.string.footer_manage_folders),
                        onClick = onManageFolders
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
//  Unified inbox row
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun UnifiedInboxRow(
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val contentColor = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface
    val iconColor = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .then(
                if (isSelected) {
                    Modifier
                        .clip(RoundedCornerShape(28.dp))
                        .background(MaterialTheme.colorScheme.primary)
                } else {
                    Modifier
                }
            )
            .clickable { onClick() }
            .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.AllInbox,
            contentDescription = null,
            tint = iconColor,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = stringResource(R.string.all_inboxes),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = contentColor
        )
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
    onLongClick: (MailFolder) -> Unit,
    onToggleExpand: () -> Unit
) {
    val indentPadding = TREE_INDENT_PER_LEVEL * node.depth
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
            // Tap selects the mail folder; long-press opens the per-row
            // overflow menu (Add to / Remove from Favorites). Compose
            // resolves the tap-vs-long-press race correctly via
            // combinedClickable - no conflict with surrounding pointerInputs.
            .combinedClickable(
                onClick = { onFolderClick(node.folder) },
                onLongClick = { onLongClick(node.folder) }
            )
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

        // ── "Also in Favorites" cue. Favorited folders render in two places
        //   (the pinned shortcut section above AND this tree row); a small
        //   star chip on the tree row makes that relationship legible so
        //   a user who missed the section header doesn't read the duplicate
        //   as a bug. The icon uses primary tint so it shares semantics
        //   with the section's `FAVORITES` header colour. ──
        if (node.folder.isFavorite) {
            Icon(
                imageVector = Icons.Default.Star,
                contentDescription = stringResource(R.string.favorites),
                tint = if (isSelected) Color.White.copy(alpha = 0.85f)
                else MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(start = 6.dp, end = 2.dp)
                    .size(14.dp)
            )
        }

        // ── Unread count badge only. The per-row favourite star toggle
        //   has been removed from the visible chrome; long-press the row
        //   to add or remove the favourite via the overflow menu. ──
        if (node.folder.unreadCount > 0) {
            Text(
                text = node.folder.unreadCount.toString(),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = badgeColor
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Account switcher row
// ─────────────────────────────────────────────────────────────────────────────

/**
 * One configured account in the header's expanded account list. The active
 * account gets the same rounded primary "pill" highlight the selected folder
 * uses, so the current account reads at a glance. Tapping a different row
 * switches the active account (and, upstream, reloads its folders).
 */
@Composable
private fun AccountRow(
    account: Account,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val contentColor = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface
    val subColor = if (isSelected) {
        Color.White.copy(alpha = 0.85f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .then(
                if (isSelected) {
                    Modifier
                        .clip(RoundedCornerShape(28.dp))
                        .background(MaterialTheme.colorScheme.primary)
                } else {
                    Modifier
                }
            )
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(avatarColorFor(account.email)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(22.dp)
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 16.dp)
        ) {
            Text(
                text = account.email.substringAfter("@"),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = account.email,
                style = MaterialTheme.typography.bodyMedium,
                color = subColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
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

// Module-internal so the inbox folder picker can reuse the same glyph
// mapping the drawer uses for its tree rows; keeping the mapping in one
// place avoids the picker showing a generic folder icon for Sent / Trash /
// Spam rows that the rest of the app already renders with a type-specific
// glyph.
internal fun iconFor(type: FolderType): ImageVector = when (type) {
    FolderType.Inbox -> Icons.Default.Inbox
    FolderType.SENT -> Icons.AutoMirrored.Filled.Send
    FolderType.DRAFTS -> Icons.Default.Drafts
    FolderType.TRASH -> Icons.Default.Delete
    FolderType.ARCHIVE, FolderType.ALL_MAIL -> Icons.Default.Archive
    FolderType.SPAM -> Icons.Outlined.Report
    FolderType.STARRED -> Icons.Default.Star
    else -> Icons.AutoMirrored.Filled.Label
}
