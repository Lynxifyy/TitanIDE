package dev.titanide.app.ui.workbench

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Source
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.titanide.core.designsystem.TitanColors
import dev.titanide.core.opencode.session.OpenCodeState

/** Source control: the modified buffers, as a change list. */
@Composable
fun SourceControlSidebar(
    branch: String?,
    modifiedPaths: Set<String>,
    onOpenFile: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        PanelHeader(title = "Source Control")
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = branch ?: "No branch",
                color = TitanColors.TextPrimary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.width(6.dp))
            if (modifiedPaths.isNotEmpty()) {
                StatusChip("${modifiedPaths.size} changed", TitanColors.GitModified)
            }
        }
        if (modifiedPaths.isEmpty()) {
            PanelEmptyState(
                icon = Icons.Filled.CheckCircle,
                title = "Working tree clean",
                detail = "Edits you make appear here until they are committed.",
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(modifiedPaths.toList()) { path ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(WorkbenchMetrics.RowHeight)
                            .clickable { onOpenFile(path) }
                            .padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "M",
                            color = TitanColors.GitModified,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.width(14.dp),
                        )
                        Text(
                            text = path,
                            color = TitanColors.TextMuted,
                            fontSize = 10.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Run and debug.
 *
 * Every action here calls the build engine with a real [BuildKind]; nothing is a stub. Stop
 * is only enabled while a build is running, and the other actions are disabled while one is
 * in flight, because the engine refuses a concurrent build for the same project and a button
 * that silently does nothing is worse than a disabled one.
 */
@Composable
fun RunSidebar(
    projectName: String?,
    buildRunning: Boolean,
    lastBuildStatus: String?,
    lastBuildExecutor: String?,
    canRun: Boolean,
    runHint: String?,
    hasApk: Boolean,
    installInProgress: Boolean,
    installedPackage: String?,
    onInstall: () -> Unit,
    onLaunch: () -> Unit,
    onBuild: () -> Unit,
    onTest: () -> Unit,
    onClean: () -> Unit,
    onRebuild: () -> Unit,
    onRun: () -> Unit,
    onStop: () -> Unit,
    onOpenProject: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        PanelHeader(title = "Run and Debug")

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = projectName ?: "No project open",
                color = TitanColors.TextPrimary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            when {
                buildRunning -> StatusChip("Building", TitanColors.Warning)
                lastBuildStatus != null -> StatusChip(
                    lastBuildStatus,
                    if (lastBuildStatus == "SUCCEEDED") TitanColors.Success else TitanColors.Error,
                )
            }
        }

        if (lastBuildExecutor != null) {
            Text(
                text = lastBuildExecutor,
                color = TitanColors.TextMuted,
                fontSize = 9.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 1.dp),
            )
        }

        SidebarAction(
            label = if (buildRunning) "Building..." else "Build project",
            icon = Icons.Filled.PlayArrow,
            tint = if (buildRunning) TitanColors.TextMuted else TitanColors.Success,
            enabled = !buildRunning,
            onClick = onBuild,
        )
        SidebarAction(
            label = "Run tests",
            icon = Icons.Filled.CheckCircle,
            tint = if (buildRunning) TitanColors.TextMuted else TitanColors.Secondary,
            enabled = !buildRunning,
            onClick = onTest,
        )
        SidebarAction(
            label = "Clean",
            icon = Icons.Filled.Delete,
            tint = if (buildRunning) TitanColors.TextMuted else TitanColors.TextMuted,
            enabled = !buildRunning,
            onClick = onClean,
        )
        SidebarAction(
            label = "Rebuild",
            icon = Icons.Filled.Refresh,
            tint = if (buildRunning) TitanColors.TextMuted else TitanColors.TextMuted,
            enabled = !buildRunning,
            onClick = onRebuild,
        )
        SidebarAction(
            label = "Run",
            icon = Icons.Filled.PlayArrow,
            tint = if (!canRun || buildRunning) TitanColors.TextMuted else TitanColors.Success,
            enabled = canRun && !buildRunning,
            onClick = onRun,
        )
        SidebarAction(
            label = when {
                installInProgress -> "Installing..."
                else -> "Install APK on this device"
            },
            icon = Icons.Filled.InstallMobile,
            tint = if (hasApk && !installInProgress) TitanColors.Primary else TitanColors.TextMuted,
            enabled = hasApk && !installInProgress,
            onClick = onInstall,
        )
        if (installedPackage != null) {
            SidebarAction(
                label = "Launch $installedPackage",
                icon = Icons.Filled.OpenInNew,
                tint = TitanColors.Success,
                onClick = onLaunch,
            )
        }
        if (buildRunning) {
            SidebarAction("Stop build", Icons.Filled.Close, TitanColors.Error, onClick = onStop)
        }
        SidebarAction("Open another project", Icons.Filled.Source, TitanColors.Secondary, onClick = onOpenProject)

        Text(
            text = runHint ?: "Builds and tests run through the build engine on the target " +
                "chosen in Settings. Running an Android app needs a device or emulator.",
            color = TitanColors.TextMuted,
            fontSize = 10.sp,
            lineHeight = 14.sp,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}

/** Extensions: installed module inventory, which is real, rather than a fake store. */
@Composable
fun ExtensionsSidebar(modifier: Modifier = Modifier) {
    val modules = listOf(
        "Editor engine" to "Syntax highlighting, folding, completions",
        "CodeGuard" to "Typo, syntax and semantic analysis",
        "Build engine" to "Local, remote agent and GitHub Actions",
        "Git" to "Clone, commit, branch, diff",
        "OpenCode agent" to "Remote autonomous coding agent",
        "Templates" to "Android, game, extension and blank projects",
    )
    Column(modifier = modifier.fillMaxWidth()) {
        PanelHeader(title = "Extensions")
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(modules) { (name, detail) ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        Icons.Filled.Extension,
                        contentDescription = null,
                        tint = TitanColors.Primary,
                        modifier = Modifier.size(12.dp).padding(top = 1.dp),
                    )
                    Spacer(Modifier.width(7.dp))
                    Column {
                        Text(text = name, color = TitanColors.TextPrimary, fontSize = 11.sp)
                        Text(text = detail, color = TitanColors.TextMuted, fontSize = 9.5.sp, lineHeight = 13.sp)
                    }
                }
            }
        }
    }
}

