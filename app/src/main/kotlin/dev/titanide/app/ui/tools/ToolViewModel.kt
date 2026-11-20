package dev.titanide.app.ui.tools

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.titanide.app.di.TitanContainer
import dev.titanide.core.data.files.ProjectScanner
import dev.titanide.core.model.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class AssetEntry(val path: String, val kind: String, val size: Long)

data class PreviewEntry(val path: String, val kind: String, val detail: String)

data class TableInfo(val name: String, val rows: Int)

data class ToolState(
    val projects: List<Project> = emptyList(),
    val project: Project? = null,
    val loading: Boolean = true,
    val assets: List<AssetEntry> = emptyList(),
    val assetPreview: Pair<String, String>? = null,
    val previews: List<PreviewEntry> = emptyList(),
    val tables: List<TableInfo> = emptyList(),
    val openTable: String? = null,
    val tableRows: List<String>? = null,
    val documents: List<String> = emptyList(),
    val openDocument: String? = null,
    val documentContent: String = "",
)

/**
 * Backs the tool screens with real project data: files are read from disk and the
 * database view queries the app's own Room instance.
 */
class ToolViewModel(private val container: TitanContainer) : ViewModel() {

    private val _state = MutableStateFlow(ToolState())
    val state: StateFlow<ToolState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            container.projectRepository.observeProjects().collect { projects ->
                val project = _state.value.project ?: projects.maxByOrNull { it.lastOpenedAt }
                _state.value = _state.value.copy(projects = projects, project = project)
                if (project != null && _state.value.assets.isEmpty() && _state.value.documents.isEmpty()) {
                    scan(project)
                }
            }
        }
        viewModelScope.launch { loadTables() }
    }

    fun selectProject(projectId: String) {
        viewModelScope.launch {
            val project = container.projectRepository.getProject(projectId) ?: return@launch
            _state.value = _state.value.copy(
                project = project,
                assets = emptyList(),
                previews = emptyList(),
                documents = emptyList(),
                assetPreview = null,
                documentContent = "",
                openDocument = null,
            )
            scan(project)
        }
    }

    private fun scan(project: Project) {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            val result = withContext(Dispatchers.IO) {
                ProjectScanner.scan(File(project.rootPath))
            }
            _state.value = _state.value.copy(
                assets = result.assets.map { AssetEntry(it.path, it.kind, it.size) },
                previews = result.previews.map { PreviewEntry(it.path, it.kind, it.detail) },
                documents = result.documents,
                loading = false,
            )
            result.documents.firstOrNull()?.let(::openDocument)
        }
    }

    fun previewAsset(path: String) {
        val project = _state.value.project ?: return
        viewModelScope.launch {
            val root = File(project.rootPath)
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    val file = File(root, path)
                    if (file.length() > 200_000 || !ProjectScanner.isTextual(file.extension)) {
                        "Binary or large file (${file.length()} bytes). Showing metadata only."
                    } else {
                        file.readText()
                    }
                }.getOrElse { it.message ?: "Could not read file" }
            }
            _state.value = _state.value.copy(assetPreview = path to text)
        }
    }

    fun openDocument(path: String) {
        val project = _state.value.project ?: return
        viewModelScope.launch {
            val content = withContext(Dispatchers.IO) {
                runCatching { File(File(project.rootPath), path).readText().take(60_000) }
                    .getOrElse { "Could not read $path: ${it.message}" }
            }
            _state.value = _state.value.copy(openDocument = path, documentContent = content)
        }
    }

    private suspend fun loadTables() {
        val dao = container.database
        val tables = withContext(Dispatchers.IO) {
            listOf(
                TableInfo("projects", dao.projectDao().count()),
                TableInfo("builds", dao.buildDao().totalCount()),
                TableInfo("open_tabs", dao.tabDao().count()),
                TableInfo("extensions", dao.extensionDao().count()),
                TableInfo("http_requests", dao.httpRequestDao().count()),
            )
        }
        _state.value = _state.value.copy(tables = tables)
    }

    fun openTable(name: String) {
        viewModelScope.launch {
            val rows = withContext(Dispatchers.IO) {
                when (name) {
                    "projects" -> container.database.projectDao().all().map {
                        "${it.name} | ${it.categoryId} | ${it.buildSystemId} | ${it.lastOpenedAt}"
                    }
                    "builds" -> container.database.buildDao().all().map {
                        "${it.kind} | ${it.status} | ${it.testsPassed}/${it.testsTotal} | ${it.startedAt}"
                    }
                    else -> emptyList()
                }
            }
            _state.value = _state.value.copy(
                openTable = name,
                tableRows = rows.ifEmpty { listOf("No rows in $name.") },
            )
        }
    }
}
