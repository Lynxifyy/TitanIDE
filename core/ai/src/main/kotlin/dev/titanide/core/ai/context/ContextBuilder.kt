package dev.titanide.core.ai.context

import dev.titanide.core.ai.AiContext
import dev.titanide.core.data.files.SafeFileSystem
import dev.titanide.core.data.repo.FileTreeRepository
import dev.titanide.core.model.BuildResult
import dev.titanide.core.model.Diagnostic
import dev.titanide.core.model.Project
import java.io.File

/**
 * Builds the context block sent to the model. Project files are only included when the
 * user has explicitly enabled project context; otherwise the context is limited to the
 * file the user is looking at.
 */
class ContextBuilder(private val files: FileTreeRepository) {

    private val buildConfigNames = setOf(
        "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts",
        "gradle.properties", "package.json", "Cargo.toml", "go.mod", "CMakeLists.txt",
        "pyproject.toml", "requirements.txt", "pubspec.yaml", "pom.xml", "Makefile",
        "AndroidManifest.xml",
    )

    suspend fun build(
        project: Project,
        includeProjectWide: Boolean,
        activeFilePath: String? = null,
        activeFileContent: String? = null,
        selection: String? = null,
        diagnostics: List<Diagnostic> = emptyList(),
        lastBuild: BuildResult? = null,
    ): AiContext {
        val root = File(project.rootPath)
        val tree = if (includeProjectWide) {
            files.list(project).take(400).map { it.path }
        } else {
            emptyList()
        }

        val configs = if (includeProjectWide) readBuildConfigs(root) else emptyMap()

        return AiContext(
            projectName = project.name,
            projectPath = project.rootPath,
            languages = project.languages.map { it.id },
            buildSystem = project.buildSystem.id,
            activeFilePath = activeFilePath,
            activeFileContent = if (selection == null) activeFileContent else null,
            selection = selection,
            projectTree = tree,
            buildConfigFiles = configs,
            recentDiagnostics = diagnostics.take(40).map {
                "${it.filePath}:${it.line}:${it.column} ${it.severity}: ${it.message}"
            },
            lastBuildSummary = lastBuild?.let { result ->
                buildString {
                    append("${result.status} via ${result.executorLabel}")
                    result.failureReason?.let { append(": $it") }
                    result.testSummary?.let {
                        append(" | tests ${it.passed} passed, ${it.failed} failed")
                    }
                }
            },
        )
    }

    private fun readBuildConfigs(root: File): Map<String, String> {
        val found = LinkedHashMap<String, String>()
        root.walkTopDown()
            .maxDepth(3)
            .onEnter { it.name !in setOf("build", ".git", "node_modules", ".gradle", "target") }
            .filter { it.isFile && it.name in buildConfigNames }
            .take(12)
            .forEach { file ->
                runCatching {
                    if (file.length() < 64 * 1024) {
                        found[file.relativeTo(root).path.replace('\\', '/')] = file.readText()
                    }
                }
            }
        return found
    }
}
