package dev.titanide.core.templates

import dev.titanide.core.model.BuildSystem
import dev.titanide.core.model.GameDefinition
import dev.titanide.core.model.GameFramework
import dev.titanide.core.model.Language
import dev.titanide.core.model.ProjectCategory
import dev.titanide.core.model.ProjectTemplate

/**
 * The template catalogue. Every entry maps to a registered [ProjectGenerator]; the
 * registry refuses to scaffold a template whose generator is missing, so the catalogue
 * can never advertise a project shape the app cannot produce.
 */
object TemplateCatalog {

    const val GEN_ANDROID_COMPOSE = "android.compose"
    const val GEN_ANDROID_EMPTY = "android.empty"
    const val GEN_ANDROID_KOTLIN = "android.kotlin"
    const val GEN_ANDROID_JAVA = "android.java"
    const val GEN_ANDROID_LIBRARY = "android.library"
    const val GEN_GRADLE_JVM = "jvm.gradle"
    const val GEN_PYTHON = "python.app"
    const val GEN_NODE = "node.app"
    const val GEN_TS_NODE = "ts.node"
    const val GEN_RUST = "rust.bin"
    const val GEN_GO = "go.module"
    const val GEN_CMAKE = "cmake.app"
    const val GEN_VSCODE = "ext.vscode"
    const val GEN_BROWSER = "ext.browser"
    const val GEN_INTELLIJ = "ext.intellij"
    const val GEN_GRADLE_PLUGIN = "ext.gradleplugin"
    const val GEN_NPM_PLUGIN = "ext.npm"
    const val GEN_PYTHON_PACKAGE = "ext.python"
    const val GEN_MINECRAFT_FABRIC = "game.mc.fabric"
    const val GEN_MINECRAFT_FORGE = "game.mc.forge"
    const val GEN_MINECRAFT_PAPER = "game.mc.paper"
    const val GEN_MINECRAFT_NATIVE = "game.mc.native"
    const val GEN_MINECRAFT_BEDROCK = "game.mc.bedrock"
    const val GEN_ROBLOX_LUAU = "game.roblox.luau"
    const val GEN_GTA_FIVEM = "game.gta.fivem"
    const val GEN_GTA_SCRIPT = "game.gta.script"
    const val GEN_UNITY_CSHARP = "game.unity.csharp"
    const val GEN_UNREAL_CPP = "game.unreal.cpp"
    const val GEN_BLANK = "blank.multi"

    val androidTemplates = listOf(
        ProjectTemplate(
            id = "android.compose",
            category = ProjectCategory.ANDROID_APP,
            name = "Jetpack Compose Application",
            description = "Single-activity Compose app with Material 3, ViewModel and a working counter screen.",
            generatorId = GEN_ANDROID_COMPOSE,
            languages = listOf(Language.KOTLIN, Language.GRADLE, Language.XML),
            buildSystem = BuildSystem.GRADLE,
            minSdk = 26,
            targetSdk = 36,
        ),
        ProjectTemplate(
            id = "android.empty",
            category = ProjectCategory.ANDROID_APP,
            name = "Empty Android Project",
            description = "Bare Activity with a single TextView, no Compose dependency.",
            generatorId = GEN_ANDROID_EMPTY,
            languages = listOf(Language.KOTLIN, Language.GRADLE, Language.XML),
            buildSystem = BuildSystem.GRADLE,
            minSdk = 26,
            targetSdk = 36,
        ),
        ProjectTemplate(
            id = "android.kotlin",
            category = ProjectCategory.ANDROID_APP,
            name = "Kotlin Application",
            description = "Kotlin-first app module with coroutines and a unit test.",
            generatorId = GEN_ANDROID_KOTLIN,
            languages = listOf(Language.KOTLIN, Language.GRADLE),
            buildSystem = BuildSystem.GRADLE,
            minSdk = 26,
            targetSdk = 36,
        ),
        ProjectTemplate(
            id = "android.java",
            category = ProjectCategory.ANDROID_APP,
            name = "Java Application",
            description = "Classic Java Android app with an Activity and a JUnit test.",
            generatorId = GEN_ANDROID_JAVA,
            languages = listOf(Language.JAVA, Language.GRADLE),
            buildSystem = BuildSystem.GRADLE,
            minSdk = 26,
            targetSdk = 36,
        ),
        ProjectTemplate(
            id = "android.library",
            category = ProjectCategory.ANDROID_APP,
            name = "Android Library",
            description = "Publishable AAR module with a public API class and tests.",
            generatorId = GEN_ANDROID_LIBRARY,
            languages = listOf(Language.KOTLIN, Language.GRADLE),
            buildSystem = BuildSystem.GRADLE,
            minSdk = 26,
            targetSdk = 36,
        ),
    )

