package dev.titanide.app.ui.ai

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.titanide.app.LocalContainer
import dev.titanide.app.ui.common.TitanTopBar
import dev.titanide.core.ai.AiMode
import dev.titanide.core.ai.ChatMessage
import dev.titanide.core.ai.agent.AgentEvent
import dev.titanide.core.designsystem.TitanColors
import dev.titanide.core.designsystem.components.EmptyState
import dev.titanide.core.designsystem.components.StatusPill
import dev.titanide.core.designsystem.components.TitanCard

@Composable
fun AiStudioScreen(onOpenSettings: () -> Unit) {
    val container = LocalContainer.current
    val viewModel: AiViewModel = viewModel { AiViewModel(container) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    LaunchedEffect(state.conversation.size) {
        if (state.conversation.isNotEmpty()) listState.animateScrollToItem(state.conversation.lastIndex)
    }

    Column(Modifier.fillMaxSize()) {
        TitanTopBar(
            title = "TITAN Intelligence",
            subtitle = "${state.providerName} · ${state.project?.name ?: "no project"}",
            actions = {
                IconButton(onClick = viewModel::newConversation) {
                    Icon(Icons.Filled.Psychology, contentDescription = "New conversation")
                }
            },
        )

        if (!state.providerConfigured) {
            TitanCard(accent = TitanColors.Warning, modifier = Modifier.padding(12.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Text("No AI provider configured", style = MaterialTheme.typography.titleSmall, color = TitanColors.TextPrimary)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Add an endpoint and key in Settings, or point TITAN at a local model such as Ollama. " +
                            "Nothing is sent anywhere until you configure a provider.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TitanColors.TextMuted,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onOpenSettings) { Text("Open settings") }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AiMode.entries.forEach { mode ->
                FilterChip(
                    selected = state.mode == mode,
                    onClick = { viewModel.setMode(mode) },
                    label = { Text(mode.label, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }

        Text(
            state.mode.description,
            style = MaterialTheme.typography.labelSmall,
            color = TitanColors.TextMuted,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )

        if (state.mode == AiMode.AGENT) {
            AgentPanel(state, viewModel)
        } else {
            Box(Modifier.weight(1f)) {
                if (state.conversation.isEmpty()) {
                    EmptyState(
                        icon = Icons.Filled.Psychology,
                        title = "Ask about your project",
                        message = when (state.mode) {
                            AiMode.EXPLAIN -> "Open a file, then ask TITAN to explain it."
                            AiMode.INLINE_EDIT -> "Open a file and describe the change you want."
                            AiMode.REFACTOR -> "Describe how the active file should be restructured."
                            AiMode.DEBUG -> "Paste a failure or run a build, then ask for a fix."
                            else -> "Ask anything about the code, build, or dependencies."
                        },
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.conversation) { entry -> ChatBubble(entry) }
                        if (state.busy) {
                            item {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(13.dp),
                                        strokeWidth = 2.dp,
                                        color = TitanColors.Secondary,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text("Thinking...", style = MaterialTheme.typography.labelSmall, color = TitanColors.TextMuted)
                                }
                            }
                        }
                    }
                }
            }
        }

        if (state.pendingEdits.isNotEmpty()) {
            PendingEditsCard(state, viewModel)
        }

        state.message?.let { message ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(message, style = MaterialTheme.typography.labelSmall, color = TitanColors.Secondary, modifier = Modifier.weight(1f))
                TextButton(onClick = viewModel::clearMessage) { Text("Dismiss") }
            }
        }

        Surface(color = TitanColors.CanvasElevated, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Send project context",
                        style = MaterialTheme.typography.labelSmall,
                        color = TitanColors.TextMuted,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = state.includeProjectContext, onCheckedChange = { viewModel.toggleContext() })
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    OutlinedTextField(
                        value = state.input,
                        onValueChange = viewModel::setInput,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp, max = 120.dp),
                        placeholder = { Text("Ask TITAN...", style = MaterialTheme.typography.bodySmall) },
                        textStyle = MaterialTheme.typography.bodySmall,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        maxLines = 4,
                    )
                    Spacer(Modifier.width(6.dp))
                    if (state.agent?.running == true) {
                        IconButton(onClick = viewModel::stopAgent) {
                            Icon(Icons.Filled.Stop, contentDescription = "Stop agent", tint = TitanColors.Error)
                        }
                    } else {
                        IconButton(onClick = viewModel::send, enabled = state.input.isNotBlank() && !state.busy) {
                            Icon(Icons.Filled.Send, contentDescription = "Send", tint = TitanColors.Primary)
                        }
                    }
                }
                if (state.canRevert) {
                    TextButton(onClick = viewModel::revertLast) {
                        Icon(Icons.Filled.Undo, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Revert last AI edit", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatBubble(entry: ChatEntry) {
    val isUser = entry.role == ChatMessage.Role.USER
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = if (isUser) TitanColors.Primary.copy(alpha = 0.18f) else TitanColors.Surface,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(0.92f),
        ) {
            Text(
                entry.text,
                style = if (isUser) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                ),
                color = TitanColors.TextPrimary,
                modifier = Modifier.padding(10.dp),
            )
        }
    }
}

@Composable
private fun PendingEditsCard(state: AiUiState, viewModel: AiViewModel) {
    TitanCard(accent = TitanColors.Warning, modifier = Modifier.padding(horizontal = 12.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "${state.pendingEdits.size} proposed change(s)",
                style = MaterialTheme.typography.titleSmall,
                color = TitanColors.TextPrimary,
            )
            state.pendingEdits.forEach { edit ->
                Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        edit.filePath,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = TitanColors.Secondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${edit.newContent.length} chars",
                        style = MaterialTheme.typography.labelSmall,
                        color = TitanColors.TextMuted,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = viewModel::applyPendingEdits,
                    colors = ButtonDefaults.buttonColors(containerColor = TitanColors.Primary),
                    shape = RoundedCornerShape(10.dp),
                ) { Text("Apply") }
                OutlinedButton(onClick = viewModel::discardPendingEdits, shape = RoundedCornerShape(10.dp)) {
                    Text("Discard")
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.AgentPanel(state: AiUiState, viewModel: AiViewModel) {
    val agent = state.agent
    Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp)) {
        if (agent == null || agent.steps.isEmpty()) {
            TitanCard {
                Column(Modifier.padding(14.dp)) {
                    Text("Autonomous agent", style = MaterialTheme.typography.titleSmall, color = TitanColors.TextPrimary)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "The agent inspects the project, edits files, then runs a real build or test to verify its work. " +
                            "It cannot report success without a passing verification step.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TitanColors.TextMuted,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = viewModel::runTests) { Text("Run tests now") }
                        if (agent?.running == true) {
                            OutlinedButton(onClick = viewModel::stopAgent) { Text("Stop") }
                        }
                    }
                }
            }
            return
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 6.dp),
        ) {
            items(agent.steps) { event -> AgentStepRow(event) }
        }
        if (agent.running) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp, color = TitanColors.Secondary)
                Spacer(Modifier.width(8.dp))
                Text("Working... step ${agent.stepCount}", style = MaterialTheme.typography.labelSmall, color = TitanColors.TextMuted)
            }
        }
    }
}

