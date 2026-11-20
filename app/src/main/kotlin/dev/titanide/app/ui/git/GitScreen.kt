package dev.titanide.app.ui.git

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CallMerge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.titanide.app.LocalContainer
import dev.titanide.app.ui.common.TitanTopBar
import dev.titanide.app.ui.common.formatRelativeTime
import dev.titanide.core.designsystem.TitanColors
import dev.titanide.core.designsystem.components.EmptyState
import dev.titanide.core.designsystem.components.StatusPill
import dev.titanide.core.designsystem.components.TitanCard

@Composable
fun GitScreen(projectId: String, onBack: () -> Unit) {
    val container = LocalContainer.current
    val viewModel: GitViewModel = viewModel { GitViewModel(container, projectId) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        TitanTopBar(
            title = "Git",
            subtitle = state.branch?.let { "${state.project?.name} · $it" } ?: state.project?.name,
            onBack = onBack,
        )

        if (!state.isRepository) {
            EmptyState(
                icon = Icons.Filled.CallMerge,
                title = "Not a Git repository",
                message = "Initialise version control for this project to commit and push changes.",
                action = {
                    Button(
                        onClick = viewModel::initRepository,
                        colors = ButtonDefaults.buttonColors(containerColor = TitanColors.Primary),
                    ) { Text("git init") }
                },
            )
            return@Column
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            state.message?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = TitanColors.Success)
            }
            state.error?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = TitanColors.Error)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = viewModel::pull, enabled = !state.busy, shape = RoundedCornerShape(10.dp)) {
                    Icon(Icons.Filled.ArrowDownward, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Pull")
                }
                OutlinedButton(onClick = viewModel::push, enabled = !state.busy, shape = RoundedCornerShape(10.dp)) {
                    Icon(Icons.Filled.ArrowUpward, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Push")
                }
                if (!state.hasToken) {
                    StatusPill("token needed to push", TitanColors.Warning)
                }
            }

            // Changes
            Text(
                "Changes (${state.status.size})",
                style = MaterialTheme.typography.titleSmall,
                color = TitanColors.TextPrimary,
            )
            if (state.status.isEmpty()) {
                Text("Working tree clean", style = MaterialTheme.typography.labelSmall, color = TitanColors.TextMuted)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = viewModel::stageAll, shape = RoundedCornerShape(10.dp)) {
                        Text("Stage all", style = MaterialTheme.typography.labelSmall)
                    }
                    FilterChip(
                        selected = state.stagedDiff,
                        onClick = viewModel::toggleStagedDiff,
                        label = { Text("Staged diff", style = MaterialTheme.typography.labelSmall) },
                    )
                }
                state.status.forEach { entry ->
                    Surface(
                        onClick = { if (entry.staged) viewModel.unstage(entry.path) else viewModel.stage(entry.path) },
                        color = TitanColors.Surface,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                entry.status.code.toString(),
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                color = when (entry.status.code) {
                                    'A' -> TitanColors.Success
                                    'D' -> TitanColors.Error
                                    'U' -> TitanColors.Warning
                                    else -> TitanColors.Secondary
                                },
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                entry.path,
                                style = MaterialTheme.typography.bodySmall,
                                color = TitanColors.TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            StatusPill(if (entry.staged) "staged" else "unstaged", TitanColors.TextMuted)
                        }
                    }
                }
            }

            if (state.diff.isNotEmpty()) {
                Text(
                    if (state.stagedDiff) "Staged diff" else "Working tree diff",
                    style = MaterialTheme.typography.titleSmall,
                    color = TitanColors.TextPrimary,
                )
                Surface(color = TitanColors.CanvasElevated, shape = RoundedCornerShape(8.dp)) {
                    Column(Modifier.padding(8.dp)) {
                        state.diff.take(300).forEach { line ->
                            Text(
                                text = line.text,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                ),
                                color = when (line.type.name) {
                                    "ADDED" -> TitanColors.Success
                                    "REMOVED" -> TitanColors.Error
                                    else -> TitanColors.TextMuted
                                },
                            )
                        }
                    }
                }
            }

            // Commit
            OutlinedTextField(
                value = state.commitMessage,
                onValueChange = viewModel::setCommitMessage,
                label = { Text("Commit message") },
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodySmall,
                maxLines = 3,
            )
            Button(
                onClick = viewModel::commit,
                enabled = !state.busy && state.commitMessage.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(44.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = TitanColors.Primary),
            ) { Text("Commit staged changes") }

            // Branches
            Text("Branches", style = MaterialTheme.typography.titleSmall, color = TitanColors.TextPrimary)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = state.newBranchName,
                    onValueChange = viewModel::setNewBranchName,
                    label = { Text("New branch") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall,
                )
                Button(
                    onClick = viewModel::createBranch,
                    enabled = state.newBranchName.isNotBlank(),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = TitanColors.Primary),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(15.dp))
                }
            }
            state.branches.forEach { branch ->
                Surface(
                    onClick = { viewModel.checkout(branch.name) },
                    color = if (branch.name == state.branch) TitanColors.Surface else Color.Transparent,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.CallMerge,
                            contentDescription = null,
                            tint = if (branch.name == state.branch) TitanColors.Success else TitanColors.TextMuted,
                            modifier = Modifier.size(13.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            branch.name,
                            style = MaterialTheme.typography.bodySmall,
                            color = TitanColors.TextPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        if (branch.isDefault) StatusPill("default", TitanColors.TextMuted)
                        if (branch.name == state.branch) StatusPill("HEAD", TitanColors.Success)
                    }
                }
            }

            // History
            Text("History", style = MaterialTheme.typography.titleSmall, color = TitanColors.TextPrimary)
            state.commits.forEach { commit ->
                TitanCard {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            commit.sha.take(7),
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = TitanColors.Secondary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                commit.message.lineSequence().first(),
                                style = MaterialTheme.typography.bodySmall,
                                color = TitanColors.TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${commit.author} · ${formatRelativeTime(commit.timestamp)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = TitanColors.TextMuted,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
        }
    }
}
