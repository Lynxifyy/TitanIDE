package dev.titanide.app.ui.workbench

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.titanide.core.designsystem.TitanColors
import dev.titanide.core.model.BuildResult
import dev.titanide.core.model.BuildStatus
import dev.titanide.core.model.Diagnostic
import dev.titanide.core.model.Severity
import dev.titanide.core.workbench.BottomTool

/**
 * The bottom tool window. Every tab shows something real: problems come from CodeGuard,
 * output and build logs come from the BuildEngine, tests come from the parsed test report.
 */
@Composable
fun BottomPanel(
    activeTool: BottomTool,
    onSelectTool: (BottomTool) -> Unit,
    onCollapse: () -> Unit,
    diagnostics: List<Diagnostic>,
    buildResult: BuildResult?,
    buildLogs: List<String>,
    buildRunning: Boolean,
    onOpenDiagnostic: (Diagnostic) -> Unit,
    onRunBuild: () -> Unit,
    onStopBuild: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().background(TitanColors.CanvasElevated)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(WorkbenchMetrics.PanelHeaderHeight)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BottomTool.entries.forEach { tool ->
                val active = tool == activeTool
                val badge = when (tool) {
                    BottomTool.PROBLEMS -> diagnostics.size
                    BottomTool.TESTS -> buildResult?.testSummary?.total ?: 0
                    else -> 0
                }
                Row(
                    modifier = Modifier
                        .height(WorkbenchMetrics.PanelHeaderHeight)
                        .clickable { onSelectTool(tool) }
                        .padding(horizontal = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = tool.label.uppercase(),
                        color = if (active) TitanColors.TextPrimary else TitanColors.TextMuted,
                        fontSize = 9.5.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        letterSpacing = 0.6.sp,
                    )
                    if (badge > 0) {
                        Spacer(Modifier.width(4.dp))
                        StatusChip(badge.toString(), if (tool == BottomTool.PROBLEMS) TitanColors.Warning else TitanColors.Secondary)
                    }
                    if (active) {
                        Spacer(Modifier.width(8.dp))
                        Box(
                            Modifier
                                .height(1.dp)
                                .width(14.dp)
                                .background(TitanColors.Primary),
                        )
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            if (buildRunning) {
                DenseIconButton(Icons.Filled.Stop, "Stop build", onStopBuild, tint = TitanColors.Error)
            } else {
                DenseIconButton(Icons.Filled.PlayArrow, "Build", onRunBuild)
            }
            DenseIconButton(Icons.Filled.ChevronLeft, "Collapse panel", onCollapse)
        }
        HorizontalDivider()
        Box(modifier = Modifier.fillMaxSize()) {
            when (activeTool) {
                BottomTool.PROBLEMS -> ProblemsView(diagnostics, onOpenDiagnostic)
                BottomTool.OUTPUT -> LogView(buildLogs, emptyMessage = "No build output yet.")
                BottomTool.BUILD -> BuildView(buildResult, buildLogs)
                BottomTool.TESTS -> TestsView(buildResult)
                BottomTool.TERMINAL -> TerminalView()
                BottomTool.DEBUG_CONSOLE -> LogView(buildLogs, emptyMessage = "The debug console is empty.")
                BottomTool.LOGCAT -> LogcatView()
            }
        }
    }
}

@Composable
private fun ProblemsView(diagnostics: List<Diagnostic>, onOpen: (Diagnostic) -> Unit) {
    if (diagnostics.isEmpty()) {
        PanelEmptyState(
            icon = Icons.Filled.PlayArrow,
            title = "No problems detected",
            detail = "CodeGuard found no issues in the open file.",
        )
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(diagnostics) { diagnostic ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(diagnostic) }
                    .padding(horizontal = 10.dp, vertical = 3.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = when (diagnostic.severity) {
                        Severity.ERROR -> "E"
                        Severity.WARNING -> "W"
                        Severity.INFO -> "I"
                        Severity.HINT -> "H"
                    },
                    color = when (diagnostic.severity) {
                        Severity.ERROR -> TitanColors.Error
                        Severity.WARNING -> TitanColors.Warning
                        Severity.INFO -> TitanColors.Info
                        Severity.HINT -> TitanColors.TextMuted
                    },
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(12.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = diagnostic.message,
                        color = TitanColors.TextPrimary.copy(alpha = 0.9f),
                        fontSize = 10.5.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${diagnostic.filePath.substringAfterLast('/')}:${diagnostic.line}",
                        color = TitanColors.TextMuted,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

@Composable
private fun LogView(lines: List<String>, emptyMessage: String) {
    if (lines.isEmpty()) {
        PanelEmptyState(
            icon = Icons.Filled.PlayArrow,
            title = "Nothing to show",
            detail = emptyMessage,
        )
        return
    }
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex)
    }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        items(lines) { line ->
            Text(
                text = line,
                color = logColor(line),
                fontSize = 9.5.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 13.sp,
            )
        }
    }
}

/** Colour by real log content, so errors stand out without a separate channel. */
private fun logColor(line: String): Color = when {
    line.contains("BUILD SUCCESSFUL") || line.contains("SUCCESS") -> TitanColors.Success
    line.contains("FAILURE") || line.contains("error:", ignoreCase = true) -> TitanColors.Error
    line.contains("warning:", ignoreCase = true) -> TitanColors.Warning
    line.startsWith(">") -> TitanColors.Secondary
    else -> TitanColors.TextMuted
}

@Composable
private fun BuildView(result: BuildResult?, logs: List<String>) {
    if (result == null) {
        PanelEmptyState(
            icon = Icons.Filled.PlayArrow,
            title = "No builds yet",
            detail = "Run a build to see stages, diagnostics and artifacts here.",
        )
        return
    }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusChip(
                label = result.status.name,
                color = when (result.status) {
                    BuildStatus.SUCCEEDED -> TitanColors.Success
                    BuildStatus.FAILED -> TitanColors.Error
                    BuildStatus.CANCELLED -> TitanColors.TextMuted
                    else -> TitanColors.Warning
                },
            )
            Text(
                text = result.executorLabel,
                color = TitanColors.TextMuted,
                fontSize = 9.5.sp,
            )
            val duration = (result.finishedAt ?: System.currentTimeMillis()) - result.startedAt
            Text(text = "${duration}ms", color = TitanColors.TextMuted, fontSize = 9.5.sp)
            if (result.artifacts.isNotEmpty()) {
                StatusChip("${result.artifacts.size} artifacts", TitanColors.Secondary)
            }
        }
        result.stages.forEach { stage ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .background(
                            when (stage.ok) {
                                true -> TitanColors.Success
                                false -> TitanColors.Error
                                null -> TitanColors.Warning
                            },
                        ),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stage.name,
                    color = TitanColors.TextPrimary.copy(alpha = 0.85f),
                    fontSize = 10.sp,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "${(stage.finishedAt ?: System.currentTimeMillis()) - stage.startedAt}ms",
                    color = TitanColors.TextMuted,
                    fontSize = 9.sp,
                )
            }
        }
        result.failureReason?.let { reason ->
            Text(
                text = reason,
                color = TitanColors.Error,
                fontSize = 10.sp,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        HorizontalDivider()
        Box(modifier = Modifier.weight(1f)) {
            LogView(logs, "The build produced no output.")
        }
    }
}

