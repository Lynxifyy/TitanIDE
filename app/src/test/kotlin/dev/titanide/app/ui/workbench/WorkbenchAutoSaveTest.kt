package dev.titanide.app.ui.workbench

import androidx.test.core.app.ApplicationProvider
import dev.titanide.app.di.TitanContainer
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

    private lateinit var container: TitanContainer
    private lateinit var projectId: String

    @Before
    fun setUp() = runBlocking {
        Dispatchers.setMain(Dispatchers.Default)
        container = TitanContainer(ApplicationProvider.getApplicationContext())
        container.settings.updateEditor { it.copy(autoSave = true, autoSaveDelayMs = 1_200) }
        val wizard = CreateProjectViewModel(container)
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
        Dispatchers.resetMain()
    }

    private suspend fun openedWorkbench(path: String, content: String): WorkbenchViewModel {
        val project = container.projectRepository.getProject(projectId)!!
        container.fileTreeRepository.write(project, path, content)
        val viewModel = WorkbenchViewModel(container)
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
}
