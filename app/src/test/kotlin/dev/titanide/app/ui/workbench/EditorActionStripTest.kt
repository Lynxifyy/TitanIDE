package dev.titanide.app.ui.workbench

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import dev.titanide.app.LocalContainer
import dev.titanide.app.testing.CloseContainersRule
import dev.titanide.app.testing.TestContainers
import dev.titanide.app.testing.ViewModelTracker
import dev.titanide.core.designsystem.TitanTheme
import dev.titanide.core.model.Language
import dev.titanide.core.model.ProjectCategory
import dev.titanide.core.templates.CreateProjectOutcome
import dev.titanide.core.templates.CreateProjectSpec
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking

import android.os.Looper
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * The editor action strip on a phone.
 *
 * Every action in the strip is the touch equivalent of a keyboard shortcut, so an action laid
 * out past the right edge is unreachable: a phone has no F2 and no Ctrl+Z. This renders at a
 * real phone width and asserts each action is inside the window.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w320dp-h640dp-xhdpi")
class EditorActionStripTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val containers = CloseContainersRule()

    @After
    fun tearDown() = ViewModelTracker.clearAll()

    /**
     * Waits for [condition] while letting the main looper run.
     *
     * Under Robolectric the test thread *is* the main thread, so a `runBlocking` wait would
     * block the looper that `viewModelScope` needs to make progress, and every wait would time
     * out. Pumping the looper is what actually advances the work.
     */
    private fun <T> await(what: String, block: () -> T?): T {
        repeat(500) {
            shadowOf(Looper.getMainLooper()).idle()
            block()?.let { return it }
            Thread.sleep(10)
        }
        throw AssertionError("timed out waiting for $what")
    }

    private val source = """
        package demo

        class Greeter {
            fun greet(): String = "hello"
        }
    """.trimIndent()

    private lateinit var viewModel: WorkbenchViewModel

    private fun openWorkbenchWithAFile() {
        val container = TestContainers.create()
        val id = "strip-project"
        val created = runBlocking {
            container.scaffolder.create(
                CreateProjectSpec(
                    name = "StripDemo",
                    packageName = "dev.titanide.strip",
                    category = ProjectCategory.BLANK,
                    templateId = "blank.kotlin-jvm",
                    language = Language.KOTLIN,
                ),
            id,
            container.storage.projectDir(id),
        )
        }
        runBlocking {
            container.projectRepository.register((created as CreateProjectOutcome.Success).project)
        }
        File(container.storage.projectDir(id), "Greeter.kt").writeText(source)

        viewModel = ViewModelTracker.track(WorkbenchViewModel(container))
        await("the project to load") { viewModel.project.value?.id }
        viewModel.refreshFiles()
        viewModel.openFile("Greeter.kt")
        await("the file to open") { viewModel.buffers.value["Greeter.kt"] }
        await("the tab to become active") { viewModel.state.value.activeTab?.path }

        compose.setContent {
            CompositionLocalProvider(LocalContainer provides container) {
                TitanTheme {
                    WorkbenchScreen(
                        viewModel = viewModel,
                        onOpenSettings = {},
                        onOpenProjects = {},
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private val actions = listOf(
        "Undo", "Redo", "Definition", "References", "Rename",
        "Find", "Comment", "Format", "Outdent", "Indent", "Save",
    )

    @Test
    fun everyActionInTheStripIsInsideTheWindow() {
        openWorkbenchWithAFile()

        val root = compose.onRoot().getUnclippedBoundsInRoot()
        val missing = actions.filter { label ->
            compose.onAllNodesWithText(label, substring = true).fetchSemanticsNodes().isEmpty()
        }
        assertTrue("actions missing from the strip: $missing", missing.isEmpty())

        val clipped = actions.filter { label ->
            val bounds = compose.onAllNodesWithText(label, substring = true)[0]
                .getUnclippedBoundsInRoot()
            bounds.right > root.right + 0.5.dp || bounds.left < root.left - 0.5.dp
        }
        assertTrue(
            "these actions are laid out off screen and cannot be tapped on a phone: $clipped",
            clipped.isEmpty(),
        )
    }

    /**
     * The strip holds eleven actions, which do not fit a 320dp phone at full label width.
     *
     * Without a scroll container the Row squeezes the labels until they are truncated, and a
     * truncated action is one the user cannot identify. Asserting the strip scrolls is what
     * separates the two: `performScrollTo` only succeeds when a scrollable parent exists.
     */
    @Test
    fun theStripScrollsSoNoActionIsSqueezedOnANarrowPhone() {
        openWorkbenchWithAFile()

        val save = compose.onNodeWithText("Save", substring = true)
        save.performScrollTo()

        val bounds = save.getUnclippedBoundsInRoot()
        val root = compose.onRoot().getUnclippedBoundsInRoot()
        assertTrue(
            "the last action must be reachable inside the window: $bounds vs $root",
            bounds.right <= root.right + 0.5.dp && bounds.left >= root.left - 0.5.dp,
        )
    }

    @Test
    fun tappingRenameReachesTheEditor() {
        openWorkbenchWithAFile()

        compose.onNodeWithText("Rename").performClick()
        compose.waitForIdle()

        // beginRename resolves the symbol on a coroutine, so the outcome has to be awaited
        // rather than read straight after the tap.
        val outcome = await("the rename prompt or its refusal") {
            viewModel.renameTarget.value ?: viewModel.message.value
        }

        // The caret starts at offset 0, which is whitespace, so the honest outcome is the
        // message that tells the user to move it. Either way the tap must reach the editor.
        assertTrue(
            "tapping Rename must reach the editor, got: $outcome",
            outcome == "Greeter" || outcome.contains("caret on a symbol"),
        )
    }
}
