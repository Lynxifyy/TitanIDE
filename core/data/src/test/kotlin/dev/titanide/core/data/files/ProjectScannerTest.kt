package dev.titanide.core.data.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProjectScannerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun write(path: String, content: String = ""): File {
        val file = File(temporaryFolder.root, path)
        file.parentFile?.mkdirs()
        file.writeText(content)
        return file
    }

    @Test
    fun `collects images fonts and resources as assets`() {
        write("res/drawable/icon.png", "x")
        write("assets/fonts/inter.ttf", "x")
        write("res/values/strings.xml", "<resources/>")

        val scan = ProjectScanner.scan(temporaryFolder.root)

        assertEquals(3, scan.assets.size)
        assertTrue(scan.assets.any { it.path == "res/drawable/icon.png" && it.kind == "PNG" })
        assertTrue(scan.assets.any { it.path == "assets/fonts/inter.ttf" && it.kind == "FONT" })
        assertTrue(scan.assets.any { it.path == "res/values/strings.xml" && it.kind == "XML" })
    }

    @Test
    fun `ignores json outside resource directories`() {
        write("config/secret.json", "{}")

        val scan = ProjectScanner.scan(temporaryFolder.root)

        assertTrue(scan.assets.isEmpty())
    }

    @Test
    fun `skips build output and hidden directories`() {
        write("build/intermediates/output.png", "x")
        write(".gradle/cache/icon.png", "x")
        write("node_modules/pkg/logo.png", "x")
        write("res/drawable/real.png", "x")

        val scan = ProjectScanner.scan(temporaryFolder.root)

        assertEquals(1, scan.assets.size)
        assertEquals("res/drawable/real.png", scan.assets.first().path)
    }

    @Test
    fun `finds compose preview function names`() {
        write(
            "src/main/kotlin/Demo.kt",
            """
            @Preview(showBackground = true)
            @Composable
            fun HeroPreview() {
                Hero()
            }
            """.trimIndent(),
        )

        val scan = ProjectScanner.scan(temporaryFolder.root)

        val preview = scan.previews.single()
        assertEquals("compose", preview.kind)
        assertEquals("HeroPreview", preview.detail)
    }

    @Test
    fun `reports multiple previews in one file`() {
        write(
            "src/Widgets.kt",
            """
            @Preview
            @Composable
            fun First() = Unit

            @Preview
            @Composable
            fun Second() = Unit
            """.trimIndent(),
        )

        val preview = ProjectScanner.scan(temporaryFolder.root).previews.single()

        assertEquals("First, Second", preview.detail)
    }

    @Test
    fun `source without preview annotation is not a preview`() {
        write("src/Plain.kt", "fun main() = Unit")

        assertTrue(ProjectScanner.scan(temporaryFolder.root).previews.isEmpty())
    }

    @Test
    fun `collects markdown documentation`() {
        write("README.md", "# Titan")
        write("docs/setup.md", "# Setup")
        write("notes.txt", "ignored")

        val scan = ProjectScanner.scan(temporaryFolder.root)

        assertEquals(listOf("README.md", "docs/setup.md"), scan.documents)
    }

    @Test
    fun `layout xml is reported as a layout preview`() {
        write("app/src/main/res/layout/activity_main.xml", "<LinearLayout/>")

        val scan = ProjectScanner.scan(temporaryFolder.root)

        val preview = scan.previews.single { it.kind == "layout" }
        assertEquals("app/src/main/res/layout/activity_main.xml", preview.path)
    }

    @Test
    fun `missing directory yields an empty scan`() {
        val scan = ProjectScanner.scan(File(temporaryFolder.root, "does-not-exist"))

        assertTrue(scan.isEmpty)
    }

    @Test
    fun `maxFiles bounds the traversal`() {
        repeat(20) { write("res/drawable/icon$it.png", "x") }

        val scan = ProjectScanner.scan(temporaryFolder.root, maxFiles = 5)

        assertTrue(scan.assets.size <= 5)
    }

    @Test
    fun `textual extension check covers previewable formats`() {
        assertTrue(ProjectScanner.isTextual("json"))
        assertTrue(ProjectScanner.isTextual("SVG"))
        assertFalse(ProjectScanner.isTextual("png"))
        assertFalse(ProjectScanner.isTextual("ttf"))
    }
}
