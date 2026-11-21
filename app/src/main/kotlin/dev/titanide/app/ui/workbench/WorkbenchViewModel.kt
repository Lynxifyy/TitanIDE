package dev.titanide.app.ui.workbench

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.titanide.app.di.TitanContainer
import dev.titanide.core.data.files.SafeFileSystem
import dev.titanide.core.editor.codeguard.CodeGuard
import dev.titanide.core.editor.EditorEngine
import dev.titanide.core.editor.SearchEngine
import dev.titanide.core.editor.SearchOptions
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

/**
 * Drives the workbench: real project files through [SafeFileSystem], real diagnostics
 * through CodeGuard, real builds through the shared BuildEngine, and the real OpenCode
 * session. The Compose layer only renders this state and forwards intents.
 */
class WorkbenchViewModel(private val container: TitanContainer) : ViewModel() {

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
    val diagnostics: StateFlow<List<Diagnostic>> = _diagnostics.asStateFlow()

    private val _expanded = MutableStateFlow<Set<String>>(emptySet())
    val expanded: StateFlow<Set<String>> = _expanded.asStateFlow()

    private val _searchHits = MutableStateFlow<List<SearchHit>>(emptyList())
    val searchHits: StateFlow<List<SearchHit>> = _searchHits.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val build: StateFlow<BuildResult?> get() = container.buildEngine.lastResult
    val buildLogs: StateFlow<Map<String, List<String>>> get() = container.buildEngine.logs
    val agent: StateFlow<OpenCodeState> get() = container.openCodeSession.state

    private val undoStack = UndoStack()
    private val searchEngine = SearchEngine()
    private var analysisJob: Job? = null
    private var searchJob: Job? = null
    private var autoSaveJob: Job? = null

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
        undoStack.clear()
        _diagnostics.value = emptyList()
        _state.value = _state.value.copy(
            projectName = project.name,
            projectPath = project.rootPath,
            groups = listOf(EditorGroup(0)),
            activeGroupIndex = 0,
        )
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

    fun closeTab(tabId: String) {
        _state.value = _state.value.closeTab(tabId)
        _buffers.value = _buffers.value - tabId
        _state.value.activeTab?.path?.let(::publishActive)
    }

    fun activateTab(tabId: String) {
        _state.value = _state.value.activateTab(tabId)
        publishActive(tabId)
    }

    private fun publishActive(path: String) {
        val buffer = _buffers.value[path] ?: return
        container.activeFile.update(
            projectId = _project.value?.id,
            path = path,
            content = buffer.content,
            language = buffer.language.id,
        )
        val errors = _diagnostics.value.count { it.severity == Severity.ERROR }
        val warnings = _diagnostics.value.count { it.severity == Severity.WARNING }
        _state.value = _state.value.copy(
            status = _state.value.status.copy(
                languageId = LanguageDetector.displayName(LanguageDetector.detect(path)),
                cursorLine = buffer.caretLine,
                cursorColumn = buffer.caretColumn,
                errorCount = errors,
                warningCount = warnings,
            ),
        )
    }

    // -------------------------------------------------------------- editing

    fun edit(path: String, content: String, caret: Int = -1) {
        val buffer = _buffers.value[path] ?: return
        // Caret movement arrives through the same channel as text changes.
        if (content == buffer.content) {
            if (caret >= 0) setCaret(path, caret)
            return
        }
        TextDiff.singleEdit(buffer.content, content)?.let(undoStack::record)
        val updated = buffer.copy(content = content, caret = if (caret >= 0) caret else buffer.caret)
        _buffers.value = _buffers.value + (path to updated)
        _state.value = _state.value.setDirty(path, updated.dirty)
        publishActive(path)

        analysisJob?.cancel()
        analysisJob = viewModelScope.launch {
            delay(350)
            analyze(updated)
        }

        if (container.currentSettings()?.editor?.autoSave ?: true) {
            autoSaveJob?.cancel()
            autoSaveJob = viewModelScope.launch {
                delay(1400)
                save(path, silent = true)
            }
        }
    }

    fun setCaret(path: String, caret: Int) {
        val buffer = _buffers.value[path] ?: return
        val updated = buffer.copy(caret = caret)
        _buffers.value = _buffers.value + (path to updated)
        _state.value = _state.value.copy(
            status = _state.value.status.copy(
                cursorLine = updated.caretLine,
                cursorColumn = updated.caretColumn,
            ),
        )
    }

