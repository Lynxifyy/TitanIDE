package dev.titanide.app.ui.editor

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
import dev.titanide.core.model.Diagnostic
import dev.titanide.core.model.Language
import dev.titanide.core.model.Project
import dev.titanide.core.model.ProjectFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class EditorTab(
    val path: String,
    val name: String,
    val language: Language,
    val content: String,
    val dirty: Boolean = false,
    val caret: Int = 0,
)

data class SearchHit(val path: String, val line: Int, val text: String)

data class EditorState(
    val project: Project? = null,
    val projects: List<Project> = emptyList(),
    val tabs: List<EditorTab> = emptyList(),
    val activeIndex: Int = 0,
    val tree: List<ProjectFile> = emptyList(),
    val expandedDirs: Set<String> = emptySet(),
    val diagnostics: List<Diagnostic> = emptyList(),
    val analyzing: Boolean = false,
    val showExplorer: Boolean = true,
    val showDiagnostics: Boolean = false,
    val searchQuery: String = "",
    val searchResults: List<SearchHit> = emptyList(),
    val searching: Boolean = false,
    val message: String? = null,
    val autoSave: Boolean = true,
) {
    val active: EditorTab? get() = tabs.getOrNull(activeIndex)
    val errorCount: Int get() = diagnostics.count { it.severity == dev.titanide.core.model.Severity.ERROR }
    val warningCount: Int get() = diagnostics.count { it.severity == dev.titanide.core.model.Severity.WARNING }

    /** Files currently visible in the tree, honouring collapsed directories. */
    val visibleTree: List<ProjectFile>
        get() = tree.filter { file ->
            val parent = file.path.substringBeforeLast('/', "")
            if (parent.isEmpty()) {
                true
            } else {
                var prefix = parent
                var visible = true
                while (prefix.isNotEmpty()) {
                    if (prefix !in expandedDirs) {
                        visible = false
                        break
                    }
                    prefix = prefix.substringBeforeLast('/', "")
                }
                visible
            }
        }
}

class EditorViewModel(private val container: TitanContainer) : ViewModel() {

    private val _state = MutableStateFlow(EditorState())
    val state: StateFlow<EditorState> = _state.asStateFlow()

