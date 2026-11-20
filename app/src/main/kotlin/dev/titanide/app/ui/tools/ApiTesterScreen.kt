package dev.titanide.app.ui.tools

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import dev.titanide.app.ui.common.TitanTopBar
import dev.titanide.core.designsystem.TitanColors
import dev.titanide.core.designsystem.components.StatusPill
import dev.titanide.core.designsystem.components.TitanCard

@Composable
fun ApiTesterScreen(onBack: () -> Unit) {
    val container = LocalContainer.current
    val viewModel: ApiTesterViewModel = viewModel { ApiTesterViewModel(container) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        TitanTopBar(title = "API Tester", subtitle = "Send real HTTP requests", onBack = onBack)

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf("GET", "POST", "PUT", "PATCH", "DELETE").forEach { method ->
                    FilterChip(
                        selected = state.method == method,
                        onClick = { viewModel.setMethod(method) },
                        label = { Text(method, style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }

            OutlinedTextField(
                value = state.url,
                onValueChange = viewModel::setUrl,
                label = { Text("URL") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
            )

            OutlinedTextField(
                value = state.headers,
                onValueChange = viewModel::setHeaders,
                label = { Text("Headers (one per line, Name: value)") },
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodySmall,
                maxLines = 4,
            )

            if (state.method != "GET" && state.method != "DELETE") {
                OutlinedTextField(
                    value = state.body,
                    onValueChange = viewModel::setBody,
                    label = { Text("Body") },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    maxLines = 8,
                )
            }

            Button(
                onClick = viewModel::send,
                enabled = !state.running && state.url.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(44.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = TitanColors.Primary),
            ) {
                if (state.running) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = TitanColors.TextPrimary)
                    Spacer(Modifier.width(8.dp))
                    Text("Sending...")
                } else {
                    Icon(Icons.Filled.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Send")
                }
            }

            state.result?.let { result ->
                TitanCard(
                    accent = when {
                        result.error != null -> TitanColors.Error
                        result.status in 200..299 -> TitanColors.Success
                        result.status in 300..399 -> TitanColors.Warning
                        else -> TitanColors.Error
                    },
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (result.error != null) {
                                StatusPill("failed", TitanColors.Error)
                            } else {
                                StatusPill("${result.status} ${result.statusText}", TitanColors.Secondary)
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "${result.durationMs}ms",
                                style = MaterialTheme.typography.labelSmall,
                                color = TitanColors.TextMuted,
                            )
                        }
                        result.error?.let {
                            Spacer(Modifier.height(6.dp))
                            Text(it, style = MaterialTheme.typography.bodySmall, color = TitanColors.Error)
                        }
                        if (result.headers.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Text("Headers", style = MaterialTheme.typography.labelSmall, color = TitanColors.TextMuted)
                            Surface(color = TitanColors.Canvas, shape = RoundedCornerShape(6.dp)) {
                                Column(Modifier.padding(6.dp).heightIn(max = 120.dp).verticalScroll(rememberScrollState())) {
                                    result.headers.forEach { (name, value) ->
                                        Text(
                                            "$name: $value",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 9.5.sp,
                                            ),
                                            color = TitanColors.TextMuted,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                        if (result.body.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text("Body", style = MaterialTheme.typography.labelSmall, color = TitanColors.TextMuted)
                            Surface(color = TitanColors.Canvas, shape = RoundedCornerShape(6.dp)) {
                                Column(
                                    Modifier
                                        .padding(8.dp)
                                        .heightIn(max = 320.dp)
                                        .verticalScroll(rememberScrollState()),
                                ) {
                                    Text(
                                        result.body,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 10.sp,
                                        ),
                                        color = TitanColors.TextPrimary,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (state.history.isNotEmpty()) {
                Text("History", style = MaterialTheme.typography.titleSmall, color = TitanColors.TextPrimary)
                state.history.take(15).forEach { entry ->
                    Surface(
                        onClick = { viewModel.loadHistory(entry) },
                        color = TitanColors.Surface,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                entry.method,
                                style = MaterialTheme.typography.labelSmall,
                                color = TitanColors.Secondary,
                                modifier = Modifier.width(48.dp),
                            )
                            Text(
                                entry.url,
                                style = MaterialTheme.typography.labelSmall,
                                color = TitanColors.TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { viewModel.deleteHistory(entry.id) }, modifier = Modifier.size(24.dp)) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = "Delete",
                                    tint = TitanColors.TextMuted,
                                    modifier = Modifier.size(13.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
