package dev.titanide.core.templates

import dev.titanide.core.model.Language
import dev.titanide.core.templates.generators.*

/** Everything a generator needs to write a project; no generator invents its own paths. */
data class ScaffoldRequest(
    val projectName: String,
    val packageName: String,
    val applicationId: String,
    val rootDir: java.io.File,
    val minSdk: Int = 26,
    val targetSdk: Int = 36,
    val compileSdk: Int = 36,
    val kotlinVersion: String = "2.22.0",
    val language: Language? = null,
    val extraOptions: Map<String, String> = emptyMap(),
)

data class ScaffoldFile(val relativePath: String, val content: String)

data class ScaffoldResult(
    val files: List<ScaffoldFile>,
    val entryPoint: String,
    val notes: List<String> = emptyList(),
)

/**
 * A template generator writes the actual files. Generators are pure: they return
 * [ScaffoldFile]s and the caller persists them, so the same generator can be unit-tested
 * without touching disk.
 */
interface ProjectGenerator {
    val id: String
    fun generate(request: ScaffoldRequest): ScaffoldResult
}

class GeneratorRegistry(private val generators: List<ProjectGenerator>) {

    private val byId = generators.associateBy { it.id }

    fun generatorFor(generatorId: String): ProjectGenerator? = byId[generatorId]

    fun isRegistered(generatorId: String): Boolean = byId.containsKey(generatorId)

    fun ids(): Set<String> = byId.keys

    companion object {
        /** The production registry: every template in the catalogue has an entry here. */
        fun default(): GeneratorRegistry = GeneratorRegistry(
            listOf(
                AndroidComposeGenerator(),
                AndroidEmptyGenerator(),
                AndroidKotlinGenerator(),
                AndroidJavaGenerator(),
                AndroidLibraryGenerator(),
                JvmGradleGenerator(),
                PythonGenerator(),
                NodeGenerator(),
                RustGenerator(),
                GoGenerator(),
                CMakeGenerator(),
                VsCodeExtensionGenerator(),
                BrowserExtensionGenerator(),
                IntelliJPluginGenerator(),
                GradlePluginGenerator(),
                NpmPackageGenerator(),
                PythonPackageGenerator(),
                FabricModGenerator(),
                ForgeModGenerator(),
                PaperPluginGenerator(),
                NativeModuleGenerator(),
                BedrockAddonGenerator(),
                RobloxLuauGenerator(),
                FiveMResourceGenerator(),
                ScriptHookScriptGenerator(),
                UnityScriptGenerator(),
                UnrealPluginGenerator(),
                TypeScriptGenerator(),
            ),
        )
    }
}
