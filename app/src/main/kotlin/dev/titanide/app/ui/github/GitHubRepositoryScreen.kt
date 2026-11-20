package dev.titanide.app.ui.github

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.titanide.app.LocalContainer
import dev.titanide.app.ui.common.DetailRow
import dev.titanide.app.ui.common.TitanTopBar
import dev.titanide.app.ui.common.formatDuration
import dev.titanide.app.ui.common.formatRelativeTime
import dev.titanide.core.designsystem.TitanColors
import dev.titanide.core.designsystem.components.StatusPill
import dev.titanide.core.designsystem.components.TitanCard
import dev.titanide.core.model.BuildStatus
import dev.titanide.core.model.ExecutionTarget

@Composable
fun GitHubRepositoryScreen(
    fullName: String,
    onBack: () -> Unit,
    onImported: (String) -> Unit,
    onBuild: (String) -> Unit,
) {
    val container = LocalContainer.current
    val viewModel: GitHubViewModel = viewModel { GitHubViewModel(container, fullName) }
    val state by viewModel.detail.collectAsStateWithLifecycle()
    val repo = state.repo

    Column(Modifier.fillMaxSize()) {
        TitanTopBar(
            title = repo?.name ?: fullName,
            subtitle = repo?.owner,
            onBack = onBack,
        )

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            state.error?.let {
                TitanCard(accent = TitanColors.Error) {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = TitanColors.Error,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            TitanCard {
                Column(Modifier.padding(12.dp)) {
                    repo?.let {
                        DetailRow("Repository", it.fullName)
                        DetailRow("Language", it.language ?: "unknown")
                        DetailRow("Default branch", it.defaultBranch)
                        DetailRow("Visibility", if (it.isPrivate) "Private" else "Public")
                        DetailRow("Updated", it.updatedAt.take(10))
                        DetailRow("Stars", it.stars.toString())
                    }
                    DetailRow("Branches", state.branches.size.toString())
                    DetailRow("Commits loaded", state.commits.size.toString())
                }
            }

            state.detected?.let { detected ->
                TitanCard(accent = TitanColors.Secondary) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Project analysis", style = MaterialTheme.typography.titleSmall, color = TitanColors.TextPrimary)
                        Spacer(Modifier.height(4.dp))
                        DetailRow("Build system", detected.buildSystem)
                        DetailRow("Languages", detected.languages.joinToString(", ").ifBlank { "none detected" })
                        DetailRow("Build tasks", detected.suggestedTasks.joinToString(", ").ifBlank { "none" })
                        DetailRow("Test tasks", detected.testTasks.joinToString(", ").ifBlank { "none" })
                        detected.notes.forEach { note ->
                            Text(
                                "· $note",
                                style = MaterialTheme.typography.labelSmall,
                                color = TitanColors.Warning,
                            )
                        }
                    }
                }
            }

            if (state.importing) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = TitanColors.Secondary)
                    Spacer(Modifier.width(8.dp))
                    Text(state.importStage, style = MaterialTheme.typography.labelSmall, color = TitanColors.TextMuted)
                }
            }

            if (state.importedProjectId == null) {
                Button(
                    onClick = viewModel::importRepository,
                    enabled = !state.importing,
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = TitanColors.Primary),
                ) {
                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Clone into TITAN")
                }
            } else {
                Button(
                    onClick = { state.importedProjectId?.let(onImported) },
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = TitanColors.Primary),
                ) { Text("Open imported project") }

                Text("Run the project's tests", style = MaterialTheme.typography.titleSmall, color = TitanColors.TextPrimary)
                Text(
                    "Runs the detected test task through the build engine. A device without the needed " +
                        "toolchain fails with the real error rather than a fake pass.",
                    style = MaterialTheme.typography.labelSmall,
                    color = TitanColors.TextMuted,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ExecutionTarget.entries.forEach { target ->
                        OutlinedButton(
                            onClick = { viewModel.testProject(target) },
                            enabled = !state.testing,
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Text(target.label.take(16), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                        }
                    }
                }

                if (state.testing) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(13.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Running tests...", style = MaterialTheme.typography.labelSmall, color = TitanColors.TextMuted)
                    }
                }

                state.testResult?.let { result ->
                    TitanCard(
                        accent = if (result.status == BuildStatus.SUCCEEDED) TitanColors.Success else TitanColors.Error,
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                StatusPill(
                                    result.status.name,
                                    if (result.status == BuildStatus.SUCCEEDED) TitanColors.Success else TitanColors.Error,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    formatDuration(result.durationMs),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TitanColors.TextMuted,
                                )
                            }
                            result.failureReason?.let {
                                Spacer(Modifier.height(4.dp))
                                Text(it, style = MaterialTheme.typography.bodySmall, color = TitanColors.Error)
                            }
                            result.testSummary?.let { tests ->
                                Text(
                                    "${tests.passed} passed · ${tests.failed} failed · ${tests.skipped} skipped",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (tests.failed == 0) TitanColors.Success else TitanColors.Error,
                                )
                            }
                            if (result.logLines.isNotEmpty()) {
                                Spacer(Modifier.height(6.dp))
                                Surface(
                                    color = TitanColors.Canvas,
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp),
                                ) {
                                    Column(
                                        Modifier
                                            .padding(8.dp)
                                            .verticalScroll(rememberScrollState()),
                                    ) {
                                        result.logLines.takeLast(120).forEach { line ->
                                            Text(
                                                line,
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontFamily = FontFamily.Monospace,
                                                    fontSize = 10.sp,
                                                ),
                                                color = TitanColors.TextMuted,
                                            )
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            OutlinedButton(onClick = { state.importedProjectId?.let(onBuild) }) {
                                Text("Open in Build")
                            }
                        }
                    }
                }
            }

            if (state.commits.isNotEmpty()) {
                Text("Recent commits", style = MaterialTheme.typography.titleSmall, color = TitanColors.TextPrimary)
                state.commits.take(10).forEach { commit ->
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
                                    commit.message.lineSequence().first().take(70),
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
            }

            if (state.entries.isNotEmpty()) {
                Text(
                    "Files${if (state.path.isNotBlank()) " in ${state.path}" else ""}",
                    style = MaterialTheme.typography.titleSmall,
                    color = TitanColors.TextPrimary,
                )
                state.entries.take(40).forEach { entry ->
                    Surface(
                        onClick = { if (entry.isDirectory) viewModel.openPath(entry.path) },
                        color = TitanColors.Surface,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (entry.isDirectory) Icons.Filled.Folder else Icons.Filled.InsertDriveFile,
                                contentDescription = null,
                                tint = if (entry.isDirectory) TitanColors.Warning else TitanColors.TextMuted,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                entry.name,
                                style = MaterialTheme.typography.bodySmall,
                                color = TitanColors.TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}
