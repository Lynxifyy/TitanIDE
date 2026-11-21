package dev.titanide.app.ui.workbench

import androidx.test.core.app.ApplicationProvider
import dev.titanide.app.di.TitanContainer
import dev.titanide.app.testing.ViewModelTracker
import dev.titanide.app.testing.resetMainDispatcher
import dev.titanide.core.model.Language
import dev.titanide.core.templates.CreateProjectOutcome
import dev.titanide.core.templates.CreateProjectSpec
import dev.titanide.core.model.ProjectCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Explorer file operations, exercised against a real scaffolded project on disk.
 *
 * These are the actions a user reaches from the row menu, so they must move real bytes and
 * keep the open buffers consistent — a rename that leaves a stale buffer would write the
 * old text over the moved file on the next save.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WorkbenchFileOpsTest {

    private val container = TitanContainer(ApplicationProvider.getApplicationContext())

    @Before
    fun installMainDispatcher() {
        Dispatchers.setMain(Dispatchers.Default)
    }

    @After
    fun restoreMainDispatcher() {
        ViewModelTracker.clearAll()
        resetMainDispatcher()
    }

    private fun scaffoldProject(id: String): String = runBlocking {
        val outcome = container.scaffolder.create(
            CreateProjectSpec(
                name = "OpsDemo",
                packageName = "dev.titanide.ops",
                category = ProjectCategory.BLANK,
                templateId = "blank.kotlin-jvm",
                language = Language.KOTLIN,
            ),
            id,
            container.storage.projectDir(id),
        )
        val success = outcome as CreateProjectOutcome.Success
        container.projectRepository.register(success.project)
        id
    }

    private suspend fun WorkbenchViewModel.awaitProject(id: String) =
        withTimeout(10_000) { project.first { it?.id == id } }

    private fun WorkbenchViewModel.awaitMessage(expected: String) = runBlocking {
        withTimeout(10_000) { message.first { it == expected } }
    }

    private fun WorkbenchViewModel.awaitFile(path: String) = runBlocking {
        withTimeout(10_000) { buffers.first { it.containsKey(path) } }[path]
    }

    private suspend fun WorkbenchViewModel.firstKotlinFile(): String =
        withTimeout(10_000) {
            projectTree.first { tree -> tree.flattened().any { !it.isDirectory && it.path.endsWith(".kt") } }
                .flattened().first { !it.isDirectory && it.path.endsWith(".kt") }.path
        }

    @Test
    fun `creating a folder writes a real directory`() = runBlocking {
        val id = scaffoldProject("ops-folder")
        val viewModel = ViewModelTracker.track(WorkbenchViewModel(container))
        viewModel.awaitProject(id)

        viewModel.createDirectory("feature/ui")
        viewModel.awaitMessage("Created feature/ui")

        val dir = File(container.storage.projectDir(id), "feature/ui")
        assertTrue("the directory must exist on disk", dir.isDirectory)
    }

    @Test
    fun `renaming a file moves it on disk and carries the open buffer`() = runBlocking {
        val id = scaffoldProject("ops-rename")
        val viewModel = ViewModelTracker.track(WorkbenchViewModel(container))
        viewModel.awaitProject(id)

        val from = viewModel.firstKotlinFile()
        viewModel.openFile(from)
        viewModel.awaitFile(from)

        val to = from.replace(".kt", "Renamed.kt")
        viewModel.renamePath(from, to)
        viewModel.awaitMessage("Renamed to $to")

        val root = container.storage.projectDir(id)
        assertFalse("the old file must be gone", File(root, from).exists())
        assertTrue("the new file must exist", File(root, to).isFile)
        assertTrue("the buffer must follow the file", viewModel.buffers.value.containsKey(to))
        assertFalse("the stale buffer must be dropped", viewModel.buffers.value.containsKey(from))
    }

    @Test
    fun `renaming keeps unsaved edits instead of reverting them`() = runBlocking {
        val id = scaffoldProject("ops-rename-dirty")
        val viewModel = ViewModelTracker.track(WorkbenchViewModel(container))
        viewModel.awaitProject(id)

        val from = viewModel.firstKotlinFile()
        viewModel.openFile(from)
        val original = viewModel.awaitFile(from)!!
        val marker = "// unsaved-rename-marker\n"
        viewModel.edit(from, marker + original.content)

        val to = from.replace(".kt", "Moved.kt")
        viewModel.renamePath(from, to)
        viewModel.awaitMessage("Renamed to $to")

        val moved = viewModel.buffers.value[to]
        assertTrue("the moved buffer must exist", moved != null)
        assertTrue("the unsaved edit must survive the rename", moved!!.content.contains(marker))
        assertTrue("the buffer must still be dirty", moved.dirty)
    }

    @Test
    fun `duplicating a file creates a real copy and leaves the original`() = runBlocking {
        val id = scaffoldProject("ops-copy")
        val viewModel = ViewModelTracker.track(WorkbenchViewModel(container))
        viewModel.awaitProject(id)

        val from = viewModel.firstKotlinFile()
        val root = container.storage.projectDir(id)
        val to = from.replace(".kt", "Copy.kt")

        viewModel.duplicatePath(from, to)
        viewModel.awaitMessage("Copied to $to")

        assertTrue("the original must remain", File(root, from).isFile)
        assertEquals(
            "the copy must have the same bytes",
            File(root, from).readText(),
            File(root, to).readText(),
        )
    }

    @Test
    fun `deleting a directory closes the buffers of its children`() = runBlocking {
        val id = scaffoldProject("ops-delete-dir")
        val viewModel = ViewModelTracker.track(WorkbenchViewModel(container))
        viewModel.awaitProject(id)

        viewModel.createDirectory("scratch")
        viewModel.awaitMessage("Created scratch")
        viewModel.createFile("scratch/note.txt")
        viewModel.awaitMessage("Created scratch/note.txt")
        viewModel.openFile("scratch/note.txt")
        viewModel.awaitFile("scratch/note.txt")

        viewModel.deleteFile("scratch")
        viewModel.awaitMessage("Deleted scratch")

        assertFalse("the directory must be gone", File(container.storage.projectDir(id), "scratch").exists())
        assertFalse(
            "the child buffer must be closed, not left pointing at a missing file",
            viewModel.buffers.value.containsKey("scratch/note.txt"),
        )
    }

    @Test
    fun `unsaved changes are reported so the delete prompt can warn`() = runBlocking {
        val id = scaffoldProject("ops-dirty-flag")
        val viewModel = ViewModelTracker.track(WorkbenchViewModel(container))
        viewModel.awaitProject(id)

        val path = viewModel.firstKotlinFile()
        viewModel.openFile(path)
        val buffer = viewModel.awaitFile(path)!!
        assertFalse("a freshly opened file is clean", viewModel.hasUnsavedChanges(path))

        viewModel.edit(path, buffer.content + "\n// dirty\n")
        assertTrue("an edited file must report unsaved changes", viewModel.hasUnsavedChanges(path))

        viewModel.save(path)
        withTimeout(10_000) { viewModel.buffers.first { !it.getValue(path).dirty } }
        assertFalse("a saved file must report no unsaved changes", viewModel.hasUnsavedChanges(path))
    }

    @Test
    fun `a rename onto an existing path is refused rather than overwriting`() = runBlocking {
        val id = scaffoldProject("ops-collision")
        val viewModel = ViewModelTracker.track(WorkbenchViewModel(container))
        viewModel.awaitProject(id)

        val from = viewModel.firstKotlinFile()
        val root = container.storage.projectDir(id)
        val existing = "existing.txt"
        File(root, existing).writeText("keep me")

        viewModel.renamePath(from, existing)
        val failure = withTimeout(10_000) { viewModel.message.first { it != null && it != "Renamed to $existing" } }

        assertTrue("the existing file must be untouched", File(root, existing).readText() == "keep me")
        assertTrue("the original must still exist", File(root, from).exists())
        assertTrue("a refusal must be reported, got '$failure'", failure != null)
    }
}