    val extensionTemplates = listOf(
        ProjectTemplate(
            id = "ext.vscode",
            category = ProjectCategory.EXTENSION,
            name = "VS Code Extension",
            description = "TypeScript extension with activation events, a command, and a manifest.",
            generatorId = GEN_VSCODE,
            languages = listOf(Language.TYPESCRIPT, Language.JSON),
            buildSystem = BuildSystem.NPM,
        ),
        ProjectTemplate(
            id = "ext.browser",
            category = ProjectCategory.EXTENSION,
            name = "Browser Extension",
            description = "Manifest V3 extension with a background service worker and popup.",
            generatorId = GEN_BROWSER,
            languages = listOf(Language.JAVASCRIPT, Language.HTML, Language.JSON),
            buildSystem = BuildSystem.NONE,
        ),
        ProjectTemplate(
            id = "ext.intellij",
            category = ProjectCategory.EXTENSION,
            name = "IntelliJ Platform Plugin",
            description = "Gradle-based IDE plugin with plugin.xml and an action.",
            generatorId = GEN_INTELLIJ,
            languages = listOf(Language.KOTLIN, Language.XML, Language.GRADLE),
            buildSystem = BuildSystem.GRADLE,
        ),
        ProjectTemplate(
            id = "ext.gradleplugin",
            category = ProjectCategory.EXTENSION,
            name = "Gradle Plugin",
            description = "Build-logic plugin module with a task extension and tests.",
            generatorId = GEN_GRADLE_PLUGIN,
            languages = listOf(Language.KOTLIN, Language.GRADLE),
            buildSystem = BuildSystem.GRADLE,
        ),
        ProjectTemplate(
            id = "ext.npm",
            category = ProjectCategory.EXTENSION,
            name = "JavaScript Package",
            description = "npm package with ESM exports and a Node test.",
            generatorId = GEN_NPM_PLUGIN,
            languages = listOf(Language.JAVASCRIPT, Language.JSON),
            buildSystem = BuildSystem.NPM,
        ),
        ProjectTemplate(
            id = "ext.python",
            category = ProjectCategory.EXTENSION,
            name = "Python Package",
            description = "Installable package with pyproject.toml and pytest tests.",
            generatorId = GEN_PYTHON_PACKAGE,
            languages = listOf(Language.PYTHON, Language.TOML),
            buildSystem = BuildSystem.PIP,
        ),
    )

    val blankTemplates = listOf(
        ProjectTemplate(
            id = "blank.python",
            category = ProjectCategory.BLANK,
            name = "Python",
            description = "Python script package with a runnable entry point and pytest.",
            generatorId = GEN_PYTHON,
            languages = listOf(Language.PYTHON),
            buildSystem = BuildSystem.PIP,
        ),
        ProjectTemplate(
            id = "blank.node",
            category = ProjectCategory.BLANK,
            name = "JavaScript (Node)",
            description = "Node.js application with npm scripts and a test.",
            generatorId = GEN_NODE,
            languages = listOf(Language.JAVASCRIPT, Language.JSON),
            buildSystem = BuildSystem.NPM,
        ),
        ProjectTemplate(
            id = "blank.typescript",
            category = ProjectCategory.BLANK,
            name = "TypeScript",
            description = "TypeScript project with tsconfig, build and test scripts.",
            generatorId = GEN_TS_NODE,
            languages = listOf(Language.TYPESCRIPT, Language.JSON),
            buildSystem = BuildSystem.NPM,
        ),
        ProjectTemplate(
            id = "blank.rust",
            category = ProjectCategory.BLANK,
            name = "Rust",
            description = "Cargo binary crate with a unit test.",
            generatorId = GEN_RUST,
            languages = listOf(Language.RUST, Language.TOML),
            buildSystem = BuildSystem.CARGO,
        ),
        ProjectTemplate(
            id = "blank.go",
            category = ProjectCategory.BLANK,
            name = "Go",
            description = "Go module with a main package and a test file.",
            generatorId = GEN_GO,
            languages = listOf(Language.GO),
            buildSystem = BuildSystem.GO_MODULES,
        ),
        ProjectTemplate(
            id = "blank.cpp",
            category = ProjectCategory.BLANK,
            name = "C++ (CMake)",
            description = "CMake project with a main target and a test target.",
            generatorId = GEN_CMAKE,
            languages = listOf(Language.CPP, Language.C),
            buildSystem = BuildSystem.CMAKE,
        ),
        ProjectTemplate(
            id = "blank.kotlin-jvm",
            category = ProjectCategory.BLANK,
            name = "Kotlin (JVM)",
            description = "Kotlin JVM application with Gradle and a unit test.",
            generatorId = GEN_GRADLE_JVM,
            languages = listOf(Language.KOTLIN, Language.GRADLE),
            buildSystem = BuildSystem.GRADLE,
        ),
        ProjectTemplate(
            id = "blank.java-jvm",
            category = ProjectCategory.BLANK,
            name = "Java (JVM)",
            description = "Java application with Gradle and a JUnit test.",
            generatorId = GEN_GRADLE_JVM,
            languages = listOf(Language.JAVA, Language.GRADLE),
            buildSystem = BuildSystem.GRADLE,
        ),
    )

