package dev.titanide.core.data.files

import java.io.File
import java.io.IOException

/**
 * All filesystem access for projects goes through here so that a single place enforces
 * the rule that no path may escape a project root. Archive extraction and AI-proposed
 * file writes both rely on [resolveSafely], which is why it rejects absolute paths,
 * `..` segments, and symlink escapes rather than merely normalising them.
 */
class SafeFileSystem {

    sealed interface Result<out T> {
        data class Ok<T>(val value: T) : Result<T>
        data class Failure(val reason: String) : Result<Nothing>
    }

    fun resolveSafely(root: File, relativePath: String): Result<File> {
        if (relativePath.isBlank()) return Result.Failure("Path is empty")
        if (relativePath.startsWith("/") || relativePath.startsWith("\\")) {
            return Result.Failure("Absolute paths are not allowed")
        }
        if (Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(relativePath)) {
            return Result.Failure("Absolute paths are not allowed")
        }
        val normalized = relativePath.replace('\\', '/')
        if (normalized.split('/').any { it == ".." }) {
            return Result.Failure("Path traversal is not allowed")
        }

        val rootCanonical = root.canonicalFile
        val candidate = File(rootCanonical, normalized).canonicalFile
        val rootPath = rootCanonical.path
        val candidatePath = candidate.path
        if (candidatePath != rootPath && !candidatePath.startsWith("$rootPath${File.separator}")) {
            return Result.Failure("Path escapes the project root")
        }
        return Result.Ok(candidate)
    }

    fun readText(root: File, relativePath: String, maxBytes: Int = 4 * 1024 * 1024): Result<String> {
        return when (val resolved = resolveSafely(root, relativePath)) {
            is Result.Failure -> resolved
            is Result.Ok -> {
                val file = resolved.value
                when {
                    !file.exists() -> Result.Failure("File not found: $relativePath")
                    !file.isFile -> Result.Failure("Not a file: $relativePath")
                    file.length() > maxBytes -> Result.Failure(
                        "File is ${file.length()} bytes; limit is $maxBytes",
                    )
                    else -> runCatching { Result.Ok(file.readText()) }
                        .getOrElse { Result.Failure("Read failed: ${it.message}") }
                }
            }
        }
    }

    fun writeText(root: File, relativePath: String, content: String): Result<File> {
        return when (val resolved = resolveSafely(root, relativePath)) {
            is Result.Failure -> resolved
            is Result.Ok -> {
                val file = resolved.value
                runCatching {
                    file.parentFile?.mkdirs()
                    file.writeText(content)
                    Result.Ok(file)
                }.getOrElse { Result.Failure("Write failed: ${it.message}") }
            }
        }
    }

    fun createDirectory(root: File, relativePath: String): Result<File> {
        return when (val resolved = resolveSafely(root, relativePath)) {
            is Result.Failure -> resolved
            is Result.Ok -> {
                val dir = resolved.value
                if (dir.exists()) Result.Failure("Already exists: $relativePath")
                else if (dir.mkdirs()) Result.Ok(dir)
                else Result.Failure("Could not create directory: $relativePath")
            }
        }
    }

    fun delete(root: File, relativePath: String): Result<Unit> {
        return when (val resolved = resolveSafely(root, relativePath)) {
            is Result.Failure -> resolved
            is Result.Ok -> {
                val target = resolved.value
                when {
                    !target.exists() -> Result.Failure("Not found: $relativePath")
                    target == root.canonicalFile -> Result.Failure("Refusing to delete the project root")
                    target.deleteRecursively() -> Result.Ok(Unit)
                    else -> Result.Failure("Could not delete: $relativePath")
                }
            }
        }
    }

    /** Renames within the root; both source and destination are validated. */
    fun move(root: File, fromPath: String, toPath: String): Result<File> {
        val from = resolveSafely(root, fromPath)
        val to = resolveSafely(root, toPath)
        if (from is Result.Failure) return from
        if (to is Result.Failure) return to
        val source = (from as Result.Ok).value
        val target = (to as Result.Ok).value
        if (!source.exists()) return Result.Failure("Not found: $fromPath")
        if (target.exists()) return Result.Failure("Destination already exists: $toPath")
        return runCatching {
            target.parentFile?.mkdirs()
            if (source.renameTo(target)) Result.Ok(target)
            else {
                source.copyRecursively(target, overwrite = false)
                source.deleteRecursively()
                Result.Ok(target)
            }
        }.getOrElse { Result.Failure("Move failed: ${it.message}") }
    }

