package dev.titanide.app.ui.workbench

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.WarningAmber
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.titanide.core.designsystem.TitanColors
import dev.titanide.core.editor.EditorCommand
import dev.titanide.core.editor.KeyBindings
import dev.titanide.core.editor.KeyChord
import dev.titanide.core.editor.KeyName
import dev.titanide.core.model.Diagnostic
import dev.titanide.core.model.Severity
import dev.titanide.core.workbench.EditorGroup
import dev.titanide.core.workbench.EditorTab
import dev.titanide.core.workbench.breadcrumbFor
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import dev.titanide.core.editor.SymbolReference

/**
 * The editor area: tab strip, breadcrumb, gutter and the code surface.
 *
 * The code surface is a real editable text field with token colouring applied through a
 * visual transformation, so typing, selection and the soft keyboard all behave natively
 * while the highlighting stays in sync.
 */
@Composable
fun EditorArea(
    group: EditorGroup,
    buffer: BufferState?,
    diagnostics: List<Diagnostic>,
    rootName: String?,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onChange: (String, Int) -> Unit,
    onSave: () -> Unit,
    onToggleComment: () -> Unit,
    onFormat: () -> Unit,
    onIndent: (Boolean) -> Unit,
    onUndo: () -> Unit = {},
    onRedo: () -> Unit = {},
    onGoToDefinition: () -> Unit = {},
    onFindReferences: () -> Unit = {},
    referenceTarget: String? = null,
    references: List<SymbolReference> = emptyList(),
    onOpenReference: (SymbolReference) -> Unit = {},
    onCloseReferences: () -> Unit = {},
    modifier: Modifier = Modifier,
    showGutter: Boolean = true,
    find: FindState = FindState(),
    appearance: EditorAppearance = EditorAppearance(),
    revealToken: Long = 0L,
    onFindQueryChange: (String) -> Unit = {},
    onFindReplaceChange: (String) -> Unit = {},
    onFindNext: () -> Unit = {},
    onFindPrevious: () -> Unit = {},
    onReplaceCurrent: () -> Unit = {},
    onReplaceAll: () -> Unit = {},
    onCloseFind: () -> Unit = {},
) {
    Column(modifier = modifier.fillMaxSize().background(TitanColors.Canvas)) {
        if (group.tabs.isNotEmpty()) {
            TabStrip(
                tabs = group.tabs,
                activeId = group.activeTab?.id,
                onSelect = onSelectTab,
                onClose = onCloseTab,
            )
        }
        if (buffer != null) {
            Breadcrumb(path = buffer.path, rootName = rootName)
            CodeSurface(
                buffer = buffer,
                diagnostics = diagnostics,
                onChange = onChange,
                onSave = onSave,
                onUndo = onUndo,
                onRedo = onRedo,
                onToggleComment = onToggleComment,
                onFormat = onFormat,
                onGoToDefinition = onGoToDefinition,
                onFindReferences = onFindReferences,
                modifier = Modifier.weight(1f),
                showGutter = showGutter && appearance.showLineNumbers,
                activeMatch = find.current,
                appearance = appearance,
                revealToken = revealToken,
            )
            if (find.open) {
                FindBar(
                    find = find,
                    onQueryChange = onFindQueryChange,
                    onReplaceChange = onFindReplaceChange,
                    onNext = onFindNext,
                    onPrevious = onFindPrevious,
                    onReplaceCurrent = onReplaceCurrent,
                    onReplaceAll = onReplaceAll,
                    onClose = onCloseFind,
                )
            }
            if (referenceTarget != null) {
                ReferencesPanel(
                    name = referenceTarget,
                    references = references,
                    onOpen = onOpenReference,
                    onClose = onCloseReferences,
                )
            }
            EditorStatusStrip(
                buffer = buffer,
                diagnostics = diagnostics,
                onSave = onSave,
                onToggleComment = onToggleComment,
                onFormat = onFormat,
                onIndent = onIndent,
                onUndo = onUndo,
                onRedo = onRedo,
                onFind = { onFindQueryChange(find.query) },
                onGoToDefinition = onGoToDefinition,
                onFindReferences = onFindReferences,
            )
        } else {
            NoFileOpen()
        }
    }
}

