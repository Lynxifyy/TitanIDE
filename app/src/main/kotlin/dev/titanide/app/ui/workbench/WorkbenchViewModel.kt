package dev.titanide.app.ui.workbench

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.titanide.app.di.TitanContainer
import dev.titanide.app.extensions.TitanCapability
import dev.titanide.core.data.files.SafeFileSystem
import dev.titanide.core.editor.codeguard.CodeGuard
import dev.titanide.core.engine.InstallOutcome
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

    private val _appearance = MutableStateFlow(EditorAppearance())
    val appearance: StateFlow<EditorAppearance> = _appearance.asStateFlow()

    private val _find = MutableStateFlow(FindState())
    val find: StateFlow<FindState> = _find.asStateFlow()

    /** Bumped whenever something must scroll the caret into view. */
    private val _revealRequests = MutableStateFlow(0L)
    val revealRequests: StateFlow<Long> = _revealRequests.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _installInProgress = MutableStateFlow(false)
    val installInProgress: StateFlow<Boolean> = _installInProgress.asStateFlow()

    private val _lastInstalledPackage = MutableStateFlow<String?>(null)
    val lastInstalledPackage: StateFlow<String?> = _lastInstalledPackage.asStateFlow()

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
                _appearance.value = EditorAppearance(
                    // Clamped so a corrupt persisted value cannot make the editor unreadable.
                    fontSizeSp = settings.editor.fontSize.coerceIn(8, 32),
                    lineHeightSp = settings.editor.lineHeight.coerceIn(12, 48),
                    wordWrap = settings.editor.wordWrap,
                    showLineNumbers = settings.editor.showLineNumbers,
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
        undoStack.clear()
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

        val editorSettings = container.currentSettings()?.editor
        if (editorSettings?.autoSave ?: true) {
            autoSaveJob?.cancel()
            autoSaveJob = viewModelScope.launch {
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
        _find.value = _find.value.copy(open = true, replaceMode = replaceMode)
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
        _find.value = _find.value.copy(query = query, matches = matches, activeIndex = start)
        active?.let { path -> matches.getOrNull(start)?.let { moveCaretTo(path, it.first) } }
    }

    fun setFindReplacement(text: String) {
        _find.value = _find.value.copy(replacement = text)
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
        _find.value = _find.value.copy(matches = matches, activeIndex = next)
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
        }
        val active = _state.value.activeTab?.path
        if (active != null && (active == from || active.startsWith("$from/"))) {
            val newActive = to + active.removePrefix(from)
            _state.value = _state.value.closeTab(active).openFile(
                path = newActive,
                languageId = LanguageDetector.detect(newActive),
            )
            publishActive(newActive)
        }
    }

    fun deleteFile(path: String) {
        val project = _project.value ?: return
        viewModelScope.launch {
            when (val result = container.fileTreeRepository.delete(project, path)) {
                is SafeFileSystem.Result.Ok -> {
                    // A directory delete takes its children with it, so their buffers go too.
                    val doomed = _buffers.value.keys.filter { it == path || it.startsWith("$path/") }
                    _buffers.value = _buffers.value - doomed.toSet()
                    doomed.forEach { _state.value = _state.value.closeTab(it) }
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
