package dev.titanide.app.ui.github

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.titanide.app.di.TitanContainer
import dev.titanide.core.data.files.ProjectDetector
import dev.titanide.core.git.local.GitRepositoryService.Outcome
import dev.titanide.core.ai.AiOutcome
import dev.titanide.core.git.api.GitEntry
import dev.titanide.core.model.DiffLineType
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
import kotlinx.coroutines.flow.update
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
    val review: String? = null,
    val reviewing: Boolean = false,
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
            _list.update { it.copy(signedIn = signedIn) }
            if (signedIn) {
                loadRepositories()
                _list.update { it.copy(login = container.gitHubApi.currentUser().getOrNull()) }
            }
        }
        repoFullName?.let(::loadRepository)
    }

    fun loadRepositories() {
        if (!container.isGitHubSignedIn()) {
            _list.update { it.copy(error = "Sign in to GitHub from Settings first", signedIn = false) }
            return
        }
        viewModelScope.launch {
            _list.update { it.copy(loading = true, error = null) }
            container.gitHubApi.listRepositories(sort = _list.value.sort.apiValue).fold(
                onSuccess = { repos ->
                    _list.update { it.copy(repos = repos, loading = false) }
                },
                onFailure = { error ->
                    _list.update {
                        it.copy(
                            loading = false,
                            error = error.message ?: "Could not load repositories",
                        )
                    }
                },
            )
        }
    }

    fun setQuery(query: String) {
        _list.update { it.copy(query = query) }
    }

    fun setSort(sort: RepoSort) {
        _list.update { it.copy(sort = sort) }
        loadRepositories()
    }

    fun togglePrivateOnly() {
        _list.update { it.copy(onlyPrivate = !it.onlyPrivate) }
    }

    /** Searches all of GitHub rather than the user's repositories. */
    fun searchAll() {
        val query = _list.value.query.trim()
        if (query.isEmpty()) return
        viewModelScope.launch {
            _list.update { it.copy(searching = true, error = null) }
            container.gitHubApi.searchRepositories(query).fold(
                onSuccess = { repos ->
                    _list.update { it.copy(repos = repos, searching = false) }
                },
                onFailure = { error ->
                    _list.update { it.copy(searching = false, error = error.message) }
                },
            )
        }
    }

    // ---- Detail ------------------------------------------------------------

    private fun loadRepository(fullName: String) {
        viewModelScope.launch {
            _detail.update { it.copy(loading = true, error = null) }
            val owner = fullName.substringBefore('/')
            val name = fullName.substringAfter('/')
            val repo = _list.value.repos.firstOrNull { it.fullName.equals(fullName, ignoreCase = true) }
                ?: container.gitHubApi.getRepository(owner, name).getOrNull()
            if (repo == null) {
                _detail.update {
                    it.copy(
                        loading = false,
                        error = "Could not load $fullName",
                    )
                }
                return@launch
            }
            // A failed listing is not an empty repository. Showing zero branches and zero files
            // after a 401 or a rate limit reads as "your code is gone", so report the reason.
            val branches = container.gitHubApi.listBranches(owner, name)
            val commits = container.gitHubApi.listCommits(owner, name, repo.defaultBranch)
            val entries = container.gitHubApi.listContents(owner, name, "")
            _detail.update {
                it.copy(
                    repo = repo,
                    branches = branches.getOrDefault(emptyList()),
                    commits = commits.getOrDefault(emptyList()),
                    entries = entries.getOrDefault(emptyList()),
                    loading = false,
                    error = entries.exceptionOrNull()?.let { e -> e.message ?: "Could not list files" },
                )
            }
        }
    }

    fun openPath(path: String) {
        val repo = _detail.value.repo ?: return
        viewModelScope.launch {
            _detail.update { it.copy(loading = true, error = null) }
            val entries = container.gitHubApi.listContents(repo.owner, repo.name, path, repo.defaultBranch)
            _detail.update {
                it.copy(
                    entries = entries.getOrDefault(emptyList()),
                    path = path,
                    loading = false,
                    error = entries.exceptionOrNull()?.let { e -> e.message ?: "Could not open $path" },
                )
            }
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
            _detail.update { it.copy(importing = true, importStage = "Cloning ${repo.fullName}", error = null) }
            val projectId = UUID.randomUUID().toString()
            val target = container.storage.projectDir(projectId)

            val clone = container.gitService.clone(
                remoteUrl = repo.cloneUrl,
                destination = target,
                token = container.githubTokenOrNull(),
                branch = repo.defaultBranch,
            )

            if (clone is Outcome.Failed) {
                _detail.update {
                    it.copy(
                        importing = false,
                        error = "Clone failed: ${clone.reason}",
                    )
                }
                return@launch
            }

            _detail.update { it.copy(importStage = "Inspecting project") }
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

            _detail.update {
                it.copy(
                    importing = false,
                    importStage = "",
                    detected = detected,
                    importedProjectId = projectId,
                )
            }
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
            _detail.update { it.copy(testing = true, testResult = null) }
            val result = container.buildEngine.runAndWait(
                BuildRequest(
                    projectId = projectId,
                    projectPath = projectPath,
                    kind = BuildKind.TEST,
                    target = target,
                    task = _detail.value.detected?.testTasks?.firstOrNull(),
                ),
            )
            _detail.update { it.copy(testing = false, testResult = result) }
        }
    }

    /**
     * Reviews the working copy's changes with the project's own diff as the input.
     *
     * The diff is produced by git rather than assembled from the editor buffers, so what the
     * model reads is what a reviewer would see. The AI call is only made when there is an
     * actual change to review — reviewing an empty diff would burn a request to say nothing.
     */
    fun reviewChanges() {
        val projectId = _detail.value.importedProjectId ?: return
        if (_detail.value.reviewing) return
        val root = container.storage.projectDir(projectId)
        viewModelScope.launch {
            _detail.update { it.copy(reviewing = true, review = null, error = null) }
            val diff = container.gitService.diff(root)
            if (diff is Outcome.Failed) {
                _detail.update {
                    it.copy(
                        reviewing = false,
                        error = "Could not read the diff: ${diff.reason}",
                    )
                }
                return@launch
            }
            val text = (diff as Outcome.Ok).value
                .joinToString("\n") { line ->
                    when (line.type) {
                        DiffLineType.ADDED -> "+${line.text}"
                        DiffLineType.REMOVED -> "-${line.text}"
                        else -> line.text
                    }
                }
            if (text.isBlank()) {
                _detail.update {
                    it.copy(
                        reviewing = false,
                        review = "No changes to review yet. Commit or edit a file first.",
                    )
                }
                return@launch
            }
            val project = container.projectRepository.getProject(projectId)
            if (project == null) {
                _detail.update { it.copy(reviewing = false, error = "Project is no longer registered") }
                return@launch
            }
            val outcome = container.intelligence.reviewPullRequest(project, text)
            _detail.update {
                it.copy(
                    reviewing = false,
                    review = when (outcome) {
                        is AiOutcome.Answer -> outcome.text
                        is AiOutcome.Edits -> outcome.explanation
                        is AiOutcome.Failure -> "Review failed: ${outcome.reason}"
                    },
                )
            }
        }
    }

    fun clearReview() {
        _detail.update { it.copy(review = null) }
    }

    fun clearError() {
        _detail.update { it.copy(error = null) }
    }
}
