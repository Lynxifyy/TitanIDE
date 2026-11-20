package dev.titanide.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.titanide.app.di.TitanContainer
import dev.titanide.core.model.Project
import dev.titanide.core.model.ToolchainInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class HomeState(
    val recentProjects: List<Project> = emptyList(),
    val buildSuccessRate: Double = 0.0,
    val totalBuilds: Int = 0,
    val storageUsed: Long = 0,
    val toolchains: List<ToolchainInfo> = emptyList(),
    val probingToolchains: Boolean = false,
    val gitHubConnected: Boolean = false,
    val aiConfigured: Boolean = false,
    val loading: Boolean = true,
)

class HomeViewModel(private val container: TitanContainer) : ViewModel() {

    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            container.projectRepository.observeProjects().collect { projects ->
                _state.value = _state.value.copy(
                    recentProjects = projects.sortedByDescending { it.lastOpenedAt }.take(6),
                    loading = false,
                )
            }
        }
        viewModelScope.launch {
            container.buildHistory.observeRecent().collect { records ->
                val finished = records.filter { it.status == "SUCCEEDED" || it.status == "FAILED" }
                val rate = if (finished.isEmpty()) {
                    0.0
                } else {
                    finished.count { it.status == "SUCCEEDED" }.toDouble() / finished.size
                }
                _state.value = _state.value.copy(buildSuccessRate = rate, totalBuilds = records.size)
            }
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(
                storageUsed = container.storageUsedBytes(),
                gitHubConnected = container.isGitHubSignedIn(),
                aiConfigured = container.currentSettings()?.ai?.providerId?.let { it != "none" && it.isNotBlank() }
                    ?: false,
            )
        }
        probeToolchains()
    }

    fun probeToolchains() {
        if (_state.value.probingToolchains) return
        _state.value = _state.value.copy(probingToolchains = true)
        viewModelScope.launch {
            val report = runCatching { container.toolchainProbe.probeAll() }.getOrNull()
            _state.value = _state.value.copy(
                toolchains = report?.tools.orEmpty(),
                probingToolchains = false,
            )
        }
    }

    fun refreshStatus() {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                storageUsed = container.storageUsedBytes(),
                gitHubConnected = container.isGitHubSignedIn(),
                aiConfigured = container.currentSettings()?.ai?.providerId?.let { it != "none" && it.isNotBlank() }
                    ?: false,
            )
        }
    }
}
