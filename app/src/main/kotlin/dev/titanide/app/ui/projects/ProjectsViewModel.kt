package dev.titanide.app.ui.projects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.titanide.app.di.TitanContainer
import dev.titanide.core.model.Project
import dev.titanide.core.model.ProjectCategory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProjectsState(
    val projects: List<Project> = emptyList(),
    val query: String = "",
    val categoryFilter: ProjectCategory? = null,
    val loading: Boolean = true,
    val deleting: String? = null,
    val error: String? = null,
) {
    val visible: List<Project>
        get() = projects
            .filter { categoryFilter == null || it.category == categoryFilter }
            .filter {
                query.isBlank() ||
                    it.name.contains(query, ignoreCase = true) ||
                    it.packageName.contains(query, ignoreCase = true)
            }
}

class ProjectsViewModel(private val container: TitanContainer) : ViewModel() {

    private val _state = MutableStateFlow(ProjectsState())
    val state: StateFlow<ProjectsState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            container.projectRepository.observeProjects().collect { projects ->
                _state.update {
                    it.copy(
                        projects = projects.sortedByDescending { it.lastOpenedAt },
                        loading = false,
                    )
                }
            }
        }
    }

    fun setQuery(query: String) {
        _state.update { it.copy(query = query) }
    }

    fun setFilter(category: ProjectCategory?) {
        _state.update { it.copy(categoryFilter = category) }
    }

    fun open(projectId: String) {
        viewModelScope.launch { container.projectRepository.touch(projectId) }
    }

    fun toggleFavorite(project: Project) {
        viewModelScope.launch {
            container.projectRepository.setFavorite(project.id, !project.favorite)
        }
    }

    /** Deletion requires the caller to have confirmed; the row is removed only on success. */
    fun delete(project: Project) {
        _state.update { it.copy(deleting = project.id, error = null) }
        viewModelScope.launch {
            val ok = container.projectRepository.deleteProject(project.id)
            _state.update {
                it.copy(
                    deleting = null,
                    error = if (ok) null else "Could not delete ${project.name}: files are still on disk",
                )
            }
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }
}
