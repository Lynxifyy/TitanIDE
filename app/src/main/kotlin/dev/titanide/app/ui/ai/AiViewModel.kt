package dev.titanide.app.ui.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.titanide.app.di.TitanContainer
import dev.titanide.core.ai.AiMode
import dev.titanide.core.ai.AiOutcome
import dev.titanide.core.ai.ChatMessage
import dev.titanide.core.ai.ProposedEdit
import dev.titanide.core.ai.agent.AgentEvent
import dev.titanide.core.ai.agent.AgentState
import dev.titanide.core.model.BuildKind
import dev.titanide.core.model.BuildRequest
import dev.titanide.core.model.ExecutionTarget
import dev.titanide.core.model.Project
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ChatEntry(
    val role: ChatMessage.Role,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
)

data class AiUiState(
    val projects: List<Project> = emptyList(),
    val project: Project? = null,
    val mode: AiMode = AiMode.CHAT,
    val input: String = "",
    val conversation: List<ChatEntry> = emptyList(),
    val busy: Boolean = false,
    val includeProjectContext: Boolean = false,
    val pendingEdits: List<ProposedEdit> = emptyList(),
    val pendingExplanation: String = "",
    val agent: AgentState? = null,
    val canRevert: Boolean = false,
    val message: String? = null,
    val providerConfigured: Boolean = false,
    val providerName: String = "",
)

class AiViewModel(private val container: TitanContainer) : ViewModel() {

