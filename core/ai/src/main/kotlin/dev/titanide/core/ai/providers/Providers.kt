package dev.titanide.core.ai.providers

import dev.titanide.core.ai.AiProvider
import dev.titanide.core.ai.ChatMessage
import dev.titanide.core.ai.ChatRequest
import dev.titanide.core.ai.ChatResponse
import dev.titanide.core.ai.ChatResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Any server that speaks the OpenAI chat-completions dialect: OpenAI itself, OpenRouter,
 * LM Studio, Ollama's compatibility endpoint, and self-hosted gateways. Because the base
 * URL is configurable, a fully local model can be used without any cloud provider.
 */
class OpenAiCompatibleProvider(
    override val displayName: String,
    private val baseUrl: String,
    private val apiKeyProvider: suspend () -> String?,
    override val models: List<String> = DEFAULT_MODELS,
    private val http: OkHttpClient = defaultClient(),
) : AiProvider {

    override val id: String = "openai_compatible"
    override val requiresApiKey: Boolean = false
    override val supportsStreaming: Boolean = false

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    private data class RequestBody(
        val model: String,
        val messages: List<MessageBody>,
        val temperature: Double,
        @SerialName("max_tokens") val maxTokens: Int,
        val stream: Boolean = false,
    )

    @Serializable
    private data class MessageBody(val role: String, val content: String)

    @Serializable
    private data class ResponseBody(
        val model: String? = null,
        val choices: List<Choice> = emptyList(),
        val usage: Usage? = null,
        val error: ErrorBody? = null,
    )

    @Serializable
    private data class Choice(
        val message: MessageBody? = null,
        @SerialName("finish_reason") val finishReason: String? = null,
    )

    @Serializable
    private data class Usage(
        @SerialName("prompt_tokens") val promptTokens: Int = 0,
        @SerialName("completion_tokens") val completionTokens: Int = 0,
    )

    @Serializable
    private data class ErrorBody(val message: String = "", val type: String? = null)

    override suspend fun chat(request: ChatRequest): ChatResult = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) {
            return@withContext ChatResult.Failure("No endpoint URL is configured for $displayName")
        }
        val apiKey = apiKeyProvider()

        val body = json.encodeToString(
            RequestBody.serializer(),
            RequestBody(
                model = request.model,
                messages = request.messages.map { MessageBody(it.role.name.lowercase(), it.content) },
                temperature = request.temperature,
                maxTokens = request.maxTokens,
            ),
        )

        val builder = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/chat/completions")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
        if (!apiKey.isNullOrBlank()) builder.header("Authorization", "Bearer $apiKey")

        runCatching {
            http.newCall(builder.build()).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val detail = runCatching {
                        json.decodeFromString(ResponseBody.serializer(), text).error?.message
                    }.getOrNull()
                    return@use ChatResult.Failure(
                        reason = detail ?: "HTTP ${response.code} from $displayName",
                        httpCode = response.code,
                        retryable = response.code == 429 || response.code >= 500,
                    )
                }
                val parsed = json.decodeFromString(ResponseBody.serializer(), text)
                val content = parsed.choices.firstOrNull()?.message?.content
                    ?: return@use ChatResult.Failure("$displayName returned no message content")
                ChatResult.Success(
                    ChatResponse(
                        content = content,
                        model = parsed.model ?: request.model,
                        promptTokens = parsed.usage?.promptTokens ?: 0,
                        completionTokens = parsed.usage?.completionTokens ?: 0,
                        finishReason = parsed.choices.firstOrNull()?.finishReason,
                    ),
                )
            }
        }.getOrElse { ChatResult.Failure("Cannot reach $displayName: ${it.message}", retryable = true) }
    }

    companion object {
        val DEFAULT_MODELS = listOf("gpt-4o-mini", "gpt-4o", "o3-mini")

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .build()
    }
}

