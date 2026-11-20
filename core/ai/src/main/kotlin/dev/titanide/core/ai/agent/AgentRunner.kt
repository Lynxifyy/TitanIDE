package dev.titanide.core.ai.agent

import dev.titanide.core.ai.AiContext
import dev.titanide.core.ai.AiProvider
import dev.titanide.core.ai.ChatMessage
import dev.titanide.core.ai.ChatRequest
import dev.titanide.core.ai.ChatResult
import dev.titanide.core.ai.Prompts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * A capability the agent can invoke. Tools return plain text describing what actually
 * happened; a tool must never report success it did not observe.
 */
interface AgentTool {
    val name: String
    val description: String
    suspend fun invoke(arguments: JsonObject): ToolResult
}

data class ToolResult(val ok: Boolean, val output: String)

/** Executes tools on behalf of the agent, enforcing a whitelist and an approval gate. */
class AgentToolbox(
    private val tools: List<AgentTool>,
    private val approvalGate: suspend (tool: String, args: JsonObject) -> Boolean = { _, _ -> true },
) {
    private val byName = tools.associateBy { it.name }

    fun descriptors(): List<String> = tools.map { "${it.name}: ${it.description}" }

    fun names(): Set<String> = byName.keys

    suspend fun invoke(name: String, args: JsonObject): ToolResult {
        val tool = byName[name] ?: return ToolResult(false, "Unknown tool '$name'")
        if (!approvalGate(name, args)) {
            return ToolResult(false, "The user declined to run '$name'")
        }
        return runCatching { tool.invoke(args) }
            .getOrElse { ToolResult(false, "Tool '$name' threw: ${it.message}") }
    }
}

sealed interface AgentEvent {
    data class Step(val index: Int, val tool: String, val args: String) : AgentEvent
    data class Observation(val index: Int, val ok: Boolean, val output: String) : AgentEvent
    data class Thinking(val text: String) : AgentEvent
    data class Completed(val summary: String, val verified: Boolean) : AgentEvent
    data class Failed(val reason: String) : AgentEvent
    data class Blocked(val reason: String) : AgentEvent
}

data class AgentState(
    val running: Boolean = false,
    val goal: String = "",
    val steps: List<AgentEvent> = emptyList(),
    val stepCount: Int = 0,
)

/**
 * The autonomous loop: plan, act, observe, repeat — then verify.
 *
 * The loop deliberately refuses to declare success unless a verification tool (build or
 * test) reported success. That is the difference between an agent that reports work and
 * one that reports *verified* work.
 */
class AgentRunner(
    private val scope: CoroutineScope,
    private val provider: () -> AiProvider,
    private val model: String,
    private val toolbox: AgentToolbox,
    private val maxSteps: Int = 12,
) {

    private val _state = MutableStateFlow(AgentState())
    val state: StateFlow<AgentState> = _state.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true }

    private val verificationTools = setOf("run_build", "run_tests")

    fun start(goal: String, context: AiContext?) {
        if (_state.value.running) return
        _state.value = AgentState(running = true, goal = goal)
        scope.launch {
            runLoop(goal, context)
        }
    }

    fun cancel() {
        _state.value = _state.value.copy(running = false)
    }

    private suspend fun runLoop(goal: String, context: AiContext?) {
        val history = ArrayList<String>()
        var verificationPassed = false
        var verificationAttempted = false

        for (step in 1..maxSteps) {
            if (!_state.value.running) {
                emit(AgentEvent.Failed("Cancelled by the user"))
                return
            }

            val messages = Prompts.agentStep(goal, context, history, toolbox.descriptors())
            val request = ChatRequest(model = model, messages = messages, temperature = 0.1)

            val reply = when (val result = provider().chat(request)) {
                is ChatResult.Success -> result.response.content
                is ChatResult.Failure -> {
                    emit(AgentEvent.Failed("Model call failed: ${result.reason}"))
                    _state.value = _state.value.copy(running = false)
                    return
                }
            }

            emit(AgentEvent.Thinking(reply.take(600)))

            if (reply.contains("DONE:", ignoreCase = true)) {
                val summary = reply.substringAfter("DONE:", "").trim().ifBlank { "Goal reported complete" }
                if (!verificationPassed) {
                    emit(
                        AgentEvent.Blocked(
                            "The agent stopped without a successful build or test run. " +
                                "Nothing is verified. Summary it gave: $summary",
                        ),
                    )
                } else {
                    emit(AgentEvent.Completed(summary, verified = true))
                }
                _state.value = _state.value.copy(running = false)
                return
            }

            val toolName = parseField(reply, "TOOL")
            val argsText = parseField(reply, "ARGS")

            if (toolName == null) {
                history += "Step $step: the model replied without a tool call. Replying with guidance."
                if (step == maxSteps) break
                continue
            }

            val args = runCatching {
                if (argsText.isNullOrBlank()) JsonObject(emptyMap())
                else json.parseToJsonElement(argsText).jsonObject
            }.getOrElse {
                emit(AgentEvent.Observation(step, false, "Arguments were not valid JSON: $argsText"))
                history += "Step $step: $toolName had malformed JSON arguments."
                continue
            }

            emit(AgentEvent.Step(step, toolName, args.toString()))
            val result = toolbox.invoke(toolName, args)
            emit(AgentEvent.Observation(step, result.ok, result.output))

            if (toolName in verificationTools) {
                verificationAttempted = true
                if (result.ok) verificationPassed = true
            }

            history += "Step $step: called $toolName -> ${if (result.ok) "success" else "failure"}: " +
                result.output.lineSequence().take(6).joinToString(" ").take(400)

            _state.value = _state.value.copy(stepCount = step)
            delay(50)
        }

        val reason = when {
            verificationPassed -> "The agent reached the step limit after a successful verification."
            verificationAttempted -> "The agent reached the step limit without a passing build or test."
            else -> "The agent reached the step limit without ever running a build or test."
        }
        emit(AgentEvent.Blocked(reason))
        _state.value = _state.value.copy(running = false)
    }

    private fun emit(event: AgentEvent) {
        _state.value = _state.value.copy(steps = _state.value.steps + event)
    }

    private fun parseField(text: String, field: String): String? {
        val line = text.lineSequence().firstOrNull { it.trimStart().startsWith("$field:", ignoreCase = true) }
            ?: return null
        return line.substringAfter(':').trim().ifBlank { null }
    }
}

/** Helpers for reading tool arguments without throwing on unexpected shapes. */
object ToolArgs {
    fun string(args: JsonObject, key: String): String? =
        (args[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    fun int(args: JsonObject, key: String): Int? =
        (args[key] as? JsonPrimitive)?.content?.toIntOrNull()
}
