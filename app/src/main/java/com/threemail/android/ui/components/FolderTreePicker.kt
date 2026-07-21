package com.threemail.android.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.threemail.android.domain.model.MailFolder

/**
 * A hierarchical, expandable folder picker. Renders [folders] as the same
 * IMAP-hierarchy tree the drawer uses (via [buildFolderTree]) so deep folder
 * layouts read as a tree instead of a long flat list. Tapping a row's text
 * area selects that folder; tapping its chevron expands/collapses its children.
 *
 * Expansion starts fully open (every folder that is a parent is expanded) and
 * is local UI state, reset whenever the [folders] set changes.
 */
@Composable
fun FolderTreePicker(
    folders: List<MailFolder>,
    modifier: Modifier = Modifier,
    onSelect: (MailFolder) -> Unit
) {
    var expandedIds by remember(folders) {
        mutableStateOf(folders.mapTo(HashSet()) { it.serverId } as Set<String>)
    }
    val nodes = remember(folders, expandedIds) { buildFolderTree(folders, expandedIds) }

    LazyColumn(modifier = modifier.fillMaxWidth()) {
        items(nodes, key = { it.folder.id }) { node ->
            FolderPickerRow(
                node = node,
                onToggle = { serverId ->
                    expandedIds = if (serverId in expandedIds) {
                        expandedIds - serverId
                    } else {
                        expandedIds + serverId
                    }
                },
                onSelect = { onSelect(node.folder) }
            )
        }
    }
}

private val PICKER_INDENT_PER_LEVEL = 20.dp
private val PICKER_CHEVRON_SIZE = 20.dp

@Composable
private fun FolderPickerRow(
    node: FolderNode,
    onToggle: (String) -> Unit,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() }
            .padding(
                start = 16.dp + PICKER_INDENT_PER_LEVEL * node.depth,
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
                    .size(PICKER_CHEVRON_SIZE)
                    .rotate(if (node.isExpanded) 0f else -90f)
                    .clickable { onToggle(node.folder.serverId) }
            )
        } else {
            Spacer(Modifier.size(PICKER_CHEVRON_SIZE))
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
            overflow = TextOverflow.Ellipsis
        )
    }
}