    val games: List<GameDefinition> = listOf(
        GameDefinition(
            id = "minecraft",
            name = "Minecraft",
            studio = "Mojang",
            summary = "Java and Bedrock editions. Server plugins and native tooling.",
            monogram = "MC",
            accent = 0xFF36D399,
            docsUrl = "https://minecraft.wiki/w/Java_Edition_protocol",
            frameworks = listOf(
                GameFramework(
                    id = "fabric",
                    name = "Fabric Mod",
                    language = Language.JAVA,
                    summary = "Lightweight Java mod loader with a Gradle Loom setup.",
                    templateId = "game.mc.fabric",
                    compatibility = "Builds a .jar for Minecraft Java Edition with the Fabric loader installed.",
                    targetRuntime = "Minecraft Java Edition (Fabric)",
                ),
                GameFramework(
                    id = "forge",
                    name = "Forge Mod",
                    language = Language.JAVA,
                    summary = "ForgeGradle mod skeleton with a mods.toml descriptor.",
                    templateId = "game.mc.forge",
                    compatibility = "Builds a .jar for Minecraft Java Edition with Forge installed.",
                    targetRuntime = "Minecraft Java Edition (Forge)",
                ),
                GameFramework(
                    id = "paper",
                    name = "Paper Plugin",
                    language = Language.JAVA,
                    summary = "Server-side Bukkit/Paper plugin with plugin.yml.",
                    templateId = "game.mc.paper",
                    compatibility = "Builds a server plugin .jar; it does not modify the client.",
                    targetRuntime = "Paper/Spigot server",
                ),
                GameFramework(
                    id = "native",
                    name = "Native C++ Module",
                    language = Language.CPP,
                    summary = "CMake shared-library skeleton for native tooling.",
                    templateId = "game.mc.native",
                    compatibility = "Builds a .so for the host ABI. It will NOT load in Minecraft on another platform; you must rebuild for each target.",
                    targetRuntime = "Android arm64-v8a / armeabi-v7a",
                ),
                GameFramework(
                    id = "bedrock",
                    name = "Bedrock Add-on",
                    language = Language.JSON,
                    summary = "Behaviour and resource pack structure with a manifest.",
                    templateId = "game.mc.bedrock",
                    compatibility = "Produces a .mcpack-compatible folder for Bedrock Edition; behaviour packs are data, not code.",
                    targetRuntime = "Minecraft Bedrock Edition",
                ),
            ),
        ),
        GameDefinition(
            id = "roblox",
            name = "Roblox",
            studio = "Roblox Corporation",
            summary = "Luau scripting and Studio tooling.",
            monogram = "RB",
            accent = 0xFFFF647C,
            docsUrl = "https://create.roblox.com/docs",
            frameworks = listOf(
                GameFramework(
                    id = "luau",
                    name = "Luau Script",
                    language = Language.LUA,
                    summary = "Script module with Rojo project metadata.",
                    templateId = "game.roblox.luau",
                    compatibility = "Produces .lua/.luau sources plus a Rojo project file; sync requires the Rojo Studio plugin.",
                    targetRuntime = "Roblox Studio via Rojo",
                ),
            ),
        ),
        GameDefinition(
            id = "gta",
            name = "GTA V",
            studio = "Rockstar Games",
            summary = "FiveM resources and script mods.",
            monogram = "GT",
            accent = 0xFFFFCA65,
            docsUrl = "https://docs.fivem.net/docs/",
            frameworks = listOf(
                GameFramework(
                    id = "fivem",
                    name = "FiveM Resource",
                    language = Language.LUA,
                    summary = "fxmanifest.lua resource with a client and server script.",
                    templateId = "game.gta.fivem",
                    compatibility = "Produces a resource folder for a FiveM server. It does not modify single-player GTA V.",
                    targetRuntime = "FiveM (GTA V multiplayer)",
                ),
                GameFramework(
                    id = "scripthook",
                    name = "ScriptHookVDotNet Script",
                    language = Language.CSHARP,
                    summary = "C# script skeleton for ScriptHookVDotNet.",
                    templateId = "game.gta.script",
                    compatibility = "Produces C# source; compiling requires the .NET SDK on Windows and the ScriptHookVDotNet assemblies.",
                    targetRuntime = "GTA V single-player (Windows)",
                    unsupportedReason = "Requires the .NET SDK and Windows-only game assemblies",
                ),
            ),
        ),
        GameDefinition(
            id = "unity",
            name = "Unity",
            studio = "Unity Technologies",
            summary = "C# scripts, packages and editor extensions.",
            monogram = "UN",
            accent = 0xFFB0B8C4,
            docsUrl = "https://docs.unity3d.com/",
            frameworks = listOf(
                GameFramework(
                    id = "csharp",
                    name = "Unity C# Script",
                    language = Language.CSHARP,
                    summary = "MonoBehaviour skeleton with asmdef and package manifest.",
                    templateId = "game.unity.csharp",
                    compatibility = "Produces C# sources and asmdef files; compiling requires the Unity Editor.",
                    targetRuntime = "Unity Editor",
                    unsupportedReason = "Requires the Unity Editor toolchain",
                ),
            ),
        ),
        GameDefinition(
            id = "unreal",
            name = "Unreal Engine",
            studio = "Epic Games",
            summary = "C++ modules and plugins.",
            monogram = "UE",
            accent = 0xFF64B5FF,
            docsUrl = "https://dev.epicgames.com/documentation/en-us/unreal-engine",
            frameworks = listOf(
                GameFramework(
                    id = "cpp",
                    name = "Unreal C++ Plugin",
                    language = Language.CPP,
                    summary = "Plugin descriptor with module and Build.cs.",
                    templateId = "game.unreal.cpp",
                    compatibility = "Produces plugin sources; building requires UnrealBuildTool and a desktop toolchain.",
                    targetRuntime = "Unreal Engine 5",
                    unsupportedReason = "Requires UnrealBuildTool on a desktop host",
                ),
            ),
        ),
    )