/** Anthropic Messages API. */
class AnthropicProvider(
    private val baseUrl: String = "https://api.anthropic.com",
    private val apiKeyProvider: suspend () -> String?,
    override val models: List<String> = DEFAULT_MODELS,
    private val http: OkHttpClient = OpenAiCompatibleProvider.defaultClient(),
) : AiProvider {

    override val id: String = "anthropic"
    override val displayName: String = "Anthropic"
    override val requiresApiKey: Boolean = true
    override val supportsStreaming: Boolean = false

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    private data class RequestBody(
        val model: String,
        @SerialName("max_tokens") val maxTokens: Int,
        val system: String? = null,
        val messages: List<MessageBody>,
        val temperature: Double,
    )

    @Serializable
    private data class MessageBody(val role: String, val content: String)

    @Serializable
    private data class ResponseBody(
        val model: String? = null,
        val content: List<ContentBlock> = emptyList(),
        val usage: Usage? = null,
        @SerialName("stop_reason") val stopReason: String? = null,
        val error: ErrorBody? = null,
    )

    @Serializable
    private data class ContentBlock(val type: String = "text", val text: String = "")

    @Serializable
    private data class Usage(
        @SerialName("input_tokens") val inputTokens: Int = 0,
        @SerialName("output_tokens") val outputTokens: Int = 0,
    )

    @Serializable
    private data class ErrorBody(val message: String = "", val type: String? = null)

    override suspend fun chat(request: ChatRequest): ChatResult = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider()
            ?: return@withContext ChatResult.Failure("An Anthropic API key is required")

        val system = request.messages.filter { it.role == ChatMessage.Role.SYSTEM }
            .joinToString("\n\n") { it.content }
            .ifBlank { null }
        val conversation = request.messages
            .filter { it.role != ChatMessage.Role.SYSTEM }
            .map { MessageBody(it.role.name.lowercase(), it.content) }

        if (conversation.isEmpty()) {
            return@withContext ChatResult.Failure("A request needs at least one user message")
        }

        val body = json.encodeToString(
            RequestBody.serializer(),
            RequestBody(
                model = request.model,
                maxTokens = request.maxTokens,
                system = system,
                messages = conversation,
                temperature = request.temperature,
            ),
        )

        val httpRequest = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/v1/messages")
            .header("Content-Type", "application/json")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        runCatching {
            http.newCall(httpRequest).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val detail = runCatching {
                        json.decodeFromString(ResponseBody.serializer(), text).error?.message
                    }.getOrNull()
                    return@use ChatResult.Failure(
                        reason = detail ?: "HTTP ${response.code} from Anthropic",
                        httpCode = response.code,
                        retryable = response.code == 429 || response.code >= 500,
                    )
                }
                val parsed = json.decodeFromString(ResponseBody.serializer(), text)
                val content = parsed.content.filter { it.type == "text" }.joinToString("") { it.text }
                if (content.isBlank()) return@use ChatResult.Failure("Anthropic returned no text content")
                ChatResult.Success(
                    ChatResponse(
                        content = content,
                        model = parsed.model ?: request.model,
                        promptTokens = parsed.usage?.inputTokens ?: 0,
                        completionTokens = parsed.usage?.outputTokens ?: 0,
                        finishReason = parsed.stopReason,
                    ),
                )
            }
        }.getOrElse { ChatResult.Failure("Cannot reach Anthropic: ${it.message}", retryable = true) }
    }

    companion object {
        val DEFAULT_MODELS = listOf(
            "claude-sonnet-4-20250514",
            "claude-3-5-haiku-20241022",
        )
    }
}

/**
 * A provider that reports it is not configured. Used so the UI always has something to
 * bind to and can explain precisely what the user must do, instead of silently failing.
 */
class UnconfiguredProvider(
    override val displayName: String = "Not configured",
    override val requiresApiKey: Boolean = false,
    override val models: List<String> = emptyList(),
) : AiProvider {
    override val id: String = "none"
    override val supportsStreaming: Boolean = false

    override suspend fun chat(request: ChatRequest): ChatResult = ChatResult.Failure(
        "No AI provider is configured. Open Settings, choose a provider, and enter its endpoint " +
            "and API key. A local model such as Ollama or LM Studio works without a cloud key.",
    )
}

/** Registry that maps a settings provider id to a live provider instance. */
class AiProviderRegistry(
    private val providers: List<AiProvider>,
) {
    fun all(): List<AiProvider> = providers

    fun byId(id: String): AiProvider? = providers.firstOrNull { it.id == id }

    companion object {
        fun default(
            endpoint: String,
            hasKey: Boolean,
            keyProvider: suspend () -> String?,
            providerId: String,
        ): AiProviderRegistry = AiProviderRegistry(
            listOf(
                UnconfiguredProvider(),
                OpenAiCompatibleProvider(
                    displayName = "OpenAI-compatible",
                    baseUrl = endpoint.ifBlank { "https://api.openai.com/v1" },
                    apiKeyProvider = keyProvider,
                ),
                AnthropicProvider(apiKeyProvider = keyProvider),
            ),
        )
    }
}
