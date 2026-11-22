package dev.titanide.core.ai

import dev.titanide.core.ai.agent.AgentRunner
import dev.titanide.core.ai.agent.AgentState
import dev.titanide.core.ai.agent.AgentToolbox
import dev.titanide.core.ai.agent.ProjectTools
import dev.titanide.core.ai.context.ContextBuilder
import dev.titanide.core.data.files.SafeFileSystem
import dev.titanide.core.data.repo.FileTreeRepository
import dev.titanide.core.model.BuildResult
import dev.titanide.core.model.Diagnostic
import dev.titanide.core.model.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What a single AI interaction produced. */
sealed interface AiOutcome {
    data class Answer(val text: String, val tokens: Int) : AiOutcome
    data class Edits(val explanation: String, val edits: List<ProposedEdit>, val raw: String) : AiOutcome
    data class Failure(val reason: String) : AiOutcome
}

/**
 * Applies an approved edit, keeping the previous content so the user can revert. Reverting
 * is a real restore of the stored text, not an undo of an in-memory buffer.
 */
class EditApplier(
    private val files: FileTreeRepository,
    private val fs: SafeFileSystem = SafeFileSystem(),
) {
    private data class Applied(val project: Project, val path: String, val previous: String?)

    private val history = ArrayList<Applied>()

    suspend fun apply(project: Project, edit: ProposedEdit): Result<Unit> {
        val previous = when (val read = files.read(project, edit.filePath)) {
            is SafeFileSystem.Result.Ok -> read.value
            is SafeFileSystem.Result.Failure -> null
        }
        return when (val write = files.write(project, edit.filePath, edit.newContent)) {
            is SafeFileSystem.Result.Ok -> {
                history += Applied(project, edit.filePath, previous)
                Result.success(Unit)
            }
            is SafeFileSystem.Result.Failure -> Result.failure(IllegalStateException(write.reason))
        }
    }

    /** Reverts the most recent applied edit for [project]; returns the reverted path. */
    suspend fun revertLast(project: Project): Result<String> {
        val index = history.indexOfLast { it.project.id == project.id }
        if (index < 0) return Result.failure(IllegalStateException("Nothing to revert"))
        val applied = history[index]
        val outcome = if (applied.previous == null) {
            files.delete(project, applied.path)
        } else {
            files.write(project, applied.path, applied.previous)
        }
        return when (outcome) {
            is SafeFileSystem.Result.Ok -> {
                history.removeAt(index)
                Result.success(applied.path)
            }
            is SafeFileSystem.Result.Failure -> Result.failure(IllegalStateException(outcome.reason))
        }
    }

    fun canRevert(project: Project): Boolean = history.any { it.project.id == project.id }
}

/**
 * TITAN Intelligence facade. It owns the provider selection, builds context, runs the
 * requested mode, and exposes the autonomous agent.
 */