/** GitHub: repository state, with the actions that exist. */
@Composable
fun GitHubSidebar(onOpenProject: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        PanelHeader(title = "GitHub")
        SidebarAction(
            label = "Import a repository",
            icon = Icons.Filled.Source,
            tint = TitanColors.Secondary,
            onClick = onOpenProject,
        )
        Text(
            text = "Sign in and browse repositories from the Projects screen. Cloned " +
                "repositories open here as projects.",
            color = TitanColors.TextMuted,
            fontSize = 10.sp,
            lineHeight = 14.sp,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}

/** Agent status view: the same session the panel shows, in a compact form. */
@Composable
fun AgentSidebar(agent: OpenCodeState, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        PanelHeader(title = "OpenCode")
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.SmartToy,
                contentDescription = null,
                tint = if (agent.isConnected) TitanColors.Success else TitanColors.TextMuted,
                modifier = Modifier.size(12.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = when {
                    agent.isConnected && agent.running -> "Working"
                    agent.isConnected -> "Ready"
                    else -> "Offline"
                },
                color = TitanColors.TextPrimary,
                fontSize = 11.sp,
            )
        }
        agent.currentTool?.let { tool ->
            Text(
                text = "Running: ${tool.name}",
                color = TitanColors.Warning,
                fontSize = 10.sp,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
            )
        }
        if (agent.changedFiles.isNotEmpty()) {
            Text(
                text = "${agent.changedFiles.size} files changed by the agent",
                color = TitanColors.TextMuted,
                fontSize = 10.sp,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun SidebarAction(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(7.dp))
        Text(
            text = label,
            color = TitanColors.TextPrimary.copy(alpha = if (enabled) 0.9f else 0.4f),
            fontSize = 11.sp,
        )
    }
}
