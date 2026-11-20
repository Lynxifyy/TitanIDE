package dev.titanide.app.ui.workbench

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.titanide.core.designsystem.TitanColors
import dev.titanide.core.opencode.model.PermissionDecision
import dev.titanide.core.opencode.model.PermissionRequest
import dev.titanide.core.opencode.session.AgentActivity
import dev.titanide.core.opencode.session.AgentStatusLine
import dev.titanide.core.opencode.session.OpenCodeConnection
import dev.titanide.core.opencode.session.OpenCodeState

/**
 * The agent panel. This is the OpenCode surface: it shows the connection's real state, the
 * server's own transcript, the server's pending permission requests, and the files the
 * server reports as changed.
 *
 * Nothing here is optimistic. A message appears because the server returned it; a tool is
 * shown as running because the server says so; an approval is answered by posting the
 * decision back to the server.
 */
@Composable
fun AgentPanel(
    state: OpenCodeState,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onAnswerPermission: (String, PermissionDecision) -> Unit,
    onReconnect: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Follow the transcript as the agent works, which is what a user expects from a log.
    LaunchedEffect(state.activity.size) {
        if (state.activity.isNotEmpty()) {
            listState.animateScrollToItem((state.activity.size - 1).coerceAtLeast(0))
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(TitanColors.CanvasElevated),
    ) {
        AgentHeader(state = state, onReconnect = onReconnect, onClose = onClose)
        HorizontalDivider()

        when (val connection = state.connection) {
            is OpenCodeConnection.Failed -> ConnectionProblem(connection.reason, onReconnect)
            OpenCodeConnection.Idle -> ConnectionProblem("Not connected. Set the OpenCode server URL in Settings.", onReconnect)
            is OpenCodeConnection.Connecting -> ConnectingState(connection.url)
            is OpenCodeConnection.Connected -> {
                if (state.pendingPermissions.isNotEmpty()) {
                    PermissionGate(
                        request = state.pendingPermissions.first(),
                        remaining = state.pendingPermissions.size - 1,
                        onAnswer = onAnswerPermission,
                    )
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) {
                    if (state.activity.isEmpty()) {
                        item { AgentIdleHint() }
                    }
                    items(state.activity) { activity ->
                        ActivityRow(activity)
                    }
                }
                if (state.changedFiles.isNotEmpty()) {
                    HorizontalDivider()
                    ChangedFilesStrip(state.changedFiles.map { it.file to it.status })
                }
                HorizontalDivider()
                AgentComposer(
                    draft = draft,
                    onDraftChange = { draft = it },
                    running = state.running,
                    onSend = {
                        if (draft.isNotBlank()) {
                            onSend(draft)
                            draft = ""
                        }
                    },
                    onStop = onStop,
                )
                state.lastError?.let { error ->
                    Text(
                        text = error,
                        color = TitanColors.Error,
                        fontSize = 10.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(TitanColors.Error.copy(alpha = 0.1f))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun AgentHeader(state: OpenCodeState, onReconnect: () -> Unit, onClose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(WorkbenchMetrics.PanelHeaderHeight)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.SmartToy,
            contentDescription = null,
            tint = TitanColors.Primary,
            modifier = Modifier.size(13.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "OPENCODE",
            color = TitanColors.TextMuted,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp,
        )
        Spacer(Modifier.width(6.dp))
        if (state.running) {
            StatusChip("working", TitanColors.Warning)
        }
        Spacer(Modifier.weight(1f))
        DenseIconButton(Icons.Filled.Refresh, "Reconnect", onReconnect)
        DenseIconButton(Icons.Filled.Close, "Close agent panel", onClose)
    }
}

@Composable
private fun ConnectionProblem(reason: String, onReconnect: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(14.dp),
    ) {
        Text(
            text = "Agent offline",
            color = TitanColors.TextPrimary,
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = reason,
            color = TitanColors.TextMuted,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(TitanColors.Primary)
                .clickable(onClick = onReconnect)
                .padding(horizontal = 14.dp, vertical = 7.dp),
        ) {
            Text(
                text = "Reconnect",
                color = TitanColors.TextPrimary,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun ConnectingState(url: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Connecting to $url",
            color = TitanColors.TextMuted,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun AgentIdleHint() {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Text(
            text = "Agent ready",
            color = TitanColors.TextPrimary,
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Ask for a change, a fix, or an explanation. The agent edits files in " +
                "the project directory and reports every tool call here.",
            color = TitanColors.TextMuted,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ActivityRow(activity: AgentActivity) {
    when (activity) {
        is AgentActivity.UserMessage -> Bubble(
            label = "You",
            text = activity.text,
            color = TitanColors.Secondary,
        )

        is AgentActivity.AssistantText -> Bubble(
            label = "Agent",
            text = activity.text.ifBlank { "(no text)" },
            color = TitanColors.Primary,
            trailing = if (activity.streaming) "streaming" else null,
        )

        is AgentActivity.Reasoning -> Bubble(
            label = "Thinking",
            text = activity.text,
            color = TitanColors.TextMuted,
            dim = true,
        )

        is AgentActivity.ToolCall -> ToolRow(activity)

        is AgentActivity.ShellCommand -> ShellRow(activity)

        is AgentActivity.Other -> Bubble(
            label = activity.type,
            text = "",
            color = TitanColors.TextMuted,
            dim = true,
        )
    }
}

@Composable
private fun Bubble(
    label: String,
    text: String,
    color: Color,
    dim: Boolean = false,
    trailing: String? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                color = color,
                fontSize = 9.5.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.5.sp,
            )
            trailing?.let {
                Spacer(Modifier.width(6.dp))
                Text(text = it, color = TitanColors.TextMuted, fontSize = 9.sp)
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = text,
            color = if (dim) TitanColors.TextMuted else TitanColors.TextPrimary.copy(alpha = 0.92f),
            fontSize = 11.5.sp,
            lineHeight = 16.sp,
        )
    }
}

@Composable
private fun ToolRow(tool: AgentActivity.ToolCall) {
    val accent = when {
        tool.isError -> TitanColors.Error
        tool.isRunning -> TitanColors.Warning
        else -> TitanColors.Success
    }
    val status = AgentStatusLine.of(tool)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(TitanColors.Surface)
            .padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(5.dp).background(accent, RoundedCornerShape(3.dp)))
            Spacer(Modifier.width(6.dp))
            Text(
                text = status.label,
                color = TitanColors.TextPrimary,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = tool.state,
                color = accent,
                fontSize = 9.sp,
            )
        }
        if (status.detail.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = status.detail,
                color = TitanColors.TextMuted,
                fontSize = 9.5.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ShellRow(shell: AgentActivity.ShellCommand) {
    val failed = shell.exit != null && shell.exit != 0
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(TitanColors.Canvas)
            .border(1.dp, TitanColors.OutlineFaint, RoundedCornerShape(4.dp))
            .padding(8.dp),
    ) {
        Text(
            text = "$ ${shell.command}",
            color = TitanColors.Secondary,
            fontSize = 10.sp,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (shell.output.isNotBlank()) {
            Spacer(Modifier.height(3.dp))
            Text(
                text = shell.output.takeLast(600),
                color = if (failed) TitanColors.Error else TitanColors.TextMuted,
                fontSize = 9.5.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                maxLines = 8,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(3.dp))
        Text(
            text = "exit ${shell.exit ?: "?"}  ·  ${shell.status}",
            color = if (failed) TitanColors.Error else TitanColors.TextMuted,
            fontSize = 9.sp,
        )
    }
}

/**
 * The approval gate. The agent cannot proceed until the user answers, and the answer is
 * posted to the server as a permission decision.
 */
@Composable
private fun PermissionGate(
    request: PermissionRequest,
    remaining: Int,
    onAnswer: (String, PermissionDecision) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(TitanColors.Warning.copy(alpha = 0.1f))
            .border(1.dp, TitanColors.Warning.copy(alpha = 0.5f), RoundedCornerShape(5.dp))
            .padding(9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "APPROVAL REQUIRED",
                color = TitanColors.Warning,
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.6.sp,
            )
            if (remaining > 0) {
                Spacer(Modifier.width(6.dp))
                Text(text = "+$remaining more", color = TitanColors.TextMuted, fontSize = 9.sp)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = request.message ?: request.action.ifBlank { "The agent wants to run an action" },
            color = TitanColors.TextPrimary,
            fontSize = 11.sp,
            lineHeight = 15.sp,
        )
        if (request.resources.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = request.resources.take(4).joinToString("\n"),
                color = TitanColors.TextMuted,
                fontSize = 9.5.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PermissionButton("Allow once", TitanColors.Success) {
                onAnswer(request.id, PermissionDecision.ONCE)
            }
            PermissionButton("Always", TitanColors.Secondary) {
                onAnswer(request.id, PermissionDecision.ALWAYS)
            }
            PermissionButton("Reject", TitanColors.Error) {
                onAnswer(request.id, PermissionDecision.REJECT)
            }
        }
    }
}

@Composable
private fun PermissionButton(label: String, color: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(color.copy(alpha = 0.18f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            text = label,
            color = color,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun ChangedFilesStrip(files: List<Pair<String, String>>) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp)) {
        Text(
            text = "CHANGED FILES (${files.size})",
            color = TitanColors.TextMuted,
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.6.sp,
        )
        Spacer(Modifier.height(3.dp))
        files.take(5).forEach { (file, status) ->
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = status.first().uppercase(),
                    color = when (status) {
                        "added" -> TitanColors.GitAdded
                        "deleted" -> TitanColors.GitDeleted
                        else -> TitanColors.GitModified
                    },
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(12.dp),
                )
                Text(
                    text = file,
                    color = TitanColors.TextMuted,
                    fontSize = 9.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (files.size > 5) {
            Text(
                text = "+${files.size - 5} more",
                color = TitanColors.TextMuted,
                fontSize = 9.sp,
            )
        }
    }
}

@Composable
private fun AgentComposer(
    draft: String,
    onDraftChange: (String) -> Unit,
    running: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SearchField(
            value = draft,
            onValueChange = onDraftChange,
            placeholder = "Ask the agent to change something",
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(6.dp))
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (running) TitanColors.Error else TitanColors.Primary)
                .clickable { if (running) onStop() else onSend() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (running) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                contentDescription = if (running) "Stop the agent" else "Send prompt",
                tint = TitanColors.TextPrimary,
                modifier = Modifier.size(15.dp),
            )
        }
    }
}
