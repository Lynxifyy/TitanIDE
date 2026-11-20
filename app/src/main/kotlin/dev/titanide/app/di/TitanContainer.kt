package dev.titanide.app.di

import android.content.Context
import androidx.room.Room
import dev.titanide.app.BuildConfig
import dev.titanide.core.ai.AiProvider
import dev.titanide.core.ai.EditApplier
import dev.titanide.core.ai.TitanIntelligence
import dev.titanide.core.ai.providers.UnconfiguredProvider
import dev.titanide.core.ai.context.ContextBuilder
import dev.titanide.core.ai.providers.AnthropicProvider
import dev.titanide.core.ai.providers.OpenAiCompatibleProvider
import dev.titanide.core.data.db.TitanDatabase
import dev.titanide.core.data.files.SafeFileSystem
import dev.titanide.core.data.repo.BuildHistoryRepository
import dev.titanide.core.data.repo.FileTreeRepository
import dev.titanide.core.data.repo.ProjectRepository
import dev.titanide.core.data.repo.ProjectStorage
import dev.titanide.core.data.repo.TabRepository
import dev.titanide.core.data.security.CredentialKey
import dev.titanide.core.data.security.SecureCredentialStore
import dev.titanide.core.data.settings.SettingsRepository
import dev.titanide.core.data.settings.TitanSettings
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
import dev.titanide.core.opencode.session.OpenCodeSession
import dev.titanide.core.templates.GeneratorRegistry
import dev.titanide.core.templates.ProjectScaffolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/**
 * Manual dependency container. A DI framework would add little here: the graph is small,
 * construction order is explicit, and the wiring stays testable because every collaborator
 * takes its dependencies as constructor parameters.
 */
class TitanContainer(private val context: Context) {

    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: TitanDatabase by lazy {
        Room.databaseBuilder(context, TitanDatabase::class.java, "titan.db")
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
    }

    val storage: ProjectStorage by lazy { ProjectStorage(context) }
    val safeFileSystem: SafeFileSystem by lazy { SafeFileSystem() }
    val settings: SettingsRepository by lazy { SettingsRepository(context) }
    val credentials: SecureCredentialStore by lazy { SecureCredentialStore(context) }

    val projectRepository: ProjectRepository by lazy {
        ProjectRepository(database.projectDao(), storage, safeFileSystem)
    }
    val fileTreeRepository: FileTreeRepository by lazy { FileTreeRepository(safeFileSystem) }
    val buildHistory: BuildHistoryRepository by lazy { BuildHistoryRepository(database.buildDao(), storage) }
    val tabRepository: TabRepository by lazy { TabRepository(database.tabDao()) }

    val processRunner: ProcessRunner by lazy { ProcessRunner(appScope) }
    val toolchainProbe: ToolchainProbe by lazy { ToolchainProbe(processRunner) }

    val gitService: GitRepositoryService by lazy { GitRepositoryService() }

    val gitHubApi: GitHubApiClient by lazy { GitHubApiClient(tokenProvider = { gitHubToken.value }) }

    val deviceFlow: DeviceFlowAuth by lazy { DeviceFlowAuth(clientId = BuildConfig.GITHUB_CLIENT_ID) }

    /** Latest settings snapshot, refreshed whenever DataStore emits. */
    val settingsState = MutableStateFlow<TitanSettings?>(null)

    private val gitHubToken = MutableStateFlow<String?>(null)
    private val branchRegistry = HashMap<String, String>()
    private var remoteAgentToken: String? = null

    val buildEngine: BuildEngine by lazy {
        val buildSettings = settingsState.value?.build
        BuildEngine(
            scope = appScope,
            localBackend = LocalBuildBackend(processRunner, safeFileSystem),
            remoteBackends = mapOf(
                ExecutionTarget.REMOTE_AGENT to RemoteAgentBackend(
                    baseUrl = buildSettings?.remoteAgentUrl.orEmpty(),
                    token = remoteAgentToken,
                ),
                ExecutionTarget.GITHUB_ACTIONS to GitHubActionsBackend(
                    owner = buildSettings?.githubOwner.orEmpty(),
                    repo = buildSettings?.githubRepo.orEmpty(),
                    workflowFile = buildSettings?.githubWorkflow ?: "titan-build.yml",
                    tokenProvider = { gitHubToken.value },
                    branchProvider = { projectId -> branchRegistry[projectId] },
                ),
            ),
            history = buildHistory,
            runner = processRunner,
        )
    }

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
            model = settingsState.value?.ai?.model.orEmpty(),
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
            gitHubToken.value = credentials.get(CredentialKey.GITHUB_TOKEN)
            remoteAgentToken = credentials.get(CredentialKey.REMOTE_AGENT_TOKEN)
            buildHistory.prune(settings.settings.first().build.keepLogsDays)
        }
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
        gitHubToken.value = credentials.get(CredentialKey.GITHUB_TOKEN)
        remoteAgentToken = credentials.get(CredentialKey.REMOTE_AGENT_TOKEN)
        refreshAiProvider()
    }

    /**
     * Connects the agent to the configured OpenCode server for [project]. The remote
     * directory falls back to the project name so a first run has a sensible default
     * instead of an empty string the server would reject.
     */
    fun connectAgent(project: dev.titanide.core.model.Project?) {
        val openCode = settingsState.value?.openCode ?: return
        if (openCode.serverUrl.isBlank()) return
        val directory = openCode.remoteProjectDir.ifBlank {
            project?.rootPath?.substringAfterLast('/') ?: project?.name.orEmpty()
        }
        appScope.launch {
            val token = credentials.get(CredentialKey.OPENCODE_TOKEN)
            openCodeSession.connect(
                url = openCode.serverUrl,
                token = token,
                directory = directory,
            )
        }
    }

    fun isGitHubSignedIn(): Boolean = !gitHubToken.value.isNullOrBlank()

    fun githubTokenOrNull(): String? = gitHubToken.value

    fun setGitHubToken(token: String?) {
        gitHubToken.value = token
    }

    fun registerBranch(projectId: String, branch: String) {
        branchRegistry[projectId] = branch
    }

    fun buildSuccessRate(): Flow<Double> = flow { emit(buildHistory.successRate()) }

    fun storageUsedBytes(): Long = storage.usedBytes()

    fun currentSettings(): TitanSettings? = settingsState.value
}
