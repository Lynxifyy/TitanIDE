package dev.titanide.core.engine

import java.io.File

/**
 * What kind of Gradle project a directory holds. The variant name that must be passed to a
 * task depends on this, and getting it wrong is not cosmetic: `assembleDebug` on a pure
 * Kotlin/JVM or a Compose Multiplatform project fails with "task not found", and
 * `testDebugUnitTest` fails the same way. The user sees a build error that has nothing to
 * do with their code.
 */
enum class GradleProjectKind {
    /** A JVM library or application: no Android plugin. */
    JVM,

    /** An Android application module. */
    ANDROID_APP,

    /** An Android library module. */
    ANDROID_LIBRARY,

    /** An Android project that also ships native code through the NDK. */
    ANDROID_NATIVE,

    /** Compose Multiplatform / Kotlin Multiplatform. */
    MULTIPLATFORM,
}

/**
 * A resolved Gradle invocation: the tasks to run and the kind of project they target.
 */
data class GradleTaskSet(
    val kind: GradleProjectKind,
    val buildTasks: List<String>,
    val testTasks: List<String>,
    val cleanTask: String,
    val runTask: String?,
    val artifactGlobs: List<String>,
    /** Human-readable evidence for the choice, surfaced in the build log. */
    val evidence: String,
)

/**
 * Chooses Gradle tasks by reading the project's build scripts and module layout.
 *
 * Detection is intentionally shallow and textual: it looks at plugin declarations and
 * module directories, which are stable across Gradle versions, rather than executing
 * Gradle to ask for its task list. Running Gradle just to decide what to run would double
 * the cost of every build and would itself fail on a project whose wrapper is missing.
 */
object GradleTaskPlanner {

    fun plan(root: File): GradleTaskSet {
        val kind = detectKind(root)
        val evidence = evidenceFor(root, kind)
        return when (kind) {
            GradleProjectKind.ANDROID_APP -> GradleTaskSet(
                kind = kind,
                buildTasks = listOf("assembleDebug"),
                testTasks = listOf("testDebugUnitTest"),
                cleanTask = "clean",
                runTask = "installDebug",
                artifactGlobs = listOf(
                    "*/build/outputs/apk/debug/*.apk",
                    "build/outputs/apk/debug/*.apk",
                    "*/build/outputs/apk/release/*.apk",
                    "build/outputs/apk/release/*.apk",
                ),
                evidence = evidence,
            )
            GradleProjectKind.ANDROID_LIBRARY -> GradleTaskSet(
                kind = kind,
                buildTasks = listOf("assembleDebug"),
                testTasks = listOf("testDebugUnitTest"),
                cleanTask = "clean",
                runTask = null,
                artifactGlobs = listOf(
                    "*/build/outputs/aar/*.aar",
                    "build/outputs/aar/*.aar",
                    "*/build/libs/*.jar",
                    "build/libs/*.jar",
                ),
                evidence = evidence,
            )
            GradleProjectKind.ANDROID_NATIVE -> GradleTaskSet(
                kind = kind,
                buildTasks = listOf("assembleDebug"),
                testTasks = listOf("testDebugUnitTest"),
                cleanTask = "clean",
                runTask = "installDebug",
                artifactGlobs = listOf(
                    "*/build/outputs/apk/debug/*.apk",
                    "build/outputs/apk/debug/*.apk",
                    "*/build/intermediates/merged_native_libs/debug/**/*.so",
                    "*/build/intermediates/stripped_native_libs/debug/**/*.so",
                ),
                evidence = evidence,
            )
            GradleProjectKind.MULTIPLATFORM -> GradleTaskSet(
                kind = kind,
                buildTasks = listOf("build"),
                testTasks = listOf("allTests"),
                cleanTask = "clean",
                runTask = null,
                artifactGlobs = listOf(
                    "*/build/libs/*.jar",
                    "build/libs/*.jar",
                    "*/build/outputs/aar/*.aar",
                ),
                evidence = evidence,
            )
            GradleProjectKind.JVM -> GradleTaskSet(
                kind = kind,
                buildTasks = listOf("build"),
                testTasks = listOf("test"),
                cleanTask = "clean",
                runTask = "run",
                artifactGlobs = listOf(
                    "*/build/libs/*.jar",
                    "build/libs/*.jar",
                    "*/build/distributions/*.zip",
                ),
                evidence = evidence,
            )
        }
    }

