package dev.titanide.app.ui.workbench

import dev.titanide.app.di.TitanContainer
import dev.titanide.app.testing.CloseContainersRule
import dev.titanide.app.testing.TestContainers
import dev.titanide.app.testing.ViewModelTracker
import dev.titanide.app.testing.resetMainDispatcher
import dev.titanide.core.model.Language
import dev.titanide.core.model.ProjectCategory
import dev.titanide.core.templates.CreateProjectOutcome
import dev.titanide.core.templates.CreateProjectSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Leaving the workbench must not lose edits.
 *
 * Auto-save is debounced, so a buffer edited a moment before the user taps back is still only
 * in memory. Tabs guard this with a discard prompt; the exit path has to guard it too, or the
 * edits disappear with the view model and nothing else holds them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class UnsavedWorkGuardTest {

    @get:Rule
    val containers = CloseContainersRule()

    private lateinit var container: TitanContainer

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Default)
        container = TestContainers.create()
    }

    @After
    fun tearDown() {
        ViewModelTracker.clearAll()
        resetMainDispatcher()
    }

    private val original = """
        package demo

        class Greeter {
            fun greet(): String = "hello"
        }
    """.trimIndent()

    private val edited = original.replace("\"hello\"", "\"goodbye\"")

    private fun openProject(id: String): WorkbenchViewModel = runBlocking {
        val outcome = container.scaffolder.create(
            CreateProjectSpec(
                name = "GuardDemo",
                packageName = "dev.titanide.guard",
                category = ProjectCategory.BLANK,
                templateId = "blank.kotlin-jvm",
                language = Language.KOTLIN,
            ),
            id,
            container.storage.projectDir(id),
        )
        container.projectRepository.register((outcome as CreateProjectOutcome.Success).project)
        File(container.storage.projectDir(id), "Greeter.kt").writeText(original)

        val viewModel = ViewModelTracker.track(WorkbenchViewModel(container))
        withTimeout(10_000) { viewModel.project.first { it?.id == id } }
        viewModel.refreshFiles()
        viewModel.openFile("Greeter.kt")
        withTimeout(10_000) {
            viewModel.buffers.first { it.containsKey("Greeter.kt") }
            viewModel.state.first { it.activeTab?.path == "Greeter.kt" }
        }
        viewModel
    }

    private fun diskText(id: String) = File(container.storage.projectDir(id), "Greeter.kt").readText()

    @Test
    fun `leaving with unsaved edits asks before discarding them`() = runBlocking {
        val viewModel = openProject("guard-ask")
        viewModel.edit("Greeter.kt", edited, edited.length)

        val left = viewModel.requestExit()

        assertFalse("an exit with unsaved edits must not proceed silently", left)
        assertTrue("the exit must be pending a decision", viewModel.pendingExit.value)
        assertEquals("nothing may be written before the user decides", original, diskText("guard-ask"))
    }

    @Test
    fun `confirming the exit writes the pending edits`() = runBlocking {
        val viewModel = openProject("guard-save")
        viewModel.edit("Greeter.kt", edited, edited.length)
        viewModel.requestExit()

        viewModel.saveAndExit()

        assertFalse("the exit prompt must close", viewModel.pendingExit.value)
        assertEquals("confirming must persist the edit", edited, diskText("guard-save"))
    }

    @Test
    fun `leaving with nothing unsaved does not prompt`() = runBlocking {
        val viewModel = openProject("guard-clean")

        val left = viewModel.requestExit()

        assertTrue("a clean workbench must exit immediately", left)
        assertFalse(viewModel.pendingExit.value)
    }

    @Test
    fun `a failed save keeps the exit pending so the edits are not lost`() = runBlocking {
        val viewModel = openProject("guard-fail")
        viewModel.edit("Greeter.kt", edited, edited.length)
        viewModel.requestExit()

        // Writing a file over an existing directory fails for a real reason, which is the
        // only honest way to reach the failure branch here: the file system recreates missing
        // parent directories, so deleting one would just succeed.
        val target = File(container.storage.projectDir("guard-fail"), "Greeter.kt")
        target.delete()
        target.mkdirs()

        val left = viewModel.saveAndExit()

        assertFalse("a save that failed must not be treated as a clean exit", left)
        assertTrue("the prompt stays open so the user can retry", viewModel.pendingExit.value)
    }
}