@Composable
private fun AgentStepRow(event: AgentEvent) {
    when (event) {
        is AgentEvent.Thinking -> {
            if (event.text.isBlank()) return
            Text(
                event.text.take(240),
                style = MaterialTheme.typography.labelSmall,
                color = TitanColors.TextMuted.copy(alpha = 0.7f),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        is AgentEvent.Step -> Surface(color = TitanColors.Surface, shape = RoundedCornerShape(8.dp)) {
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusPill("step ${event.index}", TitanColors.Primary)
                Spacer(Modifier.width(8.dp))
                Text(
                    event.tool,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = TitanColors.Secondary,
                )
            }
        }
        is AgentEvent.Observation -> Surface(
            color = if (event.ok) TitanColors.Success.copy(alpha = 0.10f) else TitanColors.Error.copy(alpha = 0.10f),
            shape = RoundedCornerShape(8.dp),
        ) {
            Text(
                event.output.take(600),
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 10.5.sp),
                color = if (event.ok) TitanColors.TextPrimary else TitanColors.Error,
                modifier = Modifier.padding(8.dp),
            )
        }
        is AgentEvent.Completed -> Surface(color = TitanColors.Success.copy(alpha = 0.16f), shape = RoundedCornerShape(8.dp)) {
            Column(Modifier.padding(10.dp)) {
                Text("Verified complete", style = MaterialTheme.typography.titleSmall, color = TitanColors.Success)
                Text(event.summary, style = MaterialTheme.typography.labelSmall, color = TitanColors.TextPrimary)
            }
        }
        is AgentEvent.Blocked -> Surface(color = TitanColors.Warning.copy(alpha = 0.14f), shape = RoundedCornerShape(8.dp)) {
            Column(Modifier.padding(10.dp)) {
                Text("Stopped without verification", style = MaterialTheme.typography.titleSmall, color = TitanColors.Warning)
                Text(event.reason, style = MaterialTheme.typography.labelSmall, color = TitanColors.TextPrimary)
            }
        }
        is AgentEvent.Failed -> Surface(color = TitanColors.Error.copy(alpha = 0.14f), shape = RoundedCornerShape(8.dp)) {
            Text(event.reason, style = MaterialTheme.typography.labelSmall, color = TitanColors.Error, modifier = Modifier.padding(10.dp))
        }
    }
}
