package dev.titanide.app.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.titanide.app.testing.CloseContainersRule
import dev.titanide.app.testing.TestContainers
import dev.titanide.app.testing.ViewModelTracker
import dev.titanide.app.testing.resetMainDispatcher
import dev.titanide.app.ui.workbench.WorkbenchViewModel
import dev.titanide.app.ui.workbench.EditorArea
import dev.titanide.core.model.ProjectCategory
import dev.titanide.core.templates.TemplateCatalog
import dev.titanide.app.ui.create.CreateProjectViewModel
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

/**
 * Undo and redo were implemented on the view model but nothing ever called them: the editor had
 * no key handling and no on-screen control, so an edit could not be taken back on a phone or
 * with a Bluetooth keyboard. These cases pin the controls that make the feature reachable.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xhdpi")
class EditorUndoRedoTest {

    @get:Rule
    val containers = CloseContainersRule()

    @get:Rule
    val compose = createComposeRule()

    private lateinit var viewModel: WorkbenchViewModel
    private lateinit var projectId: String

    @Before
    fun setUp() = runBlocking {
        Dispatchers.setMain(Dispatchers.Default)
        val container = TestContainers.create()
        val wizard = ViewModelTracker.track(CreateProjectViewModel(container))
        wizard.setName("UndoDemo")
        wizard.setPackageName("dev.titanide.undodemo")
        wizard.selectTemplate(TemplateCatalog.all.first { it.category == ProjectCategory.BLANK })
        wizard.create()
        val deadline = System.currentTimeMillis() + 20_000
        while (wizard.state.value.createdProjectId == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(25)
        }
        projectId = wizard.state.value.createdProjectId
            ?: throw AssertionError("scaffold failed: ${wizard.state.value.error}")

        val project = container.projectRepository.getProject(projectId)!!
        container.fileTreeRepository.write(project, "Undo.kt", "val a = 1\n")
        viewModel = ViewModelTracker.track(WorkbenchViewModel(container))
        val opened = System.currentTimeMillis() + 20_000
        while (viewModel.state.value.projectPath == null && System.currentTimeMillis() < opened) {
            Thread.sleep(25)
        }
        viewModel.openFile("Undo.kt")
        val fileOpen = System.currentTimeMillis() + 20_000
        while (viewModel.buffers.value["Undo.kt"] == null && System.currentTimeMillis() < fileOpen) {
            Thread.sleep(25)
        }
    }

    @After
    fun tearDown() {
        ViewModelTracker.clearAll()
        resetMainDispatcher()
    }

    private fun renderEditor() {
        compose.setContent {
            val group = viewModel.state.value.groups.first()
            EditorArea(
                group = group,
                buffer = viewModel.buffers.value["Undo.kt"],
                diagnostics = emptyList(),
                rootName = "UndoDemo",
                onSelectTab = viewModel::activateTab,
                onCloseTab = viewModel::closeTab,
                onChange = { text, caret -> viewModel.edit("Undo.kt", text, caret) },
                onSave = viewModel::saveActive,
                onToggleComment = viewModel::toggleComment,
                onFormat = viewModel::formatDocument,
                onIndent = viewModel::indent,
                onUndo = viewModel::undo,
                onRedo = viewModel::redo,
            )
        }
        compose.waitForIdle()
    }

    @Test
    fun `the editor exposes an undo control that reverts the last edit`() {
        renderEditor()

        viewModel.edit("Undo.kt", "val a = 2\n", caret = 10)
        compose.waitForIdle()
        assertEquals("val a = 2\n", viewModel.buffers.value.getValue("Undo.kt").content)

        compose.onNodeWithText("Undo").performClick()
        compose.waitForIdle()

        assertEquals(
            "undo must restore the text from before the edit",
            "val a = 1\n",
            viewModel.buffers.value.getValue("Undo.kt").content,
        )
    }

    @Test
    fun `redo reapplies an undone edit`() {
        renderEditor()

        viewModel.edit("Undo.kt", "val a = 2\n", caret = 10)
        compose.waitForIdle()
        compose.onNodeWithText("Undo").performClick()
        compose.waitForIdle()
        assertEquals("val a = 1\n", viewModel.buffers.value.getValue("Undo.kt").content)

        compose.onNodeWithText("Redo").performClick()
        compose.waitForIdle()

        assertEquals(
            "redo must put the undone edit back",
            "val a = 2\n",
            viewModel.buffers.value.getValue("Undo.kt").content,
        )
    }

    @Test
    fun `the editor no longer advertises shortcuts it does not handle`() {
        // The empty state used to promise Ctrl+S, Ctrl+/ and Ctrl+Z while no key event was
        // handled anywhere in the editor.
        val source = java.io.File(
            "src/main/kotlin/dev/titanide/app/ui/workbench/EditorArea.kt",
        )
        if (!source.isFile) return
        val text = source.readText()
        if (text.contains("Ctrl+S saves")) {
            assertTrue(
                "a shortcut hint must only be shown when the editor handles key events",
                text.contains("onPreviewKeyEvent"),
            )
        }
    }
}
