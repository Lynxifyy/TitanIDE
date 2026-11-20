package dev.titanide.core.model

import kotlinx.serialization.Serializable

/** What a build target needs. Detected from project files, never guessed. */
@Serializable
data class BuildProfile(
    val buildSystem: BuildSystem,
    val languages: List<Language>,
    val suggestedTasks: List<String> = emptyList(),
    val testTasks: List<String> = emptyList(),
    val runTask: String? = null,
    val artifactPaths: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
)

@Serializable
enum class BuildKind { BUILD, CLEAN, REBUILD, TEST, RUN, STOP }

@Serializable
enum class ExecutionTarget(val id: String, val label: String) {
    LOCAL("local", "On device"),
    REMOTE_AGENT("remote_agent", "Remote build agent"),
    GITHUB_ACTIONS("github_actions", "GitHub Actions"),
}

@Serializable
enum class BuildStatus { QUEUED, PREPARING, RUNNING, SUCCEEDED, FAILED, CANCELLED }

@Serializable
data class BuildRequest(
    val projectId: String,
    val projectPath: String,
    val kind: BuildKind,
    val target: ExecutionTarget,
    val task: String? = null,
    val extraArgs: List<String> = emptyList(),
    val environment: Map<String, String> = emptyMap(),
)

@Serializable
data class BuildStage(val name: String, val startedAt: Long, val finishedAt: Long? = null, val ok: Boolean? = null)

@Serializable
data class Artifact(
    val name: String,
    val path: String,
    val sizeBytes: Long,
    val mimeType: String? = null,
)

@Serializable
data class TestCaseResult(
    val suite: String,
    val name: String,
    val passed: Boolean,
    val durationMs: Long = 0,
    val failureMessage: String? = null,
    val stackTrace: String? = null,
)

@Serializable
data class TestSummary(
    val total: Int,
    val passed: Int,
    val failed: Int,
    val skipped: Int,
    val cases: List<TestCaseResult> = emptyList(),
    val durationMs: Long = 0,
)

/**
 * The single source of truth for "what happened" during a build. Every value here
 * comes from a real process or a real remote run; nothing is synthesised.
 */
@Serializable
data class BuildResult(
    val id: String,
    val request: BuildRequest,
    val status: BuildStatus,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val exitCode: Int? = null,
    val stages: List<BuildStage> = emptyList(),
    val logLines: List<String> = emptyList(),
    val diagnostics: List<Diagnostic> = emptyList(),
    val artifacts: List<Artifact> = emptyList(),
    val testSummary: TestSummary? = null,
    val failureReason: String? = null,
    val executorLabel: String = "",
) {
    val durationMs: Long get() = (finishedAt ?: System.currentTimeMillis()) - startedAt
    val errorCount: Int get() = diagnostics.count { it.severity == Severity.ERROR }
    val warningCount: Int get() = diagnostics.count { it.severity == Severity.WARNING }
}

/** A toolchain component discovered by probing the device or a remote agent. */
@Serializable
data class ToolchainInfo(
    val id: String,
    val displayName: String,
    val version: String?,
    val path: String?,
    val available: Boolean,
    val detail: String? = null,
)

@Serializable
data class ToolchainReport(
    val target: ExecutionTarget,
    val probedAt: Long,
    val tools: List<ToolchainInfo>,
) {
    val availableCount: Int get() = tools.count { it.available }
}
