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

data class ProjectDetailState(
    val project: Project? = null,
    val fileCount: Int = 0,
    val sizeBytes: Long = 0,
    val buildHistory: List<BuildRecordEntity> = emptyList(),
    val loading: Boolean = true,
    val deleting: Boolean = false,
    val error: String? = null,
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
            val size = project?.let { container.projectSizeBytes(it) } ?: 0L
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

    /**
     * Deletes the project and leaves the screen only when the files are actually gone. The
     * repository keeps the row when the directory cannot be removed, so navigating back
     * regardless would return the user to a list the project is still in.
     */
    fun delete(onDeleted: () -> Unit) {
        if (_state.value.deleting) return
        _state.update { it.copy(deleting = true, error = null) }
        viewModelScope.launch {
            val deleted = container.projectDeleter(projectId)
            if (deleted) {
                onDeleted()
                return@launch
            }
            val name = _state.value.project?.name ?: "the project"
            _state.update {
                it.copy(
                    deleting = false,
                    error = "Could not delete $name: files are still on disk",
                )
            }
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }
}
