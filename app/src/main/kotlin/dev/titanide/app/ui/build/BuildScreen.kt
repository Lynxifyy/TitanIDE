package dev.titanide.app.ui.build

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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import dev.titanide.app.ui.common.formatDuration
import dev.titanide.core.designsystem.TitanColors
import dev.titanide.core.designsystem.components.EmptyState
import dev.titanide.core.designsystem.components.StatusPill
import dev.titanide.core.designsystem.components.TitanCard
import dev.titanide.core.model.BuildKind
import dev.titanide.core.model.BuildResult
import dev.titanide.core.model.BuildStatus
import dev.titanide.core.model.ExecutionTarget
import dev.titanide.core.model.Severity

@Composable
fun BuildScreen(
    projectId: String,
    onBack: () -> Unit,
    onOpenAi: () -> Unit,
) {
    val container = LocalContainer.current
    BuildScreen(
        viewModel = viewModel { BuildViewModel(container, projectId) },
        onBack = onBack,
        onOpenAi = onOpenAi,
    )
}

@Composable
fun BuildScreen(
    viewModel: BuildViewModel,
    onBack: () -> Unit,
    onOpenAi: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        TitanTopBar(
            title = "Build",
            subtitle = state.project?.name ?: "No project",
            onBack = onBack,
            actions = {
                IconButton(onClick = viewModel::probeToolchains) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Re-probe toolchain")
                }
            },
        )

        if (state.project == null) {
            EmptyState(
                icon = Icons.Filled.Build,
                title = "No project selected",
                message = "Open a project before building.",
            )
            return@Column
        }

        Column(Modifier.fillMaxSize().padding(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ExecutionTarget.entries.forEach { target ->
                    FilterChip(
                        selected = state.target == target,
                        onClick = { viewModel.setTarget(target) },
                        label = { Text(target.label.take(14), style = MaterialTheme.typography.labelSmall, maxLines = 1) },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            val running = state.running != null
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                if (running) {
                    Button(
                        onClick = viewModel::stop,
                        modifier = Modifier.weight(1f).height(44.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = TitanColors.Error),
                    ) {
                        Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Stop")
                    }
                } else {
                    Button(
                        onClick = { viewModel.run(BuildKind.BUILD) },
                        modifier = Modifier.weight(1f).height(44.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = TitanColors.Primary),
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Build")
                    }
                }
                OutlinedButton(
                    onClick = { viewModel.run(BuildKind.REBUILD) },
                    enabled = !running,
                    modifier = Modifier.height(44.dp),
                    shape = RoundedCornerShape(12.dp),
                ) { Text("Rebuild") }
                OutlinedButton(
                    onClick = { viewModel.run(BuildKind.TEST) },
                    enabled = !running,
                    modifier = Modifier.height(44.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.Filled.Science, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Test")
                }
                IconButton(
                    onClick = { viewModel.run(BuildKind.CLEAN) },
                    enabled = !running,
                ) { Icon(Icons.Filled.CleaningServices, contentDescription = "Clean") }
            }

            state.message?.let {
                Text(it, color = TitanColors.Warning, style = MaterialTheme.typography.labelSmall)
            }

            state.toolchains?.let { report ->
                Spacer(Modifier.height(6.dp))
                Text(
                    "${report.availableCount} of ${report.tools.size} available on this device",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (report.availableCount == 0) TitanColors.Warning else TitanColors.TextMuted,
                )
            }

            Spacer(Modifier.height(8.dp))

            val result = state.last
            if (result != null) {
                ResultSummary(result, onOpenAi)
                Spacer(Modifier.height(8.dp))
            } else if (state.running != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = TitanColors.Secondary)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${state.running?.status?.name ?: "RUNNING"} · ${state.running?.executorLabel.orEmpty()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TitanColors.TextMuted,
                    )
                }
                Spacer(Modifier.height(8.dp))
            }

            LogConsole(state.logLines, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ResultSummary(result: BuildResult, onOpenAi: () -> Unit) {
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
                Spacer(Modifier.weight(1f))
                Text(
                    result.executorLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = TitanColors.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            result.failureReason?.let { reason ->
                Spacer(Modifier.height(6.dp))
                Text(reason, style = MaterialTheme.typography.bodySmall, color = TitanColors.Error)
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = onOpenAi) {
                    Icon(Icons.Filled.Psychology, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Ask TITAN to fix")
                }
            }

            if (result.errorCount > 0 || result.warningCount > 0) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "${result.errorCount} errors · ${result.warningCount} warnings",
                    style = MaterialTheme.typography.labelSmall,
                    color = TitanColors.TextMuted,
                )
            }

            result.testSummary?.let { tests ->
                Spacer(Modifier.height(6.dp))
                Text(
                    "Tests: ${tests.passed} passed, ${tests.failed} failed, ${tests.skipped} skipped",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (tests.failed == 0) TitanColors.Success else TitanColors.Error,
                )
            }

            if (result.diagnostics.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.height(110.dp)) {
                    items(result.diagnostics.take(30)) { diagnostic ->
                        Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
                            Text(
                                if (diagnostic.severity == Severity.ERROR) "E" else "W",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (diagnostic.severity == Severity.ERROR) TitanColors.Error else TitanColors.Warning,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "${diagnostic.filePath}:${diagnostic.line + 1} ${diagnostic.message}",
                                style = MaterialTheme.typography.labelSmall,
                                color = TitanColors.TextMuted,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            if (result.artifacts.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("Artifacts", style = MaterialTheme.typography.labelSmall, color = TitanColors.TextPrimary)
                result.artifacts.take(6).forEach { artifact ->
                    Text(
                        "· ${artifact.name} (${dev.titanide.app.ui.common.formatBytes(artifact.sizeBytes)})",
                        style = MaterialTheme.typography.labelSmall,
                        color = TitanColors.Secondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun LogConsole(lines: List<String>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex)
    }

    Surface(
        color = TitanColors.CanvasElevated,
        shape = RoundedCornerShape(10.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(10.dp)) {
            Text(
                "BUILD OUTPUT",
                style = MaterialTheme.typography.labelSmall,
                color = TitanColors.TextMuted,
            )
            Spacer(Modifier.height(6.dp))
            if (lines.isEmpty()) {
                Text(
                    "No output yet. Start a build to see real compiler output here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TitanColors.TextMuted,
                )
            } else {
                LazyColumn(state = listState) {
                    items(lines) { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                            ),
                            color = colorForLine(line),
                        )
                    }
                }
            }
        }
    }
}

private fun colorForLine(line: String): Color = when {
    line.contains("error", ignoreCase = true) -> TitanColors.Error
    line.contains("warning", ignoreCase = true) -> TitanColors.Warning
    line.startsWith(">") -> TitanColors.Secondary
    line.contains("BUILD SUCCESSFUL") -> TitanColors.Success
    line.contains("FAILED") -> TitanColors.Error
    else -> TitanColors.TextMuted
}
