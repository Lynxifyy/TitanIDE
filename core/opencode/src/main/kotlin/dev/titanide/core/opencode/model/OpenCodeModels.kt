package dev.titanide.core.opencode.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Wire models for the OpenCode HTTP API.
 *
 * Every type here mirrors `https://opencode.ai/docs/openapi.json` (title `opencode
 * HttpApi`, version 0.0.1). Field names and the `data`/`location` envelopes are taken
 * from that spec, so nothing in this file is guessed. Where the spec marks a field
 * nullable or optional it is nullable here, because the server omits fields freely.
 */

@Serializable
data class ServerInfo(
    val version: String = "",
    val pid: Int = 0,
    val hostname: String? = null,
    val urls: List<String> = emptyList(),
    val paths: ServerPaths? = null,
    val capabilities: ServerCapabilities? = null,
)

@Serializable
data class ServerPaths(val tmp: String = "")

@Serializable
data class ServerCapabilities(val persistentPty: Boolean? = null)

/** `Session.Info` as returned by `session.create`. */
@Serializable
data class SessionInfo(
    val id: String,
    val parentID: String? = null,
    val projectID: String? = null,
    val agent: String? = null,
    val model: ModelRef? = null,
    val title: String? = null,
    val cost: Double? = null,
    val tokens: TokenUsage? = null,
    val outcome: String? = null,
    val time: SessionTimes? = null,
)

@Serializable
data class SessionTimes(
    val created: Long? = null,
    val updated: Long? = null,
    val archived: Long? = null,
)

@Serializable
data class ModelRef(
    val providerID: String? = null,
    val modelID: String? = null,
    val id: String? = null,
)

@Serializable
data class TokenUsage(
    val input: Long? = null,
    val output: Long? = null,
    val reasoning: Long? = null,
    val cache: JsonObject? = null,
)

/**
 * `Session.Message.Info` is an `anyOf` discriminated on `type`. Decoding is done
 * manually in [dev.titanide.core.opencode.session.MessageParser] so an unknown future
 * `type` degrades to a placeholder instead of throwing and killing the session view.
 */
@Serializable
data class MessageEnvelope(
    val data: List<JsonObject> = emptyList(),
    val cursor: Cursor? = null,
)

@Serializable
data class Cursor(val previous: String? = null, val next: String? = null)

/** `FileDiff.Info` from `GET /api/session/{id}/diff`. */
@Serializable
data class FileDiff(
    val file: String,
    val patch: String = "",
    val additions: Int = 0,
    val deletions: Int = 0,
    val status: String = "modified",
    val more: Boolean? = null,
) {
    val isNew: Boolean get() = status == "added"
    val isDeleted: Boolean get() = status == "deleted"
}

/** `Permission.Request` from `GET /api/session/{id}/permission`. */
@Serializable
data class PermissionRequest(
    val id: String,
    val sessionID: String = "",
    val action: String = "",
    val resources: List<String> = emptyList(),
    val save: List<String> = emptyList(),
    val metadata: JsonObject? = null,
    val source: JsonObject? = null,
    val message: String? = null,
)

/** `Permission.Reply` enum from the spec. */
@Serializable
enum class PermissionDecision {
    @SerialName("once")
    ONCE,

    @SerialName("always")
    ALWAYS,

    @SerialName("reject")
    REJECT,
}

@Serializable
data class AgentInfo(
    val name: String = "",
    val description: String? = null,
    val id: String? = null,
    val mode: String? = null,
)

@Serializable
data class ModelInfo(
    val id: String = "",
    val providerID: String? = null,
    val name: String? = null,
    val modelID: String? = null,
)

@Serializable
data class ProviderInfo(
    val id: String = "",
    val name: String? = null,
    val models: List<ModelInfo> = emptyList(),
)

/** Envelopes. The server wraps almost every payload in `data`. */
@Serializable
data class DataEnvelope<T>(val data: T)

@Serializable
data class ListEnvelope<T>(
    val data: List<T> = emptyList(),
    val location: JsonObject? = null,
)

/** Body for `POST /api/session`. */
@Serializable
data class CreateSessionBody(
    val title: String? = null,
    val agent: String? = null,
    val model: ModelRef? = null,
)

/** Body for `POST /api/session/{id}/prompt`. `text` is the only required field. */
@Serializable
data class PromptBody(
    val text: String,
    val metadata: JsonObject? = null,
)

/** Body for `POST /api/session/{id}/permission/{requestID}/reply`. */
@Serializable
data class PermissionReplyBody(
    val decision: PermissionDecision,
    val message: String? = null,
)
