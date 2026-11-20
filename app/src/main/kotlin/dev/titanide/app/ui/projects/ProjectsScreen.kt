package dev.titanide.app.ui.projects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.titanide.app.LocalContainer
import dev.titanide.app.ui.common.TitanTopBar
import dev.titanide.app.ui.common.formatRelativeTime
import dev.titanide.core.designsystem.TitanColors
import dev.titanide.core.designsystem.components.EmptyState
import dev.titanide.core.designsystem.components.MonogramBadge
import dev.titanide.core.designsystem.components.TitanCard
import dev.titanide.core.model.Project
import dev.titanide.core.model.ProjectCategory

@Composable
fun ProjectsScreen(
    onCreateProject: () -> Unit,
    onOpenProject: (String) -> Unit,
    onOpenGitHub: () -> Unit,
) {
    val container = LocalContainer.current
    val viewModel: ProjectsViewModel = viewModel { ProjectsViewModel(container) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<Project?>(null) }

    Scaffold(
        containerColor = TitanColors.Canvas,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onCreateProject,
                containerColor = TitanColors.Primary,
                contentColor = TitanColors.TextPrimary,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("New project") },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TitanTopBar(
                title = "Projects",
                subtitle = "${state.visible.size} of ${state.projects.size}",
                actions = {
                    IconButton(onClick = onOpenGitHub) {
                        Icon(Icons.Filled.CloudDownload, contentDescription = "Import from GitHub")
                    }
                },
            )

            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Search projects") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = state.categoryFilter == null,
                    onClick = { viewModel.setFilter(null) },
                    label = { Text("All") },
                )
                ProjectCategory.entries.take(3).forEach { category ->
                    FilterChip(
                        selected = state.categoryFilter == category,
                        onClick = { viewModel.setFilter(category) },
                        label = { Text(category.title.take(12), maxLines = 1) },
                    )
                }
            }

            state.error?.let { message ->
                Text(
                    message,
                    color = TitanColors.Error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }

            if (state.loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = TitanColors.Primary)
                }
            } else if (state.visible.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    EmptyState(
                        icon = Icons.Filled.FolderOpen,
                        title = if (state.projects.isEmpty()) "No projects" else "No matches",
                        message = if (state.projects.isEmpty()) {
                            "Create a project or import one from GitHub."
                        } else {
                            "No project matches \"${state.query}\"."
                        },
                        action = if (state.projects.isEmpty()) {
                            {
                                Button(
                                    onClick = onCreateProject,
                                    colors = ButtonDefaults.buttonColors(containerColor = TitanColors.Primary),
                                ) { Text("Create project") }
                            }
                        } else {
                            null
                        },
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 8.dp,
                        bottom = 96.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.visible, key = { it.id }) { project ->
                        ProjectCard(
                            project = project,
                            deleting = state.deleting == project.id,
                            onOpen = {
                                viewModel.open(project.id)
                                onOpenProject(project.id)
                            },
                            onToggleFavorite = { viewModel.toggleFavorite(project) },
                            onDelete = { pendingDelete = project },
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { project ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete ${project.name}?") },
            text = {
                Text(
                    "This permanently deletes the project directory and all of its files. " +
                        "This cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.delete(project)
                        pendingDelete = null
                    },
                ) { Text("Delete", color = TitanColors.Error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ProjectCard(
    project: Project,
    deleting: Boolean,
    onOpen: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDelete: () -> Unit,
) {
    TitanCard(onClick = onOpen) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MonogramBadge(project.name, TitanColors.Primary, size = 44)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        project.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = TitanColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                Text(
                    project.packageName,
                    style = MaterialTheme.typography.labelSmall,
                    color = TitanColors.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "${project.category.title} · ${project.buildSystem.displayName} · ${formatRelativeTime(project.lastOpenedAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TitanColors.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    imageVector = if (project.favorite) Icons.Filled.Star else Icons.Outlined.StarOutline,
                    contentDescription = if (project.favorite) "Remove favourite" else "Add favourite",
                    tint = if (project.favorite) TitanColors.Warning else TitanColors.TextMuted,
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(onClick = onDelete, enabled = !deleting) {
                if (deleting) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Delete project",
                        tint = TitanColors.TextMuted,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}
