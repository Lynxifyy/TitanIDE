package dev.titanide.core.model

import kotlinx.serialization.Serializable

/** The four top-level answers to "What are you trying to build?". */
@Serializable
enum class ProjectCategory(val id: String, val title: String, val tagline: String) {
    ANDROID_APP("android_app", "Android App", "Native Android applications and libraries"),
    GAME("game", "Game Client / Tools", "Mods, plugins, clients and tools for games"),
    EXTENSION("extension", "App Extension", "Extensions and plugins for other applications"),
    BLANK("blank", "Just a Project", "Start blank and pick your own stack"),
    ;

    companion object {
        fun fromId(id: String): ProjectCategory = entries.firstOrNull { it.id == id } ?: BLANK
    }
}

@Serializable
enum class BuildSystem(val id: String, val displayName: String) {
    GRADLE("gradle", "Gradle"),
    NPM("npm", "npm"),
    CARGO("cargo", "Cargo"),
    GO_MODULES("go", "Go modules"),
    CMAKE("cmake", "CMake"),
    MAKE("make", "Make"),
    PIP("pip", "pip"),
    COMPOSER("composer", "Composer"),
    BUNDLER("bundler", "Bundler"),
    PUB("pub", "Dart pub"),
    DOTNET("dotnet", ".NET"),
    NONE("none", "None"),
    ;

    companion object {
        fun fromId(id: String): BuildSystem = entries.firstOrNull { it.id == id } ?: NONE
    }
}

/**
 * A concrete, generatable project shape. [generatorId] selects the implementation in
 * `core:templates`; a template without a registered generator is rejected at creation
 * time rather than producing an empty directory.
 */
@Serializable
data class ProjectTemplate(
    val id: String,
    val category: ProjectCategory,
    val name: String,
    val description: String,
    val generatorId: String,
    val languages: List<Language>,
    val buildSystem: BuildSystem,
    val minSdk: Int? = null,
    val targetSdk: Int? = null,
    /** Non-empty when the template needs tooling or a host platform TITAN cannot provide. */
    val requirements: List<String> = emptyList(),
    val docsUrl: String? = null,
    val gameId: String? = null,
) {
    val isSupportedOnDevice: Boolean
        get() = requirements.none { it.startsWith(UNSUPPORTED_PREFIX) }

    companion object {
        const val UNSUPPORTED_PREFIX = "unsupported:"
    }
}

/** A game that TITAN knows how to scaffold tooling for. */
@Serializable
data class GameDefinition(
    val id: String,
    val name: String,
    val studio: String,
    val summary: String,
    val monogram: String,
    val accent: Long,
    val docsUrl: String,
    val frameworks: List<GameFramework>,
)

@Serializable
data class GameFramework(
    val id: String,
    val name: String,
    val language: Language,
    val summary: String,
    val templateId: String,
    /** What the produced artifact can and cannot do, stated plainly. */
    val compatibility: String,
    val targetRuntime: String,
    val unsupportedReason: String? = null,
)
