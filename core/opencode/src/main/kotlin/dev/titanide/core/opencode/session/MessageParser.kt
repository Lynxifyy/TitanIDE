package dev.titanide.core.opencode.session

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * A single thing the agent did or said, flattened out of `Session.Message.Info`.
 *
 * The server models an assistant turn as a list of content parts. TITAN's activity feed
 * is a projection of that list, so what the user sees is always something the server
 * actually reported — there is no client-side invention of "steps".
 */
sealed interface AgentActivity {
    val messageId: String
    val at: Long

    data class UserMessage(
        override val messageId: String,
        override val at: Long,
        val text: String,
    ) : AgentActivity

    data class AssistantText(
        override val messageId: String,
        override val at: Long,
        val text: String,
        val streaming: Boolean,
    ) : AgentActivity

    data class Reasoning(
        override val messageId: String,
        override val at: Long,
        val text: String,
    ) : AgentActivity

    /** A tool call. [state] is the server's own state discriminator. */
    data class ToolCall(
        override val messageId: String,
        override val at: Long,
        val toolId: String,
        val name: String,
        val state: String,
        val input: String?,
        val output: String?,
        val error: String?,
    ) : AgentActivity {
        val isRunning: Boolean get() = state == "running" || state == "streaming"
        val isError: Boolean get() = state == "error"
        val isDone: Boolean get() = state == "completed"
    }

    /** A shell command the agent ran, with its real output. */
    data class ShellCommand(
        override val messageId: String,
        override val at: Long,
        val command: String,
        val status: String,
        val exit: Int?,
        val output: String,
    ) : AgentActivity

    /** Anything the spec defines that TITAN does not render specially yet. */
    data class Other(
        override val messageId: String,
        override val at: Long,
        val type: String,
    ) : AgentActivity
}

/**
 * Turns raw `Session.Message.Info` JSON into [AgentActivity].
 *
 * Written by hand against the published schema rather than with polymorphic
 * serialization, for one practical reason: the server adds message `type`s over time, and
 * a `@Serializable sealed` hierarchy would throw on any unknown discriminator and blank
 * out the whole conversation. Here an unknown type becomes [AgentActivity.Other] and the
 * rest of the transcript still renders.
 */
object MessageParser {

    fun parseAll(messages: List<JsonObject>): List<AgentActivity> =
        messages.flatMap { parse(it) }

    fun parse(message: JsonObject): List<AgentActivity> {
        val id = message.str("id") ?: return emptyList()
        val type = message.str("type") ?: return emptyList()
        val at = message["time"]?.let { t ->
            (t as? JsonObject)?.get("created")?.asLong()
        } ?: 0L

        return when (type) {
            "user" -> listOf(AgentActivity.UserMessage(id, at, message.str("text").orEmpty()))

            "assistant" -> parseAssistant(id, at, message)

            "shell" -> listOf(
                AgentActivity.ShellCommand(
                    messageId = id,
                    at = at,
                    command = message.str("command").orEmpty(),
                    status = message.str("status") ?: "running",
                    exit = message["exit"]?.asLong()?.toInt(),
                    output = message["output"]?.let { out ->
                        (out as? JsonObject)?.str("output")
                    }.orEmpty(),
                ),
            )

            "reasoning" -> listOf(AgentActivity.Reasoning(id, at, message.str("text").orEmpty()))

            else -> listOf(AgentActivity.Other(id, at, type))
        }
    }

    private fun parseAssistant(id: String, at: Long, message: JsonObject): List<AgentActivity> {
        val completed = message["time"]?.let { (it as? JsonObject)?.get("completed") } != null
        val parts = message["content"]?.let { runCatching { it.jsonArray }.getOrNull() } ?: return emptyList()

        return parts.mapNotNull { element ->
            val part = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
            when (part.str("type")) {
                "text" -> AgentActivity.AssistantText(
                    messageId = id,
                    at = at,
                    text = part.str("text").orEmpty(),
                    streaming = !completed,
                )

                "reasoning" -> AgentActivity.Reasoning(id, at, part.str("text").orEmpty())

                "tool" -> parseTool(id, at, part)

                else -> null
            }
        }
    }

    private fun parseTool(id: String, at: Long, part: JsonObject): AgentActivity.ToolCall {
        val stateObj = part["state"] as? JsonObject
        val stateName = stateObj?.str("status")
            ?: stateObj?.str("type")
            ?: "unknown"

        // Tool arguments and results live under different keys per state in the spec
        // (Streaming/Running/Completed/Error). Search the known shapes rather than
        // assuming one.
        val input = stateObj?.let { s ->
            s.str("input") ?: s["input"]?.toString() ?: s["args"]?.toString()
        }
        val output = stateObj?.let { s ->
            s.str("output") ?: s["output"]?.toString() ?: s["result"]?.toString()
        }
        val error = stateObj?.let { s ->
            s.str("error") ?: s["error"]?.toString()
        }

        return AgentActivity.ToolCall(
            messageId = id,
            at = at,
            toolId = part.str("id").orEmpty(),
            name = part.str("name") ?: "tool",
            state = stateName,
            input = input,
            output = output,
            error = error,
        )
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun kotlinx.serialization.json.JsonElement.asLong(): Long? =
        (this as? JsonPrimitive)?.let { it.longOrNull ?: it.intOrNull?.toLong() ?: it.contentOrNull?.toLongOrNull() }
}

/** Compact projection used by the UI to render one line of agent status. */
data class AgentStatusLine(
    val label: String,
    val detail: String,
    val kind: Kind,
) {
    enum class Kind { INFO, TOOL, SHELL, ERROR, DONE }

    companion object {
        fun of(activity: AgentActivity): AgentStatusLine = when (activity) {
            is AgentActivity.ToolCall -> AgentStatusLine(
                label = activity.name,
                detail = when {
                    activity.isError -> activity.error ?: "failed"
                    activity.isRunning -> "running"
                    else -> activity.output?.lineSequence()?.firstOrNull()?.take(80) ?: "done"
                },
                kind = if (activity.isError) Kind.ERROR else Kind.TOOL,
            )

            is AgentActivity.ShellCommand -> AgentStatusLine(
                label = "$ ${activity.command}",
                detail = when (activity.status) {
                    "exited" -> "exit ${activity.exit ?: "?"}"
                    else -> activity.status
                },
                kind = if (activity.exit != null && activity.exit != 0) Kind.ERROR else Kind.SHELL,
            )

            is AgentActivity.AssistantText -> AgentStatusLine("assistant", activity.text.take(80), Kind.INFO)
            is AgentActivity.Reasoning -> AgentStatusLine("thinking", activity.text.take(80), Kind.INFO)
            is AgentActivity.UserMessage -> AgentStatusLine("you", activity.text.take(80), Kind.INFO)
            is AgentActivity.Other -> AgentStatusLine(activity.type, "", Kind.INFO)
        }
    }
}
