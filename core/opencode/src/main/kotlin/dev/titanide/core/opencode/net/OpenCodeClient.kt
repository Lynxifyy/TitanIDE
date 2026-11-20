package dev.titanide.core.opencode.net

import dev.titanide.core.opencode.model.AgentInfo
import dev.titanide.core.opencode.model.CreateSessionBody
import dev.titanide.core.opencode.model.DataEnvelope
import dev.titanide.core.opencode.model.FileDiff
import dev.titanide.core.opencode.model.ListEnvelope
import dev.titanide.core.opencode.model.MessageEnvelope
import dev.titanide.core.opencode.model.ModelInfo
import dev.titanide.core.opencode.model.PermissionReplyBody
import dev.titanide.core.opencode.model.PermissionRequest
import dev.titanide.core.opencode.model.PromptBody
import dev.titanide.core.opencode.model.ProviderInfo
import dev.titanide.core.opencode.model.ServerInfo
import dev.titanide.core.opencode.model.SessionInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A failure the UI can show verbatim. Never swallow the server's own message. */
class OpenCodeException(
    message: String,
    val statusCode: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Typed client for the OpenCode HTTP API.
 *
 * Only endpoints that exist in the published OpenAPI document are exposed. The base URL
 * is whatever host the user configured (a remote agent, a Codespace forward, or a
 * tunnel), so this class makes no assumption that the server is local.
 */
class OpenCodeClient(
    baseUrl: String,
    private val token: String? = null,
    private val http: OkHttpClient = defaultClient(),
    private val json: Json = defaultJson,
) {

    private val root: HttpUrl = baseUrl.trimEnd('/').toHttpUrlOrNull()
        ?: throw OpenCodeException("Not a valid server URL: $baseUrl")

    // ---------------------------------------------------------------- health

    suspend fun info(): ServerInfo = get("/api/info", ServerInfo.serializer())

    /**
     * Cheap reachability probe. Returns null when the server answered, or a human
     * readable reason when it did not, so the UI can say *why* it is offline.
     */
    suspend fun probe(): String? = try {
        info()
        null
    } catch (e: OpenCodeException) {
        e.message ?: "Server did not respond"
    }

    // --------------------------------------------------------------- sessions

    suspend fun createSession(body: CreateSessionBody): SessionInfo =
        post(
            path = "/api/session",
            body = body,
            bodySerializer = CreateSessionBody.serializer(),
            responseSerializer = DataEnvelope.serializer(SessionInfo.serializer()),
        ).data

    suspend fun listSessions(limit: Int = 30): List<SessionInfo> =
        get("/api/session?limit=$limit", ListEnvelope.serializer(SessionInfo.serializer())).data

    suspend fun getSession(sessionId: String): SessionInfo =
        get("/api/session/$sessionId", DataEnvelope.serializer(SessionInfo.serializer())).data

    suspend fun deleteSession(sessionId: String) {
        delete("/api/session/$sessionId")
    }

    /** Sends a user turn. This is the spec's prompt endpoint, not a message POST. */
    suspend fun prompt(sessionId: String, text: String, metadata: JsonObject? = null) {
        post(
            path = "/api/session/$sessionId/prompt",
            body = PromptBody(text = text, metadata = metadata),
            bodySerializer = PromptBody.serializer(),
            responseSerializer = JsonObject.serializer(),
        )
    }

    suspend fun messages(sessionId: String, limit: Int = 200): List<JsonObject> =
        get("/api/session/$sessionId/message?limit=$limit", MessageEnvelope.serializer()).data

    suspend fun interrupt(sessionId: String) {
        post(
            path = "/api/session/$sessionId/interrupt",
            body = JsonObject(emptyMap()),
            bodySerializer = JsonObject.serializer(),
            responseSerializer = JsonObject.serializer(),
        )
    }

    suspend fun diff(sessionId: String): List<FileDiff> =
        get("/api/session/$sessionId/diff", DataEnvelope.serializer(FileDiff.serializer().let { kotlinx.serialization.builtins.ListSerializer(it) })).data

    // ------------------------------------------------------------ permissions

    suspend fun permissions(sessionId: String): List<PermissionRequest> =
        get("/api/session/$sessionId/permission", DataEnvelope.serializer(kotlinx.serialization.builtins.ListSerializer(PermissionRequest.serializer()))).data

    suspend fun replyPermission(sessionId: String, requestId: String, body: PermissionReplyBody) {
        post(
            path = "/api/session/$sessionId/permission/$requestId/reply",
            body = body,
            bodySerializer = PermissionReplyBody.serializer(),
            responseSerializer = JsonObject.serializer(),
        )
    }

    // ----------------------------------------------------------------- config

    suspend fun agents(): List<AgentInfo> = get("/api/agent", ListEnvelope.serializer(AgentInfo.serializer())).data

    suspend fun models(): List<ModelInfo> = get("/api/model", ListEnvelope.serializer(ModelInfo.serializer())).data

    suspend fun providers(): List<ProviderInfo> = get("/api/provider", ListEnvelope.serializer(ProviderInfo.serializer())).data

    // -------------------------------------------------------------- plumbing

    private suspend fun <T> get(path: String, serializer: kotlinx.serialization.DeserializationStrategy<T>): T =
        withContext(Dispatchers.IO) { execute(request(path).get().build(), serializer) }

    private suspend fun <B, T> post(
        path: String,
        body: B,
        bodySerializer: kotlinx.serialization.SerializationStrategy<B>,
        responseSerializer: kotlinx.serialization.DeserializationStrategy<T>,
    ): T = withContext(Dispatchers.IO) {
        val payload = json.encodeToString(bodySerializer, body)
        execute(request(path).post(payload.toRequestBody(JSON_MEDIA)).build(), responseSerializer)
    }

    private suspend fun delete(path: String) = withContext(Dispatchers.IO) {
        execute(request(path).delete().build(), JsonObject.serializer())
    }

    private fun request(path: String): Request.Builder {
        val url = root.resolve(path) ?: throw OpenCodeException("Could not resolve $path against $root")
        val builder = Request.Builder().url(url).header("Accept", "application/json")
        if (!token.isNullOrBlank()) builder.header("Authorization", "Bearer $token")
        return builder
    }

    private fun <T> execute(call: Request, serializer: kotlinx.serialization.DeserializationStrategy<T>): T {
        val response = try {
            http.newCall(call).execute()
        } catch (e: IOException) {
            throw OpenCodeException("Cannot reach OpenCode server at ${root.host}: ${e.message}", cause = e)
        }
        response.use {
            val text = it.body.string()
            if (!it.isSuccessful) {
                throw OpenCodeException(httpErrorMessage(it.code, text), statusCode = it.code)
            }
            // Some endpoints answer 200 with an empty body; treat that as an empty object
            // rather than a parse failure.
            val body = text.ifBlank { "{}" }
            return try {
                json.decodeFromString(serializer, body)
            } catch (e: Exception) {
                throw OpenCodeException(
                    "Unexpected response from ${call.url.encodedPath}: ${e.message}. Body was: ${body.take(300)}",
                    statusCode = it.code,
                    cause = e,
                )
            }
        }
    }

    private fun httpErrorMessage(code: Int, body: String): String {
        val detail = runCatching {
            val obj = json.parseToJsonElement(body).jsonObject
            val err = obj["error"]?.jsonObject
            err?.get("message")?.toString()?.trim('"')
                ?: obj["message"]?.toString()?.trim('"')
        }.getOrNull()
        return "OpenCode returned HTTP $code" + if (detail != null) ": $detail" else "."
    }

    /**
     * Opens the server-sent event stream. Frames are `{id?, event, data}` where `data` is
     * a JSON *string*, so it is passed through raw and decoded by the caller.
     *
     * Cancelling the coroutine cancels the underlying call, which is what the panel does
     * when the user closes the agent.
     */
    suspend fun events(onEvent: (event: String, data: String) -> Unit) {
        val url = root.resolve("/api/event") ?: throw OpenCodeException("Bad event URL")
        val builder = Request.Builder().url(url).header("Accept", "text/event-stream")
        if (!token.isNullOrBlank()) builder.header("Authorization", "Bearer $token")
        val call = http.newCall(builder.get().build())

        suspendCancellableCoroutine<Unit> { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(OpenCodeException("Event stream failed: ${e.message}", cause = e))
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { res ->
                        if (!res.isSuccessful) {
                            if (cont.isActive) {
                                cont.resumeWithException(OpenCodeException("Event stream rejected: HTTP ${res.code}"))
                            }
                            return
                        }
                        val source = res.body.source()
                        var eventName: String? = null
                        val data = StringBuilder()
                        try {
                            while (!source.exhausted()) {
                                val line = source.readUtf8Line() ?: break
                                when {
                                    line.startsWith("event:") -> eventName = line.removePrefix("event:").trim()
                                    line.startsWith("data:") -> data.append(line.removePrefix("data:").trim())
                                    line.isEmpty() -> {
                                        if (data.isNotEmpty()) {
                                            onEvent(eventName ?: "message", data.toString())
                                            data.clear()
                                        }
                                        eventName = null
                                    }
                                }
                            }
                        } catch (e: IOException) {
                            if (cont.isActive) cont.resumeWithException(OpenCodeException("Event stream dropped: ${e.message}", cause = e))
                            return
                        }
                        if (cont.isActive) cont.resume(Unit)
                    }
                }
            })
        }
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        val defaultJson: Json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            explicitNulls = false
            encodeDefaults = true
        }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(0, java.util.concurrent.TimeUnit.SECONDS) // SSE is long lived
            .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }
}
