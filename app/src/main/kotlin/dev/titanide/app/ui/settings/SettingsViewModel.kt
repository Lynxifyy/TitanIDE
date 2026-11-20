package dev.titanide.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.titanide.app.di.TitanContainer
import dev.titanide.core.data.security.CredentialKey
import dev.titanide.core.data.settings.TitanSettings
import dev.titanide.core.model.ToolchainInfo
import dev.titanide.core.git.auth.DeviceFlowAuth
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DeviceFlowUi(
    val userCode: String = "",
    val verificationUri: String = "",
    val waiting: Boolean = false,
    val error: String? = null,
)

data class SettingsUiState(
    val settings: TitanSettings? = null,
    val toolchains: List<ToolchainInfo> = emptyList(),
    val probing: Boolean = false,
    val gitHubLogin: String? = null,
    val gitHubSignedIn: Boolean = false,
    val deviceFlow: DeviceFlowUi? = null,
    val aiKeyStored: Boolean = false,
    val remoteTokenStored: Boolean = false,
    val message: String? = null,
    val storageUsed: Long = 0,
)

class SettingsViewModel(private val container: TitanContainer) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    private var pollJob: Job? = null

    init {
        viewModelScope.launch {
            container.settings.settings.collect { snapshot ->
                _state.value = _state.value.copy(
                    settings = snapshot,
                    aiKeyStored = container.credentials.contains(CredentialKey.AI_API_KEY),
                    remoteTokenStored = container.credentials.contains(CredentialKey.REMOTE_AGENT_TOKEN),
                )
            }
        }
        viewModelScope.launch {
            refreshGitHubIdentity()
            _state.value = _state.value.copy(storageUsed = container.storageUsedBytes())
        }
        probeToolchains()
    }

    fun probeToolchains() {
        if (_state.value.probing) return
        _state.value = _state.value.copy(probing = true)
        viewModelScope.launch {
            val report = runCatching { container.toolchainProbe.probeAll() }.getOrNull()
            _state.value = _state.value.copy(toolchains = report?.tools.orEmpty(), probing = false)
        }
    }

    suspend fun refreshGitHubIdentity() {
        if (!container.isGitHubSignedIn()) {
            _state.value = _state.value.copy(gitHubSignedIn = false, gitHubLogin = null)
            return
        }
        val login = container.gitHubApi.currentUser().getOrNull()
        _state.value = _state.value.copy(
            gitHubSignedIn = container.isGitHubSignedIn(),
            gitHubLogin = login,
        )
    }

    // ---- GitHub device flow -------------------------------------------------

    fun startGitHubSignIn() {
        if (_state.value.deviceFlow?.waiting == true) return
        _state.value = _state.value.copy(deviceFlow = DeviceFlowUi(waiting = true))
        viewModelScope.launch {
            val start = runCatching { container.deviceFlow.requestDeviceCode().getOrThrow() }
            start.onFailure { error ->
                _state.value = _state.value.copy(
                    deviceFlow = DeviceFlowUi(error = error.message ?: "Could not start GitHub sign-in"),
                )
            }
            val code = start.getOrNull() ?: return@launch
            _state.value = _state.value.copy(
                deviceFlow = DeviceFlowUi(
                    userCode = code.userCode,
                    verificationUri = code.verificationUri,
                    waiting = true,
                ),
            )

            pollJob?.cancel()
            pollJob = viewModelScope.launch {
                val deadline = System.currentTimeMillis() + code.expiresInSeconds * 1000L
                while (System.currentTimeMillis() < deadline) {
                    delay(code.pollIntervalSeconds * 1000L)
                    when (val outcome = runCatching { container.deviceFlow.pollOnce(code.deviceCode) }.getOrNull()) {
                        is DeviceFlowAuth.PollOutcome.Authorized -> {
                            container.credentials.put(CredentialKey.GITHUB_TOKEN, outcome.token)
                            container.setGitHubToken(outcome.token)
                            _state.value = _state.value.copy(
                                deviceFlow = null,
                                gitHubSignedIn = true,
                                message = "Signed in to GitHub",
                            )
                            refreshGitHubIdentity()
                            return@launch
                        }
                        is DeviceFlowAuth.PollOutcome.Pending -> Unit
                        is DeviceFlowAuth.PollOutcome.SlowDown -> delay(3000)
                        is DeviceFlowAuth.PollOutcome.Expired -> {
                            _state.value = _state.value.copy(
                                deviceFlow = DeviceFlowUi(error = "The code expired. Start again."),
                            )
                            return@launch
                        }
                        is DeviceFlowAuth.PollOutcome.Denied -> {
                            _state.value = _state.value.copy(
                                deviceFlow = DeviceFlowUi(error = "Authorisation was denied."),
                            )
                            return@launch
                        }
                        is DeviceFlowAuth.PollOutcome.Failed -> {
                            _state.value = _state.value.copy(
                                deviceFlow = DeviceFlowUi(error = outcome.reason),
                            )
                            return@launch
                        }
                        null -> Unit
                    }
                }
                _state.value = _state.value.copy(
                    deviceFlow = DeviceFlowUi(error = "Timed out waiting for authorisation."),
                )
            }
        }
    }

    fun cancelDeviceFlow() {
        pollJob?.cancel()
        _state.value = _state.value.copy(deviceFlow = null)
    }

    fun signOutGitHub() {
        viewModelScope.launch {
            container.credentials.remove(CredentialKey.GITHUB_TOKEN)
            container.setGitHubToken(null)
            _state.value = _state.value.copy(gitHubSignedIn = false, gitHubLogin = null, message = "Signed out of GitHub")
        }
    }

    // ---- Credentials --------------------------------------------------------

    fun saveAiKey(key: String) {
        if (key.isBlank()) return
        viewModelScope.launch {
            container.credentials.put(CredentialKey.AI_API_KEY, key)
            container.refreshCredentials()
            _state.value = _state.value.copy(
                aiKeyStored = true,
                message = "API key stored in the Android Keystore",
            )
        }
    }

    fun clearAiKey() {
        viewModelScope.launch {
            container.credentials.remove(CredentialKey.AI_API_KEY)
            container.refreshCredentials()
            _state.value = _state.value.copy(aiKeyStored = false, message = "API key removed")
        }
    }

    fun saveRemoteToken(token: String) {
        if (token.isBlank()) return
        viewModelScope.launch {
            container.credentials.put(CredentialKey.REMOTE_AGENT_TOKEN, token)
            container.refreshCredentials()
            _state.value = _state.value.copy(remoteTokenStored = true, message = "Remote agent token stored")
        }
    }

    fun clearRemoteToken() {
        viewModelScope.launch {
            container.credentials.remove(CredentialKey.REMOTE_AGENT_TOKEN)
            container.refreshCredentials()
            _state.value = _state.value.copy(remoteTokenStored = false, message = "Remote agent token removed")
        }
    }

    // ---- Settings writes ----------------------------------------------------

    fun setAiProvider(providerId: String) {
        viewModelScope.launch {
            container.settings.updateAi { it.copy(providerId = providerId) }
            container.refreshAiProvider()
        }
    }

    fun setAiEndpoint(endpoint: String) {
        viewModelScope.launch {
            container.settings.updateAi { it.copy(endpoint = endpoint.trim()) }
            container.refreshAiProvider()
        }
    }

    fun setAiModel(model: String) {
        viewModelScope.launch {
            container.settings.updateAi { it.copy(model = model.trim()) }
            container.refreshAiProvider()
        }
    }

    fun setAllowProjectContext(allowed: Boolean) {
        viewModelScope.launch { container.settings.updateAi { it.copy(allowProjectContext = allowed) } }
    }

    fun setBuildTarget(targetId: String) {
        viewModelScope.launch { container.settings.updateBuild { it.copy(defaultTargetId = targetId) } }
    }

    fun setRemoteAgentUrl(url: String) {
        viewModelScope.launch { container.settings.updateBuild { it.copy(remoteAgentUrl = url.trim()) } }
    }

    fun setGitHubBuildRepo(owner: String, repo: String) {
        viewModelScope.launch {
            container.settings.updateBuild { it.copy(githubOwner = owner.trim(), githubRepo = repo.trim()) }
        }
    }

    fun setGitHubWorkflow(workflow: String) {
        viewModelScope.launch { container.settings.updateBuild { it.copy(githubWorkflow = workflow.trim()) } }
    }

    fun setEditorFontSize(size: Int) {
        viewModelScope.launch { container.settings.updateEditor { it.copy(fontSize = size.coerceIn(9, 28)) } }
    }

    fun setCodeGuard(enabled: Boolean) {
        viewModelScope.launch { container.settings.updateEditor { it.copy(codeGuardEnabled = enabled) } }
    }

    fun setAutoSave(enabled: Boolean) {
        viewModelScope.launch { container.settings.updateEditor { it.copy(autoSave = enabled) } }
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null)
    }
}