    fun save(path: String, silent: Boolean = false) {
        val project = _project.value ?: return
        val buffer = _buffers.value[path] ?: return
        if (!buffer.dirty) return
        viewModelScope.launch {
            when (val result = container.fileTreeRepository.write(project, path, buffer.content)) {
                is SafeFileSystem.Result.Ok -> {
                    _buffers.value = _buffers.value + (path to buffer.copy(savedContent = buffer.content))
                    _state.value = _state.value.setDirty(path, false)
                    if (!silent) _message.value = "Saved ${path.substringAfterLast('/')}"
                }

                is SafeFileSystem.Result.Failure -> _message.value = "Save failed: ${result.reason}"
            }
        }
    }

    fun saveActive() {
        _state.value.activeTab?.path?.let { save(it) }
    }

    fun undo() {
        val path = _state.value.activeTab?.path ?: return
        val buffer = _buffers.value[path] ?: return
        val previous = undoStack.undo(TextDocument.of(buffer.content)) ?: return
        applyText(path, previous.text)
    }

    fun redo() {
        val path = _state.value.activeTab?.path ?: return
        val buffer = _buffers.value[path] ?: return
        val next = undoStack.redo(TextDocument.of(buffer.content)) ?: return
        applyText(path, next.text)
    }

    fun toggleComment() {
        val path = _state.value.activeTab?.path ?: return
        val buffer = _buffers.value[path] ?: return
        val document = TextDocument.of(buffer.content)
        val caret = buffer.caret.coerceIn(0, buffer.content.length)
        val line = document.lineOf(caret)
        val transform = EditorEngine(buffer.language)
            .toggleLineComment(document, document.lineStart(line), document.lineEnd(line)) ?: return
        applyTransform(path, transform)
    }

    fun formatDocument() {
        val path = _state.value.activeTab?.path ?: return
        val buffer = _buffers.value[path] ?: return
        val transform = EditorEngine(buffer.language).formatDocument(TextDocument.of(buffer.content)) ?: return
        applyTransform(path, transform)
        _message.value = "Formatted ${path.substringAfterLast('/')}"
    }

    fun indent(outdent: Boolean) {
        val path = _state.value.activeTab?.path ?: return
        val buffer = _buffers.value[path] ?: return
        val document = TextDocument.of(buffer.content)
        val caret = buffer.caret.coerceIn(0, buffer.content.length)
        val line = document.lineOf(caret)
        val transform = EditorEngine(buffer.language)
            .indentSelection(document, document.lineStart(line), document.lineEnd(line), outdent)
        applyTransform(path, transform)
    }

    private fun applyTransform(path: String, transform: EditorEngine.Transform) {
        transform.edit?.let(undoStack::record)
        val buffer = _buffers.value[path] ?: return
        val updated = buffer.copy(content = transform.document.text, caret = transform.caret)
        _buffers.value = _buffers.value + (path to updated)
        _state.value = _state.value.setDirty(path, updated.dirty)
        publishActive(path)
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
            _diagnostics.value = emptyList()
            return
        }
        // Analysis tokenizes the whole buffer; running it inline would stall typing.
        viewModelScope.launch {
            val found = withContext(Dispatchers.Default) {
                CodeGuard().analyze(buffer.content, buffer.language, buffer.path)
            }
            applyDiagnostics(buffer, found)
        }
    }

    private fun applyDiagnostics(buffer: BufferState, found: List<Diagnostic>) {
        if (_state.value.activeTab?.path == buffer.path) {
            _diagnostics.value = found
            _state.value = _state.value.copy(
                status = _state.value.status.copy(
                    errorCount = found.count { it.severity == Severity.ERROR },
                    warningCount = found.count { it.severity == Severity.WARNING },
                ),
            )
        }
    }

    // --------------------------------------------------------------- search

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

    // ---------------------------------------------------------------- build

    fun runBuild(kind: BuildKind) {
        val project = _project.value ?: return
        if (container.buildEngine.isRunning(project.id)) return
        val target = container.currentSettings()?.build?.defaultTargetId
            ?.let { id -> ExecutionTarget.entries.firstOrNull { it.id == id } }
            ?: ExecutionTarget.LOCAL
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
        _state.value = _state.value.copy(sidebarVisible = false)
    }

    fun toggleAgentPanel() {
        _state.value = _state.value.toggleAgentPanel()
        if (!agent.value.isConnected) container.connectAgent(_project.value)
    }

    fun onSizeChanged(widthDp: Int, heightDp: Int) {
        _state.value = _state.value.withLayout(widthDp, heightDp)
    }

    fun toggleAndroidView() {
        _state.value = _state.value.copy(androidViewMode = !_state.value.androidViewMode)
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

    fun deleteFile(path: String) {
        val project = _project.value ?: return
        viewModelScope.launch {
            when (val result = container.fileTreeRepository.delete(project, path)) {
                is SafeFileSystem.Result.Ok -> {
                    _buffers.value = _buffers.value - path
                    _state.value = _state.value.closeTab(path)
                    refreshFiles()
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
