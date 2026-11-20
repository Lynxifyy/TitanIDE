package dev.titanide.core.workbench

/**
 * Builds the Android Studio-style project tree ("Android" view) from a real file listing.
 *
 * Android Studio groups by meaning rather than by directory: manifests, sources, resources,
 * Gradle scripts, dependencies. Doing that well is a classification problem, so it lives
 * here as pure logic with tests rather than as branches inside a composable.
 */

/** A node in the project tree. [path] is always project-relative and slash separated. */
data class ProjectNode(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val children: List<ProjectNode> = emptyList(),
    val languageId: String = "plaintext",
    val group: AndroidGroup? = null,
) {
    val extension: String get() = name.substringAfterLast('.', "")
    val hasChildren: Boolean get() = children.isNotEmpty()
}

/** Sections Android Studio uses in the Android view. */
enum class AndroidGroup(val label: String, val order: Int) {
    MANIFESTS("Manifests", 0),
    SOURCES("Kotlin+Java", 1),
    GENERATED("Generated", 2),
    RESOURCES("res", 3),
    ASSETS("Assets", 4),
    GRADLE("Gradle Scripts", 5),
    OTHER("Other", 6),
}

object AndroidProjectView {

    private val manifestNames = setOf("AndroidManifest.xml")

    private val gradleNames = setOf(
        "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts",
        "gradle.properties", "gradlew", "gradlew.bat", "libs.versions.toml",
        "local.properties", "proguard-rules.pro", "gradle-wrapper.properties",
    )

    private val sourceExtensions = setOf("kt", "java", "kts")

    private val resourceExtensions = setOf(
        "xml", "png", "jpg", "jpeg", "webp", "svg", "9.png", "ttf", "otf",
    )

    /**
     * Classifies one project-relative path into an Android view section.
     *
     * Ordering matters: `app/build/generated/...` is generated code even though it lives
     * under a source directory, and `app/src/main/res/...` is a resource even though it is
     * an XML file, so the checks go from most specific to least.
     */
    fun classify(path: String): AndroidGroup {
        val segments = path.split('/')
        val fileName = segments.lastOrNull().orEmpty()
        val extension = fileName.substringAfterLast('.', "").lowercase()

        if (segments.any { it == "generated" }) return AndroidGroup.GENERATED
        if (fileName in manifestNames) return AndroidGroup.MANIFESTS
        if (segments.any { it == "res" }) return AndroidGroup.RESOURCES
        if (segments.any { it == "assets" }) return AndroidGroup.ASSETS
        if (fileName in gradleNames) return AndroidGroup.GRADLE
        if (fileName.startsWith("gradle-wrapper")) return AndroidGroup.GRADLE
        if (fileName == "libs.versions.toml") return AndroidGroup.GRADLE
        if (extension in sourceExtensions && segments.any { it == "src" }) return AndroidGroup.SOURCES
        if (extension in sourceExtensions) return AndroidGroup.SOURCES
        if (extension in resourceExtensions) return AndroidGroup.OTHER
        return AndroidGroup.OTHER
    }

    /**
     * Builds the Android view from a flat list of project-relative file paths.
     *
     * Only files are given; directories are inferred, so the caller can pass whatever the
     * filesystem walk produced without pre-processing.
     */
    fun build(filePaths: List<String>): List<ProjectNode> {
        if (filePaths.isEmpty()) return emptyList()

        val grouped = filePaths
            .filter { it.isNotBlank() }
            .groupBy { classify(it) }

        return AndroidGroup.entries.mapNotNull { group ->
            val paths = grouped[group] ?: return@mapNotNull null
            val children = buildSubtrees(paths)
            ProjectNode(
                name = group.label,
                path = "android://${group.name.lowercase()}",
                isDirectory = true,
                children = children,
                group = group,
            )
        }.sortedBy { it.group?.order ?: Int.MAX_VALUE }
    }

    /**
     * Turns a flat path list into nested nodes. Common single-child directory chains are
     * collapsed the way Android Studio does (`com/example/app` stays one row).
     */
    fun buildSubtrees(paths: List<String>): List<ProjectNode> = buildLevel(paths, "")

    private fun buildLevel(paths: List<String>, prefix: String): List<ProjectNode> {
        val directFiles = ArrayList<ProjectNode>()
        val directories = LinkedHashMap<String, MutableList<String>>()

        for (path in paths) {
            val remainder = if (prefix.isEmpty()) path else path.removePrefix("$prefix/")
            if (remainder == path && prefix.isNotEmpty()) continue
            val slash = remainder.indexOf('/')
            if (slash == -1) {
                directFiles += ProjectNode(
                    name = remainder,
                    path = path,
                    isDirectory = false,
                    languageId = LanguageDetector.detect(remainder),
                )
            } else {
                val dirName = remainder.substring(0, slash)
                directories.getOrPut(dirName) { mutableListOf() }.add(path)
            }
        }

        val dirNodes = directories.map { (name, childPaths) ->
            val childPrefix = if (prefix.isEmpty()) name else "$prefix/$name"
            ProjectNode(
                name = name,
                path = childPrefix,
                isDirectory = true,
                children = buildLevel(childPaths, childPrefix),
            )
        }

        return (dirNodes.sortedBy { it.name.lowercase() } + directFiles.sortedBy { it.name.lowercase() })
    }

    /** Plain filesystem view: every directory and file, no classification. */
    fun buildProjectView(filePaths: List<String>): List<ProjectNode> = buildLevel(filePaths.filter { it.isNotBlank() }, "")
}