class TitanIntelligence(
    private val scope: CoroutineScope,
    private val providerProvider: () -> AiProvider,
    private val modelProvider: () -> String,
    private val contextBuilder: ContextBuilder,
    private val files: FileTreeRepository,
    val editApplier: EditApplier,
) {

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _lastOutcome = MutableStateFlow<AiOutcome?>(null)
    val lastOutcome: StateFlow<AiOutcome?> = _lastOutcome.asStateFlow()

    private val _agentState = MutableStateFlow<AgentState?>(null)
    val agentState: StateFlow<AgentState?> = _agentState.asStateFlow()

    private var agentRunner: AgentRunner? = null

    suspend fun explain(project: Project, code: String, language: String?, includeContext: Boolean): AiOutcome =
        run(Prompts.explain(language, code, contextBuilder.build(project, includeContext)))

    suspend fun inlineEdit(
        project: Project,
        instruction: String,
        code: String,
        language: String?,
    ): AiOutcome = run(Prompts.inlineEdit(instruction, code, language), extractEdits = false)

    suspend fun refactor(project: Project, code: String, language: String?, goal: String?): AiOutcome =
        run(Prompts.refactor(code, language, goal), extractEdits = false)

    suspend fun generateTests(project: Project, code: String, language: String?, path: String?): AiOutcome =
        run(Prompts.generateTests(code, language, path))

    suspend fun securityAudit(project: Project, code: String, language: String?): AiOutcome =
        run(Prompts.securityAudit(code, language))

    suspend fun reviewPullRequest(project: Project, diff: String): AiOutcome =
        run(Prompts.reviewPullRequest(diff, contextBuilder.build(project, includeProjectWide = true)))

    suspend fun fixFailure(
        project: Project,
        failure: String,
        logs: List<String>,
        filePath: String?,
        fileContent: String?,
        diagnostics: List<Diagnostic>,
    ): AiOutcome {
        val context = contextBuilder.build(
            project,
            includeProjectWide = true,
            diagnostics = diagnostics,
        )
        return run(Prompts.debug(failure, logs, filePath, fileContent, context))
    }

    suspend fun chat(project: Project, history: List<ChatMessage>, includeContext: Boolean): AiOutcome {
        val context = if (includeContext) contextBuilder.build(project, includeProjectWide = true) else null
        val messages = buildList {
            add(ChatMessage(ChatMessage.Role.SYSTEM, Prompts.SYSTEM))
            if (context != null) add(ChatMessage(ChatMessage.Role.SYSTEM, context.render()))
            addAll(history)
        }
        return run(messages)
    }

    /** Starts the autonomous agent for [goal]. Progress is observable through [agentState]. */
    fun startAgent(
        project: Project,
        goal: String,
        buildRunner: suspend (dev.titanide.core.model.BuildRequest) -> BuildResult,
        approveTool: suspend (String, kotlinx.serialization.json.JsonObject) -> Boolean = { _, _ -> true },
    ) {
        if (agentRunner?.state?.value?.running == true) return
        val tools = ProjectTools(project, files, buildRunner)
        val toolbox = AgentToolbox(tools.all(), approveTool)
        val runner = AgentRunner(
            scope = scope,
            provider = providerProvider,
            // The runner is built per run, so the value current at start time is correct.
            model = modelProvider(),
            toolbox = toolbox,
        )
        agentRunner = runner
        _agentState.value = runner.state.value
        scope.launch {
            runner.state.collect { _agentState.value = it }
        }
        scope.launch {
            val context = contextBuilder.build(project, includeProjectWide = true)
            runner.start(goal, context)
        }
    }

    fun cancelAgent() {
        agentRunner?.cancel()
    }

    private suspend fun run(messages: List<ChatMessage>, extractEdits: Boolean = true): AiOutcome {
        _busy.value = true
        return try {
            val provider = providerProvider()
            val result = provider.chat(
                ChatRequest(
                    model = modelProvider().ifBlank { provider.models.firstOrNull().orEmpty() },
                    messages = messages,
                ),
            )
            val outcome = when (result) {
                is ChatResult.Failure -> AiOutcome.Failure(result.reason)
                is ChatResult.Success -> {
                    val text = result.response.content
                    if (extractEdits) {
                        val edits = EditExtractor.extract(text)
                        if (edits.isEmpty()) {
                            AiOutcome.Answer(text, result.response.totalTokens)
                        } else {
                            AiOutcome.Edits(
                                explanation = EditExtractor.proseBeforeBlocks(text),
                                edits = edits,
                                raw = text,
                            )
                        }
                    } else {
                        AiOutcome.Answer(text, result.response.totalTokens)
                    }
                }
            }
            _lastOutcome.value = outcome
            outcome
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // A provider may throw instead of returning a Failure. Letting it escape left the
            // caller with no outcome at all and its busy flag stuck, so the UI showed nothing
            // and stayed disabled. Report it as an ordinary failure instead.
            AiOutcome.Failure(e.message ?: e::class.java.simpleName).also { _lastOutcome.value = it }
        } finally {
            _busy.value = false
        }
    }
}

/**
 * Pulls file edits out of a model reply. The model is asked to fence each file with its
 * relative path; anything that does not carry a plausible path is ignored rather than
 * guessed at.
 */
object EditExtractor {

    private val FENCE = Regex("```([A-Za-z0-9_./-]*)\\n([\\s\\S]*?)```")

    fun extract(reply: String): List<ProposedEdit> {
        val edits = ArrayList<ProposedEdit>()
        FENCE.findAll(reply).forEach { match ->
            val info = match.groupValues[1].trim()
            val body = match.groupValues[2]
            if (!looksLikePath(info)) return@forEach
            edits += ProposedEdit(
                filePath = info,
                originalContent = null,
                newContent = body,
                explanation = "",
                isNewFile = true,
            )
        }
        return edits
    }

    fun proseBeforeBlocks(reply: String): String {
        val firstFence = reply.indexOf("```")
        return if (firstFence < 0) reply.trim() else reply.substring(0, firstFence).trim()
    }

    private fun looksLikePath(info: String): Boolean {
        if (info.isBlank()) return false
        if (info.contains(' ')) return false
        if (!info.contains('.')) return false
        val extension = info.substringAfterLast('.')
        if (extension.length !in 1..5) return false
        return extension.all { it.isLetterOrDigit() }
    }
}