@Composable
private fun TestsView(result: BuildResult?) {
    val summary = result?.testSummary
    if (summary == null) {
        PanelEmptyState(
            icon = Icons.Filled.PlayArrow,
            title = "No test results",
            detail = "Run the project tests to see per-case results.",
        )
        return
    }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            StatusChip("${summary.total} total", TitanColors.TextMuted)
            StatusChip("${summary.passed} passed", TitanColors.Success)
            if (summary.failed > 0) StatusChip("${summary.failed} failed", TitanColors.Error)
            if (summary.skipped > 0) StatusChip("${summary.skipped} skipped", TitanColors.Warning)
        }
        HorizontalDivider()
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(summary.cases) { case ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (case.passed) "PASS" else "FAIL",
                        color = if (case.passed) TitanColors.Success else TitanColors.Error,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.width(32.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = case.name,
                            color = TitanColors.TextPrimary.copy(alpha = 0.85f),
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = case.suite,
                            color = TitanColors.TextMuted,
                            fontSize = 9.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(text = "${case.durationMs}ms", color = TitanColors.TextMuted, fontSize = 9.sp)
                }
            }
        }
    }
}

/**
 * The terminal tab. TITAN has no on-device POSIX shell it can legally spawn, so this is
 * explicit about that rather than rendering a prompt that cannot execute anything.
 */
@Composable
private fun TerminalView() {
    Column(modifier = Modifier.fillMaxSize().padding(10.dp)) {
        Text(
            text = "Local shell unavailable",
            color = TitanColors.TextPrimary,
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Android does not expose a shell TITAN can spawn for interactive use. " +
                "Builds and tests still run locally through the build engine, and the " +
                "OpenCode agent runs commands on its own host.",
            color = TitanColors.TextMuted,
            fontSize = 10.5.sp,
            lineHeight = 15.sp,
        )
    }
}

@Composable
private fun LogcatView() {
    PanelEmptyState(
        icon = Icons.Filled.PlayArrow,
        title = "Logcat requires a connected device",
        detail = "Install an APK and connect a device to stream logs.",
    )
}

/** The bottom status bar. */
@Composable
fun StatusBar(
    branch: String?,
    dirtyCount: Int,
    errors: Int,
    warnings: Int,
    line: Int,
    column: Int,
    language: String?,
    buildState: String?,
    agentState: String?,
    onProblemsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(WorkbenchMetrics.StatusBarHeight)
            .background(TitanColors.PrimaryContainer.copy(alpha = 0.55f))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        branch?.let {
            StatusItem(text = "\u2387 $it${if (dirtyCount > 0) " ($dirtyCount)" else ""}", tint = TitanColors.TextPrimary)
        }
        StatusItem(
            text = "x $errors",
            tint = if (errors > 0) TitanColors.Error else TitanColors.TextMuted,
            onClick = onProblemsClick,
        )
        StatusItem(text = "! $warnings", tint = if (warnings > 0) TitanColors.Warning else TitanColors.TextMuted)
        Spacer(Modifier.weight(1f))
        buildState?.let { StatusItem(text = it, tint = TitanColors.TextPrimary) }
        agentState?.let { StatusItem(text = it, tint = TitanColors.Secondary) }
        language?.let { StatusItem(text = it, tint = TitanColors.TextPrimary) }
        StatusItem(text = "Ln $line, Col $column", tint = TitanColors.TextPrimary)
        StatusItem(text = "UTF-8", tint = TitanColors.TextPrimary)
    }
}

@Composable
private fun StatusItem(text: String, tint: Color, onClick: (() -> Unit)? = null) {
    Text(
        text = text,
        color = tint,
        fontSize = 9.5.sp,
        maxLines = 1,
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
    )
}
