package dev.titanide.core.data.files

import java.io.File

/**
 * Scans a project directory for assets, declared UI previews and documentation.
 *
 * Kept separate from the ViewModel so the traversal rules (which directories are skipped,
 * which extensions count as assets) are unit-testable without an Android runtime.
 */
object ProjectScanner {

    private val SKIP_DIRECTORIES = setOf("build", "node_modules", ".git", ".gradle", ".idea", "vendor")
    private const val MAX_FILES = 6000
    private const val MAX_PREVIEW_SOURCE_BYTES = 512L * 1024

    data class Asset(val path: String, val kind: String, val size: Long)

    data class Preview(val path: String, val kind: String, val detail: String)

    data class Scan(
        val assets: List<Asset>,
        val previews: List<Preview>,
        val documents: List<String>,
    ) {
        val isEmpty: Boolean get() = assets.isEmpty() && previews.isEmpty() && documents.isEmpty()
    }

    private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif", "svg")
    private val FONT_EXTENSIONS = setOf("ttf", "otf", "woff", "woff2")
    private val RESOURCE_EXTENSIONS = setOf("json", "xml", "yaml", "yml")
    private val DOC_EXTENSIONS = setOf("md", "markdown")
    private val SOURCE_EXTENSIONS = setOf("kt", "java")

    fun scan(root: File, maxFiles: Int = MAX_FILES): Scan {
        if (!root.isDirectory) return Scan(emptyList(), emptyList(), emptyList())

        val assets = ArrayList<Asset>()
        val previews = ArrayList<Preview>()
        val documents = ArrayList<String>()
        var visited = 0

        root.walkTopDown()
            .onEnter { it.name !in SKIP_DIRECTORIES && !it.name.startsWith(".") }
            .filter { it.isFile }
            .forEach { file ->
                if (visited >= maxFiles) return@forEach
                visited++

                val relative = file.relativeTo(root).path.replace(File.separatorChar, '/')
                val extension = file.extension.lowercase()

                when {
                    extension in IMAGE_EXTENSIONS ->
                        assets += Asset(relative, extension.uppercase(), file.length())
                    extension in FONT_EXTENSIONS ->
                        assets += Asset(relative, "FONT", file.length())
                    extension in RESOURCE_EXTENSIONS && isResourcePath(relative) ->
                        assets += Asset(relative, extension.uppercase(), file.length())
                }

                when {
                    extension in DOC_EXTENSIONS -> documents += relative
                    extension == "xml" && isLayoutPath(relative) ->
                        previews += Preview(relative, "layout", "")
                    extension in SOURCE_EXTENSIONS -> previewNames(file)?.let { names ->
                        previews += Preview(relative, "compose", names)
                    }
                }
            }

        return Scan(
            assets = assets.sortedBy { it.path },
            previews = previews.sortedBy { it.path },
            documents = documents.sorted(),
        )
    }

    private fun isResourcePath(relative: String): Boolean =
        relative.contains("res/") || relative.contains("assets/")

    private fun isLayoutPath(relative: String): Boolean =
        relative.contains("layout")

    /** Returns the names of functions annotated with `@Preview`, or null when there are none. */
    private fun previewNames(file: File): String? {
        if (file.length() > MAX_PREVIEW_SOURCE_BYTES) return null
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        if (!text.contains("@Preview")) return null
        val names = PREVIEW_REGEX.findAll(text).map { it.groupValues[1] }.toList()
        return names.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }

    private val PREVIEW_REGEX = Regex("@Preview[\\s\\S]{0,400}?fun\\s+(\\w+)")

    /** True when [extension] names a file that can be shown as text in a preview pane. */
    fun isTextual(extension: String): Boolean =
        extension.lowercase() in setOf("json", "xml", "yaml", "yml", "svg", "txt", "md", "kt", "java", "css")
}
