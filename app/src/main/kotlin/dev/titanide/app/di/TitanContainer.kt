package dev.titanide.app.di

import android.content.Context
import android.util.Log
import androidx.room.Room
import dev.titanide.app.BuildConfig
import dev.titanide.app.extensions.CapabilityGate
import dev.titanide.app.extensions.TitanCapability
import dev.titanide.core.ai.AiProvider
import dev.titanide.core.ai.EditApplier
import dev.titanide.core.ai.TitanIntelligence
import dev.titanide.core.ai.providers.UnconfiguredProvider
import dev.titanide.core.ai.context.ContextBuilder
import dev.titanide.core.ai.providers.AnthropicProvider
import dev.titanide.core.ai.providers.OpenAiCompatibleProvider
import dev.titanide.core.data.db.TitanDatabase
import dev.titanide.core.data.db.TitanMigrations
import dev.titanide.core.data.files.SafeFileSystem
import dev.titanide.core.data.repo.BuildHistoryRepository
import dev.titanide.core.data.repo.FileTreeRepository
import dev.titanide.core.data.repo.ProjectRepository
import dev.titanide.core.data.repo.ProjectStorage
import dev.titanide.core.data.repo.TabRepository
import dev.titanide.core.data.security.CredentialKey
import dev.titanide.core.git.auth.GitHubAuthState
import dev.titanide.core.git.auth.resolveGitHubAuth
import dev.titanide.core.data.security.SecureCredentialStore
import dev.titanide.core.data.settings.SettingsRepository
import dev.titanide.core.data.settings.TitanSettings
import dev.titanide.core.engine.ApkInstaller
import dev.titanide.core.engine.BuildEngine
import dev.titanide.core.engine.LocalBuildBackend
import dev.titanide.core.engine.ProcessRunner
import dev.titanide.core.engine.ToolchainProbe
import dev.titanide.core.engine.remote.GitHubActionsBackend
import dev.titanide.core.engine.remote.RemoteAgentBackend
import dev.titanide.core.git.api.GitHubApiClient
import dev.titanide.core.git.auth.DeviceFlowAuth
import dev.titanide.core.git.local.GitRepositoryService
import dev.titanide.core.model.ExecutionTarget
import dev.titanide.core.model.Project
import dev.titanide.core.opencode.session.OpenCodeSession
import dev.titanide.core.opencode.setup.DiscoveryOutcome
import dev.titanide.core.opencode.setup.OpenCodeDiscovery
import dev.titanide.core.templates.GeneratorRegistry
import dev.titanide.core.templates.ProjectScaffolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

/**
 * Manual dependency container. A DI framework would add little here: the graph is small,
 * construction order is explicit, and the wiring stays testable because every collaborator
 * takes its dependencies as constructor parameters.
 */
