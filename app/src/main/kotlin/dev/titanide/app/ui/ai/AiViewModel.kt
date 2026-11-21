package dev.titanide.app.ui.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.titanide.app.di.TitanContainer
import dev.titanide.app.extensions.TitanCapability
import dev.titanide.core.ai.AiMode
import dev.titanide.core.ai.AiOutcome
import dev.titanide.core.ai.ChatMessage
import dev.titanide.core.ai.ProposedEdit
import dev.titanide.core.ai.agent.AgentEvent
import dev.titanide.core.ai.agent.AgentState
import dev.titanide.core.ai.agent.ToolPolicy
import dev.titanide.core.model.BuildKind
import dev.titanide.core.model.BuildRequest
import dev.titanide.core.model.ExecutionTarget
import dev.titanide.core.model.Project
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ChatEntry(
    val role: ChatMessage.Role,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
)

/** A tool call the agent is waiting on the user to allow. */
data class PendingApproval(
    val tool: String,
    val args: String,
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
    val pendingApproval: PendingApproval? = null,
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
        if (!container.capabilityGate.isEnabled(TitanCapability.AI)) {
            _state.value = _state.value.copy(
                message = "${TitanCapability.AI.label} is switched off in Extensions",
            )
            return
        }

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
            approveTool = { tool, args -> awaitToolApproval(tool, args) },
        )
        _state.value = _state.value.copy(busy = false)
    }

    /**
     * Blocks the agent until the user answers.
     *
     * Reads and builds run unattended, which is what makes an autonomous mode worth having.
     * A tool that changes the project waits instead: the point of watching the agent is lost
     * if a wrong edit lands unseen, and the buffer reload below makes the change visible the
     * moment it is allowed.
     */
    suspend fun awaitToolApproval(tool: String, args: kotlinx.serialization.json.JsonObject): Boolean {
        if (!ToolPolicy.requiresApproval(tool)) return true
        val answer = CompletableDeferred<Boolean>()
        approval = answer
        _state.value = _state.value.copy(pendingApproval = PendingApproval(tool, args.toString().take(400)))
        return try {
            answer.await()
        } finally {
            approval = null
            _state.value = _state.value.copy(pendingApproval = null)
        }
    }

    fun approvePendingTool() {
        approval?.complete(true)
    }

    fun denyPendingTool() {
        approval?.complete(false)
    }

    fun stopAgent() {
        // Release a blocked approval first, or the agent coroutine never returns.
        approval?.complete(false)
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

    /** Generates tests for the active file. */
    fun generateTestsForActiveFile() {
        runOnActiveFile("Generate tests") { project, code, language, path ->
            container.intelligence.generateTests(project, code, language, path)
        }
    }

    /** Audits the active file for security problems. */
    fun auditActiveFile() {
        runOnActiveFile("Security audit") { project, code, language, _ ->
            container.intelligence.securityAudit(project, code, language)
        }
    }

    /**
     * Runs [action] on the active file's selection, or the whole file when nothing is selected,
     * and reports the outcome in the conversation. Without a file there is nothing to send, so
     * the reason is stated rather than a request being made with an empty document.
     */
    private fun runOnActiveFile(
        label: String,
        action: suspend (Project, String, String?, String?) -> AiOutcome,
    ) {
        val project = _state.value.project ?: return
        if (_state.value.busy) return
        val snapshot = container.activeFile.current.value
        if (!snapshot.hasFile) {
            _state.value = _state.value.copy(
                message = "$label needs a file open in the editor",
            )
            return
        }
        val code = snapshot.selection ?: snapshot.content
        if (code.isBlank()) {
            _state.value = _state.value.copy(message = "$label needs a non-empty file or selection")
            return
        }
        val prompt = ChatEntry(ChatMessage.Role.USER, "$label: ${snapshot.path.orEmpty()}")
        _state.value = _state.value.copy(
            busy = true,
            conversation = _state.value.conversation + prompt,
            message = null,
        )
        viewModelScope.launch {
            val outcome = action(project, code, snapshot.language, snapshot.path)
            _state.value = _state.value.copy(
                busy = false,
                conversation = _state.value.conversation + outcomeToEntry(outcome),
                pendingEdits = (outcome as? AiOutcome.Edits)?.edits ?: emptyList(),
            )
        }
    }

    private fun outcomeToEntry(outcome: AiOutcome): ChatEntry = when (outcome) {
        is AiOutcome.Answer -> ChatEntry(ChatMessage.Role.ASSISTANT, outcome.text)
        is AiOutcome.Edits -> ChatEntry(
            ChatMessage.Role.ASSISTANT,
            outcome.explanation.ifBlank { "Proposed ${outcome.edits.size} file change(s)." },
        )
        is AiOutcome.Failure -> ChatEntry(ChatMessage.Role.ASSISTANT, "Error: ${outcome.reason}")
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null)
    }

    fun newConversation() {
        _state.value = _state.value.copy(conversation = emptyList(), pendingEdits = emptyList())
    }

    private var approval: CompletableDeferred<Boolean>? = null

    private fun activeFile(): Triple<String, String, String>? {
        val snapshot = container.activeFile.current.value
        if (!snapshot.hasFile) return null
        return Triple(snapshot.path.orEmpty(), snapshot.content, snapshot.path.orEmpty())
    }
}