    val gameTemplates: List<ProjectTemplate> = listOf(
        ProjectTemplate(
            id = "game.mc.fabric",
            category = ProjectCategory.GAME,
            name = "Fabric Mod",
            description = "Fabric mod with fabric.mod.json, a mixin config and a Gradle Loom build.",
            generatorId = GEN_MINECRAFT_FABRIC,
            languages = listOf(Language.JAVA, Language.GRADLE, Language.JSON),
            buildSystem = BuildSystem.GRADLE,
            gameId = "minecraft",
            requirements = listOf("Minecraft Java Edition", "Fabric Loader"),
            docsUrl = "https://fabricmc.net/develop/",
        ),
        ProjectTemplate(
            id = "game.mc.forge",
            category = ProjectCategory.GAME,
            name = "Forge Mod",
            description = "ForgeGradle mod with a mods.toml descriptor and an event handler.",
            generatorId = GEN_MINECRAFT_FORGE,
            languages = listOf(Language.JAVA, Language.GRADLE, Language.TOML),
            buildSystem = BuildSystem.GRADLE,
            gameId = "minecraft",
            requirements = listOf("Minecraft Java Edition", "Forge"),
            docsUrl = "https://docs.minecraftforge.net/",
        ),
        ProjectTemplate(
            id = "game.mc.paper",
            category = ProjectCategory.GAME,
            name = "Paper Plugin",
            description = "Server plugin with plugin.yml, a command and a listener.",
            generatorId = GEN_MINECRAFT_PAPER,
            languages = listOf(Language.JAVA, Language.GRADLE, Language.YAML),
            buildSystem = BuildSystem.GRADLE,
            gameId = "minecraft",
            requirements = listOf("Paper or Spigot server"),
            docsUrl = "https://docs.papermc.io/",
        ),
        ProjectTemplate(
            id = "game.mc.native",
            category = ProjectCategory.GAME,
            name = "Native C++ Module",
            description = "CMake shared library for native tooling, built per ABI.",
            generatorId = GEN_MINECRAFT_NATIVE,
            languages = listOf(Language.CPP, Language.C),
            buildSystem = BuildSystem.CMAKE,
            gameId = "minecraft",
            requirements = listOf("CMake", "Android NDK for device builds"),
            docsUrl = "https://developer.android.com/ndk/guides/cmake",
        ),
        ProjectTemplate(
            id = "game.mc.bedrock",
            category = ProjectCategory.GAME,
            name = "Bedrock Add-on",
            description = "Behaviour + resource pack with a manifest and a custom item.",
            generatorId = GEN_MINECRAFT_BEDROCK,
            languages = listOf(Language.JSON),
            buildSystem = BuildSystem.NONE,
            gameId = "minecraft",
            requirements = listOf("Minecraft Bedrock Edition"),
            docsUrl = "https://learn.microsoft.com/minecraft/creator/",
        ),
        ProjectTemplate(
            id = "game.roblox.luau",
            category = ProjectCategory.GAME,
            name = "Luau Script",
            description = "ModuleScript with a Rojo default.project.json.",
            generatorId = GEN_ROBLOX_LUAU,
            languages = listOf(Language.LUA, Language.JSON),
            buildSystem = BuildSystem.NONE,
            gameId = "roblox",
            requirements = listOf("Roblox Studio", "Rojo plugin"),
            docsUrl = "https://rojo.space/docs/",
        ),
        ProjectTemplate(
            id = "game.gta.fivem",
            category = ProjectCategory.GAME,
            name = "FiveM Resource",
            description = "fxmanifest resource with client and server Lua scripts.",
            generatorId = GEN_GTA_FIVEM,
            languages = listOf(Language.LUA),
            buildSystem = BuildSystem.NONE,
            gameId = "gta",
            requirements = listOf("FiveM server"),
            docsUrl = "https://docs.fivem.net/docs/scripting-manual/",
        ),
        ProjectTemplate(
            id = "game.gta.script",
            category = ProjectCategory.GAME,
            name = "ScriptHookVDotNet Script",
            description = "C# script skeleton for GTA V single-player.",
            generatorId = GEN_GTA_SCRIPT,
            languages = listOf(Language.CSHARP),
            buildSystem = BuildSystem.DOTNET,
            gameId = "gta",
            requirements = listOf(
                ProjectTemplate.UNSUPPORTED_PREFIX + "Requires the .NET SDK and Windows-only game assemblies",
            ),
            docsUrl = "https://github.com/scripthookvdotnet/scripthookvdotnet",
        ),
        ProjectTemplate(
            id = "game.unity.csharp",
            category = ProjectCategory.GAME,
            name = "Unity C# Script",
            description = "MonoBehaviour with an assembly definition and package manifest.",
            generatorId = GEN_UNITY_CSHARP,
            languages = listOf(Language.CSHARP, Language.JSON),
            buildSystem = BuildSystem.NONE,
            gameId = "unity",
            requirements = listOf(ProjectTemplate.UNSUPPORTED_PREFIX + "Requires the Unity Editor"),
            docsUrl = "https://docs.unity3d.com/Manual/ScriptingSection.html",
        ),
        ProjectTemplate(
            id = "game.unreal.cpp",
            category = ProjectCategory.GAME,
            name = "Unreal C++ Plugin",
            description = "Plugin descriptor, module source and Build.cs.",
            generatorId = GEN_UNREAL_CPP,
            languages = listOf(Language.CPP, Language.CSHARP),
            buildSystem = BuildSystem.CMAKE,
            gameId = "unreal",
            requirements = listOf(ProjectTemplate.UNSUPPORTED_PREFIX + "Requires UnrealBuildTool on a desktop host"),
            docsUrl = "https://dev.epicgames.com/documentation/en-us/unreal-engine/plugins-in-unreal-engine",
        ),
    )

    val all: List<ProjectTemplate> = androidTemplates + extensionTemplates + blankTemplates + gameTemplates

    fun forCategory(category: ProjectCategory): List<ProjectTemplate> =
        all.filter { it.category == category }

    fun byId(id: String): ProjectTemplate? = all.firstOrNull { it.id == id }

    fun game(id: String): GameDefinition? = games.firstOrNull { it.id == id }
}
