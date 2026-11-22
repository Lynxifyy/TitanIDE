package dev.titanide.app.ui.git

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.titanide.app.di.TitanContainer
import dev.titanide.core.git.local.GitRepositoryService.Outcome
import dev.titanide.core.model.GitBranch
import dev.titanide.core.model.GitCommit
import dev.titanide.core.model.GitDiffLine
import dev.titanide.core.model.GitStatusEntry
import dev.titanide.core.model.Project
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class GitUiState(
    val project: Project? = null,
    val isRepository: Boolean = false,
    val branch: String? = null,
    val branches: List<GitBranch> = emptyList(),
    val status: List<GitStatusEntry> = emptyList(),
    val commits: List<GitCommit> = emptyList(),
    val diff: List<GitDiffLine> = emptyList(),
    val stagedDiff: Boolean = false,
    val commitMessage: String = "",
    val busy: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    val hasToken: Boolean = false,
    val newBranchName: String = "",
)

class GitViewModel(
    private val container: TitanContainer,
    private val projectId: String,
) : ViewModel() {

    private val _state = MutableStateFlow(GitUiState())
    val state: StateFlow<GitUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val project = container.projectRepository.getProject(projectId)
            _state.update { it.copy(project = project, hasToken = container.isGitHubSignedIn()) }
            project?.let { refresh() }
        }
    }

    private fun root(): File? = _state.value.project?.let { File(it.rootPath) }

    /**
     * Resolves the repository root or reports why the action cannot run. Silently returning left
     * the screen unchanged, so a tap looked like it had done nothing at all.
     */
    private fun requireRoot(): File? {
        val root = root()
        if (root == null) feedback(error = "This project is still loading")
        return root
    }

    /**
     * Sets the feedback banner. Both fields are written every time, so a stale error cannot
     * linger next to a newer success message (and the other way round).
     */
    private fun feedback(message: String? = null, error: String? = null) {
        _state.update { it.copy(message = message, error = error) }
    }

    fun refresh() {
        val project = _state.value.project ?: return
        val root = File(project.rootPath)
        viewModelScope.launch {
            if (!container.gitService.isRepository(root)) {
                _state.update { it.copy(isRepository = false) }
                return@launch
            }
            val branch = container.gitService.currentBranch(root)
            val status = (container.gitService.status(root) as? Outcome.Ok)?.value.orEmpty()
            val commits = (container.gitService.log(root, 30) as? Outcome.Ok)?.value.orEmpty()
            val branches = (container.gitService.branches(root) as? Outcome.Ok)?.value.orEmpty()
            val diff = (container.gitService.diff(root, _state.value.stagedDiff) as? Outcome.Ok)?.value.orEmpty()
            _state.update {
                it.copy(
                    isRepository = true,
                    branch = branch,
                    status = status,
                    commits = commits,
                    branches = branches,
                    diff = diff,
                )
            }
        }
    }

    fun initRepository() {
        val root = requireRoot() ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            when (val outcome = container.gitService.init(root)) {
                is Outcome.Ok -> {
                    _state.update { it.copy(busy = false) }
                    feedback(message = "Repository initialised")
                    refresh()
                }
                is Outcome.Failed -> {
                    _state.update { it.copy(busy = false) }
                    feedback(error = outcome.reason)
                }
            }
        }
    }

    fun setCommitMessage(text: String) {
        _state.update { it.copy(commitMessage = text) }
    }

    fun setNewBranchName(text: String) {
        _state.update { it.copy(newBranchName = text) }
    }

    fun toggleStagedDiff() {
        _state.update { it.copy(stagedDiff = !it.stagedDiff) }
        refresh()
    }

    fun stage(path: String) = runStage(listOf(path))

    fun unstage(path: String) {
        val root = requireRoot() ?: return
        viewModelScope.launch {
            when (val outcome = container.gitService.unstage(root, listOf(path))) {
                is Outcome.Ok -> refresh()
                is Outcome.Failed -> feedback(error = outcome.reason)
            }
        }
    }

    fun stageAll() = runStage(_state.value.status.map { it.path })

    private fun runStage(paths: List<String>) {
        if (paths.isEmpty()) return
        val root = requireRoot() ?: return
        viewModelScope.launch {
            when (val outcome = container.gitService.stage(root, paths)) {
                is Outcome.Ok -> refresh()
                is Outcome.Failed -> feedback(error = outcome.reason)
            }
        }
    }

    fun commit() {
        val root = requireRoot() ?: return
        val message = _state.value.commitMessage.trim()
        if (message.isEmpty()) {
            feedback(error = "A commit message is required")
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            feedback()
            when (val outcome = container.gitService.commit(root, message, null, null)) {
                is Outcome.Ok -> {
                    _state.update { it.copy(busy = false, commitMessage = "") }
                    feedback(message = "Committed ${outcome.value.take(7)}")
                    refresh()
                }
                is Outcome.Failed -> {
                    _state.update { it.copy(busy = false) }
                    feedback(error = outcome.reason)
                }
            }
        }
    }

    fun push() {
        val root = requireRoot() ?: return
        val token = container.githubTokenOrNull()
        if (token.isNullOrBlank()) {
            feedback(error = "Sign in to GitHub or store a token before pushing")
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            feedback()
            when (val outcome = container.gitService.push(root, token)) {
                is Outcome.Ok -> {
                    _state.update { it.copy(busy = false) }
                    feedback(message = "Pushed to origin")
                }
                is Outcome.Failed -> {
                    _state.update { it.copy(busy = false) }
                    feedback(error = outcome.reason)
                }
            }
        }
    }

    fun pull() {
        val root = requireRoot() ?: return
        val token = container.githubTokenOrNull()
        if (token.isNullOrBlank()) {
            feedback(error = "Sign in to GitHub before pulling")
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            feedback()
            when (val outcome = container.gitService.pull(root, token)) {
                is Outcome.Ok -> {
                    _state.update { it.copy(busy = false) }
                    feedback(message = "Pulled from origin")
                    refresh()
                }
                is Outcome.Failed -> {
                    _state.update { it.copy(busy = false) }
                    feedback(error = outcome.reason)
                }
            }
        }
    }

    fun createBranch() {
        val root = requireRoot() ?: return
        val name = _state.value.newBranchName.trim()
        if (name.isEmpty()) {
            feedback(error = "Branch name is required")
            return
        }
        viewModelScope.launch {
            when (val outcome = container.gitService.createBranch(root, name)) {
                is Outcome.Ok -> {
                    _state.update { it.copy(newBranchName = "") }
                    feedback(message = "Switched to $name")
                    container.registerBranch(projectId, name)
                    refresh()
                }
                is Outcome.Failed -> feedback(error = outcome.reason)
            }
        }
    }

    fun checkout(name: String) {
        val root = requireRoot() ?: return
        viewModelScope.launch {
            when (val outcome = container.gitService.checkout(root, name)) {
                is Outcome.Ok -> {
                    feedback(message = "Checked out $name")
                    container.registerBranch(projectId, name)
                    refresh()
                }
                is Outcome.Failed -> feedback(error = outcome.reason)
            }
        }
    }

    /** Merges [name] into the current branch. Conflicts are reported, never swallowed. */
    fun merge(name: String) {
        val root = requireRoot() ?: return
        if (name == _state.value.branch) {
            feedback(error = "$name is already checked out")
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            feedback()
            when (val outcome = container.gitService.merge(root, name)) {
                is Outcome.Ok -> {
                    _state.update { it.copy(busy = false) }
                    feedback(message = "Merged $name into ${_state.value.branch}")
                    refresh()
                }
                is Outcome.Failed -> {
                    _state.update { it.copy(busy = false) }
                    feedback(error = outcome.reason)
                    refresh()
                }
            }
        }
    }

    fun clearFeedback() = feedback()
}
