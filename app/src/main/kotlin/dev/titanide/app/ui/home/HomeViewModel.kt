package dev.titanide.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.titanide.app.di.TitanContainer
import dev.titanide.core.model.Project
import dev.titanide.core.model.ToolchainInfo
import dev.titanide.core.model.ToolchainReport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeState(
    val recentProjects: List<Project> = emptyList(),
    val buildSuccessRate: Double = 0.0,
    val totalBuilds: Int = 0,
    val storageUsed: Long = 0,
    val toolchains: List<ToolchainInfo> = emptyList(),
    val probingToolchains: Boolean = false,
    /** Why the last probe failed, or null. An empty [toolchains] alone also means "nothing installed". */
    val probeError: String? = null,
    val gitHubConnected: Boolean = false,
    val aiConfigured: Boolean = false,
    val loading: Boolean = true,
)

class HomeViewModel(
    private val container: TitanContainer,
    /** Seam for tests that need to control what probing returns. */
    private val probe: suspend () -> ToolchainReport = { container.toolchainProbe.probeAll() },
) : ViewModel() {

    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            container.projectRepository.observeProjects().collect { projects ->
                _state.update {
                    it.copy(
                        recentProjects = projects.sortedByDescending { it.lastOpenedAt }.take(6),
                        loading = false,
                    )
                }
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
                _state.update { it.copy(buildSuccessRate = rate, totalBuilds = records.size) }
            }
        }
        viewModelScope.launch {
            val storageUsed = container.storageUsedBytes()
            _state.update {
                it.copy(
                    storageUsed = storageUsed,
                    gitHubConnected = container.isGitHubSignedIn(),
                    aiConfigured = container.currentSettings()?.ai?.providerId?.let { it != "none" && it.isNotBlank() }
                        ?: false,
                )
            }
        }
        probeToolchains()
    }

    fun probeToolchains() {
        if (_state.value.probingToolchains) return
        _state.update { it.copy(probingToolchains = true) }
        viewModelScope.launch {
            try {
                val report = probe()
                _state.update {
                    it.copy(toolchains = report.tools, probingToolchains = false, probeError = null)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        toolchains = emptyList(),
                        probingToolchains = false,
                        probeError = "Toolchain probe failed: ${e.message ?: "unknown error"}",
                    )
                }
            }
        }
    }

    fun refreshStatus() {
        viewModelScope.launch {
            val storageUsed = container.storageUsedBytes()
            _state.update {
                it.copy(
                    storageUsed = storageUsed,
                    gitHubConnected = container.isGitHubSignedIn(),
                    aiConfigured = container.currentSettings()?.ai?.providerId?.let { it != "none" && it.isNotBlank() }
                        ?: false,
                )
            }
        }
    }
}
