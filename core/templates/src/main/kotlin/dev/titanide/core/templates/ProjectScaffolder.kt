package dev.titanide.core.templates

import dev.titanide.core.data.files.SafeFileSystem
import dev.titanide.core.model.BuildSystem
import dev.titanide.core.model.Language
import dev.titanide.core.model.Project
import dev.titanide.core.model.ProjectCategory
import dev.titanide.core.model.SdkConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class CreateProjectSpec(
    val name: String,
    val packageName: String,
    val category: ProjectCategory,
    val templateId: String,
    val minSdk: Int = 26,
    val targetSdk: Int = 36,
    val compileSdk: Int = 36,
    val applicationId: String? = null,
    val language: Language? = null,
    val customLocation: String? = null,
)

sealed interface CreateProjectOutcome {
    data class Success(val project: Project, val filesWritten: Int, val entryPoint: String, val notes: List<String>) :
        CreateProjectOutcome
    data class Failure(val reason: String) : CreateProjectOutcome
}

/**
 * Creates a project on disk from a catalogue template. It validates that the template
 * exists and that its generator is registered *before* writing anything, so a failed
 * create never leaves a half-built directory behind.
 */
class ProjectScaffolder(
    private val registry: GeneratorRegistry = GeneratorRegistry.default(),
    private val fs: SafeFileSystem = SafeFileSystem(),
) {

    val lastErrors = ArrayDeque<String>()

    fun validate(spec: CreateProjectSpec): String? {
        val template = TemplateCatalog.byId(spec.templateId) ?: return "Unknown template: ${spec.templateId}"
        if (template.category != spec.category) {
            return "Template ${template.id} does not belong to ${spec.category.title}"
        }
        if (!registry.isRegistered(template.generatorId)) {
            return "No generator is registered for ${template.name}"
        }
        if (spec.name.isBlank()) return "Project name is required"
        if (!isValidPackage(spec.packageName)) {
            return "Invalid package name: ${spec.packageName}"
        }
        return null
    }

    suspend fun create(spec: CreateProjectSpec, projectId: String, targetDir: File): CreateProjectOutcome =
        withContext(Dispatchers.IO) {
            validate(spec)?.let { return@withContext CreateProjectOutcome.Failure(it) }
            val template = TemplateCatalog.byId(spec.templateId)!!
            val generator = registry.generatorFor(template.generatorId)
                ?: return@withContext CreateProjectOutcome.Failure("No generator for ${template.name}")

            if (targetDir.exists() && targetDir.listFiles()?.isNotEmpty() == true) {
                return@withContext CreateProjectOutcome.Failure(
                    "Directory is not empty: ${targetDir.absolutePath}",
                )
            }
            if (!targetDir.mkdirs() && !targetDir.exists()) {
                return@withContext CreateProjectOutcome.Failure("Could not create ${targetDir.absolutePath}")
            }

            val request = ScaffoldRequest(
                projectName = spec.name,
                packageName = spec.packageName,
                applicationId = spec.applicationId ?: spec.packageName,
                rootDir = targetDir,
                minSdk = spec.minSdk,
                targetSdk = spec.targetSdk,
                compileSdk = spec.compileSdk,
                language = spec.language ?: template.languages.firstOrNull(),
            )

            val result = generator.generate(request)
            val failures = ArrayList<String>()
            var written = 0
            for (file in result.files) {
                when (val writeResult = fs.writeText(targetDir, file.relativePath, file.content)) {
                    is SafeFileSystem.Result.Ok -> written++
                    is SafeFileSystem.Result.Failure -> failures += "${file.relativePath}: ${writeResult.reason}"
                }
            }

            if (failures.isNotEmpty()) {
                lastErrors.clear()
                lastErrors.addAll(failures)
                return@withContext CreateProjectOutcome.Failure(
                    "Wrote $written file(s) but ${failures.size} failed: ${failures.first()}",
                )
            }

            val now = System.currentTimeMillis()
            val project = Project(
                id = projectId,
                name = spec.name,
                packageName = spec.packageName,
                rootPath = targetDir.absolutePath,
                category = spec.category,
                templateId = template.id,
                languages = template.languages,
                buildSystem = template.buildSystem,
                createdAt = now,
                lastOpenedAt = now,
                sdkConfig = if (template.category == ProjectCategory.ANDROID_APP) {
                    SdkConfig(
                        minSdk = spec.minSdk,
                        targetSdk = spec.targetSdk,
                        compileSdk = spec.compileSdk,
                        applicationId = spec.applicationId ?: spec.packageName,
                    )
                } else null,
            )
            CreateProjectOutcome.Success(project, written, result.entryPoint, result.notes)
        }

    companion object {
        private val PACKAGE_SEGMENT = Regex("^[a-zA-Z_][a-zA-Z0-9_]*$")

        fun isValidPackage(packageName: String): Boolean {
            val parts = packageName.split('.')
            if (parts.size < 2) return false
            return parts.all { PACKAGE_SEGMENT.matches(it) }
        }

        fun newProjectId(): String = UUID.randomUUID().toString()
    }
}

/** Maps a build system to the executable that drives it, used by the build engine. */
fun BuildSystem.primaryCommand(): String? = when (this) {
    BuildSystem.GRADLE -> "./gradlew"
    BuildSystem.NPM -> "npm"
    BuildSystem.CARGO -> "cargo"
    BuildSystem.GO_MODULES -> "go"
    BuildSystem.CMAKE -> "cmake"
    BuildSystem.MAKE -> "make"
    BuildSystem.PIP -> "python3"
    BuildSystem.COMPOSER -> "composer"
    BuildSystem.BUNDLER -> "bundle"
    BuildSystem.PUB -> "dart"
    BuildSystem.DOTNET -> "dotnet"
    BuildSystem.NONE -> null
}