    private val codeGuard = CodeGuard()
    private var codeGuardEnabled = true
    private val searchEngine = SearchEngine()
    private val undoStack = UndoStack()
    private var analysisJob: Job? = null
    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            container.settings.settings.collect { settings ->
                codeGuardEnabled = settings.editor.codeGuardEnabled
                if (!codeGuardEnabled) {
                    _state.value = _state.value.copy(diagnostics = emptyList())
                }
            }
        }
        viewModelScope.launch {
            container.projectRepository.observeProjects().collect { projects ->
                val current = _state.value
                val project = current.project
                    ?: projects.maxByOrNull { it.lastOpenedAt }
                _state.value = current.copy(projects = projects, project = project)
                if (project != null && current.tree.isEmpty()) {
                    loadTree(project)
                }
            }
        }
    }

    fun selectProject(projectId: String) {
        viewModelScope.launch {
            val project = container.projectRepository.getProject(projectId) ?: return@launch
            container.projectRepository.touch(projectId)
            _state.value = _state.value.copy(project = project, tabs = emptyList(), activeIndex = 0, tree = emptyList())
            undoStack.clear()
            loadTree(project)
        }
    }

    private fun loadTree(project: Project) {
        viewModelScope.launch {
            val tree = container.fileTreeRepository.list(project)
            val topLevelDirs = tree.filter { it.kind == dev.titanide.core.model.FileKind.DIRECTORY }
                .filter { !it.path.contains('/') }
                .map { it.path }
                .toSet()
            _state.value = _state.value.copy(
                tree = tree,
                expandedDirs = _state.value.expandedDirs + topLevelDirs,
            )
        }
    }

    fun refreshTree() {
        _state.value.project?.let(::loadTree)
    }

    fun toggleDir(path: String) {
        val expanded = _state.value.expandedDirs
        _state.value = _state.value.copy(
            expandedDirs = if (path in expanded) expanded - path else expanded + path,
        )
    }

    fun openFile(path: String) {
        val project = _state.value.project ?: return
        val existing = _state.value.tabs.indexOfFirst { it.path == path }
        if (existing >= 0) {
            _state.value = _state.value.copy(activeIndex = existing)
            return
        }
        viewModelScope.launch {
            when (val result = container.fileTreeRepository.read(project, path)) {
                is SafeFileSystem.Result.Ok -> {
                    val language = Language.fromFileName(path)
                    val tab = EditorTab(
                        path = path,
                        name = path.substringAfterLast('/'),
                        language = language,
                        content = result.value,
                    )
                    _state.value = _state.value.copy(
                        tabs = _state.value.tabs + tab,
                        activeIndex = _state.value.tabs.size,
                    )
                    publishActive(tab)
                    analyze(result.value, language, path)
                }
                is SafeFileSystem.Result.Failure ->
                    _state.value = _state.value.copy(message = result.reason)
            }
        }
    }

    fun closeTab(index: Int) {
        val tabs = _state.value.tabs.toMutableList()
        if (index !in tabs.indices) return
        tabs.removeAt(index)
        val newIndex = _state.value.activeIndex.coerceAtMost((tabs.size - 1).coerceAtLeast(0))
        _state.value = _state.value.copy(tabs = tabs, activeIndex = newIndex)
        val next = tabs.getOrNull(newIndex)
        if (next != null) publishActive(next) else container.activeFile.clear()
    }

    fun selectTab(index: Int) {
        if (index in _state.value.tabs.indices) {
            _state.value = _state.value.copy(activeIndex = index)
            _state.value.active?.let {
                publishActive(it)
                analyze(it.content, it.language, it.path)
            }
        }
    }

    /** Keeps the shared active-file session in step with the editor buffer. */
    private fun publishActive(tab: EditorTab) {
        container.activeFile.update(
            projectId = _state.value.project?.id,
            path = tab.path,
            content = tab.content,
            language = tab.language.id,
        )
    }

    /** Called on every keystroke: updates the buffer, marks dirty, schedules analysis. */
    fun edit(newContent: String, caret: Int = -1) {
        val state = _state.value
        val active = state.active ?: return
        TextDiff.singleEdit(active.content, newContent)?.let(undoStack::record)
        val tabs = state.tabs.toMutableList()
        tabs[state.activeIndex] = active.copy(
            content = newContent,
            dirty = true,
            caret = if (caret >= 0) caret else active.caret,
        )
        publishActive(tabs[state.activeIndex])
        _state.value = state.copy(tabs = tabs)

        analysisJob?.cancel()
        analysisJob = viewModelScope.launch {
            delay(400)
            analyze(newContent, active.language, active.path)
        }

        if (state.autoSave) {
            viewModelScope.launch {
                delay(1200)
                saveActive(silent = true)
            }
        }
    }

    fun setCaret(offset: Int) {
        val state = _state.value
        val active = state.active ?: return
        val tabs = state.tabs.toMutableList()
        tabs[state.activeIndex] = active.copy(caret = offset)
        _state.value = state.copy(tabs = tabs)
    }

    fun saveActive(silent: Boolean = false) {
        val state = _state.value
        val project = state.project ?: return
        val active = state.active ?: return
        viewModelScope.launch {
            when (val result = container.fileTreeRepository.write(project, active.path, active.content)) {
                is SafeFileSystem.Result.Ok -> {
                    val tabs = _state.value.tabs.toMutableList()
                    if (_state.value.activeIndex in tabs.indices) {
                        tabs[_state.value.activeIndex] = tabs[_state.value.activeIndex].copy(dirty = false)
                    }
                    _state.value = _state.value.copy(
                        tabs = tabs,
                        message = if (silent) null else "Saved ${active.name}",
                    )
                }
                is SafeFileSystem.Result.Failure ->
                    _state.value = _state.value.copy(message = "Save failed: ${result.reason}")
            }
        }
    }

    fun createFile(path: String) {
        val project = _state.value.project ?: return
        viewModelScope.launch {
            when (val result = container.fileTreeRepository.write(project, path, "")) {
                is SafeFileSystem.Result.Ok -> {
                    loadTree(project)
                    openFile(path)
                }
                is SafeFileSystem.Result.Failure ->
                    _state.value = _state.value.copy(message = result.reason)
            }
        }
    }

    fun deleteFile(path: String) {
        val project = _state.value.project ?: return
        viewModelScope.launch {
            when (val result = container.fileTreeRepository.delete(project, path)) {
                is SafeFileSystem.Result.Ok -> {
                    val tabs = _state.value.tabs.filterNot { it.path == path }
                    _state.value = _state.value.copy(
                        tabs = tabs,
                        activeIndex = _state.value.activeIndex.coerceAtMost((tabs.size - 1).coerceAtLeast(0)),
                    )
                    loadTree(project)
                }
                is SafeFileSystem.Result.Failure ->
                    _state.value = _state.value.copy(message = result.reason)
            }
        }
    }

    fun rename(oldPath: String, newPath: String) {
        val project = _state.value.project ?: return
        viewModelScope.launch {
            when (val result = container.fileTreeRepository.move(project, oldPath, newPath)) {
                is SafeFileSystem.Result.Ok -> {
                    val tabs = _state.value.tabs.map {
                        if (it.path == oldPath) it.copy(path = newPath, name = newPath.substringAfterLast('/')) else it
                    }
                    _state.value = _state.value.copy(tabs = tabs)
                    loadTree(project)
                }
                is SafeFileSystem.Result.Failure ->
                    _state.value = _state.value.copy(message = result.reason)
            }
        }
    }

    fun toggleComment() {
        val state = _state.value
        val active = state.active ?: return
        val document = TextDocument.of(active.content)
        val engine = EditorEngine(active.language)
        val caret = active.caret.coerceIn(0, active.content.length)
        val lineStart = document.lineStart(document.lineOf(caret))
        val lineEnd = document.lineEnd(document.lineOf(caret))
        val transform = engine.toggleLineComment(document, lineStart, lineEnd) ?: return
        applyTransform(transform)
    }

    fun indent(outdent: Boolean) {
        val state = _state.value
        val active = state.active ?: return
        val document = TextDocument.of(active.content)
        val engine = EditorEngine(active.language)
        val caret = active.caret.coerceIn(0, active.content.length)
        val transform = engine.indentSelection(
            document,
            document.lineStart(document.lineOf(caret)),
            document.lineEnd(document.lineOf(caret)),
            outdent,
        )
        applyTransform(transform)
    }

    fun formatDocument() {
        val state = _state.value
        val active = state.active ?: return
        val engine = EditorEngine(active.language)
        val transform = engine.formatDocument(TextDocument.of(active.content)) ?: return
        applyTransform(transform)
        _state.value = _state.value.copy(message = "Formatted ${active.name}")
    }

    private fun applyTransform(transform: EditorEngine.Transform) {
        val state = _state.value
        val active = state.active ?: return
        transform.edit?.let { undoStack.record(it) }
        val newText = transform.document.text
        val tabs = state.tabs.toMutableList()
        tabs[state.activeIndex] = active.copy(
            content = newText,
            dirty = true,
            caret = transform.caret,
        )
        _state.value = state.copy(tabs = tabs)
        publishActive(tabs[state.activeIndex])
        analyze(newText, active.language, active.path)
    }

    fun undo() {
        val active = _state.value.active ?: return
        val restored = undoStack.undo(TextDocument.of(active.content)) ?: return
        val tabs = _state.value.tabs.toMutableList()
        tabs[_state.value.activeIndex] = active.copy(content = restored.text, dirty = true)
        _state.value = _state.value.copy(tabs = tabs)
        analyze(restored.text, active.language, active.path)
    }

    fun redo() {
        val active = _state.value.active ?: return
        val restored = undoStack.redo(TextDocument.of(active.content)) ?: return
        val tabs = _state.value.tabs.toMutableList()
        tabs[_state.value.activeIndex] = active.copy(content = restored.text, dirty = true)
        _state.value = _state.value.copy(tabs = tabs)
        analyze(restored.text, active.language, active.path)
    }

    private fun analyze(content: String, language: Language, path: String) {
        if (!codeGuardEnabled) {
            _state.value = _state.value.copy(diagnostics = emptyList(), analyzing = false)
            return
        }
        _state.value = _state.value.copy(analyzing = true)
        viewModelScope.launch {
            val diagnostics = withContext(Dispatchers.Default) {
                codeGuard.analyze(content, language, path)
            }
            _state.value = _state.value.copy(diagnostics = diagnostics, analyzing = false)
        }
    }

    fun search(query: String) {
        _state.value = _state.value.copy(searchQuery = query)
        searchJob?.cancel()
        if (query.isBlank()) {
            _state.value = _state.value.copy(searchResults = emptyList(), searching = false)
            return
        }
        val project = _state.value.project ?: return
        searchJob = viewModelScope.launch {
            _state.value = _state.value.copy(searching = true)
            val corpus = container.fileTreeRepository.loadTextFiles(project, limit = 300)
            val hits = ArrayList<SearchHit>()
            corpus.forEach { (path, content) ->
                val document = TextDocument.of(content)
                searchEngine.search(document, query, SearchOptions(caseSensitive = false)).take(8).forEach { match ->
                    hits += SearchHit(path, match.line + 1, match.lineText.trim().take(160))
                }
            }
            _state.value = _state.value.copy(searchResults = hits.take(200), searching = false)
        }
    }

    fun toggleExplorer() {
        _state.value = _state.value.copy(showExplorer = !_state.value.showExplorer)
    }

    fun toggleDiagnostics() {
        _state.value = _state.value.copy(showDiagnostics = !_state.value.showDiagnostics)
    }

    fun toggleAutoSave() {
        _state.value = _state.value.copy(autoSave = !_state.value.autoSave)
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null)
    }

    /** Applies an AI-proposed replacement to the active buffer. */
    fun applyAiEdit(newContent: String) {
        val active = _state.value.active ?: return
        val tabs = _state.value.tabs.toMutableList()
        tabs[_state.value.activeIndex] = active.copy(content = newContent, dirty = true)
        _state.value = _state.value.copy(tabs = tabs, message = "AI edit applied to ${active.name}")
        publishActive(tabs[_state.value.activeIndex])
        analyze(newContent, active.language, active.path)
    }
}