@Composable
private fun TabStrip(
    tabs: List<EditorTab>,
    activeId: String?,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .height(WorkbenchMetrics.TabHeight)
            .background(TitanColors.CanvasElevated),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(tabs, key = { it.id }) { tab ->
            val active = tab.id == activeId
            Row(
                modifier = Modifier
                    .fillMaxHeight()
                    .background(if (active) TitanColors.Canvas else Color.Transparent)
                    .clickable { onSelect(tab.id) }
                    .padding(start = 10.dp, end = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (active) {
                    Box(
                        modifier = Modifier
                            .width(2.dp)
                            .height(WorkbenchMetrics.TabHeight)
                            .background(TitanColors.Primary),
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = tab.title,
                    color = if (active) TitanColors.TextPrimary else TitanColors.TextMuted,
                    fontSize = 11.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(132.dp),
                )
                // The dirty marker sits beside the close button, never instead of it: a tab
                // with unsaved edits still has to be closable.
                Box(
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .size(6.dp)
                        .background(
                            if (tab.dirty) TitanColors.Primary else Color.Transparent,
                            androidx.compose.foundation.shape.CircleShape,
                        ),
                )
                DenseIconButton(
                    icon = Icons.Filled.Close,
                    contentDescription = "Close ${tab.title}",
                    onClick = { onClose(tab.id) },
                )
            }
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(16.dp)
                    .background(TitanColors.OutlineFaint),
            )
        }
    }
}

