package dev.titanide.core.data.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SafeFileSystemTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val fs = SafeFileSystem()

    private fun newRoot(): File = temp.newFolder("project")

    @Test
    fun `normal relative path resolves inside root`() {
        val root = newRoot()
        val result = fs.resolveSafely(root, "src/main/Main.kt")
        assertTrue(result is SafeFileSystem.Result.Ok)
        val file = (result as SafeFileSystem.Result.Ok).value
        assertTrue(file.path.startsWith(root.canonicalPath))
    }

    @Test
    fun `parent traversal is rejected`() {
        val root = newRoot()
        val result = fs.resolveSafely(root, "../escape.txt")
        assertTrue(result is SafeFileSystem.Result.Failure)
        assertTrue((result as SafeFileSystem.Result.Failure).reason.contains("traversal"))
    }

    @Test
    fun `nested traversal is rejected`() {
        val root = newRoot()
        assertTrue(fs.resolveSafely(root, "a/b/../../../etc/passwd") is SafeFileSystem.Result.Failure)
    }

    @Test
    fun `absolute unix path is rejected`() {
        val root = newRoot()
        assertTrue(fs.resolveSafely(root, "/etc/passwd") is SafeFileSystem.Result.Failure)
    }

    @Test
    fun `absolute windows path is rejected`() {
        val root = newRoot()
        assertTrue(fs.resolveSafely(root, "C:\\Windows\\system32") is SafeFileSystem.Result.Failure)
    }

    @Test
    fun `empty path is rejected`() {
        val root = newRoot()
        assertTrue(fs.resolveSafely(root, "   ") is SafeFileSystem.Result.Failure)
    }

    @Test
    fun `write then read round trips`() {
        val root = newRoot()
        assertTrue(fs.writeText(root, "notes/todo.md", "# Tasks") is SafeFileSystem.Result.Ok)
        val read = fs.readText(root, "notes/todo.md")
        assertTrue(read is SafeFileSystem.Result.Ok)
        assertEquals("# Tasks", (read as SafeFileSystem.Result.Ok).value)
    }

    @Test
    fun `write outside root is refused`() {
        val root = newRoot()
        assertTrue(fs.writeText(root, "../outside.txt", "nope") is SafeFileSystem.Result.Failure)
        assertTrue(!File(root.parentFile, "outside.txt").exists())
    }

    @Test
    fun `reading a missing file reports failure not exception`() {
        val root = newRoot()
        assertTrue(fs.readText(root, "missing.txt") is SafeFileSystem.Result.Failure)
    }

    @Test
    fun `oversized file is refused`() {
        val root = newRoot()
        fs.writeText(root, "big.txt", "x".repeat(2048))
        assertTrue(fs.readText(root, "big.txt", maxBytes = 1024) is SafeFileSystem.Result.Failure)
    }

    @Test
    fun `delete refuses the project root itself`() {
        val root = newRoot()
        assertTrue(fs.delete(root, ".") is SafeFileSystem.Result.Failure)
    }

    @Test
    fun `delete removes a nested directory`() {
        val root = newRoot()
        fs.writeText(root, "tmp/a.txt", "a")
        fs.writeText(root, "tmp/b/c.txt", "c")
        assertTrue(fs.delete(root, "tmp") is SafeFileSystem.Result.Ok)
        assertTrue(!File(root, "tmp").exists())
    }

    @Test
    fun `move renames within root and rejects cross-root targets`() {
        val root = newRoot()
        fs.writeText(root, "old.txt", "content")
        assertTrue(fs.move(root, "old.txt", "new.txt") is SafeFileSystem.Result.Ok)
        assertTrue(File(root, "new.txt").exists())
        assertTrue(fs.move(root, "new.txt", "../escaped.txt") is SafeFileSystem.Result.Failure)
    }

    @Test
    fun `move refuses to overwrite an existing destination`() {
        val root = newRoot()
        fs.writeText(root, "a.txt", "a")
        fs.writeText(root, "b.txt", "b")
        assertTrue(fs.move(root, "a.txt", "b.txt") is SafeFileSystem.Result.Failure)
        assertEquals("b", File(root, "b.txt").readText())
    }

    @Test
    fun `copy duplicates a file inside root`() {
        val root = newRoot()
        fs.writeText(root, "a.txt", "a")
        assertTrue(fs.copy(root, "a.txt", "b.txt") is SafeFileSystem.Result.Ok)
        assertEquals("a", File(root, "b.txt").readText())
    }

    @Test
    fun `listTree skips build directories and puts directories first`() {
        val root = newRoot()
        fs.writeText(root, "src/Main.kt", "fun main() {}")
        fs.writeText(root, "build/output.jar", "x")
        fs.writeText(root, "README.md", "# readme")

        val nodes = fs.listTree(root)
        val paths = nodes.map { it.relativePath }
        assertTrue(paths.any { it == "src" })
        assertTrue(paths.none { it.startsWith("build") })
        assertTrue(paths.any { it == "README.md" })
        assertTrue("directories should come first", nodes.first().isDirectory)
    }

    @Test
    fun `listTree honours the entry cap`() {
        val root = newRoot()
        repeat(30) { fs.writeText(root, "f$it.txt", "x") }
        assertTrue(fs.listTree(root, maxEntries = 10).size <= 10)
    }

    @Test
    fun `listTree on a missing subpath returns empty`() {
        val root = newRoot()
        assertTrue(fs.listTree(root, "does/not/exist").isEmpty())
    }
}

class SafeZipExtractorTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val extractor = SafeZipExtractor()

    private fun zipWith(entries: Map<String, String>): File {
        val file = temp.newFile("archive.zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return file
    }

    @Test
    fun `valid archive extracts all entries`() {
        val dest = temp.newFolder("out")
        val zip = zipWith(mapOf("src/A.kt" to "class A", "README.md" to "# hi"))
        val result = extractor.extract(zip, dest)
        assertTrue(result is SafeFileSystem.Result.Ok)
        val report = (result as SafeFileSystem.Result.Ok).value
        assertEquals(2, report.filesWritten)
        assertEquals("class A", File(dest, "src/A.kt").readText())
    }

    @Test
    fun `zip slip entry is skipped and nothing is written outside`() {
        val dest = temp.newFolder("out")
        val zip = zipWith(mapOf("../escaped.txt" to "evil", "safe.txt" to "ok"))
        val result = extractor.extract(zip, dest)
        val report = (result as SafeFileSystem.Result.Ok).value
        assertEquals(1, report.filesWritten)
        assertTrue(report.skipped.isNotEmpty())
        assertTrue(!File(dest.parentFile, "escaped.txt").exists())
    }

    @Test
    fun `oversized archive stops early`() {
        val dest = temp.newFolder("out")
        val zip = zipWith(mapOf("big.txt" to "x".repeat(5000)))
        val result = extractor.extract(zip, dest, maxTotalBytes = 100)
        val report = (result as SafeFileSystem.Result.Ok).value
        assertEquals(0, report.filesWritten)
        assertTrue(report.skipped.any { it.contains("exceeds") })
    }

    @Test
    fun `missing archive reports failure`() {
        val dest = temp.newFolder("out")
        assertTrue(extractor.extract(File(temp.root, "nope.zip"), dest) is SafeFileSystem.Result.Failure)
    }
}

class ProjectDetectorTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `gradle android project is detected with test tasks`() {
        val root = temp.newFolder("app")
        File(root, "settings.gradle.kts").writeText("")
        File(root, "gradlew").writeText("")
        File(root, "app").mkdirs()
        File(root, "app/build.gradle.kts").writeText("")
        File(root, "app/src").mkdirs()
        File(root, "app/src/MainActivity.kt").writeText("class MainActivity")

        val detected = ProjectDetector.detect(root)
        assertEquals("gradle", detected.buildSystem)
        assertTrue(detected.languages.contains("kotlin"))
        assertTrue(detected.testTasks.contains("testDebugUnitTest"))
        assertTrue(detected.notes.any { it.contains("Android project") })
    }

    @Test
    fun `node project is detected`() {
        val root = temp.newFolder("web")
        File(root, "package.json").writeText("{}")
        File(root, "index.js").writeText("console.log(1)")

        val detected = ProjectDetector.detect(root)
        assertEquals("npm", detected.buildSystem)
        assertTrue(detected.languages.contains("javascript"))
        assertTrue(detected.notes.any { it.contains("not installed") })
    }

    @Test
    fun `unknown project reports no build system honestly`() {
        val root = temp.newFolder("misc")
        File(root, "notes.txt").writeText("hello")
        val detected = ProjectDetector.detect(root)
        assertEquals("none", detected.buildSystem)
        assertTrue(detected.notes.any { it.contains("No recognised build system") })
        assertTrue(detected.suggestedTasks.isEmpty())
    }

    @Test
    fun `rust project is detected`() {
        val root = temp.newFolder("rs")
        File(root, "Cargo.toml").writeText("[package]")
        File(root, "src").mkdirs()
        File(root, "src/main.rs").writeText("fn main() {}")
        val detected = ProjectDetector.detect(root)
        assertEquals("cargo", detected.buildSystem)
        assertTrue(detected.languages.contains("rust"))
    }
}
