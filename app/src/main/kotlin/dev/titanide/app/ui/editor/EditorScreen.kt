package dev.titanide.app.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FormatAlignLeft
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.titanide.app.LocalContainer
import dev.titanide.app.ui.common.TitanTopBar
import dev.titanide.core.designsystem.SyntaxColors
import dev.titanide.core.designsystem.TitanColors
import dev.titanide.core.designsystem.components.EmptyState
import dev.titanide.core.designsystem.components.StatusPill
import dev.titanide.core.editor.Token
import dev.titanide.core.editor.TokenType
import dev.titanide.core.editor.Tokenizer
import dev.titanide.core.model.Diagnostic
import dev.titanide.core.model.FileKind
import dev.titanide.core.model.Language
import dev.titanide.core.model.Project
import dev.titanide.core.model.ProjectFile
import dev.titanide.core.model.Severity

@Composable
fun EditorScreen(
    onOpenProjects: () -> Unit,
    onBuild: (String) -> Unit,
    onGit: (String) -> Unit,
) {
    val container = LocalContainer.current
    val viewModel: EditorViewModel = viewModel { EditorViewModel(container) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    val density = LocalDensity.current
    val windowWidth = with(density) { LocalWindowInfo.current.containerSize.width.toDp() }
    val twoPane = windowWidth >= 840.dp

    Column(Modifier.fillMaxSize()) {
        TitanTopBar(
            title = state.active?.name ?: "Editor",
            subtitle = state.project?.name ?: "No project selected",
            actions = {
                IconButton(onClick = viewModel::toggleExplorer) {
                    Icon(Icons.Filled.FolderOpen, contentDescription = "Toggle file explorer")
                }
                IconButton(onClick = viewModel::toggleDiagnostics) {
                    Icon(Icons.Filled.ErrorOutline, contentDescription = "Diagnostics")
                }
                state.project?.let { project ->
                    IconButton(onClick = { onBuild(project.id) }) {
                        Icon(Icons.Filled.Build, contentDescription = "Build")
                    }
                    IconButton(onClick = { onGit(project.id) }) {
                        Icon(Icons.Filled.Cloud, contentDescription = "Git")
                    }
                }
            },
        )

        if (state.project == null) {
            EmptyState(
                icon = Icons.Filled.Code,
                title = "No project open",
                message = "Select a project to start editing.",
                action = {
                    androidx.compose.material3.Button(onClick = onOpenProjects) { Text("Open projects") }
                },
            )
            return@Column
        }

        if (state.tabs.isNotEmpty()) TabStrip(state, viewModel)

        Row(Modifier.weight(1f)) {
            if (state.showExplorer) {
                FileExplorer(
                    state = state,
                    onToggleDir = viewModel::toggleDir,
                    onOpenFile = viewModel::openFile,
                    onRefresh = viewModel::refreshTree,
                    modifier = Modifier.width(if (twoPane) 260.dp else 200.dp).fillMaxHeight(),
                )
            }
            Column(Modifier.weight(1f)) {
                if (state.showDiagnostics) {
                    DiagnosticsPanel(state.diagnostics, state.analyzing) { diagnostic ->
                        state.tabs.indexOfFirst { it.path == diagnostic.filePath }.takeIf { it >= 0 }
                            ?.let(viewModel::selectTab)
                    }
                }
                Box(Modifier.weight(1f)) {
                    val active = state.active
                    if (active == null) {
                        EmptyState(
                            icon = Icons.Filled.Code,
                            title = "No file open",
                            message = "Pick a file from the explorer to start editing.",
                        )
                    } else {
                        CodeEditor(active, viewModel, state.appearance, state.autoSave, state.showDiagnostics)
                    }
                }
                if (state.showDiagnostics) {
                    SearchPanel(state, viewModel)
                }
            }
        }
    }
}

@Composable
private fun TabStrip(state: EditorState, viewModel: EditorViewModel) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .background(TitanColors.CanvasElevated)
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(state.tabs.indices.toList(), key = { state.tabs[it].path }) { index ->
            val tab = state.tabs[index]
            val selected = index == state.activeIndex
            Surface(
                onClick = { viewModel.selectTab(index) },
                color = if (selected) TitanColors.Surface else Color.Transparent,
                shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp),
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Row(
                    modifier = Modifier.padding(start = 10.dp, end = 2.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = tab.name + if (tab.dirty) " •" else "",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (selected) TitanColors.TextPrimary else TitanColors.TextMuted,
                        maxLines = 1,
                    )
                    IconButton(
                        onClick = { viewModel.closeTab(index) },
                        modifier = Modifier.size(24.dp),
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Close ${tab.name}",
                            tint = TitanColors.TextMuted,
                            modifier = Modifier.size(13.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FileExplorer(
    state: EditorState,
    onToggleDir: (String) -> Unit,
    onOpenFile: (String) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(color = TitanColors.CanvasElevated, modifier = modifier) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "EXPLORER",
                    style = MaterialTheme.typography.labelSmall,
                    color = TitanColors.TextMuted,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onRefresh, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Filled.Search,
                        contentDescription = "Refresh",
                        tint = TitanColors.TextMuted,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(state.visibleTree, key = { it.path }) { file ->
                    ExplorerRow(
                        file = file,
                        depth = file.path.count { it == '/' },
                        expanded = file.path in state.expandedDirs,
                        selected = state.active?.path == file.path,
                        onClick = {
                            if (file.kind == FileKind.DIRECTORY) onToggleDir(file.path) else onOpenFile(file.path)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ExplorerRow(
    file: ProjectFile,
    depth: Int,
    expanded: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) TitanColors.Surface else Color.Transparent)
            .padding(start = (10 + depth * 12).dp, top = 7.dp, bottom = 7.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(onClick = onClick, color = Color.Transparent) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = when {
                        file.kind == FileKind.DIRECTORY -> if (expanded) "▾" else "▸"
                        else -> fileLanguageMark(file.language)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (file.kind == FileKind.DIRECTORY) TitanColors.Warning else TitanColors.TextMuted,
                    modifier = Modifier.width(16.dp),
                )
                Text(
                    file.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selected) TitanColors.TextPrimary else TitanColors.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun fileLanguageMark(language: Language): String = when (language) {
    Language.KOTLIN -> "K"
    Language.JAVA -> "J"
    Language.PYTHON -> "P"
    Language.TYPESCRIPT, Language.JAVASCRIPT -> "T"
    Language.RUST -> "R"
    Language.GO -> "G"
    Language.C, Language.CPP -> "C"
    Language.JSON -> "{}"
    Language.YAML, Language.XML -> "X"
    else -> "·"
}

@Composable
private fun DiagnosticsPanel(
    diagnostics: List<Diagnostic>,
    analyzing: Boolean,
    onSelect: (Diagnostic) -> Unit,
) {
    Surface(color = TitanColors.Canvas, modifier = Modifier.fillMaxWidth().height(150.dp)) {
        Column(Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "PROBLEMS",
                    style = MaterialTheme.typography.labelSmall,
                    color = TitanColors.TextMuted,
                    modifier = Modifier.weight(1f),
                )
                if (analyzing) {
                    CircularProgressIndicator(modifier = Modifier.size(11.dp), strokeWidth = 1.5.dp)
                }
            }
            if (diagnostics.isEmpty()) {
                Text(
                    if (analyzing) "Analyzing..." else "No problems found",
                    style = MaterialTheme.typography.bodySmall,
                    color = TitanColors.Success,
                    modifier = Modifier.padding(top = 6.dp),
                )
            } else {
                LazyColumn {
                    items(diagnostics) { diagnostic ->
                        Surface(onClick = { onSelect(diagnostic) }, color = Color.Transparent) {
                            Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
                                Icon(
                                    if (diagnostic.severity == Severity.ERROR) Icons.Filled.ErrorOutline else Icons.Filled.Warning,
                                    contentDescription = null,
                                    tint = if (diagnostic.severity == Severity.ERROR) TitanColors.Error else TitanColors.Warning,
                                    modifier = Modifier.size(13.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "${diagnostic.line + 1}: ${diagnostic.message}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TitanColors.TextMuted,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchPanel(state: EditorState, viewModel: EditorViewModel) {
    var query by remember { mutableStateOf(state.searchQuery) }
    Surface(color = TitanColors.CanvasElevated, modifier = Modifier.fillMaxWidth().height(140.dp)) {
        Column(Modifier.padding(8.dp)) {
            TextField(
                value = query,
                onValueChange = {
                    query = it
                    viewModel.search(it)
                },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search in project", style = MaterialTheme.typography.bodySmall) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = TitanColors.Surface,
                    unfocusedContainerColor = TitanColors.Surface,
                ),
            )
            if (state.searching) {
                Text("Searching...", style = MaterialTheme.typography.labelSmall, color = TitanColors.TextMuted)
            } else if (query.isNotBlank()) {
                Text(
                    "${state.searchResults.size} results",
                    style = MaterialTheme.typography.labelSmall,
                    color = TitanColors.TextMuted,
                )
            }
            LazyColumn {
                items(state.searchResults) { hit ->
                    Surface(
                        onClick = { viewModel.openFile(hit.path) },
                        color = Color.Transparent,
                    ) {
                        Text(
                            "${hit.path}:${hit.line}  ${hit.text}",
                            style = MaterialTheme.typography.labelSmall,
                            color = TitanColors.TextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CodeEditor(
    tab: EditorTab,
    viewModel: EditorViewModel,
    appearance: EditorAppearance,
    autoSave: Boolean,
    showDiagnostics: Boolean,
) {
    var showMenu by remember { mutableStateOf(false) }
    val lineNumbers = remember(tab.content) { tab.content.count { it == '\n' } + 1 }
    val highlighted = remember(tab.content, tab.language) {
        highlight(tab.content, tab.language)
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(TitanColors.CanvasElevated)
                .padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusPill(tab.language.displayName, TitanColors.Secondary)
            Spacer(Modifier.width(6.dp))
            Text(
                "$lineNumbers lines",
                style = MaterialTheme.typography.labelSmall,
                color = TitanColors.TextMuted,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = viewModel::saveActive, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Filled.Save, contentDescription = "Save", modifier = Modifier.size(16.dp))
            }
            IconButton(onClick = viewModel::formatDocument, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Filled.FormatAlignLeft, contentDescription = "Format", modifier = Modifier.size(16.dp))
            }
            Box {
                IconButton(onClick = { showMenu = true }, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More", modifier = Modifier.size(16.dp))
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Toggle comment") },
                        onClick = { viewModel.toggleComment(); showMenu = false },
                    )
                    DropdownMenuItem(
                        text = { Text("Indent") },
                        onClick = { viewModel.indent(false); showMenu = false },
                    )
                    DropdownMenuItem(
                        text = { Text("Outdent") },
                        onClick = { viewModel.indent(true); showMenu = false },
                    )
                    DropdownMenuItem(
                        text = { Text("Undo") },
                        onClick = { viewModel.undo(); showMenu = false },
                    )
                    DropdownMenuItem(
                        text = { Text("Redo") },
                        onClick = { viewModel.redo(); showMenu = false },
                    )
                    DropdownMenuItem(
                        text = { Text(if (tab.dirty) "Save now" else "Saved") },
                        onClick = { viewModel.saveActive(); showMenu = false },
                    )
                    DropdownMenuItem(
                        text = { Text(if (autoSave) "Auto-save: on" else "Auto-save: off") },
                        onClick = { viewModel.toggleAutoSave(); showMenu = false },
                    )
                    DropdownMenuItem(
                        text = { Text(if (showDiagnostics) "Hide problems" else "Show problems") },
                        onClick = { viewModel.toggleDiagnostics(); showMenu = false },
                    )
                }
            }
        }

        Row(Modifier.fillMaxSize()) {
            if (appearance.showLineNumbers) {
                Column(
                    modifier = Modifier
                        .background(TitanColors.CanvasElevated)
                        .fillMaxHeight()
                        .width(40.dp)
                        .padding(top = 8.dp),
                    horizontalAlignment = Alignment.End,
                ) {
                    LazyColumn(state = rememberLazyListState()) {
                        items(lineNumbers) { index ->
                            Text(
                                text = "${index + 1}",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = editorFontFamily(appearance.fontFamily),
                                    fontSize = (appearance.fontSizeSp - 3).coerceAtLeast(7).sp,
                                ),
                                color = TitanColors.TextMuted.copy(alpha = 0.55f),
                                modifier = Modifier.padding(end = 6.dp),
                            )
                        }
                    }
                }
            }

            // Wrapping is a real setting. Compose's BasicTextField always wraps, so with
            // wrapping off the field is given unbounded width inside a horizontal scroller:
            // long lines then extend sideways instead of folding, which is what a developer
            // expects from a code editor.
            val editorTextStyle = TextStyle(
                fontFamily = editorFontFamily(appearance.fontFamily),
                fontSize = appearance.fontSizeSp.sp,
                lineHeight = appearance.lineHeightSp.sp,
                color = SyntaxColors.Plain,
            )
            val fieldModifier = if (appearance.wordWrap) {
                Modifier.weight(1f).fillMaxHeight().padding(8.dp)
            } else {
                Modifier.fillMaxHeight().padding(8.dp)
            }
            if (appearance.wordWrap) {
                EditorTextField(tab, viewModel, editorTextStyle, fieldModifier)
            } else {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .horizontalScroll(rememberScrollState()),
                ) {
                    EditorTextField(
                        tab = tab,
                        viewModel = viewModel,
                        textStyle = editorTextStyle,
                        modifier = Modifier,
                    )
                }
            }
        }
    }
}

/** The editable field itself, shared by the wrapping and non-wrapping layouts. */
@Composable
private fun EditorTextField(
    tab: EditorTab,
    viewModel: EditorViewModel,
    textStyle: TextStyle,
    modifier: Modifier,
) {
    BasicTextField(
        value = androidx.compose.ui.text.input.TextFieldValue(
            text = tab.content,
            selection = androidx.compose.ui.text.TextRange(
                tab.caret.coerceIn(0, tab.content.length),
            ),
        ),
        onValueChange = { value -> viewModel.edit(value.text, value.selection.start) },
        modifier = modifier,
        textStyle = textStyle,
        cursorBrush = androidx.compose.ui.graphics.SolidColor(TitanColors.Primary),
        visualTransformation = SyntaxHighlightTransformation(tab.language),
    )
}

/**
 * Maps the stored family name to a real font.
 *
 * Only monospace variants are honoured: a proportional font makes code indentation
 * meaningless, so an unknown value falls back to monospace rather than being applied.
 */
private fun editorFontFamily(name: String): FontFamily = when (name.lowercase()) {
    "serif" -> FontFamily.Serif
    "sans", "sans-serif" -> FontFamily.SansSerif
    "cursive" -> FontFamily.Cursive
    else -> FontFamily.Monospace
}

/**
 * Applies token colours through the text field's own transformation, so the editable text
 * and the coloured text are the same string — no overlay, no drift between caret and glyphs.
 */
private class SyntaxHighlightTransformation(
    private val language: Language,
) : androidx.compose.ui.text.input.VisualTransformation {

    override fun filter(text: AnnotatedString): androidx.compose.ui.text.input.TransformedText {
        val plain = text.text
        val coloured = highlight(plain, language)
        return androidx.compose.ui.text.input.TransformedText(coloured, androidx.compose.ui.text.input.OffsetMapping.Identity)
    }

    override fun equals(other: Any?): Boolean = other is SyntaxHighlightTransformation && other.language == language

    override fun hashCode(): Int = language.hashCode()
}

/** Maps tokens to colours. Token offsets come from the real tokenizer. */
private fun highlight(text: String, language: Language): AnnotatedString {
    if (language == Language.PLAINTEXT) return AnnotatedString(text)
    val tokens = runCatching { Tokenizer.tokenize(text, language) }.getOrDefault(emptyList())
    if (tokens.isEmpty()) return AnnotatedString(text)

    return buildAnnotatedString {
        var cursor = 0
        for (token in tokens) {
            if (token.start > cursor) append(text.substring(cursor, token.start))
            val start = token.start.coerceIn(0, text.length)
            val end = token.end.coerceIn(start, text.length)
            if (start >= end) continue
            val slice = text.substring(start, end)
            val style = SpanStyle(color = colorFor(token))
            withStyle(style) { append(slice) }
            cursor = end
        }
        if (cursor < text.length) append(text.substring(cursor))
    }
}

private fun colorFor(token: Token): Color = when (token.type) {
    TokenType.KEYWORD -> SyntaxColors.Keyword
    TokenType.STRING -> SyntaxColors.String
    TokenType.NUMBER -> SyntaxColors.Number
    TokenType.COMMENT -> SyntaxColors.Comment
    TokenType.FUNCTION -> SyntaxColors.Function
    TokenType.TYPE -> SyntaxColors.Type
    TokenType.ANNOTATION -> SyntaxColors.Annotation
    TokenType.OPERATOR -> SyntaxColors.Operator
    TokenType.PUNCTUATION -> SyntaxColors.Punctuation
    TokenType.IDENTIFIER -> SyntaxColors.Plain
    TokenType.PLAIN -> SyntaxColors.Plain
}
