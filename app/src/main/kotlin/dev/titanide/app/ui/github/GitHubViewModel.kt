package dev.titanide.app.ui.github

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.titanide.app.di.TitanContainer
import dev.titanide.core.data.files.ProjectDetector
import dev.titanide.core.git.local.GitRepositoryService.Outcome
import dev.titanide.core.git.api.GitEntry
import dev.titanide.core.model.BuildKind
import dev.titanide.core.model.BuildRequest
import dev.titanide.core.model.BuildResult
import dev.titanide.core.model.BuildSystem
import dev.titanide.core.model.ExecutionTarget
import dev.titanide.core.model.GitBranch
import dev.titanide.core.model.GitCommit
import dev.titanide.core.model.GitRepository
import dev.titanide.core.model.Language
import dev.titanide.core.model.Project
import dev.titanide.core.model.ProjectCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

enum class RepoSort(val label: String, val apiValue: String) {
    UPDATED("Updated", "updated"),
    PUSHED("Pushed", "pushed"),
    NAME("Name", "full_name"),
    CREATED("Created", "created"),
}

data class GitHubListState(
    val repos: List<GitRepository> = emptyList(),
    val query: String = "",
    val sort: RepoSort = RepoSort.UPDATED,
    val onlyPrivate: Boolean = false,
    val loading: Boolean = false,
    val searching: Boolean = false,
    val signedIn: Boolean = false,
    val login: String? = null,
    val error: String? = null,
    val importing: String? = null,
) {
    val visible: List<GitRepository>
        get() = repos
            .filter { !onlyPrivate || it.isPrivate }
            .filter {
                query.isBlank() ||
                    it.name.contains(query, ignoreCase = true) ||
                    it.description?.contains(query, ignoreCase = true) == true
            }
}

data class RepoDetailState(
    val repo: GitRepository? = null,
    val branches: List<GitBranch> = emptyList(),
    val commits: List<GitCommit> = emptyList(),
    val entries: List<GitEntry> = emptyList(),
    val path: String = "",
    val loading: Boolean = false,
    val error: String? = null,
    val detected: ProjectDetector.Detected? = null,
    val importing: Boolean = false,
    val importStage: String = "",
    val importedProjectId: String? = null,
    val testResult: BuildResult? = null,
    val testing: Boolean = false,
)

