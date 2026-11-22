package dev.titanide.app.ui.workbench

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.titanide.app.di.TitanContainer
import dev.titanide.app.extensions.TitanCapability
import dev.titanide.core.data.files.SafeFileSystem
import dev.titanide.core.editor.codeguard.CodeGuard
import dev.titanide.core.engine.InstallOutcome
import dev.titanide.core.editor.EditorEngine
import dev.titanide.core.editor.RenamePlan
import dev.titanide.core.editor.RenamePlanner
import dev.titanide.core.editor.SearchEngine
import dev.titanide.core.editor.SearchOptions
import dev.titanide.core.editor.SymbolDeclaration
import dev.titanide.core.editor.SymbolIndex
import dev.titanide.core.editor.SymbolReference
import dev.titanide.core.editor.TextDiff
import dev.titanide.core.editor.TextDocument
import dev.titanide.core.editor.UndoStack
import dev.titanide.core.model.BuildKind
import dev.titanide.core.model.BuildRequest
import dev.titanide.core.model.BuildResult
import dev.titanide.core.model.BuildStatus
import dev.titanide.core.model.Diagnostic
import dev.titanide.core.model.ExecutionTarget
import dev.titanide.core.model.FileKind
import dev.titanide.core.model.Language
import dev.titanide.core.model.Project
import dev.titanide.core.model.Severity
import dev.titanide.core.opencode.model.PermissionDecision
import dev.titanide.core.opencode.session.OpenCodeState
import dev.titanide.core.workbench.Activity
import dev.titanide.core.workbench.AndroidProjectView
import dev.titanide.core.workbench.BottomTool
import dev.titanide.core.workbench.EditorGroup
import dev.titanide.core.workbench.LanguageDetector
import dev.titanide.core.workbench.ProjectNode
import dev.titanide.core.workbench.WorkbenchState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One open buffer. Separate from the tab metadata, which holds no content. */
data class BufferState(
    val path: String,
    val content: String,
    val language: Language,
    val caret: Int = 0,
    val savedContent: String = "",
) {
    val dirty: Boolean get() = content != savedContent
    val lineCount: Int get() = content.count { it == '\n' } + 1
    val caretLine: Int get() = content.take(caret.coerceIn(0, content.length)).count { it == '\n' } + 1
    val caretColumn: Int
        get() {
            val upto = content.take(caret.coerceIn(0, content.length))
            return upto.length - upto.lastIndexOf('\n')
        }
}

/** A search hit in the project. */
data class SearchHit(val path: String, val line: Int, val text: String)

/** The subset of editor settings that affect how text is drawn. */
data class EditorAppearance(
    val fontSizeSp: Int = 13,
    val lineHeightSp: Int = 20,
    val tabWidth: Int = 4,
    val wordWrap: Boolean = false,
    val showLineNumbers: Boolean = true,
    val fontFamily: String = "monospace",
    val highlightCurrentLine: Boolean = true,
)

/**
 * In-file find and replace. [matches] are character offsets into the buffer so the editor can
 * select one without re-running the search; [activeIndex] is the match the user is on.
 */
data class FindState(
    val open: Boolean = false,
    val query: String = "",
    val replacement: String = "",
    val replaceMode: Boolean = false,
    val matches: List<IntRange> = emptyList(),
    val activeIndex: Int = 0,
) {
    val current: IntRange? get() = matches.getOrNull(activeIndex)
    val count: Int get() = matches.size
}

/** How many on-disk files the symbol index will read. Keeps a huge repo from stalling. */
private const val MAX_INDEXED_FILES = 400

/**
 * Drives the workbench: real project files through [SafeFileSystem], real diagnostics
 * through CodeGuard, real builds through the shared BuildEngine, and the real OpenCode
 * session. The Compose layer only renders this state and forwards intents.
 */
