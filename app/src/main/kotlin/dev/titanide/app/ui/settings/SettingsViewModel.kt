package dev.titanide.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.titanide.app.di.TitanContainer
import dev.titanide.core.data.security.CredentialKey
import dev.titanide.core.git.auth.GitHubAuthState
import dev.titanide.core.data.settings.SettingsRepository
import dev.titanide.core.data.settings.TitanSettings
import dev.titanide.core.model.ToolchainInfo
import dev.titanide.core.model.ToolchainReport
import dev.titanide.core.git.auth.DeviceFlowAuth
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
    /** Set when the probe itself failed, as opposed to finding nothing. */
    val probeError: String? = null,
    val gitHubLogin: String? = null,
    val gitHubSignedIn: Boolean = false,
    /** False when this APK was built without TITAN's OAuth client id. */
    val gitHubOAuthAvailable: Boolean = false,
    val deviceFlow: DeviceFlowUi? = null,
    val aiKeyStored: Boolean = false,
    val remoteTokenStored: Boolean = false,
    val openCodeTokenStored: Boolean = false,
    val message: String? = null,
    val storageUsed: Long = 0,
)

class SettingsViewModel(
    private val container: TitanContainer,
    /** Seam for tests that need to control what probing returns. */
    private val probe: suspend () -> ToolchainReport = { container.toolchainProbe.probeAll() },
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    private var pollJob: Job? = null

    init {
        viewModelScope.launch {
            container.settings.settings.collect { snapshot ->
                _state.update {
                    it.copy(
                        settings = snapshot,
                        aiKeyStored = container.credentials.contains(CredentialKey.AI_API_KEY),
                        remoteTokenStored = container.credentials.contains(CredentialKey.REMOTE_AGENT_TOKEN),
                        openCodeTokenStored = container.credentials.contains(CredentialKey.OPENCODE_TOKEN),
                    )
                }
            }
        }
        // Whether sign-in is even possible is fixed at build time, so read it once.
        _state.update {
            it.copy(
                gitHubOAuthAvailable = container.deviceFlow.isConfigured(),
            )
        }
        viewModelScope.launch {
            refreshGitHubIdentity()
            val storageUsed = container.storageUsedBytes()
            _state.update { it.copy(storageUsed = storageUsed) }
        }
        probeToolchains()
    }

    fun probeToolchains() {
        if (_state.value.probing) return
        _state.update { it.copy(probing = true) }
        viewModelScope.launch {
            try {
                val report = probe()
                _state.update { it.copy(toolchains = report.tools, probing = false, probeError = null) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // An empty list is also what a device with nothing installed looks like, so the
                // reason has to be carried separately or the panel silently lies.
                _state.update {
                    it.copy(
                        probing = false,
                        probeError = "Toolchain probe failed: ${e.message ?: "unknown error"}",
                    )
                }
            }
        }
    }

    suspend fun refreshGitHubIdentity() {
        if (!container.isGitHubSignedIn()) {
            _state.update { it.copy(gitHubSignedIn = false, gitHubLogin = null) }
            return
        }
        val login = container.gitHubApi.currentUser().getOrNull()
        _state.update {
            it.copy(
                gitHubSignedIn = container.isGitHubSignedIn(),
                gitHubLogin = login,
            )
        }
    }

    // ---- GitHub device flow -------------------------------------------------

    fun startGitHubSignIn() {
        if (_state.value.deviceFlow?.waiting == true) return
        _state.update { it.copy(deviceFlow = DeviceFlowUi(waiting = true)) }
        viewModelScope.launch {
            val start = runCatching { container.deviceFlow.requestDeviceCode().getOrThrow() }
            start.onFailure { error ->
                _state.update {
                    it.copy(
                        deviceFlow = DeviceFlowUi(error = error.message ?: "Could not start GitHub sign-in"),
                    )
                }
            }
            val code = start.getOrNull() ?: return@launch
            _state.update {
                it.copy(
                    deviceFlow = DeviceFlowUi(
                        userCode = code.userCode,
                        verificationUri = code.verificationUri,
                        waiting = true,
                    ),
                )
            }

            pollJob?.cancel()
            pollJob = viewModelScope.launch {
                val deadline = System.currentTimeMillis() + code.expiresInSeconds * 1000L
                while (System.currentTimeMillis() < deadline) {
                    delay(code.pollIntervalSeconds * 1000L)
                    when (val outcome = runCatching { container.deviceFlow.pollOnce(code.deviceCode) }.getOrNull()) {
                        is DeviceFlowAuth.PollOutcome.Authorized -> {
                            val stored = container.credentials.put(CredentialKey.GITHUB_TOKEN, outcome.token)
                            if (!stored) {
                                _state.update {
                                    it.copy(
                                        deviceFlow = DeviceFlowUi(
                                            error = "Signed in, but the token could not be saved to the Keystore. " +
                                                "The session will not survive a restart.",
                                        ),
                                    )
                                }
                                return@launch
                            }
                            container.setGitHubToken(outcome.token)
                            // Report the state GitHub actually resolved, not an assumption.
                            val resolved = container.gitHubAuth.value
                            _state.update {
                                it.copy(
                                    deviceFlow = null,
                                    gitHubSignedIn = resolved.isSignedIn,
                                    gitHubLogin = (resolved as? GitHubAuthState.SignedIn)?.login,
                                    message = when {
                                        resolved.isSignedIn -> "Signed in to GitHub"
                                        resolved is GitHubAuthState.Expired -> "GitHub rejected the new token"
                                        resolved is GitHubAuthState.Error -> "Signed in, but GitHub was unreachable"
                                        else -> "Signed in"
                                    },
                                )
                            }
                            return@launch
                        }
                        is DeviceFlowAuth.PollOutcome.Pending -> Unit
                        is DeviceFlowAuth.PollOutcome.SlowDown -> delay(3000)
                        is DeviceFlowAuth.PollOutcome.Expired -> {
                            _state.update {
                                it.copy(
                                    deviceFlow = DeviceFlowUi(error = "The code expired. Start again."),
                                )
                            }
                            return@launch
                        }
                        is DeviceFlowAuth.PollOutcome.Denied -> {
                            _state.update {
                                it.copy(
                                    deviceFlow = DeviceFlowUi(error = "Authorisation was denied."),
                                )
                            }
                            return@launch
                        }
                        is DeviceFlowAuth.PollOutcome.Failed -> {
                            _state.update {
                                it.copy(
                                    deviceFlow = DeviceFlowUi(error = outcome.reason),
                                )
                            }
                            return@launch
                        }
                        null -> Unit
                    }
                }
                _state.update {
                    it.copy(
                        deviceFlow = DeviceFlowUi(error = "Timed out waiting for authorisation."),
                    )
                }
            }
        }
    }

    fun cancelDeviceFlow() {
        pollJob?.cancel()
        _state.update { it.copy(deviceFlow = null) }
    }

    fun signOutGitHub() {
        viewModelScope.launch {
            container.credentials.remove(CredentialKey.GITHUB_TOKEN)
            container.setGitHubToken(null)
            _state.update {
                it.copy(
                    gitHubSignedIn = false,
                    gitHubLogin = null,
                    message = "Signed out of GitHub",
                )
            }
        }
    }

    // ---- Credentials --------------------------------------------------------

    fun saveAiKey(key: String) {
        if (key.isBlank()) return
        viewModelScope.launch {
            val stored = container.credentials.put(CredentialKey.AI_API_KEY, key)
            container.refreshCredentials()
            _state.update {
                it.copy(
                    aiKeyStored = stored,
                    message = if (stored) {
                        "API key stored in the Android Keystore"
                    } else {
                        "The Keystore refused the key, so it was not saved"
                    },
                )
            }
        }
    }

    fun clearAiKey() {
        viewModelScope.launch {
            container.credentials.remove(CredentialKey.AI_API_KEY)
            container.refreshCredentials()
            _state.update { it.copy(aiKeyStored = false, message = "API key removed") }
        }
    }

    fun saveRemoteToken(token: String) {
        if (token.isBlank()) return
        viewModelScope.launch {
            val stored = container.credentials.put(CredentialKey.REMOTE_AGENT_TOKEN, token)
            container.refreshCredentials()
            _state.update {
                it.copy(
                    remoteTokenStored = stored,
                    message = if (stored) "Remote agent token stored" else "The Keystore refused the token, so it was not saved",
                )
            }
        }
    }

    fun clearRemoteToken() {
        viewModelScope.launch {
            container.credentials.remove(CredentialKey.REMOTE_AGENT_TOKEN)
            container.refreshCredentials()
            _state.update { it.copy(remoteTokenStored = false, message = "Remote agent token removed") }
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

    fun setOpenCodeUrl(url: String) {
        viewModelScope.launch { container.settings.updateOpenCode { it.copy(serverUrl = url) } }
    }

    fun setOpenCodeDir(dir: String) {
        viewModelScope.launch { container.settings.updateOpenCode { it.copy(remoteProjectDir = dir) } }
    }

    fun setOpenCodeAutoConnect(enabled: Boolean) {
        viewModelScope.launch { container.settings.updateOpenCode { it.copy(autoConnect = enabled) } }
    }

    fun saveOpenCodeToken(token: String) {
        if (token.isBlank()) return
        viewModelScope.launch {
            val stored = container.credentials.put(CredentialKey.OPENCODE_TOKEN, token)
            _state.update {
                it.copy(
                    openCodeTokenStored = stored,
                    message = if (stored) "OpenCode token stored" else "The Keystore refused the token, so it was not saved",
                )
            }
        }
    }

    fun clearOpenCodeToken() {
        viewModelScope.launch {
            container.credentials.remove(CredentialKey.OPENCODE_TOKEN)
            _state.update { it.copy(openCodeTokenStored = false, message = "OpenCode token removed") }
        }
    }

    fun setTabWidth(width: Int) {
        viewModelScope.launch { container.settings.updateEditor { it.copy(tabWidth = width.coerceIn(2, 8)) } }
    }

    fun setWordWrap(enabled: Boolean) {
        viewModelScope.launch { container.settings.updateEditor { it.copy(wordWrap = enabled) } }
    }

    fun setShowLineNumbers(enabled: Boolean) {
        viewModelScope.launch { container.settings.updateEditor { it.copy(showLineNumbers = enabled) } }
    }

    /**
     * Line height is stored as an absolute sp value, so it has to be recomputed from the
     * percentage whenever the font size moves: leaving it alone rescaled the spacing the user
     * had chosen, and the settings chips showed a different percentage than the one selected.
     */
    fun setEditorLineSpacing(percent: Int) {
        viewModelScope.launch {
            container.settings.updateEditor { editor ->
                editor.copy(
                    lineSpacingPercent = percent.coerceIn(
                        SettingsRepository.MIN_LINE_SPACING_PERCENT,
                        SettingsRepository.MAX_LINE_SPACING_PERCENT,
                    ),
                )
            }
        }
    }

    fun setEditorFontSize(size: Int) {
        viewModelScope.launch {
            container.settings.updateEditor {
                it.copy(fontSize = size.coerceIn(SettingsRepository.MIN_FONT_SIZE, SettingsRepository.MAX_FONT_SIZE))
            }
        }
    }

    fun setCodeGuard(enabled: Boolean) {
        viewModelScope.launch { container.settings.updateEditor { it.copy(codeGuardEnabled = enabled) } }
    }

    fun setHighlightCurrentLine(enabled: Boolean) {
        viewModelScope.launch { container.settings.updateEditor { it.copy(highlightCurrentLine = enabled) } }
    }

    fun setAutoSave(enabled: Boolean) {
        viewModelScope.launch { container.settings.updateEditor { it.copy(autoSave = enabled) } }
    }

    fun setTerminalFontSize(size: Int) {
        viewModelScope.launch { container.settings.updateTerminal { it.copy(fontSize = size.coerceIn(9, 24)) } }
    }

    fun setTerminalMaxSessions(count: Int) {
        viewModelScope.launch { container.settings.updateTerminal { it.copy(maxSessions = count.coerceIn(1, 12)) } }
    }

    fun clearMessage() {
        _state.update { it.copy(message = null) }
    }

}
