package dev.titanide.app.ui.build

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.titanide.app.di.TitanContainer
import dev.titanide.core.model.BuildKind
import dev.titanide.core.model.BuildRequest
import dev.titanide.core.model.BuildResult
import dev.titanide.core.model.BuildStatus
import dev.titanide.core.model.ExecutionTarget
import dev.titanide.core.model.Project
import dev.titanide.core.model.ToolchainReport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class BuildUiState(
    val project: Project? = null,
    val projects: List<Project> = emptyList(),
    val running: BuildResult? = null,
    val last: BuildResult? = null,
    val logLines: List<String> = emptyList(),
    val target: ExecutionTarget = ExecutionTarget.LOCAL,
    val toolchains: ToolchainReport? = null,
    val probing: Boolean = false,
    val message: String? = null,
)

class BuildViewModel(private val container: TitanContainer, initialProjectId: String) : ViewModel() {

    private val _state = MutableStateFlow(BuildUiState())
    val state: StateFlow<BuildUiState> = _state.asStateFlow()

    private var currentProjectId: String = initialProjectId

    init {
        viewModelScope.launch {
            container.projectRepository.observeProjects().collect { projects ->
                _state.value = _state.value.copy(projects = projects)
                if (_state.value.project == null) {
                    val project = projects.firstOrNull { it.id == currentProjectId }
                        ?: projects.maxByOrNull { it.lastOpenedAt }
                    if (project != null) {
                        currentProjectId = project.id
                        _state.value = _state.value.copy(project = project)
                    }
                }
            }
        }
        viewModelScope.launch {
            container.buildEngine.active.collect { active ->
                val running = active[currentProjectId]
                _state.value = _state.value.copy(running = running)
            }
        }
        viewModelScope.launch {
            container.buildEngine.lastResult.collect { result ->
                if (result == null) return@collect
                if (result.request.projectId != currentProjectId) return@collect
                _state.value = _state.value.copy(
                    last = result,
                    running = if (result.status == BuildStatus.RUNNING || result.status == BuildStatus.PREPARING) {
                        result
                    } else {
                        null
                    },
                    logLines = result.logLines,
                )
            }
        }
        viewModelScope.launch {
            container.buildEngine.logs.collect { logs ->
                val active = _state.value.running ?: return@collect
                logs[active.id]?.let { lines ->
                    _state.value = _state.value.copy(logLines = lines)
                }
            }
        }
        viewModelScope.launch {
            container.settingsState.collect { settings ->
                val target = settings?.build?.defaultTargetId?.let {
                    ExecutionTarget.entries.firstOrNull { e -> e.id == it }
                } ?: ExecutionTarget.LOCAL
                _state.value = _state.value.copy(target = target)
            }
        }
        probeToolchains()
    }

    fun selectProject(projectId: String) {
        viewModelScope.launch {
            val project = container.projectRepository.getProject(projectId) ?: return@launch
            currentProjectId = projectId
            _state.value = _state.value.copy(project = project, last = null, logLines = emptyList())
        }
    }

    fun setTarget(target: ExecutionTarget) {
        _state.value = _state.value.copy(target = target)
    }

    fun probeToolchains() {
        if (_state.value.probing) return
        _state.value = _state.value.copy(probing = true)
        viewModelScope.launch {
            val report = runCatching { container.toolchainProbe.probeAll() }.getOrNull()
            _state.value = _state.value.copy(toolchains = report, probing = false)
        }
    }

    /** Starts a build. The engine runs it off the main thread and streams the log back. */
    fun run(kind: BuildKind, task: String? = null) {
        val project = _state.value.project ?: return
        if (container.buildEngine.isRunning(project.id)) {
            _state.value = _state.value.copy(message = "A build is already running for this project")
            return
        }
        _state.value = _state.value.copy(logLines = emptyList(), last = null, message = null)
        container.buildEngine.start(
            BuildRequest(
                projectId = project.id,
                projectPath = project.rootPath,
                kind = kind,
                target = _state.value.target,
                task = task,
            ),
        )
    }

    fun stop() {
        val project = _state.value.project ?: return
        container.buildEngine.stop(project.id)
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null)
    }
}
