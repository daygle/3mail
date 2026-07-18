package com.threemail.android.ui.screens.inbox

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MarkEmailRead
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDrawerState
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.threemail.android.R
import com.threemail.android.domain.model.MailMessage
import com.threemail.android.ui.components.EmptyState
import com.threemail.android.ui.components.FolderDrawerContent
import com.threemail.android.ui.components.LoadingIndicator
import com.threemail.android.ui.components.MailListItem
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(
    viewModel: InboxViewModel,
    onNavigateToCompose: () -> Unit,
    onNavigateToSearch: () -> Unit,
    onNavigateToAccounts: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToCalendar: () -> Unit,
    onNavigateToMessage: (Long) -> Unit,
    onNavigateToAddAccount: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val recoverableAuthLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        viewModel.onRecoverableAuthHandled()
        viewModel.retryAfterRecoverableAuth()
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
                onSettings = {
                    scope.launch { drawerState.close() }
                    onNavigateToSettings()
                },
                onCalendar = {
                    scope.launch { drawerState.close() }
                    onNavigateToCalendar()
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
            topBar = {
                if (state.selectionMode) {
                    SelectionTopBar(
                        count = state.selectedIds.size,
                        onClear = { viewModel.clearSelection() },
                        onSelectAll = { viewModel.selectAll() },
                        onMarkRead = { viewModel.markSelectedRead(true) },
                        onMarkUnread = { viewModel.markSelectedRead(false) },
                        onStar = { viewModel.starSelected(true) },
                        onArchive = { viewModel.archiveSelected() },
                        onDelete = { viewModel.deleteSelected() }
                    )
                } else {
                    InboxTopBar(
                        title = when {
                            state.unifiedInbox -> stringResource(R.string.unified_inbox)
                            else -> state.selectedFolder?.name ?: stringResource(R.string.app_name)
                        },
                        scrollBehavior = scrollBehavior,
                        onOpenDrawer = { scope.launch { drawerState.open() } },
                        onSearch = onNavigateToSearch,
                        onSync = { viewModel.sync() },
                        onMarkAllRead = { viewModel.markAllRead() }
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
                                            onArchive = { viewModel.archive(message) },
                                            onDelete = { viewModel.delete(message) },
                                            onClick = {
                                                if (state.selectionMode) {
                                                    viewModel.toggleSelection(message)
                                                } else {
                                                    viewModel.markAsRead(message, true)
                                                    onNavigateToMessage(message.id)
                                                }
                                            },
                                            onLongClick = { viewModel.toggleSelection(message) },
                                            onToggleStar = { viewModel.toggleStar(message) }
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InboxTopBar(
    title: String,
    scrollBehavior: androidx.compose.material3.TopAppBarScrollBehavior,
    onOpenDrawer: () -> Unit,
    onSearch: () -> Unit,
    onSync: () -> Unit,
    onMarkAllRead: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            IconButton(onClick = onOpenDrawer) {
                Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.accounts))
            }
        },
        actions = {
            IconButton(onClick = onSearch) {
                Icon(Icons.Default.Search, contentDescription = stringResource(R.string.search))
            }
            IconButton(onClick = onSync) {
                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.sync))
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopBar(
    count: Int,
    onClear: () -> Unit,
    onSelectAll: () -> Unit,
    onMarkRead: () -> Unit,
    onMarkUnread: () -> Unit,
    onStar: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    TopAppBar(
        title = { Text(stringResource(R.string.selected_count, count)) },
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
                    text = { Text(stringResource(R.string.star)) },
                    leadingIcon = { Icon(Icons.Default.Star, contentDescription = null) },
                    onClick = { menuOpen = false; onStar() }
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
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleStar: () -> Unit
) {
    // In selection mode swipe-to-dismiss is suppressed: the row is a tap
    // target for (de)selection, not a destructive gesture surface.
    if (selectionMode) {
        MailListItem(
            message = message,
            onClick = onClick,
            onLongClick = onLongClick,
            selected = selected,
            onToggleStar = onToggleStar
        )
        return
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

    // The viewmodel mutates `state.messages` synchronously when archive /
    // delete succeeds, but on failure (e.g. offline, optimistic-only
    // mutation) the row can stay alive long enough for the user to swipe it
    // a *second* time — re-triggering a destructive action. The guard below
    // ensures each row instance handles the gesture exactly once. The flag
    // is keyed against this row's stable `message.id`, so a different row
    // gets a fresh boolean.
    var handled by remember { mutableStateOf(false) }
    LaunchedEffect(dismissState.currentValue) {
        if (!handled && dismissState.currentValue != SwipeToDismissBoxValue.Settled) {
            handled = true
            when (dismissState.currentValue) {
                SwipeToDismissBoxValue.StartToEnd -> onArchive()
                SwipeToDismissBoxValue.EndToStart -> onDelete()
                SwipeToDismissBoxValue.Settled -> Unit
            }
        }
    }

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            val isArchive = dismissState.dismissDirection == SwipeToDismissBoxValue.StartToEnd
            val color = if (isArchive) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .background(color)
                    .padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isArchive) {
                    Icon(Icons.Default.Archive, contentDescription = "Archive", tint = Color.White)
                }
                Spacer(Modifier.weight(1f))
                if (!isArchive) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.White)
                }
            }
        }
    ) {
        MailListItem(
            message = message,
            onClick = onClick,
            onLongClick = onLongClick,
            onToggleStar = onToggleStar
        )
    }
}
