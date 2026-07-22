package com.threemail.android.ui.screens.folders

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.threemail.android.data.repository.FolderPaths
import com.threemail.android.domain.model.AccountType
import com.threemail.android.domain.model.FolderType
import com.threemail.android.domain.model.MailFolder
import com.threemail.android.ui.components.FolderNode
import com.threemail.android.ui.components.buildFolderTree
import com.threemail.android.ui.components.iconFor
import com.threemail.android.ui.theme.appTopBarColors
import com.threemail.android.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderManagementScreen(
    viewModel: FolderManagementViewModel,
    onNavigateBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    // Structural folder edits (rename/move/delete) are IMAP-only; Gmail's
    // labels and POP3's fixed inbox don't map onto the RENAME/DELETE commands.
    val isImap = state.selectedAccount?.accountType == AccountType.IMAP

    // Which folder (if any) each dialog is currently acting on.
    var renameTarget by remember { mutableStateOf<MailFolder?>(null) }
    var moveTarget by remember { mutableStateOf<MailFolder?>(null) }
    var deleteTarget by remember { mutableStateOf<MailFolder?>(null) }

    // Drain the view-model's one-shot events into the snackbar. getString (not
    // stringResource) because this runs in a coroutine, not composition.
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            val message = when (event) {
                is FolderManagementViewModel.FolderEvent.Renamed ->
                    context.getString(R.string.folder_renamed, event.name)
                is FolderManagementViewModel.FolderEvent.Moved ->
                    context.getString(R.string.folder_moved, event.name)
                is FolderManagementViewModel.FolderEvent.Deleted ->
                    context.getString(R.string.folder_deleted, event.name)
                is FolderManagementViewModel.FolderEvent.Failed ->
                    context.getString(R.string.folder_op_failed, event.name)
                FolderManagementViewModel.FolderEvent.InvalidName ->
                    context.getString(R.string.folder_name_invalid)
                FolderManagementViewModel.FolderEvent.DuplicateName ->
                    context.getString(R.string.folder_name_duplicate)
            }
            snackbarHostState.showSnackbar(message)
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.manage_folders_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cancel))
                    }
                },
                colors = appTopBarColors(),
                scrollBehavior = scrollBehavior
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (state.accounts.size > 1) {
                Row(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    state.accounts.forEach { account ->
                        FilterChip(
                            selected = state.selectedAccount?.id == account.id,
                            onClick = { viewModel.selectAccount(account) },
                            label = { Text(account.email, maxLines = 1) }
                        )
                    }
                }
            }

            Text(
                text = stringResource(R.string.manage_folders_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            // Render the folders as the same IMAP-hierarchy tree the drawer
            // uses, so subfolders sit under their parents instead of a flat
            // list. Starts fully expanded; expansion is local UI state.
            var expandedIds by remember(state.folders) {
                mutableStateOf(state.folders.mapTo(HashSet()) { it.serverId } as Set<String>)
            }
            val nodes = remember(state.folders, expandedIds) {
                buildFolderTree(state.folders, expandedIds)
            }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(nodes, key = { it.folder.id }) { node ->
                    FolderSubscriptionRow(
                        node = node,
                        // Only user-created folders may be renamed/moved/deleted;
                        // system folders (Inbox, Sent, Trash, …) stay protected so
                        // core mail flows can't be broken from here.
                        showActions = isImap && node.folder.type == FolderType.CUSTOM,
                        onToggleExpand = { serverId ->
                            expandedIds = if (serverId in expandedIds) {
                                expandedIds - serverId
                            } else {
                                expandedIds + serverId
                            }
                        },
                        onSetVisible = { visible -> viewModel.setHidden(node.folder, !visible) },
                        onRename = { renameTarget = node.folder },
                        onMove = { moveTarget = node.folder },
                        onDelete = { deleteTarget = node.folder }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                }
            }
        }
    }

    renameTarget?.let { target ->
        RenameFolderDialog(
            folder = target,
            onDismiss = { renameTarget = null },
            onConfirm = { newName ->
                viewModel.renameFolder(target, newName)
                renameTarget = null
            }
        )
    }

    moveTarget?.let { target ->
        val separator = FolderPaths.separatorOf(state.folders)
        // Any folder can be a parent except the folder itself and its own
        // descendants (a folder can't be moved into its own subtree).
        val candidates = state.folders.filter { candidate ->
            candidate.serverId != target.serverId &&
                !FolderPaths.isSelfOrDescendant(target.serverId, candidate.serverId, separator)
        }
        MoveFolderDialog(
            folder = target,
            candidates = candidates,
            onDismiss = { moveTarget = null },
            onConfirm = { newParent ->
                viewModel.moveFolder(target, newParent)
                moveTarget = null
            }
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.delete_folder_title)) },
            text = { Text(stringResource(R.string.delete_folder_message, target.name)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteFolder(target)
                    deleteTarget = null
                }) {
                    Text(stringResource(R.string.folder_action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun RenameFolderDialog(
    folder: MailFolder,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember(folder.serverId) { mutableStateOf(folder.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rename_folder_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text(stringResource(R.string.rename_folder_label)) }
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text) },
                enabled = text.isNotBlank()
            ) {
                Text(stringResource(R.string.folder_action_rename))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun MoveFolderDialog(
    folder: MailFolder,
    candidates: List<MailFolder>,
    onDismiss: () -> Unit,
    onConfirm: (MailFolder?) -> Unit
) {
    // null = "Top level (no parent)". Default the radio selection to the top
    // level so a single confirm tap un-nests a folder.
    var selected by remember(folder.serverId) { mutableStateOf<MailFolder?>(null) }
    var topLevelChosen by remember(folder.serverId) { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.move_folder_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                MoveDestinationRow(
                    label = stringResource(R.string.move_folder_top_level),
                    subtitle = null,
                    selected = topLevelChosen,
                    onSelect = { selected = null; topLevelChosen = true }
                )
                candidates.forEach { candidate ->
                    MoveDestinationRow(
                        label = candidate.name,
                        // Show the full server path when it differs from the leaf,
                        // so two folders with the same name in different parents
                        // are distinguishable.
                        subtitle = candidate.serverId.takeIf { it != candidate.name },
                        selected = !topLevelChosen && selected?.serverId == candidate.serverId,
                        onSelect = { selected = candidate; topLevelChosen = false }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(if (topLevelChosen) null else selected) }) {
                Text(stringResource(R.string.folder_action_move))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun MoveDestinationRow(
    label: String,
    subtitle: String?,
    selected: Boolean,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private val SUBSCRIPTION_INDENT_PER_LEVEL = 20.dp
private val SUBSCRIPTION_CHEVRON_SIZE = 20.dp

@Composable
private fun FolderSubscriptionRow(
    node: FolderNode,
    showActions: Boolean,
    onToggleExpand: (String) -> Unit,
    onSetVisible: (Boolean) -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = 16.dp + SUBSCRIPTION_INDENT_PER_LEVEL * node.depth,
                end = 16.dp,
                top = 12.dp,
                bottom = 12.dp
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (node.hasChildren) {
            Icon(
                imageVector = Icons.Default.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(SUBSCRIPTION_CHEVRON_SIZE)
                    .rotate(if (node.isExpanded) 0f else -90f)
                    .clickable { onToggleExpand(node.folder.serverId) }
            )
        } else {
            Spacer(Modifier.size(SUBSCRIPTION_CHEVRON_SIZE))
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            imageVector = iconFor(node.folder.type),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = node.folder.name,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(12.dp))
        // Switch "on" = visible, so hiding reads naturally.
        Switch(
            checked = !node.folder.isHidden,
            onCheckedChange = onSetVisible
        )
        // Overflow with rename / move / delete for eligible (custom, IMAP)
        // folders only; system folders and non-IMAP accounts omit it entirely.
        if (showActions) {
            var menuOpen by remember { mutableStateOf(false) }
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = stringResource(R.string.folder_actions_content_description),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.folder_action_rename)) },
                    onClick = { menuOpen = false; onRename() }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.folder_action_move)) },
                    onClick = { menuOpen = false; onMove() }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.folder_action_delete)) },
                    onClick = { menuOpen = false; onDelete() }
                )
            }
        }
    }
}
