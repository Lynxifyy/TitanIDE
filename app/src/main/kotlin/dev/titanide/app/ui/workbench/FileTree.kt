package dev.titanide.app.ui.workbench

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.titanide.core.designsystem.TitanColors
import dev.titanide.core.workbench.AndroidGroup
import dev.titanide.core.workbench.ProjectNode

/**
 * One visible row in the flattened tree. Flattening happens once per expansion change so
 * the lazy list only ever deals with a flat, recyclable sequence — a deep tree on a phone
 * must not build a composable per node.
 */
data class TreeRow(
    val node: ProjectNode,
    val depth: Int,
    val isExpanded: Boolean,
    val group: AndroidGroup? = null,
)

/** Flattens a tree into rows, honouring the expanded set. */
fun flattenTree(
    nodes: List<ProjectNode>,
    expanded: Set<String>,
    depth: Int = 0,
    inheritedGroup: AndroidGroup? = null,
): List<TreeRow> {
    val rows = ArrayList<TreeRow>()
    for (node in nodes) {
        val group = node.group ?: inheritedGroup
        val isExpanded = node.path in expanded
        rows += TreeRow(node, depth, isExpanded, group)
        if (node.isDirectory && isExpanded && node.children.isNotEmpty()) {
            rows += flattenTree(node.children, expanded, depth + 1, group)
        }
    }
    return rows
}

private fun iconFor(node: ProjectNode, expanded: Boolean): ImageVector = when {
    node.group == AndroidGroup.MANIFESTS -> Icons.Filled.InsertDriveFile
    node.group == AndroidGroup.GRADLE -> Icons.Filled.InsertDriveFile
    node.isDirectory -> if (expanded) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowRight
    else -> Icons.Filled.InsertDriveFile
}

/** Colour per language, so the tree is scannable at a glance. */
private fun colorFor(node: ProjectNode): Color = when (node.languageId) {
    "kotlin" -> TitanColors.Primary
    "java" -> TitanColors.Warning
    "xml" -> TitanColors.Secondary
    "json", "yaml" -> TitanColors.Success
    "cpp", "c" -> TitanColors.Info
    "rust" -> TitanColors.Warning
    "markdown" -> TitanColors.TextMuted
    else -> TitanColors.TextMuted
}

