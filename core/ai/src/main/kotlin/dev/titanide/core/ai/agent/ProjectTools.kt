package dev.titanide.core.ai.agent

import dev.titanide.core.data.files.SafeFileSystem
import dev.titanide.core.data.repo.FileTreeRepository
import dev.titanide.core.editor.SearchEngine
import dev.titanide.core.editor.SearchOptions
import dev.titanide.core.editor.TextDocument
import dev.titanide.core.model.BuildKind
import dev.titanide.core.model.BuildRequest
import dev.titanide.core.model.BuildStatus
import dev.titanide.core.model.ExecutionTarget
import dev.titanide.core.model.Project
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * The tools the autonomous agent may call. Every tool performs the real operation and
 * reports the real outcome; none of them simulate a result.
 */
class ProjectTools(
    private val project: Project,
    private val files: FileTreeRepository,
    private val buildRunner: suspend (BuildRequest) -> dev.titanide.core.model.BuildResult,
    private val fs: SafeFileSystem = SafeFileSystem(),
) {
    private val root: File get() = File(project.rootPath)

    fun all(): List<AgentTool> = listOf(
        listFiles(),
        readFile(),
        writeFile(),
        searchCode(),
        runBuild(),
        runTests(),
        deleteFile(),
    )

    private fun listFiles() = object : AgentTool {
        override val name = "list_files"
        override val description = "List project files. ARGS: {\"path\": \"optional/subdir\"}"

        override suspend fun invoke(arguments: JsonObject): ToolResult {
            val path = ToolArgs.string(arguments, "path").orEmpty()
            val entries = files.list(project, path)
            if (entries.isEmpty()) return ToolResult(true, "No files found under '${path.ifBlank { "." }}'")
            val listing = entries.take(300).joinToString("\n") { entry ->
                (if (entry.kind == dev.titanide.core.model.FileKind.DIRECTORY) "[dir]  " else "       ") + entry.path
            }
            return ToolResult(true, "${entries.size} entries:\n$listing")
        }
    }

    private fun readFile() = object : AgentTool {
        override val name = "read_file"
        override val description = "Read a file. ARGS: {\"path\": \"src/Main.kt\"}"

        override suspend fun invoke(arguments: JsonObject): ToolResult {
            val path = ToolArgs.string(arguments, "path")
                ?: return ToolResult(false, "Missing 'path' argument")
            return when (val result = files.read(project, path)) {
                is SafeFileSystem.Result.Ok -> ToolResult(true, result.value)
                is SafeFileSystem.Result.Failure -> ToolResult(false, result.reason)
            }
        }
    }

    private fun writeFile() = object : AgentTool {
        override val name = "write_file"
        override val description =
            "Create or overwrite a file with full contents. ARGS: {\"path\": \"src/Main.kt\", \"content\": \"...\"}"

        override suspend fun invoke(arguments: JsonObject): ToolResult {
            val path = ToolArgs.string(arguments, "path")
                ?: return ToolResult(false, "Missing 'path' argument")
            val content = ToolArgs.string(arguments, "content")
                ?: return ToolResult(false, "Missing 'content' argument")
            return when (val result = files.write(project, path, content)) {
                is SafeFileSystem.Result.Ok ->
                    ToolResult(true, "Wrote ${content.length} characters to $path")
                is SafeFileSystem.Result.Failure -> ToolResult(false, result.reason)
            }
        }
    }

    private fun deleteFile() = object : AgentTool {
        override val name = "delete_file"
        override val description = "Delete a file or directory. ARGS: {\"path\": \"src/Old.kt\"}"

        override suspend fun invoke(arguments: JsonObject): ToolResult {
            val path = ToolArgs.string(arguments, "path")
                ?: return ToolResult(false, "Missing 'path' argument")
            return when (val result = files.delete(project, path)) {
                is SafeFileSystem.Result.Ok -> ToolResult(true, "Deleted $path")
                is SafeFileSystem.Result.Failure -> ToolResult(false, result.reason)
            }
        }
    }

    private fun searchCode() = object : AgentTool {
        override val name = "search_code"
        override val description = "Search text across project files. ARGS: {\"query\": \"text\"}"

        override suspend fun invoke(arguments: JsonObject): ToolResult {
            val query = ToolArgs.string(arguments, "query")
                ?: return ToolResult(false, "Missing 'query' argument")
            val corpus = files.loadTextFiles(project, limit = 400)
            if (corpus.isEmpty()) return ToolResult(true, "No readable source files found")
            val engine = SearchEngine()
            val hits = ArrayList<String>()
            for ((path, content) in corpus) {
                val matches = engine.search(TextDocument.of(content), query, SearchOptions())
                matches.take(5).forEach { match ->
                    hits += "$path:${match.line + 1}: ${match.lineText.trim().take(160)}"
                }
                if (hits.size >= 60) break
            }
            return if (hits.isEmpty()) {
                ToolResult(true, "No matches for '$query'")
            } else {
                ToolResult(true, "${hits.size} matches:\n${hits.joinToString("\n")}")
            }
        }
    }

    private fun runBuild() = object : AgentTool {
        override val name = "run_build"
        override val description = "Compile the project. ARGS: {}"

        override suspend fun invoke(arguments: JsonObject): ToolResult {
            val result = buildRunner(
                BuildRequest(
                    projectId = project.id,
                    projectPath = project.rootPath,
                    kind = BuildKind.BUILD,
                    target = ExecutionTarget.LOCAL,
                ),
            )
            return summarise(result)
        }
    }

    private fun runTests() = object : AgentTool {
        override val name = "run_tests"
        override val description = "Run the project's test suite. ARGS: {}"

        override suspend fun invoke(arguments: JsonObject): ToolResult {
            val result = buildRunner(
                BuildRequest(
                    projectId = project.id,
                    projectPath = project.rootPath,
                    kind = BuildKind.TEST,
                    target = ExecutionTarget.LOCAL,
                ),
            )
            return summarise(result)
        }
    }

    private fun summarise(result: dev.titanide.core.model.BuildResult): ToolResult {
        val ok = result.status == BuildStatus.SUCCEEDED
        val summary = buildString {
            appendLine("Status: ${result.status} (${result.executorLabel})")
            result.failureReason?.let { appendLine("Reason: $it") }
            result.testSummary?.let { tests ->
                appendLine("Tests: ${tests.passed} passed, ${tests.failed} failed, ${tests.skipped} skipped of ${tests.total}")
            }
            if (result.diagnostics.isNotEmpty()) {
                appendLine("Diagnostics:")
                result.diagnostics.take(15).forEach {
                    appendLine("  ${it.filePath}:${it.line}:${it.column} ${it.severity}: ${it.message}")
                }
            }
            val tail = result.logLines.takeLast(25)
            if (tail.isNotEmpty()) {
                appendLine("Log tail:")
                tail.forEach { appendLine("  $it") }
            }
        }
        return ToolResult(ok, summary)
    }
}