class WorkbenchViewModel(
    private val container: TitanContainer,
    /** Seam for tests that need to control how long analysis takes. */
    private val analyzer: (String, Language, String) -> List<Diagnostic> = { text, language, path ->
        CodeGuard().analyze(text, language, path)
    },
) : ViewModel() {

    private val _state = MutableStateFlow(WorkbenchState())
    val state: StateFlow<WorkbenchState> = _state.asStateFlow()

    private val _buffers = MutableStateFlow<Map<String, BufferState>>(emptyMap())
    val buffers: StateFlow<Map<String, BufferState>> = _buffers.asStateFlow()

    private val _project = MutableStateFlow<Project?>(null)
    val project: StateFlow<Project?> = _project.asStateFlow()

    private val _androidTree = MutableStateFlow<List<ProjectNode>>(emptyList())
    val androidTree: StateFlow<List<ProjectNode>> = _androidTree.asStateFlow()

    private val _projectTree = MutableStateFlow<List<ProjectNode>>(emptyList())
    val projectTree: StateFlow<List<ProjectNode>> = _projectTree.asStateFlow()

    private val _diagnostics = MutableStateFlow<List<Diagnostic>>(emptyList())

    // Analysis runs off the main thread, one coroutine per keystroke. Without a sequence number
    // a slower analysis of older text can finish last and publish diagnostics for a line the
    // user has already fixed, so errors appear to stick.
    private val analyzeGeneration = java.util.concurrent.atomic.AtomicLong()
    private val analyzeJobs = mutableMapOf<String, Job>()

    // _diagnostics is the panel for the active tab. Keeping the per-file results means
    // switching tabs shows that file's problems instead of the ones the panel happened to
    // hold last, and it does not need a second analysis pass to do it.
    private val diagnosticsByPath = java.util.concurrent.ConcurrentHashMap<String, List<Diagnostic>>()
    val diagnostics: StateFlow<List<Diagnostic>> = _diagnostics.asStateFlow()

    private val _expanded = MutableStateFlow<Set<String>>(emptySet())
    val expanded: StateFlow<Set<String>> = _expanded.asStateFlow()

    private val _searchHits = MutableStateFlow<List<SearchHit>>(emptyList())
    val searchHits: StateFlow<List<SearchHit>> = _searchHits.asStateFlow()

    private val _appearance = MutableStateFlow(EditorAppearance())
    val appearance: StateFlow<EditorAppearance> = _appearance.asStateFlow()

    private val _find = MutableStateFlow(FindState())
    val find: StateFlow<FindState> = _find.asStateFlow()

    /** Bumped whenever something must scroll the caret into view. */
    private val _revealRequests = MutableStateFlow(0L)
    val revealRequests: StateFlow<Long> = _revealRequests.asStateFlow()

    private val _symbolResults = MutableStateFlow<List<SymbolDeclaration>>(emptyList())
    val symbolResults: StateFlow<List<SymbolDeclaration>> = _symbolResults.asStateFlow()

    private val _references = MutableStateFlow<List<SymbolReference>>(emptyList())
    val references: StateFlow<List<SymbolReference>> = _references.asStateFlow()

    /** The name the references panel is currently showing, or null when it is closed. */
    private val _referenceTarget = MutableStateFlow<String?>(null)
    val referenceTarget: StateFlow<String?> = _referenceTarget.asStateFlow()

    /** The symbol the user asked to rename, before a new name has been typed. */
    private val _renameTarget = MutableStateFlow<String?>(null)
    val renameTarget: StateFlow<String?> = _renameTarget.asStateFlow()

    /** A rename waiting for the user to confirm it. Nothing is written until they do. */
    private val _pendingRename = MutableStateFlow<RenamePlan?>(null)
    val pendingRename: StateFlow<RenamePlan?> = _pendingRename.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** A tab whose close was interrupted because it has unsaved edits. */
    private val _pendingClose = MutableStateFlow<String?>(null)
    val pendingClose: StateFlow<String?> = _pendingClose.asStateFlow()

    /** True while leaving the workbench is waiting on the unsaved-edits decision. */
    private val _pendingExit = MutableStateFlow(false)
    val pendingExit: StateFlow<Boolean> = _pendingExit.asStateFlow()

    private val _installInProgress = MutableStateFlow(false)
    val installInProgress: StateFlow<Boolean> = _installInProgress.asStateFlow()

    private val _lastInstalledPackage = MutableStateFlow<String?>(null)
    val lastInstalledPackage: StateFlow<String?> = _lastInstalledPackage.asStateFlow()

    val build: StateFlow<BuildResult?> get() = container.buildEngine.lastResult
    val buildLogs: StateFlow<Map<String, List<String>>> get() = container.buildEngine.logs
    val agent: StateFlow<OpenCodeState> get() = container.openCodeSession.state

    /** Undo history is per file: an edit record's offsets only mean anything in its own buffer. */
    private val undoStacks = java.util.concurrent.ConcurrentHashMap<String, UndoStack>()

    private fun undoStackFor(path: String): UndoStack =
        undoStacks.computeIfAbsent(path) { UndoStack() }
    private val searchEngine = SearchEngine()
    private val symbolIndex = SymbolIndex()
    /** Debounce handles are per file, so work queued for one file is never cancelled by another. */
    private val debounceJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            container.projectRepository.observeProjects().collect { projects ->
                val current = _project.value
                val target = current?.let { existing -> projects.firstOrNull { it.id == existing.id } }
                    ?: projects.maxByOrNull { it.lastOpenedAt }
                if (target != null && target.id != current?.id) loadProject(target)
            }
        }
        viewModelScope.launch {
            container.settings.settings.collect { settings ->
                _appearance.value = EditorAppearance(
                    // Clamped so a corrupt persisted value cannot make the editor unreadable.
                    fontSizeSp = settings.editor.fontSize.coerceIn(8, 32),
                    lineHeightSp = settings.editor.lineHeight.coerceIn(12, 48),
                    wordWrap = settings.editor.wordWrap,
                    showLineNumbers = settings.editor.showLineNumbers,
                    highlightCurrentLine = settings.editor.highlightCurrentLine,
                )
            }
        }
        viewModelScope.launch {
            container.settings.settings.collect { settings ->
                if (settings.openCode.autoConnect && !agent.value.isConnected) {
                    container.connectAgent(_project.value)
                }
            }
        }
    }

    // ------------------------------------------------------------- project

    fun openProject(projectId: String) {
        viewModelScope.launch {
            val project = container.projectRepository.getProject(projectId) ?: return@launch
            container.projectRepository.touch(projectId)
            loadProject(project)
        }
    }

    private fun loadProject(project: Project) {
        _project.value = project
        _buffers.value = emptyMap()
        undoStacks.clear()
        _diagnostics.value = emptyList()
        _state.update {
            it.copy(
                projectName = project.name,
                projectPath = project.rootPath,
                groups = listOf(EditorGroup(0)),
                activeGroupIndex = 0,
            )
        }
        refreshFiles()
        container.connectAgent(project)
    }

    fun refreshFiles() {
        val project = _project.value ?: return
        viewModelScope.launch {
            val tree = container.fileTreeRepository.list(project)
            val paths = tree.filter { it.kind == FileKind.FILE }.map { it.path }
            val android = withContext(Dispatchers.Default) { AndroidProjectView.build(paths) }
            val plain = withContext(Dispatchers.Default) { AndroidProjectView.buildProjectView(paths) }
            _androidTree.value = android
            _projectTree.value = plain
            if (_expanded.value.isEmpty()) {
                _expanded.value = paths
                    .flatMap { path ->
                        val dir = path.substringBeforeLast('/', "")
                        generateSequence(dir.takeIf { it.isNotEmpty() }) { it.substringBeforeLast('/', "").ifEmpty { null } }
                            .toList()
                    }
                    .toSet()
            }
        }
    }

    // ---------------------------------------------------------------- tabs

    /**
     * Opens a file and puts the caret on [line] (1-based), so a project-search hit lands where
     * the user clicked instead of at the top of the file.
     */
    fun openFileAtLine(path: String, line: Int) {
        openFile(path)
        viewModelScope.launch {
            // openFile reads from disk asynchronously, so wait for the buffer to exist.
            val deadline = System.currentTimeMillis() + 5_000
            while (_buffers.value[path] == null && System.currentTimeMillis() < deadline) {
                kotlinx.coroutines.delay(20)
            }
            val buffer = _buffers.value[path] ?: return@launch
            moveCaretTo(path, offsetOfLine(buffer.content, line))
        }
    }

    private fun offsetOfLine(content: String, line: Int): Int {
        if (line <= 1) return 0
        var seen = 1
        content.forEachIndexed { index, ch ->
            if (ch == '\n') {
                seen++
                if (seen == line) return index + 1
            }
        }
        return content.length
    }

    /** Moves the caret and asks the editor to scroll it into view. */
    private fun moveCaretTo(path: String, offset: Int) {
        setCaret(path, offset)
        _revealRequests.value = _revealRequests.value + 1
    }

    fun openFile(path: String) {
        val project = _project.value ?: return
        if (_buffers.value.containsKey(path)) {
            _state.value = _state.value.activateTab(path)
            publishActive(path)
            return
        }
        viewModelScope.launch {
            when (val result = container.fileTreeRepository.read(project, path)) {
                is SafeFileSystem.Result.Ok -> {
                    val buffer = BufferState(
                        path = path,
                        content = result.value,
                        language = Language.fromFileName(path),
                        savedContent = result.value,
                    )
                    _buffers.value = _buffers.value + (path to buffer)
                    _state.value = _state.value.openFile(path = path, languageId = LanguageDetector.detect(path))
                    publishActive(path)
                    analyze(buffer)
                }

                is SafeFileSystem.Result.Failure -> _message.value = result.reason
            }
        }
    }

    /** Every buffer whose text is not on disk, in tab order. */
    fun unsavedPaths(): List<String> = _state.value.activeGroup.tabs
        .map { it.path }
        .filter { hasUnsavedChanges(it) }

    /**
     * Asks to leave the workbench.
     *
     * Returns true when the caller may navigate away immediately. Otherwise the edits are
     * only in memory, so the exit waits for [saveAndExit] or [discardAndExit].
     */
    fun requestExit(): Boolean {
        if (unsavedPaths().isEmpty()) {
            _pendingExit.value = false
            return true
        }
        _pendingExit.value = true
        return false
    }

    /**
     * Writes every dirty buffer, then reports whether the exit may proceed.
     *
     * A write that fails keeps the prompt open: the alternative is navigating away from text
     * the user believes was saved.
     */
    suspend fun saveAndExit(): Boolean {
        val paths = unsavedPaths()
        var allWritten = true
        for (path in paths) {
            val content = _buffers.value[path]?.content ?: continue
            if (!saveNow(path, content, silent = true)) allWritten = false
        }
        if (!allWritten) return false
        _pendingExit.value = false
        _message.value = "Saved ${paths.size} file${if (paths.size == 1) "" else "s"}"
        return true
    }

    /** Abandons the unsaved edits and leaves. Only reachable from the exit prompt. */
    fun discardAndExit() {
        _pendingExit.value = false
        for (path in unsavedPaths()) forgetPath(path)
    }

    fun cancelExit() {
        _pendingExit.value = false
    }

    fun closeTab(tabId: String) {
        // Closing drops the buffer, so unsaved edits would go with it and nothing else holds
        // them. Ask first; discardAndCloseTab is the confirmed path.
        if (hasUnsavedChanges(tabId)) {
            _pendingClose.value = tabId
            return
        }
        closeTabNow(tabId)
    }

    /** Closes a tab whose unsaved edits the user agreed to discard. */
    fun discardAndCloseTab() {
        val tabId = _pendingClose.value ?: return
        _pendingClose.value = null
        closeTabNow(tabId)
    }

    fun cancelPendingClose() {
        _pendingClose.value = null
    }

    private fun closeTabNow(tabId: String) {
        _state.value = _state.value.closeTab(tabId)
        forgetPath(tabId)
        publishActiveState()
        refreshFind(previousContent = null, caret = _buffers.value[_state.value.activeTab?.path]?.caret ?: 0)
    }

    /** Drops every piece of state keyed by [path]; a path with no buffer must leave no trace. */
    private fun forgetPath(path: String) {
        _buffers.value = _buffers.value - path
        diagnosticsByPath.remove(path)
        undoStacks.remove(path)
        debounceJobs.remove("analyze:$path")?.cancel()
        debounceJobs.remove("save:$path")?.cancel()
        analyzeJobs.remove(path)?.cancel()
    }

    fun activateTab(tabId: String) {
        _state.value = _state.value.activateTab(tabId)
        publishActive(tabId)
        refreshFind(previousContent = null, caret = _buffers.value[tabId]?.caret ?: 0)
    }

    /**
     * Keeps the recorded match ranges pointing at the query. A match is an offset range in one
     * buffer, so it goes stale on any edit before it and is meaningless in another file.
     */
    private fun refreshFind(previousContent: String?, caret: Int) {
        val state = _find.value
        if (state.query.isEmpty()) return
        val content = activePath()?.let { _buffers.value[it]?.content } ?: return
        val matches = matchRanges(content, state.query)
        // Keep the selection on the same occurrence when the text merely shifted.
        val previous = state.current
        val moved = previous != null && previousContent != null
        val index = when {
            matches.isEmpty() -> 0
            moved -> {
                val delta = content.length - previousContent.length
                val target = (previous.first + delta).coerceAtLeast(0)
                matches.indexOfFirst { it.first == target }.takeIf { it >= 0 }
                    ?: matches.indexOfFirst { it.first >= caret }.coerceAtLeast(0)
            }
            else -> matches.indexOfFirst { it.first >= caret }.coerceAtLeast(0)
        }
        _find.value = state.copy(matches = matches, activeIndex = index)
    }

    /** Republish for whatever is active now, clearing the panel when nothing is. */
    private fun publishActiveState() {
        val path = _state.value.activeTab?.path
        if (path == null) {
            _diagnostics.value = emptyList()
            _state.update { it.copy(status = it.status.copy(errorCount = 0, warningCount = 0)) }
            return
        }
        publishActive(path)
    }

    private fun publishActive(path: String) {
        val buffer = _buffers.value[path] ?: return
        container.activeFile.update(
            projectId = _project.value?.id,
            path = path,
            content = buffer.content,
            language = buffer.language.id,
        )
        val own = diagnosticsByPath[path].orEmpty()
        _diagnostics.value = own
        val errors = own.count { it.severity == Severity.ERROR }
        val warnings = own.count { it.severity == Severity.WARNING }
        _state.update {
            it.copy(
                status = it.status.copy(
                    languageId = LanguageDetector.displayName(LanguageDetector.detect(path)),
                    cursorLine = buffer.caretLine,
                    cursorColumn = buffer.caretColumn,
                    errorCount = errors,
                    warningCount = warnings,
                ),
            )
        }
    }

    // -------------------------------------------------------------- editing

    fun edit(path: String, content: String, caret: Int = -1) {
        val buffer = _buffers.value[path] ?: return
        // Caret movement arrives through the same channel as text changes.
        if (content == buffer.content) {
            if (caret >= 0) setCaret(path, caret)
            return
        }
        TextDiff.singleEdit(buffer.content, content)?.let(undoStackFor(path)::record)
        val updated = buffer.copy(content = content, caret = if (caret >= 0) caret else buffer.caret)
        _buffers.value = _buffers.value + (path to updated)
        _state.value = _state.value.setDirty(path, updated.dirty)
        publishActive(path)
        refreshFind(previousContent = buffer.content, caret = updated.caret)

        debounceJobs.remove("analyze:$path")?.cancel()
        debounceJobs["analyze:$path"] = viewModelScope.launch {
            delay(350)
            analyze(updated)
        }

        val editorSettings = container.currentSettings()?.editor
        if (editorSettings?.autoSave ?: true) {
            debounceJobs.remove("save:$path")?.cancel()
            debounceJobs["save:$path"] = viewModelScope.launch {
                delay((editorSettings?.autoSaveDelayMs ?: 1_200L).coerceIn(300L, 10_000L))
                save(path, silent = true)
            }
        }
    }

    fun setCaret(path: String, caret: Int) {
        val buffer = _buffers.value[path] ?: return
        val updated = buffer.copy(caret = caret)
        _buffers.value = _buffers.value + (path to updated)
        _state.update {
            it.copy(
                status = it.status.copy(
                    cursorLine = updated.caretLine,
                    cursorColumn = updated.caretColumn,
                ),
            )
        }
    }

    fun save(path: String, silent: Boolean = false) {
        val buffer = _buffers.value[path] ?: return
        if (buffer.content == buffer.savedContent) return
        // The text is captured here, not inside the coroutine: a launch can start after the
        // user typed again, and writing that newer text would report it saved while the buffer
        // has already moved on.
        val written = buffer.content
        viewModelScope.launch { saveNow(path, written, silent) }
    }

    /**
     * Writes [written] to [path] and reports whether the bytes reached the disk.
     *
     * The caller decides what a failure means: the exit guard must stay open, while an
     * auto-save just shows the message.
     */
    private suspend fun saveNow(path: String, written: String, silent: Boolean): Boolean {
        val project = _project.value ?: return false
        _buffers.value[path] ?: return true
        return when (val result = container.fileTreeRepository.write(project, path, written)) {
            is SafeFileSystem.Result.Ok -> {
                // Only the text that was written counts as saved. If the user typed while
                // the write was in flight the buffer has moved on, and marking it clean
                // would skip the next auto-save and lose that text when the tab closes.
                _buffers.update { buffers ->
                    val latest = buffers[path] ?: return@update buffers
                    buffers + (path to latest.copy(savedContent = written))
                }
                val stillSaved = _buffers.value[path]?.content == written
                _state.value = _state.value.setDirty(path, !stillSaved)
                if (!silent) _message.value = "Saved ${path.substringAfterLast('/')}"
                true
            }

            is SafeFileSystem.Result.Failure -> {
                _message.value = "Save failed: ${result.reason}"
                false
            }
        }
    }

    fun saveActive() {
        _state.value.activeTab?.path?.let { save(it) }
    }

    fun undo() {
        val path = _state.value.activeTab?.path ?: return
        val buffer = _buffers.value[path] ?: return
        val previous = undoStackFor(path).undo(TextDocument.of(buffer.content)) ?: return
        applyText(path, previous.text)
    }

    fun redo() {
        val path = _state.value.activeTab?.path ?: return
        val buffer = _buffers.value[path] ?: return
        val next = undoStackFor(path).redo(TextDocument.of(buffer.content)) ?: return
        applyText(path, next.text)
    }

    fun toggleComment() {
        val path = _state.value.activeTab?.path ?: return
        val buffer = _buffers.value[path] ?: return
        val document = TextDocument.of(buffer.content)
        val caret = buffer.caret.coerceIn(0, buffer.content.length)
        val line = document.lineOf(caret)
        val transform = EditorEngine(buffer.language, indentUnit())
            .toggleLineComment(document, document.lineStart(line), document.lineEnd(line)) ?: return
        applyTransform(path, transform)
    }

    fun formatDocument() {
        val path = _state.value.activeTab?.path ?: return
        val buffer = _buffers.value[path] ?: return
        val transform = EditorEngine(buffer.language, indentUnit()).formatDocument(TextDocument.of(buffer.content)) ?: return
        applyTransform(path, transform)
        _message.value = "Formatted ${path.substringAfterLast('/')}"
    }

    fun indent(outdent: Boolean) {
        val path = _state.value.activeTab?.path ?: return
        val buffer = _buffers.value[path] ?: return
        val document = TextDocument.of(buffer.content)
        val caret = buffer.caret.coerceIn(0, buffer.content.length)
        val line = document.lineOf(caret)
        val transform = EditorEngine(buffer.language, indentUnit())
            .indentSelection(document, document.lineStart(line), document.lineEnd(line), outdent)
        applyTransform(path, transform)
    }

    private fun applyTransform(path: String, transform: EditorEngine.Transform) {
        transform.edit?.let(undoStackFor(path)::record)
        val buffer = _buffers.value[path] ?: return
        val updated = buffer.copy(content = transform.document.text, caret = transform.caret)
        _buffers.value = _buffers.value + (path to updated)
        _state.value = _state.value.setDirty(path, updated.dirty)
        publishActive(path)
        refreshFind(previousContent = buffer.content, caret = updated.caret)
        analyze(updated)
    }

    private fun applyText(path: String, text: String) {
        val buffer = _buffers.value[path] ?: return
        val updated = buffer.copy(content = text)
        _buffers.value = _buffers.value + (path to updated)
        _state.value = _state.value.setDirty(path, updated.dirty)
        publishActive(path)
    }

    private fun analyze(buffer: BufferState) {
        if (!(container.currentSettings()?.editor?.codeGuardEnabled ?: true)) {
            analyzeJobs.remove(buffer.path)?.cancel()
            diagnosticsByPath.remove(buffer.path)
            _diagnostics.value = emptyList()
            return
        }
        // Analysis tokenizes the whole buffer; running it inline would stall typing. The newest
        // request wins: an older job is cancelled, and the generation check drops its result if
        // it had already finished computing.
        val generation = analyzeGeneration.incrementAndGet()
        analyzeJobs.remove(buffer.path)?.cancel()
        analyzeJobs[buffer.path] = viewModelScope.launch {
            val found = withContext(Dispatchers.Default) {
                analyzer(buffer.content, buffer.language, buffer.path)
            }
            if (generation != analyzeGeneration.get()) return@launch
            applyDiagnostics(buffer, found)
        }
    }

    private fun applyDiagnostics(buffer: BufferState, found: List<Diagnostic>) {
        diagnosticsByPath[buffer.path] = found
        if (_state.value.activeTab?.path == buffer.path) {
            _diagnostics.value = found
            _state.update {
                it.copy(
                    status = it.status.copy(
                        errorCount = found.count { it.severity == Severity.ERROR },
                        warningCount = found.count { it.severity == Severity.WARNING },
                    ),
                )
            }
        }
    }

    // --------------------------------------------------------------- search

    // ----------------------------------------------------------------- find in file

    fun openFind(replaceMode: Boolean = false) {
        _find.update { it.copy(open = true, replaceMode = replaceMode) }
    }

    fun closeFind() {
        _find.value = FindState()
    }

    fun setFindQuery(query: String) {
        val active = activePath()
        val content = active?.let { _buffers.value[it]?.content }.orEmpty()
        val matches = matchRanges(content, query)
        // Keep the user near where they were: prefer the first match at or after the caret.
        val caret = active?.let { _buffers.value[it]?.caret } ?: 0
        val start = matches.indexOfFirst { it.first >= caret }.coerceAtLeast(0)
        _find.update { it.copy(query = query, matches = matches, activeIndex = start) }
        active?.let { path -> matches.getOrNull(start)?.let { moveCaretTo(path, it.first) } }
    }

    fun setFindReplacement(text: String) {
        _find.update { it.copy(replacement = text) }
    }

    fun findNext() {
        val state = _find.value
        if (state.matches.isEmpty()) return
        val next = (state.activeIndex + 1) % state.matches.size
        _find.value = state.copy(activeIndex = next)
        activePath()?.let { path -> state.matches[next].let { moveCaretTo(path, it.first) } }
    }

    fun findPrevious() {
        val state = _find.value
        if (state.matches.isEmpty()) return
        val previous = if (state.activeIndex <= 0) state.matches.lastIndex else state.activeIndex - 1
        _find.value = state.copy(activeIndex = previous)
        activePath()?.let { path -> state.matches[previous].let { moveCaretTo(path, it.first) } }
    }

    /** Replaces the current match. Literal text only, so a `$` in the replacement is safe. */
    fun replaceCurrent() {
        val path = activePath() ?: return
        val buffer = _buffers.value[path] ?: return
        val match = _find.value.current ?: return
        val updated = buffer.content.replaceRange(match.first, match.last + 1, _find.value.replacement)
        edit(path, updated)
        val matches = matchRanges(updated, _find.value.query)
        val next = matches.indexOfFirst { it.first > match.first }.coerceAtLeast(0)
        _find.update { it.copy(matches = matches, activeIndex = next) }
        matches.getOrNull(next)?.let { moveCaretTo(path, it.first) }
    }

    fun replaceAll() {
        val path = activePath() ?: return
        val buffer = _buffers.value[path] ?: return
        val state = _find.value
        if (state.query.isEmpty() || state.matches.isEmpty()) return
        val updated = StringBuilder()
        var cursor = 0
        state.matches.forEach { match ->
            updated.append(buffer.content, cursor, match.first)
            updated.append(state.replacement)
            cursor = match.last + 1
        }
        updated.append(buffer.content, cursor, buffer.content.length)
        val replaced = state.matches.size
        edit(path, updated.toString())
        _find.value = state.copy(matches = emptyList(), activeIndex = 0)
        _message.value = "Replaced $replaced occurrence(s) of \"${state.query}\""
    }

    private fun activePath(): String? = _state.value.activeTab?.path

    /** All non-overlapping literal occurrences of [query], in buffer order. */
    private fun matchRanges(content: String, query: String): List<IntRange> {
        if (query.isEmpty()) return emptyList()
        val ranges = ArrayList<IntRange>()
        var index = content.indexOf(query)
        while (index >= 0) {
            ranges += index until (index + query.length)
            index = content.indexOf(query, index + query.length)
        }
        return ranges
    }

    fun search(query: String) {
        searchJob?.cancel()
        if (query.isBlank()) {
            _searchHits.value = emptyList()
            return
        }
        val project = _project.value ?: return
        searchJob = viewModelScope.launch {
            delay(220)
            val options = SearchOptions(caseSensitive = false)
            val files = LinkedHashMap<String, String>()
            for (path in _buffers.value.keys) {
                _buffers.value[path]?.let { files[path] = it.content }
            }
            val hits = ArrayList<SearchHit>()
            searchEngine.searchFiles(files, query, options).forEach { result ->
                result.matches.forEach { match ->
                    hits += SearchHit(result.filePath, match.line + 1, match.lineText.trim())
                }
            }
            // Files that are not open still need searching, so walk them from disk.
            if (hits.size < 300) {
                val open = files.keys
                val onDisk = container.fileTreeRepository.list(project)
                    .filter { it.kind == FileKind.FILE && it.path !in open }
                    .map { it.path }
                    .take(300)
                for (path in onDisk) {
                    when (val read = container.fileTreeRepository.read(project, path)) {
                        is SafeFileSystem.Result.Ok -> {
                            val matches = searchEngine.search(TextDocument.of(read.value), query, options)
                            matches.forEach { match ->
                                hits += SearchHit(path, match.line + 1, match.lineText.trim())
                            }
                        }

                        is SafeFileSystem.Result.Failure -> Unit
                    }
                    if (hits.size > 400) break
                }
            }
            _searchHits.value = hits
        }
    }

    // ------------------------------------------------------------ navigation

    private var indexJob: Job? = null
    private var symbolJob: Job? = null

    /**
     * Rebuilds the project symbol index from the open buffers and the files on disk.
     *
     * There is no language server, so go-to-definition and find-references are answered from
     * this index. It is rebuilt on demand rather than on every keystroke because a project-wide
     * re-tokenise is far more work than a single-file analysis.
     */
    fun buildSymbolIndex() {
        indexJob?.cancel()
        indexJob = viewModelScope.launch { buildSymbolIndexNow() }
    }

    private suspend fun buildSymbolIndexNow() {
        val project = _project.value ?: return
            val open = _buffers.value
            val sources = LinkedHashMap<String, String>()
            open.forEach { (path, buffer) -> sources[path] = buffer.content }
            withContext(Dispatchers.Default) {
                val onDisk = container.fileTreeRepository.list(project)
                    .filter { it.kind == FileKind.FILE && it.path !in open }
                    .map { it.path }
                    .take(MAX_INDEXED_FILES)
                for (path in onDisk) {
                    val read = container.fileTreeRepository.read(project, path)
                    if (read is SafeFileSystem.Result.Ok) sources[path] = read.value
                }
            }
        withContext(Dispatchers.Default) {
            symbolIndex.clear()
            sources.forEach { (path, text) ->
                symbolIndex.put(path, text, Language.fromFileName(path))
            }
        }
    }

    /**
     * Builds the index if it is empty and waits for it.
     *
     * Navigation used to kick off a rebuild and then read the index immediately, so the first
     * go-to-definition in a session found nothing. Awaiting the build is what makes the first
     * use work.
     */
    private suspend fun ensureIndex() {
        if (symbolIndex.size > 0) return
        indexJob?.join()
        if (symbolIndex.size > 0) return
        buildSymbolIndexNow()
    }

    /** Symbols whose name contains [query], for the go-to-symbol list. */
    fun searchSymbols(query: String) {
        if (query.isBlank()) {
            _symbolResults.value = emptyList()
            return
        }
        symbolJob?.cancel()
        symbolJob = viewModelScope.launch {
            ensureIndex()
            _symbolResults.value = symbolIndex.search(query)
        }
    }

    /** Jump to the declaration under the caret in the active buffer. */
    fun goToDefinition() {
        val path = _state.value.activeTab?.path ?: return
        val buffer = _buffers.value[path] ?: return
        val caret = buffer.caret
        viewModelScope.launch {
            ensureIndex()
            val definition = symbolIndex.definitionAt(path, caret)
            if (definition == null) {
                _message.value = "No declaration found for the symbol at the caret"
                return@launch
            }
            if (definition.symbol.filePath == path) {
                moveCaretTo(definition.line, definition.column)
            } else {
                openFileAtLine(definition.symbol.filePath, definition.line + 1)
            }
        }
    }

    /** Show every use of the symbol under the caret in the active buffer. */
    fun findReferences() {
        val path = _state.value.activeTab?.path ?: return
        val buffer = _buffers.value[path] ?: return
        val caret = buffer.caret
        viewModelScope.launch {
            ensureIndex()
            val name = symbolIndex.identifierAt(path, caret)
            if (name == null) {
                _message.value = "Put the caret on a symbol to find its references"
                return@launch
            }
            val found = symbolIndex.referencesTo(name)
            _referenceTarget.value = name
            _references.value = found
            if (found.isEmpty()) _message.value = "No references to $name"
        }
    }

    fun closeReferences() {
        _referenceTarget.value = null
        _references.value = emptyList()
    }

    // ---------------------------------------------------------------- rename

    /**
     * Resolves the symbol under the caret and asks the user for a new name.
     *
     * The name has to be typed somewhere, so this only finds the old one; [renameSymbol]
     * receives the new one from the prompt.
     */
    fun beginRename() {
        val path = _state.value.activeTab?.path ?: return
        val buffer = _buffers.value[path] ?: return
        val caret = buffer.caret
        viewModelScope.launch {
            ensureIndex()
            val name = symbolIndex.identifierAt(path, caret)
            if (name == null) {
                _message.value = "Put the caret on a symbol to rename it"
                return@launch
            }
            _renameTarget.value = name
        }
    }

    /** Abandons the rename prompt without planning anything. */
    fun cancelBeginRename() {
        _renameTarget.value = null
    }

    /**
     * Plans a rename of the symbol under the caret.
     *
     * The plan is only shown: renaming across files is destructive, so the user sees every
     * file and occurrence that would change before anything is written.
     */
    fun renameSymbol(newName: String) {
        _renameTarget.value = null
        val path = _state.value.activeTab?.path ?: return
        val buffer = _buffers.value[path] ?: return
        val caret = buffer.caret
        viewModelScope.launch {
            ensureIndex()
            val oldName = symbolIndex.identifierAt(path, caret)
            if (oldName == null) {
                _message.value = "Put the caret on a symbol to rename it"
                return@launch
            }
            val plan = planRename(oldName, newName)
            val rejection = plan.rejection
            if (rejection != null) {
                _message.value = rejection
                _pendingRename.value = null
                return@launch
            }
            _pendingRename.value = plan
        }
    }

    /** Drops a rename the user decided not to apply. */
    fun cancelRename() {
        _pendingRename.value = null
    }

    /**
     * Writes a confirmed rename.
     *
     * Each changed file is recorded on its own undo stack, so the rename can be taken back
     * one file at a time exactly like a typed edit.
     */
    fun applyRename() {
        val plan = _pendingRename.value ?: return
        val project = _project.value ?: return
        _pendingRename.value = null
        viewModelScope.launch {
            var written = 0
            val failures = ArrayList<String>()
            for (change in plan.changedFiles) {
                when (val result = container.fileTreeRepository.write(project, change.filePath, change.after)) {
                    is SafeFileSystem.Result.Ok -> {
                        written++
                        // Record on every changed file, not only the open ones, so undo covers
                        // the whole rename rather than just what happened to be on screen.
                        val (_, record) = TextDocument.of(change.before)
                            .replace(0 until change.before.length, change.after)
                        undoStackFor(change.filePath).record(record)
                        if (_buffers.value.containsKey(change.filePath)) {
                            // The new text is already on disk, so the buffer is marked saved
                            // rather than written a second time. A second write raced the
                            // save's own message and could overwrite this one.
                            applyText(change.filePath, change.after)
                            _buffers.update { buffers ->
                                val latest = buffers[change.filePath] ?: return@update buffers
                                buffers + (change.filePath to latest.copy(savedContent = change.after))
                            }
                            _state.value = _state.value.setDirty(change.filePath, false)
                        }
                    }

                    is SafeFileSystem.Result.Failure -> failures += "${change.filePath}: ${result.reason}"
                }
            }
            refreshFiles()
            symbolIndex.clear()
            _message.value = when {
                failures.isNotEmpty() -> "Renamed $written file(s); failed: ${failures.joinToString("; ")}"
                else -> "Renamed ${plan.oldName} to ${plan.newName} in $written file(s)"
            }
        }
    }

    private suspend fun planRename(oldName: String, newName: String): RenamePlan {
        val project = _project.value
        val sources = LinkedHashMap<String, String>()
        val languages = HashMap<String, Language>()
        _buffers.value.forEach { (path, buffer) ->
            sources[path] = buffer.content
            languages[path] = buffer.language
        }
        if (project != null) {
            withContext(Dispatchers.Default) {
                val onDisk = container.fileTreeRepository.list(project)
                    .filter { it.kind == FileKind.FILE && it.path !in sources }
                    .map { it.path }
                    .take(MAX_INDEXED_FILES)
                for (path in onDisk) {
                    val read = container.fileTreeRepository.read(project, path)
                    if (read is SafeFileSystem.Result.Ok) {
                        sources[path] = read.value
                        languages[path] = Language.fromFileName(path)
                    }
                }
            }
        }
        return withContext(Dispatchers.Default) {
            RenamePlanner.plan(sources, languages, oldName, newName)
        }
    }

    private fun moveCaretTo(line: Int, column: Int) {
        val path = _state.value.activeTab?.path ?: return
        val buffer = _buffers.value[path] ?: return
        val document = TextDocument.of(buffer.content)
        val offset = (document.lineStart(line) + column).coerceIn(0, buffer.content.length)
        setCaret(path, offset)
        _revealRequests.value = _revealRequests.value + 1
    }

    /**
     * One indent level from the editor settings. Read at call time so a changed tab width
     * takes effect on the next keystroke without restarting the editor.
     */
    private fun indentUnit(): String? {
        val width = container.currentSettings()?.editor?.tabWidth ?: return null
        return " ".repeat(width.coerceIn(2, 8))
    }

    // ---------------------------------------------------------------- build

    fun runBuild(kind: BuildKind) {
        val project = _project.value ?: return
        if (container.buildEngine.isRunning(project.id)) return
        val configured = container.currentSettings()?.build?.defaultTargetId
            ?.let { id -> ExecutionTarget.entries.firstOrNull { it.id == id } }
            ?: ExecutionTarget.LOCAL
        // A remote target is only usable while the remote backend extension is on; otherwise
        // the build would dispatch to a backend the user switched off.
        val target = if (configured == ExecutionTarget.LOCAL ||
            container.capabilityGate.isEnabled(TitanCapability.REMOTE_BUILD)
        ) {
            configured
        } else {
            ExecutionTarget.LOCAL
        }
        if (target != configured) {
            _message.value =
                "Remote builds are switched off in Extensions, so this build runs on the device."
        }
        _state.value = _state.value.showBottom(BottomTool.BUILD)
        container.buildEngine.start(
            BuildRequest(
                projectId = project.id,
                projectPath = project.rootPath,
                kind = kind,
                target = target,
            ),
        )
    }

    fun stopBuild() {
        _project.value?.let { container.buildEngine.stop(it.id) }
    }

    // ---------------------------------------------------------------- install

    /**
     * Installs the APK produced by the last build, then offers to launch it.
     *
     * The APK comes from the build result rather than a guessed path, so what is installed
     * is exactly what the engine produced. Every failure is reported with the package
     * manager's own reason; nothing is reported as installed until Android confirms it.
     */
    fun installLastBuildApk() {
        val result = build.value
        if (result == null) {
            _message.value = "Build the project first: there is no APK to install."
            return
        }
        val apk = result.artifacts.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
        if (apk == null) {
            _message.value = "The last build produced no APK. An Android application build " +
                "is required."
            return
        }
        if (_installInProgress.value) return
        _installInProgress.value = true
        viewModelScope.launch {
            try {
                when (val outcome = container.apkInstaller.install(java.io.File(apk.path))) {
                    is InstallOutcome.Installed -> {
                        _lastInstalledPackage.value = outcome.packageName
                        _message.value = "Installed ${outcome.packageName} " +
                            "${outcome.versionName.orEmpty()}".trim()
                    }
                    is InstallOutcome.NeedsUserConsent -> {
                        _message.value = outcome.reason
                        container.apkInstaller.openInstallPermissionSettings()
                    }
                    is InstallOutcome.Rejected ->
                        _message.value = "Install failed: ${outcome.message}"
                    is InstallOutcome.InvalidArtifact ->
                        _message.value = outcome.reason
                }
            } finally {
                _installInProgress.value = false
            }
        }
    }

    /** Launches the package installed by [installLastBuildApk], if it has a launcher. */
    fun launchInstalledApp() {
        val packageName = _lastInstalledPackage.value ?: return
        if (!container.apkInstaller.launch(packageName)) {
            _message.value = "$packageName has no launcher activity to start."
        }
    }

    fun selectBottomTool(tool: BottomTool) {
        _state.value = _state.value.showBottom(tool)
    }

    fun toggleBottomPanel() {
        _state.value = _state.value.toggleBottom()
    }

    // ------------------------------------------------------------ navigation

    fun selectActivity(activity: Activity) {
        _state.value = _state.value.selectActivity(activity)
    }

    fun closeSidebar() {
        _state.update { it.copy(sidebarVisible = false) }
    }

    fun toggleAgentPanel() {
        _state.value = _state.value.toggleAgentPanel()
        if (!agent.value.isConnected) container.connectAgent(_project.value)
    }

    fun onSizeChanged(widthDp: Int, heightDp: Int) {
        _state.value = _state.value.withLayout(widthDp, heightDp)
    }

    fun toggleAndroidView() {
        _state.update { it.copy(androidViewMode = !it.androidViewMode) }
    }

    fun toggleExpanded(path: String) {
        val current = _expanded.value
        _expanded.value = if (path in current) current - path else current + path
    }

    fun clearMessage() {
        _message.value = null
    }

    // ---------------------------------------------------------------- files

    fun createFile(path: String) {
        val project = _project.value ?: return
        viewModelScope.launch {
            when (val result = container.fileTreeRepository.write(project, path, "")) {
                is SafeFileSystem.Result.Ok -> {
                    refreshFiles()
                    openFile(path)
                    _message.value = "Created $path"
                }

                is SafeFileSystem.Result.Failure -> _message.value = result.reason
            }
        }
    }

    fun createDirectory(path: String) {
        val project = _project.value ?: return
        viewModelScope.launch {
            when (val result = container.fileTreeRepository.createDirectory(project, path)) {
                is SafeFileSystem.Result.Ok -> {
                    refreshFiles()
                    _expanded.value = _expanded.value + path
                    _message.value = "Created $path"
                }

                is SafeFileSystem.Result.Failure -> _message.value = result.reason
            }
        }
    }

    /**
     * Renames or moves a file or directory.
     *
     * A buffer with unsaved edits is carried over to the new path rather than dropped: the
     * alternative is to write the stale saved text over the moved file, or to lose the
     * edits, and both are silent data loss.
     */
    fun renamePath(from: String, to: String) {
        val project = _project.value ?: return
        if (from == to) return
        viewModelScope.launch {
            when (val result = container.fileTreeRepository.move(project, from, to)) {
                is SafeFileSystem.Result.Ok -> {
                    moveBuffers(from, to)
                    refreshFiles()
                    _message.value = "Renamed to $to"
                }

                is SafeFileSystem.Result.Failure -> _message.value = result.reason
            }
        }
    }

    /** Copies a path, refusing to overwrite an existing file. */
    fun duplicatePath(from: String, to: String) {
        val project = _project.value ?: return
        viewModelScope.launch {
            when (val result = container.fileTreeRepository.copy(project, from, to)) {
                is SafeFileSystem.Result.Ok -> {
                    refreshFiles()
                    _message.value = "Copied to $to"
                }

                is SafeFileSystem.Result.Failure -> _message.value = result.reason
            }
        }
    }

    /** True when [path] has edits that are not on disk, so the UI can warn before losing them. */
    fun hasUnsavedChanges(path: String): Boolean {
        val buffer = _buffers.value[path] ?: return false
        return buffer.content != buffer.savedContent
    }

    private fun moveBuffers(from: String, to: String) {
        val moved = _buffers.value.filterKeys { it == from || it.startsWith("$from/") }
        if (moved.isEmpty()) {
            _state.value.activeGroup.tabs
                .firstOrNull { it.path == from }
                ?.let { tab -> _state.value = _state.value.closeTab(tab.id) }
            return
        }
        _buffers.value = _buffers.value - moved.keys
        moved.forEach { (oldPath, buffer) ->
            val newPath = to + oldPath.removePrefix(from)
            _buffers.value = _buffers.value + (newPath to buffer.copy(path = newPath))
            // History and pending work describe this buffer's text, so they move with it.
            undoStacks.remove(oldPath)?.let { undoStacks[newPath] = it }
            for (kind in listOf("analyze", "save")) {
                debounceJobs.remove("$kind:$oldPath")?.let { debounceJobs["$kind:$newPath"] = it }
            }
        }
        val active = _state.value.activeTab?.path
        if (active != null && (active == from || active.startsWith("$from/"))) {
            val newActive = to + active.removePrefix(from)
            _state.value = _state.value.closeTab(active).openFile(
                path = newActive,
                languageId = LanguageDetector.detect(newActive),
            )
            publishActive(newActive)
            refreshFind(previousContent = null, caret = _buffers.value[newActive]?.caret ?: 0)
        }
    }

    fun deleteFile(path: String) {
        val project = _project.value ?: return
        viewModelScope.launch {
            when (val result = container.fileTreeRepository.delete(project, path)) {
                is SafeFileSystem.Result.Ok -> {
                    // A directory delete takes its children with it, so their state goes too.
                    val doomed = _buffers.value.keys.filter { it == path || it.startsWith("$path/") }
                    doomed.forEach { _state.value = _state.value.closeTab(it) }
                    // Forget the deleted paths themselves, not only the ones that had a buffer,
                    // or cached diagnostics outlive the file they describe.
                    val stale = diagnosticsByPath.keys + undoStacks.keys + doomed
                    stale.filter { it == path || it.startsWith("$path/") }.forEach(::forgetPath)
                    refreshFiles()
                    publishActiveState()
                    refreshFind(previousContent = null, caret = 0)
                    _message.value = "Deleted $path"
                }

                is SafeFileSystem.Result.Failure -> _message.value = result.reason
            }
        }
    }

    // ---------------------------------------------------------------- agent

    fun sendAgentPrompt(text: String) = container.openCodeSession.send(text)

    fun stopAgent() = container.openCodeSession.stop()

    fun answerPermission(requestId: String, decision: PermissionDecision) =
        container.openCodeSession.answerPermission(requestId, decision)

    /** User-initiated retry, so it is allowed to probe for a server. */
    fun reconnectAgent() = container.connectAgent(_project.value, explicit = true)

    override fun onCleared() {
        container.openCodeSession.disconnect()
        super.onCleared()
    }
}

/** True while the engine has a build in flight. */
fun BuildResult?.isRunningBuild(): Boolean =
    this != null && (status == BuildStatus.RUNNING || status == BuildStatus.PREPARING || status == BuildStatus.QUEUED)