class TitanContainer(
    private val context: Context,
    /** Overridable so tests can point the GitHub client at a local server. */
    gitHubApiBase: String = "https://api.github.com",
) {

    private companion object {
        const val TAG = "TitanContainer"
        const val NOT_CONFIGURED_MESSAGE =
            "No OpenCode server configured. TITAN cannot run OpenCode on this device because the " +
                "released binaries target desktop Linux and Windows, not Android. Run " +
                "`opencode serve` on a computer or server, then set its URL in Settings."
    }

    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val databaseDelegate = lazy {
        Room.databaseBuilder(context, TitanDatabase::class.java, "titan.db")
            // No destructive fallback: projects, build history and open tabs are user data,
            // so an unhandled schema change must fail loudly instead of wiping the database.
            .addMigrations(*TitanMigrations.ALL)
            .build()
    }

    val database: TitanDatabase by databaseDelegate

    val storage: ProjectStorage by lazy { ProjectStorage(context) }

    /** Installs built APKs through the system package installer. */
    val apkInstaller: ApkInstaller by lazy { ApkInstaller(context) }
    val safeFileSystem: SafeFileSystem by lazy { SafeFileSystem() }
    val settings: SettingsRepository by lazy { SettingsRepository(context) }
    val credentials: SecureCredentialStore by lazy { SecureCredentialStore(context) }

    val projectRepository: ProjectRepository by lazy {
        ProjectRepository(database.projectDao(), storage, safeFileSystem)
    }
    val fileTreeRepository: FileTreeRepository by lazy { FileTreeRepository(safeFileSystem) }
    val buildHistory: BuildHistoryRepository by lazy { BuildHistoryRepository(database.buildDao(), storage) }
    val tabRepository: TabRepository by lazy { TabRepository(database.tabDao()) }

    /**
     * Which built-in extensions are switched on. Enabled state is persisted by the extensions
     * screen; an extension with no stored row counts as enabled so a fresh install keeps every
     * feature. Consumers ask this before using a gated feature.
     */
    val capabilityGate: CapabilityGate = CapabilityGate { capability ->
        capabilityStates.value[capability] ?: true
    }

    private val capabilityStates = MutableStateFlow<Map<TitanCapability, Boolean>>(emptyMap())

    private fun observeCapabilities() {
        appScope.launch {
            database.extensionDao().observeAll().collect { stored ->
                val byId = stored.associateBy { it.id }
                capabilityStates.value = TitanCapability.entries.associateWith { capability ->
                    byId[capability.extensionId]?.enabled ?: true
                }
            }
        }
    }

    val processRunner: ProcessRunner by lazy { ProcessRunner(appScope) }
    val toolchainProbe: ToolchainProbe by lazy { ToolchainProbe(processRunner) }

    val gitService: GitRepositoryService by lazy { GitRepositoryService() }

    val gitHubApi: GitHubApiClient by lazy {
        GitHubApiClient(tokenProvider = { gitHubToken.value }, apiBase = gitHubApiBase)
    }

    val deviceFlow: DeviceFlowAuth by lazy { DeviceFlowAuth(clientId = BuildConfig.GITHUB_CLIENT_ID) }

    /** Latest settings snapshot, refreshed whenever DataStore emits. */
    val settingsState = MutableStateFlow<TitanSettings?>(null)

    private val gitHubToken = MutableStateFlow<String?>(null)

    /** Last login GitHub confirmed, so a restart can show the account before re-verifying. */
    @Volatile
    private var cachedGitHubLogin: String? = null

    private val _gitHubAuth = MutableStateFlow<GitHubAuthState>(GitHubAuthState.Initializing)

    /**
     * Settled GitHub auth state. Screens must observe this rather than reading the raw
     * token, which is null until the Keystore read completes and would make them
     * render "signed out" for a frame or two on every cold start.
     */
    val gitHubAuth: StateFlow<GitHubAuthState> = _gitHubAuth.asStateFlow()
    private val branchRegistry = HashMap<String, String>()

    /** Set once the lazy engine exists; the local backend reports sessions through it. */
    @Volatile
    private var buildEngineRef: BuildEngine? = null

    /**
     * The build engine is created once, but every backend reads its configuration through
     * a provider at execution time. Capturing settings here instead would freeze the
     * remote URL, token, owner and workflow at first use, so editing Settings would only
     * take effect after an app restart.
     */
    val buildEngine: BuildEngine by lazy {
        BuildEngine(
            scope = appScope,
            localBackend = LocalBuildBackend(
                runner = processRunner,
                onSessionStarted = { projectId, sessionId ->
                    buildEngineRef?.registerSession(projectId, sessionId)
                },
            ),
            remoteBackends = mapOf(
                ExecutionTarget.REMOTE_AGENT to RemoteAgentBackend(
                    baseUrlProvider = { settingsState.value?.build?.remoteAgentUrl.orEmpty() },
                    tokenProvider = { credentials.get(CredentialKey.REMOTE_AGENT_TOKEN) },
                ),
                ExecutionTarget.GITHUB_ACTIONS to GitHubActionsBackend(
                    ownerProvider = { settingsState.value?.build?.githubOwner.orEmpty() },
                    repoProvider = { settingsState.value?.build?.githubRepo.orEmpty() },
                    workflowProvider = {
                        settingsState.value?.build?.githubWorkflow?.takeIf { it.isNotBlank() }
                            ?: "titan-build.yml"
                    },
                    tokenProvider = { gitHubToken.value },
                    branchProvider = { projectId -> branchRegistry[projectId] },
                ),
            ),
            history = buildHistory,
            runner = processRunner,
        ).also { buildEngineRef = it }
    }

    /**
     * Real probes against the configured server and the loopback default, so the agent
     * panel can report a specific setup problem instead of a generic failure.
     */
    val openCodeDiscovery: OpenCodeDiscovery by lazy { OpenCodeDiscovery() }

    /**
     * The OpenCode agent conversation for the current project. One per app process: the
     * panel is the only consumer, and reconnecting is an explicit user action.
     */
    val openCodeSession: OpenCodeSession by lazy {
        OpenCodeSession(scope = appScope)
    }

    /** Shared "file the user is looking at", written by the editor and read by AI modes. */
    val activeFile: ActiveFileSession = ActiveFileSession()

    val scaffolder: ProjectScaffolder by lazy { ProjectScaffolder(GeneratorRegistry.default(), safeFileSystem) }

    private val aiProviderState = MutableStateFlow<AiProvider>(UnconfiguredProvider())

    val contextBuilder: ContextBuilder by lazy { ContextBuilder(fileTreeRepository) }

    val editApplier: EditApplier by lazy { EditApplier(fileTreeRepository, safeFileSystem) }

    val intelligence: TitanIntelligence by lazy {
        TitanIntelligence(
            scope = appScope,
            providerProvider = { aiProviderState.value },
            // Read on every request: the user can change the model in Settings at any time,
            // and this facade is built once for the process lifetime.
            modelProvider = { settingsState.value?.ai?.model.orEmpty() },
            contextBuilder = contextBuilder,
            files = fileTreeRepository,
            editApplier = editApplier,
        )
    }

    init {
        appScope.launch {
            settings.settings.collect { snapshot ->
                settingsState.value = snapshot
                refreshAiProvider(snapshot)
            }
        }
        appScope.launch {
            // Sequential on purpose: EncryptedSharedPreferences is not safe to read from
            // two coroutines at once, and concurrent reads corrupted state under load.
            // The remote agent token is not read here: the backend resolves it per build.
            restoreGitHubAuth()
            buildHistory.prune(settings.settings.first().build.keepLogsDays)
        }
        observeCapabilities()
    }

    /** Rebuilds the AI provider from settings; called after the user edits AI settings. */
    suspend fun refreshAiProvider(snapshot: TitanSettings? = settingsState.value) {
        val current = snapshot ?: settings.settings.first()
        val ai = current.ai
        val key = credentials.get(CredentialKey.AI_API_KEY)
        aiProviderState.value = when {
            ai.providerId == "none" || ai.providerId.isBlank() -> UnconfiguredProvider()
            ai.providerId == "anthropic" && key.isNullOrBlank() -> UnconfiguredProvider(
                displayName = "Anthropic (API key missing)",
            )
            ai.providerId == "anthropic" -> AnthropicProvider(apiKeyProvider = { key })
            else -> OpenAiCompatibleProvider(
                displayName = providerLabel(ai.endpoint),
                baseUrl = ai.endpoint.ifBlank { "https://api.openai.com/v1" },
                apiKeyProvider = { key },
                models = (listOfNotNull(ai.model.takeIf { it.isNotBlank() }) + OpenAiCompatibleProvider.DEFAULT_MODELS)
                    .distinct(),
            )
        }
    }

    private fun providerLabel(endpoint: String): String = when {
        endpoint.isBlank() -> "OpenAI-compatible"
        endpoint.contains("localhost") || endpoint.contains("127.0.0.1") -> "Local model"
        endpoint.contains("openrouter") -> "OpenRouter"
        else -> "OpenAI-compatible"
    }

    suspend fun refreshCredentials() {
        restoreGitHubAuth()
        refreshAiProvider()
    }

    /**
     * Loads the saved token at startup. This deliberately does NOT call GitHub: a network
     * round trip here would leave a coroutine running for the whole process lifetime and
     * make startup depend on connectivity. A token found on disk is reported as
     * [GitHubAuthState.SignedIn] optimistically and verified on first real use, where a
     * rejection is visible to the caller anyway.
     */
    private suspend fun restoreGitHubAuth() {
        val saved = runCatching { credentials.get(CredentialKey.GITHUB_TOKEN) }
            .onFailure { Log.w(TAG, "Could not read the saved GitHub token", it) }
            .getOrNull()
        gitHubToken.value = saved
        _gitHubAuth.value = if (saved.isNullOrBlank()) {
            GitHubAuthState.SignedOut
        } else {
            GitHubAuthState.SignedIn(login = cachedGitHubLogin.orEmpty(), avatarUrl = null)
        }
    }

    private suspend fun verifyGitHubToken(token: String): GitHubAuthState =
        resolveGitHubAuth(
            result = gitHubApi.currentUser(),
            isUnauthorized = ::isUnauthorized,
            isOffline = { it is IOException },
            onTokenRejected = {
                // The token is stale. Drop it so the user is not left half-signed-in.
                runCatching { credentials.remove(CredentialKey.GITHUB_TOKEN) }
                gitHubToken.value = null
                cachedGitHubLogin = null
            },
        ).also { state ->
            if (state is GitHubAuthState.SignedIn && state.login.isNotEmpty()) {
                cachedGitHubLogin = state.login
            }
        }

    private fun isUnauthorized(e: Throwable): Boolean =
        e.message?.contains("401") == true || e.message?.contains("rejected the token") == true

    /** Marks the session as expired after a 401 from any GitHub call. */
    fun markGitHubExpired() {
        _gitHubAuth.value = GitHubAuthState.Expired
    }

    fun beginGitHubSignIn(userCode: String, verificationUri: String, expiresAt: Long) {
        _gitHubAuth.value = GitHubAuthState.SigningIn(userCode, verificationUri, expiresAt)
    }

    fun cancelGitHubSignIn() {
        if (_gitHubAuth.value is GitHubAuthState.SigningIn) {
            _gitHubAuth.value = GitHubAuthState.SignedOut
        }
    }

    /**
     * Connects the agent to the configured OpenCode server for [project]. The remote
     * directory falls back to the project name so a first run has a sensible default
     * instead of an empty string the server would reject.
     */
    fun connectAgent(project: dev.titanide.core.model.Project?, explicit: Boolean = false) {
        val openCode = settingsState.value?.openCode ?: return
        val directory = openCode.remoteProjectDir.ifBlank {
            project?.rootPath?.substringAfterLast('/') ?: project?.name.orEmpty()
        }
        val configured = openCode.serverUrl.trim()
        // Loading a project must not touch the network: probing loopback on every open made
        // startup depend on connectivity and left work running for the whole process. A
        // configured URL is trusted and connected to directly; loopback discovery only runs
        // when the user asks for it.
        if (configured.isEmpty() && !explicit) {
            openCodeSession.reportSetupProblem(NOT_CONFIGURED_MESSAGE)
            return
        }
        appScope.launch {
            val token = credentials.get(CredentialKey.OPENCODE_TOKEN)
            if (!explicit && configured.isNotEmpty()) {
                openCodeSession.connect(url = configured, token = token, directory = directory)
                return@launch
            }
            when (val outcome = openCodeDiscovery.discover(configured, token)) {
                is DiscoveryOutcome.Ready -> openCodeSession.connect(
                    url = outcome.url,
                    token = token,
                    directory = directory,
                )
                is DiscoveryOutcome.NoModels -> openCodeSession.reportSetupProblem(
                    "Found an OpenCode server at ${outcome.url}, but it has no model configured. " +
                        "Run `opencode auth login` on that machine, then reconnect.",
                )
                is DiscoveryOutcome.Unhealthy -> openCodeSession.reportSetupProblem(
                    "An OpenCode server answered at ${outcome.url} but is not healthy: ${outcome.reason}",
                )
                DiscoveryOutcome.NotFound -> openCodeSession.reportSetupProblem(NOT_CONFIGURED_MESSAGE)
            }
        }
    }

    /**
     * True only when the token has finished loading AND is usable. Callers that need to
     * distinguish "still loading" from "signed out" must read [gitHubAuth].
     */
    fun isGitHubSignedIn(): Boolean = _gitHubAuth.value.hasUsableToken

    fun githubTokenOrNull(): String? = gitHubToken.value

    /** Stores a freshly issued token and resolves the signed-in account. */
    suspend fun setGitHubToken(token: String?) {
        gitHubToken.value = token
        if (token.isNullOrBlank()) {
            credentials.remove(CredentialKey.GITHUB_TOKEN)
            _gitHubAuth.value = GitHubAuthState.SignedOut
            return
        }
        credentials.put(CredentialKey.GITHUB_TOKEN, token)
        _gitHubAuth.value = verifyGitHubToken(token)
    }

    fun registerBranch(projectId: String, branch: String) {
        branchRegistry[projectId] = branch
    }

    fun buildSuccessRate(): Flow<Double> = flow { emit(buildHistory.successRate()) }

    /**
     * Bytes used by the app's own directories. Overridable so a test can observe which thread
     * performs the walk without creating a large tree on disk.
     */
    var storageProbe: () -> Long = { storage.usedBytes() }

    suspend fun storageUsedBytes(): Long = withContext(Dispatchers.IO) { storageProbe() }

    /** Bytes used by one project's directory. Overridable so a test can observe the thread. */
    var projectSizeProbe: suspend (Project) -> Long = { project ->
        val dir = File(project.rootPath)
        if (dir.exists()) dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0L
    }

    suspend fun projectSizeBytes(project: Project): Long =
        withContext(Dispatchers.IO) { projectSizeProbe(project) }

    /** Overridable so a test can force the failure path without an unwritable directory. */
    var projectDeleter: suspend (String) -> Boolean = { id -> projectRepository.deleteProject(id) }

    fun currentSettings(): TitanSettings? = settingsState.value

    /**
     * Releases the resources this container owns: the background scope and the database.
     *
     * Only the database is closed when it was actually opened, so a container that never
     * touched storage does not create one on the way out. Unit tests build a container per
     * class; without this, every class left an open SQLite connection behind and a later
     * class eventually failed with SQLITE_BUSY.
     */
    fun close() {
        appScope.cancel()
        if (databaseDelegate.isInitialized()) databaseDelegate.value.close()
    }
}
