package com.threemail.android.ui.screens.inbox

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MarkEmailRead
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.outlined.Report
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.threemail.android.ui.theme.appTopBarColors
import com.threemail.android.R
import com.threemail.android.data.repository.UndoKind
import com.threemail.android.data.settings.MessageDensity
import com.threemail.android.data.settings.SwipeAction
import com.threemail.android.data.settings.TopBarItemId
import com.threemail.android.ui.screens.settings.SettingsViewModel
import com.threemail.android.domain.model.FolderType
import com.threemail.android.domain.model.MailMessage
import com.threemail.android.ui.components.EmptyState
import com.threemail.android.ui.components.FolderDrawerContent
import com.threemail.android.ui.components.LoadingIndicator
import com.threemail.android.ui.components.MailListItem
import com.threemail.android.ui.components.iconFor
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(
    viewModel: InboxViewModel,
    onNavigateToCompose: () -> Unit,
    onNavigateToSearch: () -> Unit,
    onNavigateToAccounts: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToMessage: (Long) -> Unit,
    onNavigateToAddAccount: () -> Unit,
    onNavigateToManageFolders: () -> Unit = {}
) {
    val state by viewModel.uiState.collectAsState()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    // Per-screen top-bar customisation. SettingsViewModel is Hilt-scoped to this
    // nav entry alongside InboxViewModel and reads from the same singleton
    // DataStore, so collecting here is cheap. The hidden set is forwarded into
    // InboxTopBar so the affected actions move from the bar into the overflow
    // popup and remain reachable.
    val settingsViewModel: SettingsViewModel = hiltViewModel()
    val appSettings by settingsViewModel.settings.collectAsState()
    val hiddenTopBarItems = appSettings.hiddenTopBarItems

    // Undo snackbar for deferred archive/delete/move/spam actions.
    val snackbarHostState = remember { SnackbarHostState() }
    val undoPending by viewModel.undoPending.collectAsState()
    val undoActionLabel = stringResource(R.string.action_undo)
    val archivedLabel = stringResource(R.string.snackbar_archived)
    val deletedLabel = stringResource(R.string.snackbar_deleted)
    val movedLabel = stringResource(R.string.snackbar_moved)
    val spamLabel = stringResource(R.string.snackbar_spam)
    LaunchedEffect(undoPending) {
        val pending = undoPending ?: return@LaunchedEffect
        val message = when (pending.kind) {
            UndoKind.ARCHIVE -> archivedLabel
            UndoKind.DELETE -> deletedLabel
            UndoKind.MOVE -> movedLabel
            UndoKind.SPAM -> spamLabel
            // Batch spam reuses the per-message spam label; the Undo affordance
            // is identical and a count-aware variant would only matter if the
            // snackbar wanted pluralisation differences.
            UndoKind.SPAM_BATCH -> spamLabel
        }
        val result = snackbarHostState.showSnackbar(
            message = message,
            actionLabel = undoActionLabel,
            withDismissAction = true
        )
        if (result == SnackbarResult.ActionPerformed) {
            viewModel.undo()
        }
    }

    val recoverableAuthLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        viewModel.onRecoverableAuthHandled()
        viewModel.retryAfterRecoverableAuth()
    }

    // Confirmation dialog state for the destructive bulk spam action. Lives
    // at top-level so the dialog overlays both the Scaffold and the modal
    // navigation drawer.
    var confirmSpam by remember { mutableStateOf(false) }

    // Confirmation dialog for emptying the Trash folder. Only active when the
    // selected folder is the Trash folder (checked before setting to true).
    var confirmEmptyTrash by remember { mutableStateOf(false) }

    // Folder-picker bottom sheet for bulk Move, sourced from
    // [InboxViewModel.getMoveTargetFolders]. The state is read here so the
    // sheet is rendered fresh each time it opens - the chosen folder set
    // doesn't change between open/close cycles but reading it lazily keeps
    // the picker consistent with whatever folder the user has selected.
    var showMovePicker by remember { mutableStateOf(false) }

    // Snackbar messages for empty-trash result feedback. The success line is a
    // plural resolved from the app resources inside the collector (the count is
    // only known there); the failure line is read at composition time.
    val context = LocalContext.current
    val emptyTrashFailureMessage = stringResource(R.string.empty_trash_failure)

    // Collect empty-trash events and show a snackbar with the result.
    // Uses Unit as the key so the collector lives for the screen's lifetime.
    LaunchedEffect(Unit) {
        viewModel.emptyTrashEvents.collect { event ->
            when (event) {
                is InboxViewModel.EmptyTrashEvent.Success -> {
                    val msg = context.resources.getQuantityString(
                        R.plurals.empty_trash_success,
                        event.expungedCount,
                        event.expungedCount
                    )
                    snackbarHostState.showSnackbar(msg)
                }
                InboxViewModel.EmptyTrashEvent.Failure -> {
                    snackbarHostState.showSnackbar(emptyTrashFailureMessage)
                }
            }
        }
    }

    LaunchedEffect(state.recoverableAuthIntent) {
        state.recoverableAuthIntent?.let { intent -> recoverableAuthLauncher.launch(intent) }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        // The contextual selection bar owns the back gesture; keep the drawer
        // swipe disabled while selecting so a stray edge-swipe doesn't yank it open.
        gesturesEnabled = !state.selectionMode,
        drawerContent = {
            FolderDrawerContent(
                account = state.selectedAccount,
                accounts = state.accounts,
                folders = state.folders,
                selectedFolder = state.selectedFolder,
                unifiedSelected = state.unifiedInbox,
                onUnifiedInbox = {
                    viewModel.selectUnifiedInbox()
                    scope.launch { drawerState.close() }
                },
                onFolderClick = { folder ->
                    viewModel.selectFolder(folder)
                    scope.launch { drawerState.close() }
                },
                onSelectAccount = { account -> viewModel.selectAccount(account) },
                onToggleFavorite = { folder -> viewModel.toggleFavorite(folder) },
                onReorderFavorite = { accountId, serverIds ->
                    viewModel.reorderFavorites(accountId, serverIds)
                },
                onManageAccounts = {
                    scope.launch { drawerState.close() }
                    onNavigateToAccounts()
                },
                onManageFolders = {
                    scope.launch { drawerState.close() }
                    onNavigateToManageFolders()
                },
                onSettings = {
                    scope.launch { drawerState.close() }
                    onNavigateToSettings()
                },
                onSync = {
                    scope.launch { drawerState.close() }
                    viewModel.sync()
                }
            )
        }
    ) {
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                if (state.selectionMode) {
                    // Move is unavailable in the unified inbox (cross-account
                    // IMAP MOVE is not supported) and when the current account
                    // has only one visible folder (the source itself). The
                    // button stays in the layout so its position doesn't pop
                    // in/out, but the tap is gated by [canMove].
                    val visibleFolderCount = state.folders.count { !it.isHidden }
                    val canMove = !state.unifiedInbox && visibleFolderCount >= 2
                    SelectionTopBar(
                        count = state.selectedIds.size,
                        canMove = canMove,
                        onClear = { viewModel.clearSelection() },
                        onSelectAll = { viewModel.selectAll() },
                        onMarkRead = { viewModel.markSelectedRead(true) },
                        onMarkUnread = { viewModel.markSelectedRead(false) },
                        onArchive = { viewModel.archiveSelected() },
                        onDelete = { viewModel.deleteSelected() },
                        onMarkSpam = { confirmSpam = true },
                        onMove = if (canMove) ({ showMovePicker = true }) else null,
                        moveDisabledReason = when {
                            state.unifiedInbox -> stringResource(R.string.move_unified_unavailable)
                            visibleFolderCount < 2 -> stringResource(R.string.move_unavailable)
                            else -> null
                        }
                    )
                } else {
                    val isTrashFolder = state.selectedFolder?.type == FolderType.TRASH
                    InboxTopBar(
                        title = when {
                            state.unifiedInbox -> stringResource(R.string.unified_inbox)
                            else -> state.selectedFolder?.name ?: stringResource(R.string.app_name)
                        },
                        scrollBehavior = scrollBehavior,
                        onOpenDrawer = { scope.launch { drawerState.open() } },
                        onSearch = onNavigateToSearch,
                        onSync = { viewModel.sync() },
                        onMarkAllRead = { viewModel.markAllRead() },
                        showEmptyTrash = isTrashFolder,
                        onEmptyTrash = { confirmEmptyTrash = true },
                        hidden = hiddenTopBarItems
                    )
                }
            },
            floatingActionButton = {
                if (!state.selectionMode) {
                    ExtendedFloatingActionButton(
                        onClick = onNavigateToCompose,
                        icon = { Icon(Icons.Default.Edit, contentDescription = null) },
                        text = { Text(stringResource(R.string.compose)) }
                    )
                }
            }
        ) { padding ->
            Box(modifier = Modifier.padding(padding)) {
                when {
                    state.accounts.isEmpty() -> EmptyState(
                        title = stringResource(R.string.no_accounts),
                        subtitle = stringResource(R.string.add_account_prompt),
                        actionLabel = stringResource(R.string.empty_state_add_account),
                        onAction = onNavigateToAddAccount
                    )
                    state.messages.isEmpty() && state.isSyncing -> LoadingIndicator()
                    else -> {
                        PullToRefreshBox(
                            isRefreshing = state.isSyncing,
                            onRefresh = { viewModel.sync() },
                            modifier = Modifier.fillMaxSize()
                        ) {
                            if (state.messages.isEmpty()) {
                                EmptyState(
                                    title = stringResource(R.string.no_messages),
                                    subtitle = stringResource(R.string.pull_to_refresh_hint)
                                )
                            } else {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(bottom = 88.dp)
                                ) {
                                    items(state.messages, key = { it.id }) { message ->
                                        val selected = message.id in state.selectedIds
                                        SwipeableMailRow(
                                            message = message,
                                            selectionMode = state.selectionMode,
                                            selected = selected,
                                            swipeRightAction = state.swipeRightAction,
                                            swipeLeftAction = state.swipeLeftAction,
                                            density = state.messageDensity,
                                            previewLines = state.previewLines,
                                            onArchive = { viewModel.archive(message) },
                                            onDelete = { viewModel.delete(message) },
                                            onToggleRead = { viewModel.markAsRead(message, !message.isRead) },
                                            onMarkSpam = { viewModel.markSpam(message) },
                                            onClick = {
                                                if (state.selectionMode) {
                                                    viewModel.toggleSelection(message)
                                                } else {
                                                    viewModel.markAsRead(message, true)
                                                    onNavigateToMessage(message.id)
                                                }
                                            },
                                            onLongClick = { viewModel.toggleSelection(message) }
                                        )
                                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmSpam) {
        AlertDialog(
            onDismissRequest = { confirmSpam = false },
            title = { Text(stringResource(R.string.confirm_mark_spam_title)) },
            // The destructive spam move is hard to recover from because the
            // server may train on it; surface the count so the user signs
            // off on the right batch.
            text = { Text(pluralStringResource(R.plurals.confirm_mark_spam_body, state.selectedIds.size, state.selectedIds.size)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmSpam = false
                    viewModel.markSpamSelected()
                }) { Text(stringResource(R.string.mark_as_spam)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmSpam = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (confirmEmptyTrash) {
        AlertDialog(
            onDismissRequest = { confirmEmptyTrash = false },
            title = { Text(stringResource(R.string.empty_trash_confirm_title)) },
            text = { Text(stringResource(R.string.empty_trash_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmEmptyTrash = false
                    viewModel.emptyTrash()
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmEmptyTrash = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // Folder picker for bulk Move. `ModalBottomSheet` is the right surface
    // for a selectable list that can run to dozens of accounts/folders on
    // deep IMAP hierarchies - an AlertDialog would clip, a full-screen picker
    // is overkill. The folder list is read eagerly from the ViewModel so a
    // mid-sheet folder refresh doesn't cause the picker contents to shift
    // underneath the user's finger.
    //
    // The sheet's state is [SkipPartiallyExpanded] because the picker is the
    // only content worth showing once the row is opened - there's no peek
    // height that adds information. Hit-the-edge dismiss returns to the
    // selection bar without firing `onMove`.
    if (showMovePicker) {
        val targetFolders = viewModel.getMoveTargetFolders()
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showMovePicker = false },
            sheetState = sheetState
        ) {
            Text(
                text = pluralStringResource(R.plurals.move_dialog_title, state.selectedIds.size, state.selectedIds.size),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
            )
            if (targetFolders.isEmpty()) {
                Text(
                    text = stringResource(R.string.move_no_targets),
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        // Cap the sheet's scrollable region so a deep IMAP
                        // tree doesn't push the system back-gesture region
                        // off-screen; the picker is intentionally compact.
                        .heightIn(max = 480.dp)
                ) {
                    items(targetFolders, key = { it.id }) { folder ->
                        ListItem(
                            headlineContent = { Text(folder.name) },
                            // Reuse the drawer's per-type folder icon so the
                            // picker stays visually consistent with the rest
                            // of the app - Sent / Trash / Spam rows don't all
                            // collapse onto the generic folder glyph.
                            leadingContent = {
                                Icon(iconFor(folder.type), contentDescription = null)
                            },
                            modifier = Modifier.clickable {
                                showMovePicker = false
                                viewModel.moveSelected(folder)
                            }
                        )
                    }
                }
            }
            Spacer(Modifier.padding(8.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InboxTopBar(
    title: String,
    scrollBehavior: androidx.compose.material3.TopAppBarScrollBehavior,
    onOpenDrawer: () -> Unit,
    onSearch: () -> Unit,
    onSync: () -> Unit,
    onMarkAllRead: () -> Unit,
    showEmptyTrash: Boolean = false,
    onEmptyTrash: () -> Unit = {},
    /**
     * Top-bar items the user has hidden via Settings -> Top Bar. Each value
     * either suppresses the matching IconButton in [actions] (when present)
     * or promotes the underlying action into the overflow menu so it remains
     * reachable. Index lookup is keyed to the value so renames/insertions in
     * [TopBarItemId] don't silently re-show what's been hidden.
     */
    hidden: Set<TopBarItemId> = emptySet()
) {
    var menuOpen by remember { mutableStateOf(false) }
    val isHidden: (TopBarItemId) -> Boolean = { id -> id in hidden }
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            IconButton(onClick = onOpenDrawer) {
                Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.accounts))
            }
        },
        actions = {
            if (!isHidden(TopBarItemId.INBOX_SEARCH)) {
                IconButton(onClick = onSearch) {
                    Icon(Icons.Default.Search, contentDescription = stringResource(R.string.search))
                }
            }
            if (!isHidden(TopBarItemId.INBOX_SYNC)) {
                IconButton(onClick = onSync) {
                    Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.sync))
                }
            }
            // The Empty-Trash button is only meaningful while viewing Trash; if
            // the user has hidden it AND we're in the Trash folder, surface it
            // in the overflow so the destructive affordance stays reachable.
            if (showEmptyTrash && !isHidden(TopBarItemId.INBOX_EMPTY_TRASH)) {
                IconButton(onClick = onEmptyTrash) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.empty_trash_action)
                    )
                }
            }
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.settings))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.mark_all_read)) },
                    leadingIcon = { Icon(Icons.Default.DoneAll, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onMarkAllRead()
                    }
                )
                // Hidden items that the user can still reach from this bar.
                // Each row is identical to its inline variant except for the
                // menu wrapper so the affordance reads the same after a hide.
                if (isHidden(TopBarItemId.INBOX_SEARCH)) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.search)) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onSearch()
                        }
                    )
                }
                if (isHidden(TopBarItemId.INBOX_SYNC)) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.sync)) },
                        leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onSync()
                        }
                    )
                }
                if (showEmptyTrash && isHidden(TopBarItemId.INBOX_EMPTY_TRASH)) {
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(R.string.empty_trash_action),
                                color = MaterialTheme.colorScheme.error
                            )
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onEmptyTrash()
                        }
                    )
                }
            }
        },
        colors = appTopBarColors(),
        scrollBehavior = scrollBehavior
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopBar(
    count: Int,
    canMove: Boolean = false,
    moveDisabledReason: String? = null,
    onClear: () -> Unit,
    onSelectAll: () -> Unit,
    onMarkRead: () -> Unit,
    onMarkUnread: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    onMarkSpam: () -> Unit,
    onMove: (() -> Unit)? = null
) {
    var menuOpen by remember { mutableStateOf(false) }
    TopAppBar(
        title = { Text(pluralStringResource(R.plurals.selected_count, count, count)) },
        navigationIcon = {
            IconButton(onClick = onClear) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.clear_selection))
            }
        },
        actions = {
            IconButton(onClick = onMarkRead) {
                Icon(Icons.Default.MarkEmailRead, contentDescription = stringResource(R.string.mark_read))
            }
            IconButton(onClick = onArchive) {
                Icon(Icons.Default.Archive, contentDescription = stringResource(R.string.archive))
            }
            // Move button is always visible so the action's position in the
            // bar is stable across views, but its tap is gated by [canMove].
            // The disabled state carries a screen-reader label explaining why
            // the action isn't available, so a TalkBack user still gets
            // useful feedback rather than a silently dead control.
            IconButton(
                onClick = { onMove?.invoke() },
                enabled = canMove && onMove != null
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.DriveFileMove,
                    contentDescription = if (canMove)
                        stringResource(R.string.move_to_folder)
                    else
                        moveDisabledReason ?: stringResource(R.string.move_to_folder)
                )
            }
            IconButton(onClick = onMarkSpam) {
                Icon(Icons.Outlined.Report, contentDescription = stringResource(R.string.mark_as_spam))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete))
            }
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.settings))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.mark_unread)) },
                    leadingIcon = { Icon(Icons.Default.MarkEmailUnread, contentDescription = null) },
                    onClick = { menuOpen = false; onMarkUnread() }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.select_all)) },
                    leadingIcon = { Icon(Icons.Default.SelectAll, contentDescription = null) },
                    onClick = { menuOpen = false; onSelectAll() }
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            actionIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer
        )
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableMailRow(
    message: MailMessage,
    selectionMode: Boolean,
    selected: Boolean,
    swipeRightAction: SwipeAction,
    swipeLeftAction: SwipeAction,
    density: MessageDensity,
    previewLines: Int,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    onToggleRead: () -> Unit,
    onMarkSpam: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    // In selection mode swipe-to-dismiss is suppressed: the row is a tap
    // target for (de)selection, not a destructive gesture surface.
    if (selectionMode) {
        MailListItem(
            message = message,
            onClick = onClick,
            onLongClick = onLongClick,
            selected = selected,
            density = density,
            previewLines = previewLines
        )
        return
    }

    val perform: (SwipeAction) -> Unit = { action ->
        when (action) {
            SwipeAction.ARCHIVE -> onArchive()
            SwipeAction.DELETE -> onDelete()
            SwipeAction.TOGGLE_READ -> onToggleRead()
            SwipeAction.MARK_SPAM -> onMarkSpam()
            SwipeAction.NONE -> Unit
        }
    }

    // `rememberSwipeToDismissBoxState` (and its `confirmValueChange`
    // parameter) is deprecated in Compose Material3. The team recommends
    // driving swipe-to-dismiss with the lower-level `AnchoredDraggable` API
    // and a curated anchor set, but that refactor is out of scope for this
    // change. In the meantime we suppress the deprecation and react to the
    // dismiss through a `LaunchedEffect` instead of through the deprecated
    // callback. `SwipeToDismissBox` (the consumer composable) is NOT
    // deprecated, so the row composable itself stays clean.
    @Suppress("DEPRECATION")
    val dismissState = rememberSwipeToDismissBoxState()

    // Guard so each row instance handles the gesture exactly once. For
    // non-removing actions (#1) we spring the row back so it stays in the
    // list; ARCHIVE/DELETE remove the row from the reactive feed, so the
    // dismissed state is left in place.
    var handled by remember { mutableStateOf(false) }
    LaunchedEffect(dismissState.currentValue) {
        if (!handled && dismissState.currentValue != SwipeToDismissBoxValue.Settled) {
            handled = true
            val action = when (dismissState.currentValue) {
                SwipeToDismissBoxValue.StartToEnd -> swipeRightAction
                SwipeToDismissBoxValue.EndToStart -> swipeLeftAction
                SwipeToDismissBoxValue.Settled -> SwipeAction.NONE
            }
            perform(action)
            if (action != SwipeAction.ARCHIVE && action != SwipeAction.DELETE && action != SwipeAction.MARK_SPAM) {
                dismissState.reset()
                handled = false
            }
        }
    }

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            val isStart = dismissState.dismissDirection == SwipeToDismissBoxValue.StartToEnd
            val action = if (isStart) swipeRightAction else swipeLeftAction
            SwipeBackground(action = action, alignEnd = !isStart)
        }
    ) {
        MailListItem(
            message = message,
            onClick = onClick,
            onLongClick = onLongClick,
            density = density,
            previewLines = previewLines
        )
    }
}

@Composable
private fun SwipeBackground(action: SwipeAction, alignEnd: Boolean) {
    val color = when (action) {
        SwipeAction.ARCHIVE -> MaterialTheme.colorScheme.tertiary
        SwipeAction.DELETE -> MaterialTheme.colorScheme.error
        SwipeAction.TOGGLE_READ -> MaterialTheme.colorScheme.primary
        SwipeAction.MARK_SPAM -> MaterialTheme.colorScheme.errorContainer
        SwipeAction.NONE -> MaterialTheme.colorScheme.surfaceVariant
    }
    val icon = when (action) {
        SwipeAction.ARCHIVE -> Icons.Default.Archive
        SwipeAction.DELETE -> Icons.Default.Delete
        SwipeAction.TOGGLE_READ -> Icons.Default.MarkEmailRead
        SwipeAction.MARK_SPAM -> Icons.Outlined.Report
        SwipeAction.NONE -> null
    }
    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(color)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (alignEnd) Spacer(Modifier.weight(1f))
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = Color.White)
        }
        if (!alignEnd) Spacer(Modifier.weight(1f))
    }
}