    private val _state = MutableStateFlow(AiUiState())
    val state: StateFlow<AiUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            container.projectRepository.observeProjects().collect { projects ->
                val project = _state.value.project ?: projects.maxByOrNull { it.lastOpenedAt }
                _state.value = _state.value.copy(projects = projects, project = project)
            }
        }
        viewModelScope.launch {
            container.settingsState.collect { settings ->
                val ai = settings?.ai
                _state.value = _state.value.copy(
                    includeProjectContext = ai?.allowProjectContext ?: false,
                    providerConfigured = ai != null && ai.providerId != "none" && ai.providerId.isNotBlank(),
                    providerName = when (ai?.providerId) {
                        "anthropic" -> "Anthropic"
                        "openai_compatible", "openai" -> "OpenAI-compatible"
                        null, "none" -> "Not configured"
                        else -> ai.providerId
                    },
                )
            }
        }
        viewModelScope.launch {
            container.intelligence.agentState.collect { agent ->
                _state.value = _state.value.copy(agent = agent)
            }
        }
        viewModelScope.launch {
            container.intelligence.busy.collect { busy ->
                _state.value = _state.value.copy(busy = busy)
            }
        }
    }

    fun selectProject(projectId: String) {
        viewModelScope.launch {
            val project = container.projectRepository.getProject(projectId) ?: return@launch
            _state.value = _state.value.copy(project = project, conversation = emptyList(), pendingEdits = emptyList())
        }
    }

    fun setMode(mode: AiMode) {
        _state.value = _state.value.copy(mode = mode)
    }

    fun setInput(text: String) {
        _state.value = _state.value.copy(input = text)
    }

    fun toggleContext() {
        _state.value = _state.value.copy(includeProjectContext = !_state.value.includeProjectContext)
    }

    /** Sends the current input in the selected mode. */
    fun send() {
        val project = _state.value.project
        val input = _state.value.input.trim()
        if (project == null || input.isEmpty() || _state.value.busy) return

        val mode = _state.value.mode
        val history = _state.value.conversation + ChatEntry(ChatMessage.Role.USER, input)
        _state.value = _state.value.copy(input = "", conversation = history, busy = true, message = null)

        viewModelScope.launch {
            val outcome = when (mode) {
                AiMode.CHAT -> container.intelligence.chat(
                    project,
                    history.map { ChatMessage(it.role, it.text) },
                    _state.value.includeProjectContext,
                )
                AiMode.AGENT -> {
                    startAgent(project, input)
                    return@launch
                }
                AiMode.EXPLAIN, AiMode.INLINE_EDIT, AiMode.REFACTOR, AiMode.DEBUG -> {
                    val snapshot = container.activeFile.current.value
                    val selection = snapshot.selection
                    val active = activeFile()
                    if (active == null) {
                        _state.value = _state.value.copy(
                            busy = false,
                            conversation = history + ChatEntry(
                                ChatMessage.Role.ASSISTANT,
                                "Open a file in the editor first: ${mode.label} works on the active file.",
                            ),
                        )
                        return@launch
                    }
                    val code = selection ?: active.second
                    when (mode) {
                        AiMode.EXPLAIN -> container.intelligence.explain(
                            project,
                            code,
                            snapshot.language,
                            _state.value.includeProjectContext,
                        )
                        AiMode.INLINE_EDIT -> container.intelligence.inlineEdit(
                            project,
                            input,
                            code,
                            snapshot.language,
                        )
                        AiMode.REFACTOR -> container.intelligence.refactor(project, code, snapshot.language, input)
                        AiMode.DEBUG -> container.intelligence.fixFailure(
                            project,
                            failure = input,
                            logs = container.buildEngine.lastResult.value?.logLines.orEmpty(),
                            filePath = active.third,
                            fileContent = active.second,
                            diagnostics = container.buildEngine.lastResult.value?.diagnostics.orEmpty(),
                        )
                        else -> error("unreachable")
                    }
                }
            }

            when (outcome) {
                is AiOutcome.Answer -> {
                    _state.value = _state.value.copy(
                        busy = false,
                        conversation = history + ChatEntry(ChatMessage.Role.ASSISTANT, outcome.text),
                        pendingEdits = emptyList(),
                    )
                }
                is AiOutcome.Edits -> {
                    _state.value = _state.value.copy(
                        busy = false,
                        conversation = history + ChatEntry(
                            ChatMessage.Role.ASSISTANT,
                            outcome.explanation.ifBlank { "Proposed ${outcome.edits.size} file change(s)." },
                        ),
                        pendingEdits = outcome.edits,
                        pendingExplanation = outcome.explanation,
                    )
                }
                is AiOutcome.Failure -> {
                    _state.value = _state.value.copy(
                        busy = false,
                        conversation = history + ChatEntry(ChatMessage.Role.ASSISTANT, "Error: ${outcome.reason}"),
                    )
                }
            }
        }
    }

    private fun startAgent(project: Project, goal: String) {
        container.intelligence.startAgent(
            project = project,
            goal = goal,
            buildRunner = { request -> container.buildEngine.runAndWait(request) },
            approveTool = { tool, _ ->
                // Destructive tools require explicit confirmation from the UI; reads and
                // builds are allowed so the agent can make progress unattended.
                tool in setOf("list_files", "read_file", "search_code", "run_build", "run_tests")
            },
        )
        _state.value = _state.value.copy(busy = false)
    }

    fun stopAgent() {
        container.intelligence.cancelAgent()
    }

    /** Applies every pending edit; the applier keeps the previous content for revert. */
    fun applyPendingEdits() {
        val project = _state.value.project ?: return
        val edits = _state.value.pendingEdits
        if (edits.isEmpty()) return
        viewModelScope.launch {
            var applied = 0
            var failure: String? = null
            edits.forEach { edit ->
                val result = container.intelligence.editApplier.apply(project, edit)
                if (result.isSuccess) {
                    applied++
                } else {
                    failure = result.exceptionOrNull()?.message
                }
            }
            _state.value = _state.value.copy(
                pendingEdits = emptyList(),
                canRevert = container.intelligence.editApplier.canRevert(project),
                message = if (failure == null) {
                    "Applied $applied file change(s)"
                } else {
                    "Applied $applied change(s), then failed: $failure"
                },
            )
        }
    }

    fun discardPendingEdits() {
        _state.value = _state.value.copy(pendingEdits = emptyList(), message = "Proposed changes discarded")
    }

    fun revertLast() {
        val project = _state.value.project ?: return
        viewModelScope.launch {
            val result = container.intelligence.editApplier.revertLast(project)
            _state.value = _state.value.copy(
                message = result.fold(
                    onSuccess = { "Reverted $it" },
                    onFailure = { "Revert failed: ${it.message}" },
                ),
                canRevert = container.intelligence.editApplier.canRevert(project),
            )
        }
    }

    fun runTests() {
        val project = _state.value.project ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(message = "Running tests...")
            val result = container.buildEngine.runAndWait(
                BuildRequest(
                    projectId = project.id,
                    projectPath = project.rootPath,
                    kind = BuildKind.TEST,
                    target = ExecutionTarget.LOCAL,
                ),
            )
            _state.value = _state.value.copy(
                message = "Tests finished: ${result.status} ${result.testSummary?.let { "(${it.passed} passed, ${it.failed} failed)" }.orEmpty()}",
            )
        }
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null)
    }

    fun newConversation() {
        _state.value = _state.value.copy(conversation = emptyList(), pendingEdits = emptyList())
    }

    private fun activeFile(): Triple<String, String, String>? {
        val snapshot = container.activeFile.current.value
        if (!snapshot.hasFile) return null
        return Triple(snapshot.path.orEmpty(), snapshot.content, snapshot.path.orEmpty())
    }
}