@Composable
fun FileTree(
    nodes: List<ProjectNode>,
    expanded: Set<String>,
    activePath: String?,
    modifiedPaths: Set<String>,
    onToggle: (String) -> Unit,
    onOpen: (ProjectNode) -> Unit,
    onCreateFile: () -> Unit,
    onCreateFolder: () -> Unit,
    onRefresh: () -> Unit,
    onDelete: (ProjectNode) -> Unit,
    onRename: (ProjectNode) -> Unit = {},
    onDuplicate: (ProjectNode) -> Unit = {},
    modifier: Modifier = Modifier,
    emptyDetail: String = "No files in this project yet.",
) {
    val rows = remember(nodes, expanded) { flattenTree(nodes, expanded) }

    androidx.compose.foundation.layout.Column(modifier = modifier.fillMaxWidth()) {
        PanelHeader(title = "Explorer") {
            DenseIconButton(Icons.Filled.Add, "New file", onCreateFile)
            DenseIconButton(Icons.Filled.CreateNewFolder, "New folder", onCreateFolder)
            DenseIconButton(Icons.Filled.Refresh, "Refresh", onRefresh)
        }
        if (rows.isEmpty()) {
            PanelEmptyState(
                icon = Icons.Filled.InsertDriveFile,
                title = "Nothing here",
                detail = emptyDetail,
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(rows, key = { it.node.path }) { row ->
                    TreeRowView(
                        row = row,
                        isActive = row.node.path == activePath,
                        isModified = row.node.path in modifiedPaths,
                        onToggle = { onToggle(row.node.path) },
                        onOpen = { onOpen(row.node) },
                        onDelete = { onDelete(row.node) },
                        onRename = { onRename(row.node) },
                        onDuplicate = { onDuplicate(row.node) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TreeRowView(
    row: TreeRow,
    isActive: Boolean,
    isModified: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
) {
    val node = row.node
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(WorkbenchMetrics.RowHeight)
            .background(if (isActive) TitanColors.SurfaceHigh else Color.Transparent)
            .combinedClickable(
                onClick = { if (node.isDirectory) onToggle() else onOpen() },
                onLongClick = { menuOpen = true },
            )
            .padding(start = (row.depth * 12).dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (node.isDirectory) {
            Icon(
                imageVector = iconFor(node, row.isExpanded),
                contentDescription = if (row.isExpanded) "Collapse" else "Expand",
                tint = TitanColors.TextMuted,
                modifier = Modifier.size(14.dp),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(colorFor(node).copy(alpha = 0.85f)),
            )
        }
        Spacer(Modifier.width(5.dp))
        Text(
            text = node.name,
            color = when {
                isActive -> TitanColors.TextPrimary
                node.isDirectory -> TitanColors.TextPrimary.copy(alpha = 0.9f)
                else -> TitanColors.TextMuted
            },
            fontSize = 11.5.sp,
            fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (isModified) {
            Text(
                text = "M",
                color = TitanColors.GitModified,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 3.dp),
            )
        }
        if (!node.isDirectory) {
            DenseIconButton(
                icon = Icons.Filled.Delete,
                contentDescription = "Delete ${node.name}",
                onClick = onDelete,
                tint = TitanColors.TextMuted.copy(alpha = 0.6f),
            )
        }
        Box {
            DenseIconButton(
                icon = Icons.Filled.MoreVert,
                contentDescription = "Actions for ${node.name}",
                onClick = { menuOpen = true },
                tint = TitanColors.TextMuted.copy(alpha = 0.6f),
            )
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Rename or move", fontSize = 11.sp) },
                    onClick = {
                        menuOpen = false
                        onRename()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Duplicate", fontSize = 11.sp) },
                    onClick = {
                        menuOpen = false
                        onDuplicate()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Delete", fontSize = 11.sp, color = TitanColors.Error) },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    },
                )
            }
        }
    }
}

/** Search view: query field plus grouped hits. */
@Composable
fun SearchView(
    query: String,
    onQueryChange: (String) -> Unit,
    hits: List<SearchHit>,
    onOpenHit: (SearchHit) -> Unit,
    modifier: Modifier = Modifier,
    onOpenFindInFile: (replace: Boolean) -> Unit = {},
) {
    androidx.compose.foundation.layout.Column(modifier = modifier.fillMaxWidth()) {
        PanelHeader(title = "Search")
        SearchField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = "Search in project",
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
        // Project search finds files; these switch to find/replace inside the open file.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Find in file",
                color = TitanColors.Secondary,
                fontSize = 10.5.sp,
                modifier = Modifier
                    .clickable { onOpenFindInFile(false) }
                    .padding(vertical = 3.dp),
            )
            Text(
                text = "Replace in file",
                color = TitanColors.Secondary,
                fontSize = 10.5.sp,
                modifier = Modifier
                    .clickable { onOpenFindInFile(true) }
                    .padding(vertical = 3.dp),
            )
        }
        if (query.isNotBlank() && hits.isEmpty()) {
            Text(
                text = "No results",
                color = TitanColors.TextMuted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(hits, key = { "${it.path}:${it.line}:${it.text.hashCode()}" }) { hit ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(WorkbenchMetrics.RowHeight)
                        .clickable { onOpenHit(hit) }
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${hit.path.substringAfterLast('/')}:${hit.line}",
                        color = TitanColors.Secondary,
                        fontSize = 10.5.sp,
                        maxLines = 1,
                        modifier = Modifier.width(120.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = hit.text,
                        color = TitanColors.TextMuted,
                        fontSize = 10.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}
