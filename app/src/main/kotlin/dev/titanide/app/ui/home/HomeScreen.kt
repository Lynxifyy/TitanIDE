package dev.titanide.app.ui.home

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Handyman
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.titanide.app.LocalContainer
import dev.titanide.app.ui.common.TitanTopBar
import dev.titanide.app.ui.common.formatBytes
import dev.titanide.app.ui.common.formatRelativeTime
import dev.titanide.core.designsystem.TitanColors
import dev.titanide.core.designsystem.components.EmptyState
import dev.titanide.core.designsystem.components.MonogramBadge
import dev.titanide.core.designsystem.components.SectionHeader
import dev.titanide.core.designsystem.components.StatTile
import dev.titanide.core.designsystem.components.StatusPill
import dev.titanide.core.designsystem.components.TitanCard
import dev.titanide.core.model.Project

@Composable
fun HomeScreen(
    onOpenProject: (String) -> Unit,
    onCreateProject: () -> Unit,
    onOpenEditor: () -> Unit,
    onOpenBuilds: () -> Unit,
    onOpenGitHub: () -> Unit,
    onOpenAi: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTools: () -> Unit,
) {
    val container = LocalContainer.current
    val viewModel: HomeViewModel = viewModel { HomeViewModel(container) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.refreshStatus() }

    Column(Modifier.fillMaxSize()) {
        TitanTopBar(
            title = "TITAN IDE",
            subtitle = "Your entire development studio",
            actions = {
                IconButton(onClick = { viewModel.probeToolchains() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Re-probe toolchains")
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = "Settings")
                }
            },
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { QuickActions(onCreateProject, onOpenEditor, onOpenGitHub, onOpenAi, onOpenTools) }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    StatTile(
                        label = "Build success",
                        value = if (state.totalBuilds == 0) "--" else "${(state.buildSuccessRate * 100).toInt()}%",
                        caption = "${state.totalBuilds} builds recorded",
                        icon = Icons.Filled.CheckCircle,
                        tint = TitanColors.Success,
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        label = "Projects",
                        value = state.recentProjects.size.toString(),
                        caption = "recently opened",
                        icon = Icons.Filled.FolderOpen,
                        tint = TitanColors.Primary,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    StatTile(
                        label = "Storage",
                        value = formatBytes(state.storageUsed),
                        caption = "projects and logs",
                        icon = Icons.Filled.Storage,
                        tint = TitanColors.Warning,
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        label = "AI",
                        value = if (state.aiConfigured) "Ready" else "Off",
                        caption = if (state.aiConfigured) "provider configured" else "configure in settings",
                        icon = Icons.Filled.Psychology,
                        tint = if (state.aiConfigured) TitanColors.Success else TitanColors.TextMuted,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            item { SectionHeader("Recent projects") }

            if (state.loading) {
                item {
                    Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = TitanColors.Primary)
                    }
                }
            } else if (state.recentProjects.isEmpty()) {
                item {
                    EmptyState(
                        title = "No projects yet",
                        message = "Create your first project to start building with TITAN.",
                        icon = Icons.Filled.FolderOpen,
                        action = {
                            Button(
                                onClick = onCreateProject,
                                colors = ButtonDefaults.buttonColors(containerColor = TitanColors.Primary),
                            ) { Text("Create project") }
                        },
                    )
                }
            } else {
                items(state.recentProjects, key = { it.id }) { project ->
                    ProjectRow(project) { onOpenProject(project.id) }
                }
            }

            item { SectionHeader("Toolchain", action = {
                Text(
                    text = if (state.probingToolchains) "probing..." else "${state.toolchains.count { it.available }} available",
                    style = MaterialTheme.typography.labelSmall,
                    color = TitanColors.TextMuted,
                )
            }) }

            if (state.probingToolchains && state.toolchains.isEmpty()) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = TitanColors.Secondary,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("Probing device toolchains...", color = TitanColors.TextMuted)
                    }
                }
            } else {
                item { ToolchainSummary(state.toolchains, onOpenSettings) }
            }

            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

@Composable
private fun QuickActions(
    onCreateProject: () -> Unit,
    onOpenEditor: () -> Unit,
    onOpenGitHub: () -> Unit,
    onOpenAi: () -> Unit,
    onOpenTools: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(
            onClick = onCreateProject,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = TitanColors.Primary),
        ) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Create project", fontWeight = FontWeight.SemiBold)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = onOpenGitHub,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(Icons.Filled.Cloud, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("GitHub", maxLines = 1)
            }
            OutlinedButton(
                onClick = onOpenEditor,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(Icons.Filled.Build, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Editor", maxLines = 1)
            }
            OutlinedButton(
                onClick = onOpenAi,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(Icons.Filled.Psychology, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("AI", maxLines = 1)
            }
        }
        OutlinedButton(
            onClick = onOpenTools,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        ) {
            Icon(Icons.Filled.Handyman, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Developer tools: API tester, assets, database, UI preview, docs", maxLines = 1)
        }
    }
}

@Composable
private fun ProjectRow(project: Project, onClick: () -> Unit) {
    TitanCard(onClick = onClick) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MonogramBadge(
                text = project.name,
                accent = TitanColors.Primary,
                size = 42,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    project.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = TitanColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${project.languages.joinToString(", ") { it.displayName }} · ${project.buildSystem.displayName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TitanColors.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                formatRelativeTime(project.lastOpenedAt),
                style = MaterialTheme.typography.labelSmall,
                color = TitanColors.TextMuted,
            )
        }
    }
}

@Composable
private fun ToolchainSummary(tools: List<dev.titanide.core.model.ToolchainInfo>, onOpenSettings: () -> Unit) {
    val available = tools.filter { it.available }
    val missing = tools.filter { !it.available }

    TitanCard {
        Column(Modifier.padding(14.dp)) {
            if (available.isEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = TitanColors.Warning)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "No build toolchain found on this device",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TitanColors.TextPrimary,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Android apps cannot run a desktop JDK or Gradle by default. Connect a remote build " +
                        "agent or use GitHub Actions to compile here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TitanColors.TextMuted,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = onOpenSettings) { Text("Configure remote build") }
            } else {
                available.take(6).forEach { tool ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = TitanColors.Success,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            tool.displayName,
                            style = MaterialTheme.typography.bodySmall,
                            color = TitanColors.TextPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            tool.version?.take(28) ?: "available",
                            style = MaterialTheme.typography.labelSmall,
                            color = TitanColors.TextMuted,
                            maxLines = 1,
                        )
                    }
                }
                if (missing.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${missing.size} toolchain components are not installed",
                        style = MaterialTheme.typography.labelSmall,
                        color = TitanColors.TextMuted,
                    )
                }
            }
        }
    }
}
