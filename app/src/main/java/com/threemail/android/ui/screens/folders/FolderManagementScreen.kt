package com.threemail.android.ui.screens.folders

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
        }
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
                        onToggleExpand = { serverId ->
                            expandedIds = if (serverId in expandedIds) {
                                expandedIds - serverId
                            } else {
                                expandedIds + serverId
                            }
                        },
                        onSetVisible = { visible -> viewModel.setHidden(node.folder, !visible) }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                }
            }
        }
    }
}

private val SUBSCRIPTION_INDENT_PER_LEVEL = 20.dp
private val SUBSCRIPTION_CHEVRON_SIZE = 20.dp

@Composable
private fun FolderSubscriptionRow(
    node: FolderNode,
    onToggleExpand: (String) -> Unit,
    onSetVisible: (Boolean) -> Unit
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
    }
}