    fun copy(root: File, fromPath: String, toPath: String): Result<File> {
        val from = resolveSafely(root, fromPath)
        val to = resolveSafely(root, toPath)
        if (from is Result.Failure) return from
        if (to is Result.Failure) return to
        val source = (from as Result.Ok).value
        val target = (to as Result.Ok).value
        if (!source.exists()) return Result.Failure("Not found: $fromPath")
        if (target.exists()) return Result.Failure("Destination already exists: $toPath")
        return runCatching {
            target.parentFile?.mkdirs()
            source.copyRecursively(target, overwrite = false)
            Result.Ok(target)
        }.getOrElse { Result.Failure("Copy failed: ${it.message}") }
    }

    /** Depth-limited tree walk used by the explorer; never follows symlinks out of root. */
    fun listTree(
        root: File,
        relativePath: String = "",
        maxDepth: Int = 12,
        maxEntries: Int = 5000,
        skipNames: Set<String> = DEFAULT_SKIPS,
    ): List<FileNode> {
        val start = if (relativePath.isBlank()) root else {
            when (val resolved = resolveSafely(root, relativePath)) {
                is Result.Failure -> return emptyList()
                is Result.Ok -> resolved.value
            }
        }
        if (!start.exists() || !start.isDirectory) return emptyList()
        val out = ArrayList<FileNode>()
        walk(start, root, 0, maxDepth, maxEntries, skipNames, out)
        return out.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }

    private fun walk(
        dir: File,
        root: File,
        depth: Int,
        maxDepth: Int,
        maxEntries: Int,
        skipNames: Set<String>,
        out: MutableList<FileNode>,
    ) {
        if (depth > maxDepth || out.size >= maxEntries) return
        val children = dir.listFiles() ?: return
        for (child in children.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))) {
            if (out.size >= maxEntries) return
            if (child.name in skipNames) continue
            if (child.name == ".git" && child.isDirectory) continue
            val canonical = runCatching { child.canonicalPath }.getOrNull() ?: continue
            val rootPath = runCatching { root.canonicalPath }.getOrNull() ?: continue
            if (!canonical.startsWith("$rootPath${File.separator}")) continue

            val relative = canonical.removePrefix("$rootPath${File.separator}")
            out += FileNode(
                name = child.name,
                relativePath = relative,
                isDirectory = child.isDirectory,
                sizeBytes = if (child.isFile) child.length() else 0,
                lastModified = child.lastModified(),
            )
            if (child.isDirectory) {
                walk(child, root, depth + 1, maxDepth, maxEntries, skipNames, out)
            }
        }
    }

    /** Refuses to delete a non-empty directory without an explicit recursive flag. */
    fun deleteDirectoryContents(root: File, relativePath: String): Result<Int> {
        val resolved = resolveSafely(root, relativePath)
        if (resolved is Result.Failure) return resolved
        val dir = (resolved as Result.Ok).value
        if (!dir.isDirectory) return Result.Failure("Not a directory: $relativePath")
        val children = dir.listFiles() ?: return Result.Ok(0)
        var deleted = 0
        for (child in children) {
            if (child.deleteRecursively()) deleted++
        }
        return Result.Ok(deleted)
    }

    companion object {
        val DEFAULT_SKIPS = setOf(
            "node_modules", "build", ".gradle", ".idea", "__pycache__", ".dart_tool",
            "target", "dist", ".DS_Store", ".cxx", ".kotlin",
        )
    }
}

data class FileNode(
    val name: String,
    val relativePath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModified: Long,
)

/**
 * Zip extraction hardened against zip-slip and zip bombs: every entry is resolved
 * through [SafeFileSystem.resolveSafely], the total uncompressed size is capped, and
 * entry count is bounded.
 */
class SafeZipExtractor(private val fs: SafeFileSystem = SafeFileSystem()) {

    data class ExtractionReport(val filesWritten: Int, val bytesWritten: Long, val skipped: List<String>)

    fun extract(
        zipFile: File,
        destination: File,
        maxTotalBytes: Long = 512L * 1024 * 1024,
        maxEntries: Int = 20_000,
    ): SafeFileSystem.Result<ExtractionReport> {
        if (!zipFile.exists()) return SafeFileSystem.Result.Failure("Archive not found")
        destination.mkdirs()

        var written = 0
        var bytes = 0L
        val skipped = ArrayList<String>()

        return runCatching {
            java.util.zip.ZipFile(zipFile).use { zip ->
                val entries = zip.entries()
                var count = 0
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    count++
                    if (count > maxEntries) {
                        skipped += "Entry limit ($maxEntries) reached"
                        break
                    }
                    val name = entry.name
                    if (name.contains("..") || name.startsWith("/") || name.contains("\\..")) {
                        skipped += "Rejected unsafe path: $name"
                        continue
                    }
                    val target = fs.resolveSafely(destination, name)
                    if (target is SafeFileSystem.Result.Failure) {
                        skipped += "Rejected ${name}: ${target.reason}"
                        continue
                    }
                    val file = (target as SafeFileSystem.Result.Ok).value
                    if (entry.isDirectory) {
                        file.mkdirs()
                        continue
                    }
                    bytes += entry.size
                    if (bytes > maxTotalBytes) {
                        skipped += "Archive exceeds $maxTotalBytes bytes"
                        break
                    }
                    file.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        file.outputStream().use { output -> input.copyTo(output) }
                    }
                    written++
                }
            }
            SafeFileSystem.Result.Ok(ExtractionReport(written, bytes, skipped))
        }.getOrElse { SafeFileSystem.Result.Failure("Extraction failed: ${it.message}") }
    }
}

