package dev.titanide.app.ui.projects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.titanide.app.di.TitanContainer
import dev.titanide.core.data.db.BuildRecordEntity
import dev.titanide.core.model.Project
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class ProjectDetailState(
    val project: Project? = null,
    val fileCount: Int = 0,
    val sizeBytes: Long = 0,
    val buildHistory: List<BuildRecordEntity> = emptyList(),
    val loading: Boolean = true,
)

class ProjectDetailViewModel(
    private val container: TitanContainer,
    private val projectId: String,
) : ViewModel() {

    private val _state = MutableStateFlow(ProjectDetailState())
    val state: StateFlow<ProjectDetailState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val project = container.projectRepository.getProject(projectId)
            val fileCount = container.projectRepository.projectFileCount(projectId)
            val size = project?.let { p ->
                val dir = File(p.rootPath)
                if (dir.exists()) dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0L
            } ?: 0L
            _state.update {
                it.copy(
                    project = project,
                    fileCount = fileCount,
                    sizeBytes = size,
                    loading = false,
                )
            }
        }
        viewModelScope.launch {
            container.buildHistory.observeForProject(projectId).collect { records ->
                _state.update { it.copy(buildHistory = records) }
            }
        }
    }

    fun openEditor() {
        viewModelScope.launch { container.projectRepository.touch(projectId) }
    }

    fun delete(onDeleted: () -> Unit) {
        viewModelScope.launch {
            container.projectRepository.deleteProject(projectId)
            onDeleted()
        }
    }
}
