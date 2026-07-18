package com.threemail.android.ui.screens.inbox

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
        drawerContent = {
            FolderDrawerContent(
                account = state.selectedAccount,
                accounts = state.accounts,
                folders = state.folders,
                selectedFolder = state.selectedFolder,
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
                TopAppBar(
                    title = { Text(state.selectedFolder?.name ?: stringResource(R.string.app_name)) },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.accounts))
                        }
                    },
                    actions = {
                        IconButton(onClick = onNavigateToSearch) {
                            Icon(Icons.Default.Search, contentDescription = stringResource(R.string.search))
                        }
                        IconButton(onClick = { viewModel.sync() }) {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.sync))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                        scrolledContainerColor = MaterialTheme.colorScheme.surface
                    ),
                    scrollBehavior = scrollBehavior
                )
            },
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = onNavigateToCompose,
                    icon = { Icon(Icons.Default.Edit, contentDescription = null) },
                    text = { Text(stringResource(R.string.compose)) }
                )
            }
        ) { padding ->
            Box(modifier = Modifier.padding(padding)) {
                if (state.isSyncing) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                when {
                    state.accounts.isEmpty() -> EmptyState(
                        title = stringResource(R.string.no_accounts),
                        subtitle = stringResource(R.string.add_account_prompt),
                        actionLabel = stringResource(R.string.empty_state_add_account),
                        onAction = onNavigateToAddAccount
                    )
                    state.messages.isEmpty() && state.isSyncing -> LoadingIndicator()
                    state.messages.isEmpty() -> EmptyState(
                        title = stringResource(R.string.no_messages),
                        subtitle = "Tap refresh to sync your mail."
                    )
                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 88.dp)
                        ) {
                            items(state.messages, key = { it.id }) { message ->
                                SwipeableMailRow(
                                    message = message,
                                    onArchive = { viewModel.archive(message) },
                                    onDelete = { viewModel.delete(message) },
                                    onClick = {
                                        viewModel.markAsRead(message, true)
                                        onNavigateToMessage(message.id)
                                    },
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableMailRow(
    message: MailMessage,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    onClick: () -> Unit,
    onToggleStar: () -> Unit
) {
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
            onToggleStar = onToggleStar
        )
    }
}
