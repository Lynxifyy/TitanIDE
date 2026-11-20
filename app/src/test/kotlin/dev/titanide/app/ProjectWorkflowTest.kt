package dev.titanide.app

import androidx.test.core.app.ApplicationProvider
import dev.titanide.app.di.TitanContainer
import dev.titanide.core.data.files.ProjectScanner
import dev.titanide.core.model.Language
import dev.titanide.core.model.ProjectCategory
import dev.titanide.core.templates.CreateProjectOutcome
import dev.titanide.core.templates.CreateProjectSpec
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * End-to-end checks over the real dependency container: scaffold a project, register it,
 * read it back from Room, edit a file, and scan it. No fake storage and no fake database.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ProjectWorkflowTest {

    private val container = TitanContainer(ApplicationProvider.getApplicationContext())

    @Test
    fun `creates a project writes files and registers it`() = runBlocking {
        val spec = CreateProjectSpec(
            name = "WorkflowDemo",
            packageName = "dev.titanide.demo",
            category = ProjectCategory.BLANK,
            templateId = "blank.kotlin-jvm",
            language = Language.KOTLIN,
        )

        val outcome = container.scaffolder.create(spec, "workflow-1", container.storage.projectDir("workflow-1"))

        assertTrue("scaffolding failed: $outcome", outcome is CreateProjectOutcome.Success)
        val success = outcome as CreateProjectOutcome.Success
        assertTrue("no files were written", success.filesWritten > 0)

        val root = File(success.project.rootPath)
        assertTrue("project directory missing", root.isDirectory)
        assertTrue(
            "expected a Kotlin source file",
            root.walkTopDown().any { it.isFile && it.extension == "kt" },
        )

        container.projectRepository.register(success.project)
        val stored = container.projectRepository.getProject("workflow-1")

        assertNotNull("project was not persisted", stored)
        assertEquals("WorkflowDemo", stored!!.name)
    }

    @Test
    fun `registers an imported project and lists it`() = runBlocking {
        val outcome = container.scaffolder.create(
            CreateProjectSpec(
                name = "Imported",
                packageName = "dev.titanide.imported",
                category = ProjectCategory.BLANK,
                templateId = "blank.kotlin-jvm",
                language = Language.KOTLIN,
            ),
            "workflow-2",
            container.storage.projectDir("workflow-2"),
        )
        val project = (outcome as CreateProjectOutcome.Success).project
        container.projectRepository.register(project)

        val projects = container.projectRepository.observeProjects().first()

        assertTrue(projects.any { it.id == "workflow-2" })
    }

    @Test
    fun `writes and reads a project file through the safe file system`() = runBlocking {
        val outcome = container.scaffolder.create(
            CreateProjectSpec(
                name = "EditorDemo",
                packageName = "dev.titanide.editor",
                category = ProjectCategory.BLANK,
                templateId = "blank.kotlin-jvm",
                language = Language.KOTLIN,
            ),
            "workflow-3",
            container.storage.projectDir("workflow-3"),
        )
        val project = (outcome as CreateProjectOutcome.Success).project
        container.projectRepository.register(project)

        val write = container.fileTreeRepository.write(project, "notes/scratch.txt", "hello titan")
        assertTrue("write failed: $write", write is dev.titanide.core.data.files.SafeFileSystem.Result.Ok<*>)

        val read = container.fileTreeRepository.read(project, "notes/scratch.txt")
        assertTrue("read failed: $read", read is dev.titanide.core.data.files.SafeFileSystem.Result.Ok<*>)
        assertEquals("hello titan", (read as dev.titanide.core.data.files.SafeFileSystem.Result.Ok<String>).value)
    }

    @Test
    fun `path traversal outside the project is rejected`() = runBlocking {
        val outcome = container.scaffolder.create(
            CreateProjectSpec(
                name = "Traversal",
                packageName = "dev.titanide.traversal",
                category = ProjectCategory.BLANK,
                templateId = "blank.kotlin-jvm",
                language = Language.KOTLIN,
            ),
            "workflow-4",
            container.storage.projectDir("workflow-4"),
        )
        val project = (outcome as CreateProjectOutcome.Success).project
        container.projectRepository.register(project)

        val escape = container.fileTreeRepository.write(project, "../escaped.txt", "nope")

        assertTrue(
            "a write escaping the project root must fail, got $escape",
            escape is dev.titanide.core.data.files.SafeFileSystem.Result.Failure,
        )
    }

    @Test
    fun `scanner finds the generated sources`() = runBlocking {
        val outcome = container.scaffolder.create(
            CreateProjectSpec(
                name = "ScanDemo",
                packageName = "dev.titanide.scan",
                category = ProjectCategory.BLANK,
                templateId = "blank.kotlin-jvm",
                language = Language.KOTLIN,
            ),
            "workflow-5",
            container.storage.projectDir("workflow-5"),
        )
        val project = (outcome as CreateProjectOutcome.Success).project

        val scan = ProjectScanner.scan(File(project.rootPath))

        assertTrue(
            "scanner found nothing in a freshly generated project",
            scan.documents.isNotEmpty() || scan.assets.isNotEmpty() || scan.previews.isNotEmpty(),
        )
    }

    @Test
    fun `settings repository persists an editor change`() = runBlocking {
        container.settings.updateEditor { it.copy(fontSize = 19) }

        val stored = container.settings.settings.first()

        assertEquals(19, stored.editor.fontSize)
    }
}