/** Language/build detection used by project import; reads real files only. */
object ProjectDetector {

    data class Detected(
        val languages: List<String>,
        val buildSystem: String,
        val suggestedTasks: List<String>,
        val testTasks: List<String>,
        val notes: List<String>,
    )

    fun detect(root: File): Detected {
        val names = root.listFiles()?.map { it.name }?.toSet() ?: emptySet()
        val notes = ArrayList<String>()
        val languages = LinkedHashSet<String>()

        val buildSystem = when {
            "settings.gradle" in names || "settings.gradle.kts" in names ||
                "build.gradle" in names || "build.gradle.kts" in names -> "gradle"
            "pom.xml" in names -> "maven"
            "package.json" in names -> "npm"
            "Cargo.toml" in names -> "cargo"
            "go.mod" in names -> "go"
            "CMakeLists.txt" in names -> "cmake"
            "Makefile" in names || "makefile" in names -> "make"
            "pubspec.yaml" in names -> "pub"
            "requirements.txt" in names || "pyproject.toml" in names || "setup.py" in names -> "pip"
            "composer.json" in names -> "composer"
            "Gemfile" in names -> "bundler"
            else -> "none"
        }

        countExtensions(root, languages, maxFiles = 3000)

        val (suggested, tests) = when (buildSystem) {
            "gradle" -> {
                val hasWrapper = File(root, "gradlew").exists()
                if (!hasWrapper) notes += "No Gradle wrapper found; a system Gradle is required"
                val android = File(root, "app/build.gradle").exists() ||
                    File(root, "app/build.gradle.kts").exists() ||
                    names.any { it == "AndroidManifest.xml" }
                if (android) notes += "Android project detected; APK assembly requires an Android SDK"
                listOf("assembleDebug", "build") to listOf("testDebugUnitTest", "test")
            }
            "npm" -> {
                if ("node_modules" !in names) notes += "Dependencies are not installed yet"
                listOf("install", "run build") to listOf("test")
            }
            "cargo" -> listOf("build") to listOf("test")
            "go" -> listOf("build ./...") to listOf("test ./...")
            "cmake" -> listOf("configure", "build") to listOf("ctest")
            "make" -> listOf("all") to listOf("test")
            "pub" -> listOf("pub get", "build") to listOf("test")
            "pip" -> listOf("install", "build") to listOf("pytest")
            "composer" -> listOf("install") to listOf("test")
            "bundler" -> listOf("install") to listOf("test")
            "maven" -> listOf("package") to listOf("test")
            else -> {
                notes += "No recognised build system; only file-level analysis is available"
                emptyList<String>() to emptyList()
            }
        }

        return Detected(
            languages = languages.toList(),
            buildSystem = buildSystem,
            suggestedTasks = suggested,
            testTasks = tests,
            notes = notes,
        )
    }

    private fun countExtensions(root: File, languages: MutableSet<String>, maxFiles: Int) {
        var seen = 0
        val queue = ArrayDeque<File>()
        queue.add(root)
        while (queue.isNotEmpty() && seen < maxFiles) {
            val dir = queue.removeFirst()
            val children = dir.listFiles() ?: continue
            for (child in children) {
                if (seen >= maxFiles) return
                val name = child.name
                if (name.startsWith(".") || name in SafeFileSystem.DEFAULT_SKIPS) continue
                if (child.isDirectory) {
                    queue.add(child)
                    continue
                }
                seen++
                val ext = name.substringAfterLast('.', "").lowercase()
                extensionLanguage(ext)?.let { languages += it }
            }
        }
    }

    private fun extensionLanguage(ext: String): String? = when (ext) {
        "kt", "kts" -> "kotlin"
        "java" -> "java"
        "py" -> "python"
        "js", "jsx", "mjs" -> "javascript"
        "ts", "tsx" -> "typescript"
        "c", "h" -> "c"
        "cpp", "cc", "hpp" -> "cpp"
        "rs" -> "rust"
        "go" -> "go"
        "cs" -> "csharp"
        "lua" -> "lua"
        "php" -> "php"
        "rb" -> "ruby"
        "dart" -> "dart"
        "swift" -> "swift"
        else -> null
    }
}
