package dev.titanide.app.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.titanide.app.LocalContainer
import dev.titanide.app.ui.common.TitanTopBar
import dev.titanide.core.designsystem.TitanColors
import dev.titanide.core.designsystem.components.StatusPill

@Composable
fun TerminalScreen() {
    val container = LocalContainer.current
    val viewModel: TerminalViewModel = viewModel { TerminalViewModel(container) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    val session = state.active

    Column(Modifier.fillMaxSize()) {
        TitanTopBar(
            title = "Terminal",
            subtitle = session?.cwd?.let { shortenPath(it) } ?: "no session",
            actions = {
                if (session?.running == true) {
                    IconButton(onClick = viewModel::cancel) {
                        Icon(Icons.Filled.Stop, contentDescription = "Stop command", tint = TitanColors.Error)
                    }
                }
                IconButton(onClick = viewModel::clear) {
                    Icon(Icons.Filled.Clear, contentDescription = "Clear output")
                }
                IconButton(onClick = viewModel::newSession) {
                    Icon(Icons.Filled.Add, contentDescription = "New session")
                }
            },
        )

        if (state.sessions.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TitanColors.CanvasElevated)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                state.sessions.forEachIndexed { index, item ->
                    val selected = index == state.activeIndex
                    Surface(
                        onClick = { viewModel.selectSession(index) },
                        color = if (selected) TitanColors.Surface else TitanColors.CanvasElevated,
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 8.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                item.title,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (selected) TitanColors.TextPrimary else TitanColors.TextMuted,
                            )
                            IconButton(onClick = { viewModel.closeSession(index) }, modifier = Modifier.size(20.dp)) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "Close session",
                                    modifier = Modifier.size(11.dp),
                                    tint = TitanColors.TextMuted,
                                )
                            }
                        }
                    }
                }
            }
        }

        Surface(
            color = TitanColors.Canvas,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            val listState = rememberLazyListState()
            val lineCount = session?.lines?.size ?: 0
            LaunchedEffect(lineCount) {
                if (lineCount > 0) listState.animateScrollToItem(lineCount - 1)
            }
            if (session == null) {
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    Text("No terminal session.", color = TitanColors.TextMuted)
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
                ) {
                    items(session.lines) { line ->
                        Text(
                            text = line.text,
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.5.sp,
                                lineHeight = 16.sp,
                            ),
                            color = when {
                                line.isCommand -> TitanColors.Secondary
                                line.isError -> TitanColors.Error
                                else -> TitanColors.TextMuted
                            },
                        )
                    }
                }
            }
        }

        Surface(color = TitanColors.CanvasElevated, modifier = Modifier.fillMaxWidth()) {
            Column {
                if (session?.running == true) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StatusPill("running", TitanColors.Warning)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "$",
                        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                        color = TitanColors.Success,
                    )
                    Spacer(Modifier.width(8.dp))
                    BasicTextField(
                        value = input,
                        onValueChange = {
                            input = it
                            // Typing leaves history browsing, otherwise the next arrow press
                            // would overwrite what the user just typed.
                            viewModel.resetHistoryCursor()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .onPreviewKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                when (event.key) {
                                    Key.DirectionUp -> {
                                        viewModel.previousHistory()
                                        viewModel.historyEntry()?.let { input = it }
                                        true
                                    }
                                    Key.DirectionDown -> {
                                        viewModel.nextHistory()
                                        input = viewModel.historyEntry().orEmpty()
                                        true
                                    }
                                    else -> false
                                }
                            },
                        textStyle = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            color = TitanColors.TextPrimary,
                        ),
                        cursorBrush = SolidColor(TitanColors.Primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                viewModel.submit(input)
                                input = ""
                            },
                        ),
                        decorationBox = { inner ->
                            if (input.isEmpty()) {
                                Text(
                                    "Enter a command",
                                    style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                                    color = TitanColors.TextMuted.copy(alpha = 0.6f),
                                )
                            }
                            inner()
                        },
                    )
                    IconButton(
                        onClick = {
                            viewModel.submit(input)
                            input = ""
                        },
                        enabled = input.isNotBlank() && session?.running != true,
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "Run command")
                    }
                }
            }
        }
    }
}

private fun shortenPath(path: String): String {
    val marker = "/projects/"
    val index = path.indexOf(marker)
    return if (index >= 0) "~/projects/" + path.substring(index + marker.length) else path
}