@Composable
private fun Breadcrumb(path: String, rootName: String?) {
    val segments = remember(path, rootName) { breadcrumbFor(path, rootName) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(WorkbenchMetrics.SectionHeaderHeight)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        segments.forEachIndexed { index, segment ->
            if (index > 0) {
                Text(
                    text = "›",
                    color = TitanColors.TextMuted.copy(alpha = 0.6f),
                    fontSize = 10.sp,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            Text(
                text = segment,
                color = if (index == segments.lastIndex) TitanColors.TextPrimary else TitanColors.TextMuted,
                fontSize = 10.5.sp,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun CodeSurface(
    buffer: BufferState,
    diagnostics: List<Diagnostic>,
    onChange: (String, Int) -> Unit,
    onSave: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onToggleComment: () -> Unit,
    onFormat: () -> Unit,
    onGoToDefinition: () -> Unit = {},
    onFindReferences: () -> Unit = {},
    referenceTarget: String? = null,
    references: List<SymbolReference> = emptyList(),
    onOpenReference: (SymbolReference) -> Unit = {},
    onCloseReferences: () -> Unit = {},
    modifier: Modifier = Modifier,
    showGutter: Boolean = true,
    activeMatch: IntRange? = null,
    appearance: EditorAppearance = EditorAppearance(),
    revealToken: Long = 0L,
) {
    val fontSize = appearance.fontSizeSp
    val lineHeight = appearance.lineHeightSp
    val currentLineTint = TitanColors.Primary.copy(alpha = 0.06f)
    val annotated = remember(buffer.content, buffer.language) {
        SyntaxHighlighting.highlight(buffer.content, buffer.language)
    }
    val errorLines = remember(diagnostics) {
        diagnostics.filter { it.severity == Severity.ERROR }.mapNotNull { it.line }.toSet()
    }
    val warningLines = remember(diagnostics) {
        diagnostics.filter { it.severity == Severity.WARNING }.mapNotNull { it.line }.toSet()
    }
    val lineCount = buffer.lineCount
    val caretLine = remember(buffer.caret, buffer.content) {
        buffer.content.take(buffer.caret.coerceIn(0, buffer.content.length)).count { it == '\n' }
    }
    val vertical = rememberScrollState()
    val horizontal = rememberScrollState()
    val focusRequester = remember { FocusRequester() }
    // A Bluetooth keyboard drives the editor through these bindings. Every one of them has a
    // touch equivalent in the status strip, so nothing here is the only way to reach a feature.
    fun handleKey(event: androidx.compose.ui.input.key.KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        val name = when (event.key) {
            Key.Z -> KeyName.Z
            Key.Y -> KeyName.Y
            Key.S -> KeyName.S
            Key.Slash -> KeyName.SLASH
            Key.F -> KeyName.F
            Key.F12 -> KeyName.F12
            else -> KeyName.UNKNOWN
        }
        val chord = KeyChord(name, ctrl = event.isCtrlPressed, shift = event.isShiftPressed)
        return when (KeyBindings.commandFor(chord)) {
            EditorCommand.UNDO -> { onUndo(); true }
            EditorCommand.REDO -> { onRedo(); true }
            EditorCommand.SAVE -> { onSave(); true }
            EditorCommand.TOGGLE_COMMENT -> { onToggleComment(); true }
            EditorCommand.FORMAT -> { onFormat(); true }
            EditorCommand.GO_TO_DEFINITION -> { onGoToDefinition(); true }
            EditorCommand.FIND_REFERENCES -> { onFindReferences(); true }
            null -> false
        }
    }

    // The field owns the selection; the buffer only knows the caret offset. Syncing the
    // other way (buffer -> field) is needed for undo, format and comment toggles.
    var fieldValue by remember(buffer.path) {
        mutableStateOf(TextFieldValue(buffer.content, TextRange(buffer.caret.coerceIn(0, buffer.content.length))))
    }
    LaunchedEffect(buffer.content) {
        if (fieldValue.text != buffer.content) {
            fieldValue = TextFieldValue(
                text = buffer.content,
                selection = TextRange(buffer.caret.coerceIn(0, buffer.content.length)),
            )
        }
    }

    // A caret move that changes no characters (a search hit, find-next) is invisible to the
    // effect above, so the view model bumps a token when it wants the caret shown. Only that
    // path forces the selection; syncing on every caret change would collapse a dragged range.
    LaunchedEffect(revealToken) {
        if (revealToken == 0L) return@LaunchedEffect
        val caret = buffer.caret.coerceIn(0, buffer.content.length)
        fieldValue = fieldValue.copy(selection = TextRange(caret))
        val lineIndex = buffer.content.take(caret).count { it == '\n' }
        vertical.animateScrollTo((lineIndex * lineHeight - 40).coerceAtLeast(0))
    }

    Row(modifier = modifier.fillMaxSize()) {
        if (showGutter) {
            Column(
                modifier = Modifier
                    .width(40.dp)
                    .fillMaxHeight()
                    .verticalScroll(vertical)
                    .padding(top = 6.dp),
                horizontalAlignment = Alignment.End,
            ) {
                repeat(lineCount) { index ->
                    val line = index + 1
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(lineHeight.dp)
                            .padding(end = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End,
                    ) {
                        if (line in errorLines) {
                            Icon(
                                Icons.Filled.ErrorOutline,
                                contentDescription = "Error on line $line",
                                tint = TitanColors.Error,
                                modifier = Modifier.size(8.dp),
                            )
                            Spacer(Modifier.width(2.dp))
                        } else if (line in warningLines) {
                            Icon(
                                Icons.Filled.WarningAmber,
                                contentDescription = "Warning on line $line",
                                tint = TitanColors.Warning,
                                modifier = Modifier.size(8.dp),
                            )
                            Spacer(Modifier.width(2.dp))
                        }
                        Text(
                            text = line.toString(),
                            color = if (line in errorLines) TitanColors.Error else TitanColors.TextMuted.copy(alpha = 0.55f),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(TitanColors.OutlineFaint),
            )
        }

        BasicTextField(
            value = fieldValue,
            onValueChange = { value ->
                fieldValue = value
                onChange(value.text, value.selection.start)
            },
            textStyle = TextStyle(
                color = TitanColors.TextPrimary,
                fontSize = fontSize.sp,
                lineHeight = lineHeight.sp,
                fontFamily = FontFamily.Monospace,
            ),
            cursorBrush = SolidColor(TitanColors.Primary),
            visualTransformation = SyntaxHighlightTransformation(buffer.language),
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .focusRequester(focusRequester)
                .onPreviewKeyEvent(::handleKey)
                .then(if (appearance.wordWrap) Modifier else Modifier.horizontalScroll(horizontal))
                .verticalScroll(vertical)
                .drawBehind {
                    if (!appearance.highlightCurrentLine) return@drawBehind
                    // Drawn in content space, inside the scroll modifier, so it moves with
                    // the text instead of sitting at a fixed height on screen.
                    val top = 6.dp.toPx() + caretLine * lineHeight.dp.toPx()
                    drawRect(
                        color = currentLineTint,
                        topLeft = Offset(0f, top),
                        size = Size(size.width, lineHeight.dp.toPx()),
                    )
                }
                .padding(start = 8.dp, top = 6.dp, end = 8.dp, bottom = 8.dp),
        )
    }
}

@Composable
private fun EditorStatusStrip(
    buffer: BufferState,
    diagnostics: List<Diagnostic>,
    onSave: () -> Unit,
    onToggleComment: () -> Unit,
    onFormat: () -> Unit,
    onIndent: (Boolean) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onFind: () -> Unit,
    onGoToDefinition: () -> Unit,
    onFindReferences: () -> Unit,
) {
    val errors = diagnostics.count { it.severity == Severity.ERROR }
    val warnings = diagnostics.count { it.severity == Severity.WARNING }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .background(TitanColors.CanvasElevated)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (errors > 0) StatusChip("$errors error${if (errors == 1) "" else "s"}", TitanColors.Error)
        if (warnings > 0) StatusChip("$warnings warning${if (warnings == 1) "" else "s"}", TitanColors.Warning)
        if (errors == 0 && warnings == 0) {
            StatusChip("No problems", TitanColors.Success)
        }
        Spacer(Modifier.weight(1f))
        // Undo has to be reachable by touch: a phone has no Ctrl+Z, and an edit that cannot be
        // taken back is not usable.
        ActionChip("Undo", onUndo)
        ActionChip("Redo", onRedo)
        // Navigation has to be tappable for the same reason: there is no F12 on a phone.
        ActionChip("Definition", onGoToDefinition)
        ActionChip("References", onFindReferences)
        ActionChip("Find", onFind)
        ActionChip("Comment", onToggleComment)
        ActionChip("Format", onFormat)
        // The soft keyboard has no Tab key, so indenting a block needs these.
        ActionChip("Outdent", { onIndent(true) })
        ActionChip("Indent", { onIndent(false) })
        ActionChip(if (buffer.dirty) "Save *" else "Saved", onSave)
    }
}

/**
 * In-file find and replace. Kept compact and above the status strip so it never covers code
 * while the keyboard is up.
 */
@Composable
private fun FindBar(
    find: FindState,
    onQueryChange: (String) -> Unit,
    onReplaceChange: (String) -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onReplaceCurrent: () -> Unit,
    onReplaceAll: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(TitanColors.CanvasElevated)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DenseTextField(
                value = find.query,
                onValueChange = onQueryChange,
                placeholder = "Find",
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (find.count == 0) "0/0" else "${find.activeIndex + 1}/${find.count}",
                color = if (find.count == 0) TitanColors.TextMuted else TitanColors.Secondary,
                fontSize = 10.sp,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
            DenseIconButton(
                icon = Icons.Filled.KeyboardArrowUp,
                contentDescription = "Previous match",
                onClick = onPrevious,
            )
            DenseIconButton(
                icon = Icons.Filled.KeyboardArrowDown,
                contentDescription = "Next match",
                onClick = onNext,
            )
            DenseIconButton(
                icon = Icons.Filled.Close,
                contentDescription = "Close find",
                onClick = onClose,
            )
        }
        if (find.replaceMode) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DenseTextField(
                    value = find.replacement,
                    onValueChange = onReplaceChange,
                    placeholder = "Replace with",
                    modifier = Modifier.weight(1f),
                )
                ActionChip("Replace", onReplaceCurrent)
                ActionChip("All", onReplaceAll)
            }
        }
    }
}

/** Minimal single-line field sized for a toolbar row. */
@Composable
private fun DenseTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(
            color = TitanColors.TextPrimary,
            fontSize = 11.5.sp,
            fontFamily = FontFamily.Monospace,
        ),
        cursorBrush = SolidColor(TitanColors.Primary),
        modifier = modifier
            .background(TitanColors.Canvas, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 5.dp),
        decorationBox = { inner ->
            if (value.isEmpty()) {
                Text(placeholder, color = TitanColors.TextMuted, fontSize = 11.5.sp)
            }
            inner()
        },
    )
}

@Composable
private fun ActionChip(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = label,
            color = TitanColors.TextMuted,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun NoFileOpen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "TITAN IDE",
                color = TitanColors.TextMuted.copy(alpha = 0.35f),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 3.sp,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Open a file from the explorer to start editing",
                color = TitanColors.TextMuted.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = "Ctrl+S saves  ·  Ctrl+/ comments  ·  Ctrl+Z undoes  ·  Ctrl+Shift+Z redoes",
                color = TitanColors.TextMuted.copy(alpha = 0.5f),
                fontSize = 10.sp,
            )
        }
    }
}