class GitHubViewModel(
    private val container: TitanContainer,
    private val repoFullName: String? = null,
) : ViewModel() {

    private val _list = MutableStateFlow(GitHubListState())
    val list: StateFlow<GitHubListState> = _list.asStateFlow()

    private val _detail = MutableStateFlow(RepoDetailState())
    val detail: StateFlow<RepoDetailState> = _detail.asStateFlow()

    init {
        viewModelScope.launch {
            val signedIn = container.isGitHubSignedIn()
            _list.value = _list.value.copy(signedIn = signedIn)
            if (signedIn) {
                loadRepositories()
                _list.value = _list.value.copy(login = container.gitHubApi.currentUser().getOrNull())
            }
        }
        repoFullName?.let(::loadRepository)
    }

    fun loadRepositories() {
        if (!container.isGitHubSignedIn()) {
            _list.value = _list.value.copy(error = "Sign in to GitHub from Settings first", signedIn = false)
            return
        }
        viewModelScope.launch {
            _list.value = _list.value.copy(loading = true, error = null)
            container.gitHubApi.listRepositories(sort = _list.value.sort.apiValue).fold(
                onSuccess = { repos ->
                    _list.value = _list.value.copy(repos = repos, loading = false)
                },
                onFailure = { error ->
                    _list.value = _list.value.copy(
                        loading = false,
                        error = error.message ?: "Could not load repositories",
                    )
                },
            )
        }
    }

    fun setQuery(query: String) {
        _list.value = _list.value.copy(query = query)
    }

    fun setSort(sort: RepoSort) {
        _list.value = _list.value.copy(sort = sort)
        loadRepositories()
    }

    fun togglePrivateOnly() {
        _list.value = _list.value.copy(onlyPrivate = !_list.value.onlyPrivate)
    }

    /** Searches all of GitHub rather than the user's repositories. */
    fun searchAll() {
        val query = _list.value.query.trim()
        if (query.isEmpty()) return
        viewModelScope.launch {
            _list.value = _list.value.copy(searching = true, error = null)
            container.gitHubApi.searchRepositories(query).fold(
                onSuccess = { repos ->
                    _list.value = _list.value.copy(repos = repos, searching = false)
                },
                onFailure = { error ->
                    _list.value = _list.value.copy(searching = false, error = error.message)
                },
            )
        }
    }

    // ---- Detail ------------------------------------------------------------

    private fun loadRepository(fullName: String) {
        viewModelScope.launch {
            _detail.value = _detail.value.copy(loading = true, error = null)
            val owner = fullName.substringBefore('/')
            val name = fullName.substringAfter('/')
            val repo = _list.value.repos.firstOrNull { it.fullName.equals(fullName, ignoreCase = true) }
                ?: _list.value.repos.firstOrNull()
            val branches = container.gitHubApi.listBranches(owner, name).getOrDefault(emptyList())
            val commits = container.gitHubApi.listCommits(owner, name, repo?.defaultBranch).getOrDefault(emptyList())
            val entries = container.gitHubApi.listContents(owner, name, "").getOrDefault(emptyList())
            _detail.value = _detail.value.copy(
                repo = repo?.copy(fullName = fullName),
                branches = branches,
                commits = commits,
                entries = entries,
                loading = false,
            )
        }
    }

    fun openPath(path: String) {
        val repo = _detail.value.repo ?: return
        viewModelScope.launch {
            _detail.value = _detail.value.copy(loading = true)
            val entries = container.gitHubApi
                .listContents(repo.owner, repo.name, path, repo.defaultBranch)
                .getOrDefault(emptyList())
            _detail.value = _detail.value.copy(entries = entries, path = path, loading = false)
        }
    }

    /**
     * Clones the repository into project storage, then inspects it. Cloning (rather than a
     * tarball download) keeps the history, so the user can immediately branch and push.
     */
    fun importRepository() {
        val repo = _detail.value.repo ?: return
        if (_detail.value.importing) return
        viewModelScope.launch {
            _detail.value = _detail.value.copy(importing = true, importStage = "Cloning ${repo.fullName}", error = null)
            val projectId = UUID.randomUUID().toString()
            val target = container.storage.projectDir(projectId)

            val clone = container.gitService.clone(
                remoteUrl = repo.cloneUrl,
                destination = target,
                token = container.githubTokenOrNull(),
                branch = repo.defaultBranch,
            )

            if (clone is Outcome.Failed) {
                _detail.value = _detail.value.copy(
                    importing = false,
                    error = "Clone failed: ${clone.reason}",
                )
                return@launch
            }

            _detail.value = _detail.value.copy(importStage = "Inspecting project")
            val detected = withContext(Dispatchers.IO) { ProjectDetector.detect(target) }
            val project = Project(
                id = projectId,
                name = repo.name,
                packageName = detected.languages.firstOrNull()?.let { "github.${repo.owner.lowercase()}.${repo.name.lowercase()}" }
                    ?: repo.name.lowercase(),
                category = ProjectCategory.BLANK,
                templateId = "imported-github",
                rootPath = target.absolutePath,
                languages = detected.languages.mapNotNull { id ->
                    Language.entries.firstOrNull { it.id == id }
                }.ifEmpty { listOf(Language.PLAINTEXT) },
                buildSystem = BuildSystem.fromId(detected.buildSystem),
                createdAt = System.currentTimeMillis(),
                lastOpenedAt = System.currentTimeMillis(),
                gitRemote = repo.cloneUrl,
                gitBranch = repo.defaultBranch,
                sdkConfig = null,
            )
            container.projectRepository.register(project)
            container.registerBranch(projectId, repo.defaultBranch)

            _detail.value = _detail.value.copy(
                importing = false,
                importStage = "",
                detected = detected,
                importedProjectId = projectId,
            )
        }
    }

    /**
     * Runs the repository's own test task through the build engine. Nothing is faked: the
     * engine reports the executor's real exit code, and a local run on a device without a
     * JDK fails with the real reason instead of pretending to pass.
     */
    fun testProject(target: ExecutionTarget) {
        val projectId = _detail.value.importedProjectId ?: return
        if (_detail.value.testing) return
        val project = _detail.value.repo ?: return
        val projectPath = container.storage.projectDir(projectId).absolutePath

        viewModelScope.launch {
            _detail.value = _detail.value.copy(testing = true, testResult = null)
            val result = container.buildEngine.runAndWait(
                BuildRequest(
                    projectId = projectId,
                    projectPath = projectPath,
                    kind = BuildKind.TEST,
                    target = target,
                    task = _detail.value.detected?.testTasks?.firstOrNull(),
                ),
            )
            _detail.value = _detail.value.copy(testing = false, testResult = result)
        }
    }

    fun clearError() {
        _detail.value = _detail.value.copy(error = null)
    }
}