    /**
     * Decides the project kind from the plugins actually applied.
     *
     * The Android plugin can be declared as `com.android.application` in a `plugins {}`
     * block, or as `apply plugin: 'com.android.application'`, or through the legacy
     * `android {}` extension, so all three shapes are recognised. The NDK check looks for
     * an external-native-build block or a CMakeLists in any module.
     */
    fun detectKind(root: File): GradleProjectKind {
        val buildFiles = buildScripts(root)
        if (buildFiles.isEmpty()) return GradleProjectKind.JVM
        val text = buildFiles.joinToString("\n") { runCatching { it.readText() }.getOrDefault("") }

        val androidApp = text.contains("com.android.application")
        val androidLib = text.contains("com.android.library")
        val androidDynamic = text.contains("com.android.dynamic-feature")
        val multiplatform = text.contains("org.jetbrains.kotlin.multiplatform") ||
            text.contains("kotlin(\"multiplatform\")") ||
            text.contains("kotlin(\"org.jetbrains.compose\")") ||
            text.contains("org.jetbrains.compose")
        val hasNative = text.contains("externalNativeBuild") ||
            text.contains("ndkVersion") ||
            root.walkTopDown().maxDepth(3).any { it.isFile && it.name == "CMakeLists.txt" }
        val hasAndroidExtension = text.contains("compileSdk") || text.contains("minSdk")

        return when {
            androidApp || androidDynamic -> {
                if (hasNative) GradleProjectKind.ANDROID_NATIVE else GradleProjectKind.ANDROID_APP
            }
            androidLib -> GradleProjectKind.ANDROID_LIBRARY
            multiplatform -> GradleProjectKind.MULTIPLATFORM
            // A root script with an `android {}` block but no recognisable plugin id is
            // still an Android project in the common single-module layout.
            hasAndroidExtension -> GradleProjectKind.ANDROID_APP
            else -> GradleProjectKind.JVM
        }
    }

    /** Build scripts in the root and one level of modules, which covers standard layouts. */
    private fun buildScripts(root: File): List<File> {
        val names = setOf("build.gradle", "build.gradle.kts")
        val direct = root.listFiles()?.filter { it.isFile && it.name in names }.orEmpty()
        val modules = root.listFiles()
            ?.filter { it.isDirectory && it.name !in SKIP_DIRECTORIES }
            ?.flatMap { dir ->
                dir.listFiles()?.filter { it.isFile && it.name in names }.orEmpty()
            }
            .orEmpty()
        return direct + modules
    }

    private fun evidenceFor(root: File, kind: GradleProjectKind): String {
        val modules = buildScripts(root).joinToString(", ") { it.relativeTo(root).path }
        val label = when (kind) {
            GradleProjectKind.JVM -> "no Android plugin applied"
            GradleProjectKind.ANDROID_APP -> "com.android.application"
            GradleProjectKind.ANDROID_LIBRARY -> "com.android.library"
            GradleProjectKind.ANDROID_NATIVE -> "Android plugin plus native build"
            GradleProjectKind.MULTIPLATFORM -> "Kotlin/Compose Multiplatform plugin"
        }
        return "Gradle project type: $label (scripts: ${modules.ifEmpty { "none" }})"
    }

    private val SKIP_DIRECTORIES = setOf(
        "build", ".gradle", ".git", "node_modules", "gradle", ".idea", "src",
    )
}