/**
 * The find-references result list.
 *
 * It sits above the status strip rather than in a sheet so the code stays visible while the
 * list is read: deciding which use matters needs both on screen.
 */
@Composable
private fun ReferencesPanel(
    name: String,
    references: List<SymbolReference>,
    onOpen: (SymbolReference) -> Unit,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 180.dp)
            .background(TitanColors.CanvasElevated),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "$name \u00b7 ${references.size} reference${if (references.size == 1) "" else "s"}",
                color = TitanColors.TextPrimary,
                fontSize = 10.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "Close",
                color = TitanColors.Secondary,
                fontSize = 10.5.sp,
                modifier = Modifier.clickable(onClick = onClose).padding(4.dp),
            )
        }
        if (references.isEmpty()) {
            Text(
                text = "Nothing in the project uses this symbol.",
                color = TitanColors.TextMuted,
                fontSize = 10.5.sp,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(references, key = { "${it.filePath}:${it.line}:${it.column}" }) { reference ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(WorkbenchMetrics.RowHeight)
                        .clickable { onOpen(reference) }
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${reference.filePath.substringAfterLast('/')}:${reference.line + 1}",
                        color = TitanColors.Secondary,
                        fontSize = 10.sp,
                        maxLines = 1,
                        modifier = Modifier.width(120.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = reference.lineText,
                        color = if (reference.isDeclaration) TitanColors.TextPrimary else TitanColors.TextMuted,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}
