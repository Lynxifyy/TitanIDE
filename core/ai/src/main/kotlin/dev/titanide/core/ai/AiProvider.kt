package dev.titanide.core.ai

import kotlinx.serialization.Serializable

/**
 * Provider-agnostic chat contract. Implementations must never fabricate a response: a
 * transport failure surfaces as [ChatResult.Failure] so the UI can show the real error.
 */
interface AiProvider {
    val id: String
    val displayName: String
    val requiresApiKey: Boolean
    val supportsStreaming: Boolean

    /** Models this provider can serve, used to populate the settings picker. */
    val models: List<String>

    suspend fun chat(request: ChatRequest): ChatResult
}

@Serializable
data class ChatMessage(
    val role: Role,
    val content: String,
    val name: String? = null,
) {
    enum class Role { SYSTEM, USER, ASSISTANT, TOOL }
}

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double = 0.2,
    val maxTokens: Int = 4096,
    val stopSequences: List<String> = emptyList(),
)

@Serializable
data class ChatResponse(
    val content: String,
    val model: String,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val finishReason: String? = null,
) {
    val totalTokens: Int get() = promptTokens + completionTokens
}

sealed interface ChatResult {
    data class Success(val response: ChatResponse) : ChatResult
    data class Failure(val reason: String, val httpCode: Int? = null, val retryable: Boolean = false) : ChatResult
}

/**
 * Describes what context the AI is allowed to see. Built explicitly so no file is sent to
 * a provider unless the user enabled project context.
 */
@Serializable
data class AiContext(
    val projectName: String,
    val projectPath: String,
    val languages: List<String>,
    val buildSystem: String,
    val activeFilePath: String? = null,
    val activeFileContent: String? = null,
    val selection: String? = null,
    val projectTree: List<String> = emptyList(),
    val buildConfigFiles: Map<String, String> = emptyMap(),
    val recentDiagnostics: List<String> = emptyList(),
    val lastBuildSummary: String? = null,
) {
    /** Renders the context as a prompt block, honouring size limits. */
    fun render(maxTreeEntries: Int = 200, maxConfigChars: Int = 4000): String = buildString {
        appendLine("## Project")
        appendLine("Name: $projectName")
        appendLine("Languages: ${languages.joinToString(", ").ifBlank { "unknown" }}")
        appendLine("Build system: $buildSystem")
        if (projectTree.isNotEmpty()) {
            appendLine("Files (${projectTree.size}):")
            projectTree.take(maxTreeEntries).forEach { appendLine("- $it") }
        }
        if (buildConfigFiles.isNotEmpty()) {
            appendLine("## Build configuration")
            buildConfigFiles.forEach { (path, content) ->
                appendLine("### $path")
                appendLine("```")
                appendLine(content.take(maxConfigChars))
                appendLine("```")
            }
        }
        if (recentDiagnostics.isNotEmpty()) {
            appendLine("## Current diagnostics")
            recentDiagnostics.take(40).forEach { appendLine("- $it") }
        }
        if (lastBuildSummary != null) {
            appendLine("## Last build")
            appendLine(lastBuildSummary)
        }
        if (activeFilePath != null) {
            appendLine("## Active file: $activeFilePath")
            if (selection != null) {
                appendLine("Selected code:")
                appendLine("```")
                appendLine(selection)
                appendLine("```")
            } else if (activeFileContent != null) {
                appendLine("```")
                appendLine(activeFileContent)
                appendLine("```")
            }
        }
    }
}

/** The AI editing modes exposed in the UI. */
enum class AiMode(val id: String, val label: String, val description: String) {
    CHAT("chat", "Chat", "Ask about the project"),
    INLINE_EDIT("inline", "Inline edit", "Rewrite the selection from an instruction"),
    EXPLAIN("explain", "Explain", "Explain what the selected code does"),
    REFACTOR("refactor", "Refactor", "Restructure without changing behaviour"),
    DEBUG("debug", "Debug", "Diagnose and fix a failure"),
    AGENT("agent", "Agent", "Plan, edit, build, and verify autonomously");

    companion object {
        fun fromId(id: String): AiMode = entries.firstOrNull { it.id == id } ?: CHAT
    }
}

/** A proposed change the user must approve before it touches disk. */
@Serializable
data class ProposedEdit(
    val filePath: String,
    val originalContent: String?,
    val newContent: String,
    val explanation: String,
    val isNewFile: Boolean = false,
) {
    val isDestructive: Boolean get() = !isNewFile && originalContent != null && newContent.isBlank()
}
