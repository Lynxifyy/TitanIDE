package dev.titanide.app.ui.workbench

import dev.titanide.app.di.TitanContainer
import dev.titanide.app.testing.CloseContainersRule
import dev.titanide.app.testing.TestContainers
import dev.titanide.app.testing.ViewModelTracker
import dev.titanide.app.testing.resetMainDispatcher
import dev.titanide.app.ui.create.CreateProjectViewModel
import dev.titanide.core.model.ProjectCategory
import dev.titanide.core.templates.TemplateCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The workbench is the editor users actually get, so its auto-save has to honour the configured
 * delay: every keystroke must restart the timer rather than queue a write per character.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WorkbenchAutoSaveTest {

    @get:Rule
    val containers = CloseContainersRule()

    private lateinit var container: TitanContainer
    private lateinit var projectId: String

    @Before
    fun setUp() = runBlocking {
        Dispatchers.setMain(Dispatchers.Default)
        container = TestContainers.create()
        container.settings.updateEditor { it.copy(autoSave = true, autoSaveDelayMs = 1_200) }
        val wizard = ViewModelTracker.track(CreateProjectViewModel(container))
        wizard.setName("WorkbenchSave")
        wizard.setPackageName("dev.titanide.workbenchsave")
        wizard.selectTemplate(TemplateCatalog.all.first { it.category == ProjectCategory.BLANK })
        wizard.create()
        val deadline = System.currentTimeMillis() + 20_000
        while (wizard.state.value.createdProjectId == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(25)
        }
        projectId = wizard.state.value.createdProjectId
            ?: throw AssertionError("scaffold failed: ${wizard.state.value.error}")
    }

    @After
    fun tearDown() = runBlocking {
        container.settings.updateEditor { it.copy(autoSave = true, autoSaveDelayMs = 1_200) }
        ViewModelTracker.clearAll()
        resetMainDispatcher()
    }

    private suspend fun openedWorkbench(path: String, content: String): WorkbenchViewModel {
        val project = container.projectRepository.getProject(projectId)!!
        container.fileTreeRepository.write(project, path, content)
        val viewModel = ViewModelTracker.track(WorkbenchViewModel(container))
        val deadline = System.currentTimeMillis() + 20_000
        while (viewModel.state.value.projectPath == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(25)
        }
        viewModel.openFile(path)
        val openDeadline = System.currentTimeMillis() + 20_000
        while (viewModel.buffers.value[path] == null && System.currentTimeMillis() < openDeadline) {
            Thread.sleep(25)
        }
        return viewModel
    }

    private fun awaitWritten(file: File, expected: String, timeoutMs: Long = 15_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (file.readText() == expected) return
            Thread.sleep(25)
        }
        throw AssertionError("expected the file to contain '$expected' but it contains '${file.readText()}'")
    }

    @Test
    fun `auto-save waits the configured delay and writes only the final text`() = runBlocking {
        container.settings.updateEditor { it.copy(autoSave = true, autoSaveDelayMs = 3_000) }
        val project = container.projectRepository.getProject(projectId)!!
        val path = "notes.txt"
        val viewModel = openedWorkbench(path, "start\n")

        val typed = "start\nabcdefghij"
        typed.indices.forEach { index -> viewModel.edit(path, typed.substring(0, index + 1)) }

        val file = File(project.rootPath, path)
        Thread.sleep(1_800)
        assertEquals(
            "auto-save must wait the delay from settings, not a shorter hardcoded one",
            "start\n",
            file.readText(),
        )

        awaitWritten(file, typed)
        assertEquals("the buffer and the file must agree", typed, viewModel.buffers.value[path]?.content)
    }

    @Test
    fun `auto-save writes nothing while the setting is off`() = runBlocking {
        container.settings.updateEditor { it.copy(autoSave = false) }
        val project = container.projectRepository.getProject(projectId)!!
        val path = "manual.txt"
        val viewModel = openedWorkbench(path, "original\n")

        viewModel.edit(path, "edited\n")
        Thread.sleep(2_000)

        assertEquals(
            "with auto-save off nothing may be written until the user saves",
            "original\n",
            File(project.rootPath, path).readText(),
        )
    }

    @Test
    fun `an explicit save writes the buffer even with auto-save off`() = runBlocking {
        container.settings.updateEditor { it.copy(autoSave = false) }
        val project = container.projectRepository.getProject(projectId)!!
        val path = "explicit.txt"
        val viewModel = openedWorkbench(path, "original\n")

        viewModel.edit(path, "saved by hand\n")
        viewModel.save(path)

        awaitWritten(File(project.rootPath, path), "saved by hand\n")
    }

    /**
     * The soft keyboard has no Tab key. `indent()` existed but nothing called it, so a block
     * could not be indented on a phone at all.
     */
    @Test
    fun `indent shifts the caret line and outdent puts it back`() = runBlocking {
        val path = "indent.py"
        val viewModel = openedWorkbench(path, "def f():\n    return 1\n")
        viewModel.edit(path, "def f():\n    return 1\n", caret = 0)

        viewModel.indent(outdent = false)

        val indented = viewModel.buffers.value[path]!!.content
        assertTrue(
            "indenting the first line must push it right, was '$indented'",
            indented.startsWith(" "),
        )
        val width = indented.indexOf("def")
        assertTrue("the indent must use the configured width, was $width", width in 2..8)

        viewModel.indent(outdent = true)

        assertEquals(
            "outdent must undo the indent",
            "def f():\n    return 1\n",
            viewModel.buffers.value[path]!!.content,
        )
    }

    /**
     * A save that finishes after the user kept typing must not mark the buffer clean. The write
     * completes with whatever text it was given, but the buffer may have moved on, and calling
     * setDirty(false) on the newer content tells the user their newer text is on disk when it is
     * not. The next auto-save then skips it because the buffer is no longer dirty, and closing
     * the tab discards the work.
     */
    @Test
    fun `a save that finishes late does not mark newer text as saved`() = runBlocking {
        val path = "src/Late.kt"
        // Auto-save would eventually save the newer text too and hide the defect.
        container.settings.updateEditor { it.copy(autoSave = false) }
        val viewModel = openedWorkbench(path, "val a = 1\n")

        viewModel.edit(path, "val a = 2\n")
        // Start the write, then type again while it is in flight.
        viewModel.save(path)
        viewModel.edit(path, "val a = 3\n")

        val deadline = System.currentTimeMillis() + 20_000
        while (viewModel.buffers.value.getValue(path).dirty && System.currentTimeMillis() < deadline) {
            Thread.sleep(25)
        }
        assertEquals(
            "the buffer still holds text that never reached disk, so it must stay dirty",
            "val a = 3\n",
            viewModel.buffers.value.getValue(path).content,
        )
        assertTrue(
            "newer text must not be reported as saved",
            viewModel.buffers.value.getValue(path).dirty,
        )
    }

    /**
     * Auto-save is armed per keystroke, and each file's pending timer is its own. A single shared
     * job handle means editing a second file cancels the first file's pending write, so the first
     * file stays unsaved until something else touches it.
     */
    @Test
    fun `editing a second file does not cancel the first file's pending save`() = runBlocking {
        container.settings.updateEditor { it.copy(autoSave = true, autoSaveDelayMs = 1_200) }
        val project = container.projectRepository.getProject(projectId)!!
        val first = "first.txt"
        val second = "second.txt"
        container.fileTreeRepository.write(project, second, "second original\n")
        val viewModel = openedWorkbench(first, "first original\n")

        viewModel.edit(first, "first edited\n")
        viewModel.openFile(second)
        val openDeadline = System.currentTimeMillis() + 20_000
        while (viewModel.buffers.value[second] == null && System.currentTimeMillis() < openDeadline) {
            Thread.sleep(25)
        }
        viewModel.edit(second, "second edited\n")

        awaitWritten(File(project.rootPath, first), "first edited\n")
        awaitWritten(File(project.rootPath, second), "second edited\n")
    }
}
