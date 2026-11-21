package dev.titanide.app.ui.editor

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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Every keystroke started its own auto-save coroutine with a fixed delay, so typing a line
 * queued one file write per character and the configured delay was ignored. These cases pin
 * the debounce: only the final content is written, and it is written once.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AutoSaveDebounceTest {

    private lateinit var container: TitanContainer
    private lateinit var projectId: String

    @Before
    fun setUp() = runBlocking {
        Dispatchers.setMain(Dispatchers.Default)
        container = TitanContainer(ApplicationProvider.getApplicationContext())
        // Tests share one DataStore file, so every case states the setting it depends on.
        container.settings.updateEditor { it.copy(autoSave = true, autoSaveDelayMs = 1_200) }
        val wizard = CreateProjectViewModel(container)
        wizard.setName("DebounceDemo")
        wizard.setPackageName("dev.titanide.debounce")
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
        // DataStore is one shared file across the whole test JVM, so a case that changes a
        // setting must put the default back or it breaks unrelated suites.
        container.settings.updateEditor { it.copy(autoSave = true, autoSaveDelayMs = 1_200) }
        Dispatchers.resetMain()
    }

    @Test
    fun `auto-save waits the configured delay, then writes the final text`() = runBlocking {
        container.settings.updateEditor { it.copy(autoSave = true, autoSaveDelayMs = 3_000) }
        val project = container.projectRepository.getProject(projectId)!!
        val path = "notes.txt"
        container.fileTreeRepository.write(project, path, "start\n")

        val viewModel = EditorViewModel(container)
        awaitProject(viewModel)
        awaitAutoSaveDelay(viewModel, expected = 3_000)
        viewModel.openFile(path)
        awaitContent(viewModel, path)

        val typed = "start\nabcdefghij"
        // One edit per character, the way a fast typist produces them.
        typed.indices.forEach { index -> viewModel.edit(typed.substring(0, index + 1)) }

        val file = File(project.rootPath, path)
        // The old code hardcoded 1200 ms, so by now it had already written the file.
        Thread.sleep(1_800)
        assertEquals(
            "auto-save must wait the delay from settings, not a hardcoded one",
            "start\n",
            file.readText(),
        )

        awaitWritten(file, typed)
        assertEquals("the editor buffer and the file must agree", typed, viewModel.state.value.active?.content)
    }

    @Test
    fun `auto-save stays off when the setting is off`() = runBlocking {
        container.settings.updateEditor { it.copy(autoSave = false) }
        val project = container.projectRepository.getProject(projectId)!!
        val path = "manual.txt"
        container.fileTreeRepository.write(project, path, "original\n")

        val viewModel = EditorViewModel(container)
        awaitProject(viewModel)
        awaitAutoSaveSetting(viewModel, expected = false)
        viewModel.openFile(path)
        awaitContent(viewModel, path)

        viewModel.edit("edited\n")
        awaitSettled()

        assertEquals(
            "with auto-save off nothing may be written until the user saves",
            "original\n",
            File(project.rootPath, path).readText(),
        )
    }

    private suspend fun awaitProject(viewModel: EditorViewModel) {
        val deadline = System.currentTimeMillis() + 10_000
        while (viewModel.state.value.project == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(25)
        }
        assertTrue("editor should attach to a project", viewModel.state.value.project != null)
    }

    private suspend fun awaitContent(viewModel: EditorViewModel, path: String) {
        val deadline = System.currentTimeMillis() + 10_000
        while (viewModel.state.value.active?.path != path && System.currentTimeMillis() < deadline) {
            Thread.sleep(25)
        }
        assertTrue("editor should open $path", viewModel.state.value.active?.path == path)
    }

    private suspend fun awaitAutoSaveSetting(viewModel: EditorViewModel, expected: Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (viewModel.state.value.autoSave != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(25)
        }
        assertEquals(expected, viewModel.state.value.autoSave)
    }

    private suspend fun awaitAutoSaveDelay(viewModel: EditorViewModel, expected: Int) {
        val deadline = System.currentTimeMillis() + 10_000
        while (viewModel.state.value.autoSaveDelayMs != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(25)
        }
        assertEquals(expected, viewModel.state.value.autoSaveDelayMs)
    }

    private fun awaitWritten(file: File, expected: String) {
        val deadline = System.currentTimeMillis() + 15_000
        while (file.readText() != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        assertEquals(expected, file.readText())
    }

    /** Longer than the auto-save delay, so a scheduled save has had every chance to run. */
    private fun awaitSettled() {
        Thread.sleep(2_000)
    }
}
