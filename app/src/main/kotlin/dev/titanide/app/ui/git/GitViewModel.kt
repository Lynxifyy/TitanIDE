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
            _state.value = _state.value.copy(project = project, hasToken = container.isGitHubSignedIn())
            project?.let { refresh() }
        }
    }

    private fun root(): File? = _state.value.project?.let { File(it.rootPath) }

    fun refresh() {
        val project = _state.value.project ?: return
        val root = File(project.rootPath)
        viewModelScope.launch {
            if (!container.gitService.isRepository(root)) {
                _state.value = _state.value.copy(isRepository = false)
                return@launch
            }
            val branch = container.gitService.currentBranch(root)
            val status = (container.gitService.status(root) as? Outcome.Ok)?.value.orEmpty()
            val commits = (container.gitService.log(root, 30) as? Outcome.Ok)?.value.orEmpty()
            val branches = (container.gitService.branches(root) as? Outcome.Ok)?.value.orEmpty()
            val diff = (container.gitService.diff(root, _state.value.stagedDiff) as? Outcome.Ok)?.value.orEmpty()
            _state.value = _state.value.copy(
                isRepository = true,
                branch = branch,
                status = status,
                commits = commits,
                branches = branches,
                diff = diff,
            )
        }
    }

    fun initRepository() {
        val root = root() ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            when (val outcome = container.gitService.init(root)) {
                is Outcome.Ok -> {
                    _state.value = _state.value.copy(busy = false, message = "Repository initialised")
                    refresh()
                }
                is Outcome.Failed -> _state.value = _state.value.copy(busy = false, error = outcome.reason)
            }
        }
    }

    fun setCommitMessage(text: String) {
        _state.value = _state.value.copy(commitMessage = text)
    }

    fun setNewBranchName(text: String) {
        _state.value = _state.value.copy(newBranchName = text)
    }

    fun toggleStagedDiff() {
        _state.value = _state.value.copy(stagedDiff = !_state.value.stagedDiff)
        refresh()
    }

    fun stage(path: String) {
        val root = root() ?: return
        viewModelScope.launch {
            container.gitService.stage(root, listOf(path))
            refresh()
        }
    }

    fun unstage(path: String) {
        val root = root() ?: return
        viewModelScope.launch {
            container.gitService.unstage(root, listOf(path))
            refresh()
        }
    }

    fun stageAll() {
        val root = root() ?: return
        val paths = _state.value.status.map { it.path }
        if (paths.isEmpty()) return
        viewModelScope.launch {
            container.gitService.stage(root, paths)
            refresh()
        }
    }

    fun commit() {
        val root = root() ?: return
        val message = _state.value.commitMessage.trim()
        if (message.isEmpty()) {
            _state.value = _state.value.copy(error = "A commit message is required")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null)
            when (val outcome = container.gitService.commit(root, message, null, null)) {
                is Outcome.Ok -> {
                    _state.value = _state.value.copy(
                        busy = false,
                        commitMessage = "",
                        message = "Committed ${outcome.value.take(7)}",
                    )
                    refresh()
                }
                is Outcome.Failed -> _state.value = _state.value.copy(busy = false, error = outcome.reason)
            }
        }
    }

    fun push() {
        val root = root() ?: return
        val token = container.githubTokenOrNull()
        if (token.isNullOrBlank()) {
            _state.value = _state.value.copy(error = "Sign in to GitHub or store a token before pushing")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null)
            when (val outcome = container.gitService.push(root, token)) {
                is Outcome.Ok -> _state.value = _state.value.copy(busy = false, message = "Pushed to origin")
                is Outcome.Failed -> _state.value = _state.value.copy(busy = false, error = outcome.reason)
            }
        }
    }

    fun pull() {
        val root = root() ?: return
        val token = container.githubTokenOrNull()
        if (token.isNullOrBlank()) {
            _state.value = _state.value.copy(error = "Sign in to GitHub before pulling")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null)
            when (val outcome = container.gitService.pull(root, token)) {
                is Outcome.Ok -> {
                    _state.value = _state.value.copy(busy = false, message = "Pulled from origin")
                    refresh()
                }
                is Outcome.Failed -> _state.value = _state.value.copy(busy = false, error = outcome.reason)
            }
        }
    }

    fun createBranch() {
        val root = root() ?: return
        val name = _state.value.newBranchName.trim()
        if (name.isEmpty()) {
            _state.value = _state.value.copy(error = "Branch name is required")
            return
        }
        viewModelScope.launch {
            when (val outcome = container.gitService.createBranch(root, name)) {
                is Outcome.Ok -> {
                    _state.value = _state.value.copy(newBranchName = "", message = "Switched to $name")
                    container.registerBranch(projectId, name)
                    refresh()
                }
                is Outcome.Failed -> _state.value = _state.value.copy(error = outcome.reason)
            }
        }
    }

    fun checkout(name: String) {
        val root = root() ?: return
        viewModelScope.launch {
            when (val outcome = container.gitService.checkout(root, name)) {
                is Outcome.Ok -> {
                    _state.value = _state.value.copy(message = "Checked out $name")
                    container.registerBranch(projectId, name)
                    refresh()
                }
                is Outcome.Failed -> _state.value = _state.value.copy(error = outcome.reason)
            }
        }
    }

    fun clearFeedback() {
        _state.value = _state.value.copy(message = null, error = null)
    }
}
