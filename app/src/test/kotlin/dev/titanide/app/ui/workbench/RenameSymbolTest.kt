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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Rename across real files. The requirement is explicit that a destructive edit must be
 * confirmed and undoable, so these tests check what actually reaches the disk: a rename is
 * planned first, nothing is written until it is applied, and the applied result is undoable.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RenameSymbolTest {

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

    private val service = """
        package demo

        class UserService {
            fun load(): String = "UserService"
        }
    """.trimIndent()

    private val caller = """
        package demo

        class Controller {
            val service = UserService()
        }
    """.trimIndent()

    private fun projectWith(id: String): WorkbenchViewModel = runBlocking {
        val outcome = container.scaffolder.create(
            CreateProjectSpec(
                name = "RenameDemo",
                packageName = "dev.titanide.rename",
                category = ProjectCategory.BLANK,
                templateId = "blank.kotlin-jvm",
                language = Language.KOTLIN,
            ),
            id,
            container.storage.projectDir(id),
        )
        container.projectRepository.register((outcome as CreateProjectOutcome.Success).project)
        val dir = container.storage.projectDir(id)
        File(dir, "UserService.kt").writeText(service)
        File(dir, "Controller.kt").writeText(caller)

        val viewModel = ViewModelTracker.track(WorkbenchViewModel(container))
        withTimeout(10_000) { viewModel.project.first { it?.id == id } }
        viewModel.refreshFiles()
        viewModel
    }

    private fun WorkbenchViewModel.openAndSettle(path: String) = runBlocking {
        openFile(path)
        withTimeout(10_000) {
            buffers.first { it.containsKey(path) }
            state.first { it.activeTab?.path == path }
        }
    }

    private fun diskText(id: String, path: String) =
        File(container.storage.projectDir(id), path).readText()

    @Test
    fun `beginning a rename names the symbol under the caret`() = runBlocking {
        val viewModel = projectWith("rename-begin")
        viewModel.openAndSettle("Controller.kt")
        viewModel.setCaret("Controller.kt", caller.indexOf("UserService()"))

        viewModel.beginRename()

        withTimeout(10_000) { viewModel.renameTarget.first { it != null } }
        assertEquals("UserService", viewModel.renameTarget.value)
    }

    @Test
    fun `a rename is planned but not written until applied`() = runBlocking {
        val viewModel = projectWith("rename-plan")
        viewModel.openAndSettle("Controller.kt")
        viewModel.setCaret("Controller.kt", caller.indexOf("UserService()"))
        viewModel.beginRename()
        withTimeout(10_000) { viewModel.renameTarget.first { it != null } }

        viewModel.renameSymbol("AccountService")

        withTimeout(10_000) { viewModel.pendingRename.first { it != null } }
        val plan = viewModel.pendingRename.value!!
        assertEquals("AccountService", plan.newName)
        assertEquals(
            "only files that actually change may be listed: ${plan.changedFiles.map { it.filePath }}",
            listOf("Controller.kt", "UserService.kt"),
            plan.changedFiles.map { it.filePath }.sorted(),
        )
        assertEquals(
            "the disk must not be touched by planning",
            service,
            diskText("rename-plan", "UserService.kt"),
        )
    }

    @Test
    fun `applying the rename rewrites every file on disk`() = runBlocking {
        val viewModel = projectWith("rename-apply")
        viewModel.openAndSettle("Controller.kt")
        viewModel.setCaret("Controller.kt", caller.indexOf("UserService()"))
        viewModel.beginRename()
        withTimeout(10_000) { viewModel.renameTarget.first { it != null } }
        viewModel.renameSymbol("AccountService")
        withTimeout(10_000) { viewModel.pendingRename.first { it != null } }

        viewModel.applyRename()

        withTimeout(10_000) { viewModel.pendingRename.first { it == null } }
        withTimeout(10_000) {
            viewModel.message.first { it?.contains("Renamed UserService") == true }
        }
        val serviceText = diskText("rename-apply", "UserService.kt")
        assertTrue("the class must be renamed: $serviceText", serviceText.contains("class AccountService"))
        val callerText = diskText("rename-apply", "Controller.kt")
        assertTrue("the use must be renamed: $callerText", callerText.contains("AccountService()"))
        assertFalse("the use must not keep the old name: $callerText", callerText.contains("UserService"))
        // The literal names the old class on purpose: rewriting it would change what the
        // program prints, which is exactly what a rename must never do.
        assertTrue(
            "a string literal must be left alone: $serviceText",
            serviceText.contains("\"UserService\""),
        )
    }

    @Test
    fun `a cancelled rename leaves the files untouched`() = runBlocking {
        val viewModel = projectWith("rename-cancel")
        viewModel.openAndSettle("Controller.kt")
        viewModel.setCaret("Controller.kt", caller.indexOf("UserService()"))
        viewModel.beginRename()
        withTimeout(10_000) { viewModel.renameTarget.first { it != null } }
        viewModel.renameSymbol("AccountService")
        withTimeout(10_000) { viewModel.pendingRename.first { it != null } }

        viewModel.cancelRename()

        assertNull(viewModel.pendingRename.value)
        assertEquals(service, diskText("rename-cancel", "UserService.kt"))
        assertEquals(caller, diskText("rename-cancel", "Controller.kt"))
    }

    @Test
    fun `an applied rename can be undone in the open file`() = runBlocking {
        val viewModel = projectWith("rename-undo")
        viewModel.openAndSettle("Controller.kt")
        viewModel.setCaret("Controller.kt", caller.indexOf("UserService()"))
        viewModel.beginRename()
        withTimeout(10_000) { viewModel.renameTarget.first { it != null } }
        viewModel.renameSymbol("AccountService")
        withTimeout(10_000) { viewModel.pendingRename.first { it != null } }
        viewModel.applyRename()
        withTimeout(10_000) {
            viewModel.buffers.first { it["Controller.kt"]?.content?.contains("AccountService") == true }
        }

        viewModel.undo()

        withTimeout(10_000) {
            viewModel.buffers.first { it["Controller.kt"]?.content?.contains("UserService") == true }
        }
        assertTrue(
            "undo must restore the pre-rename text",
            viewModel.buffers.value.getValue("Controller.kt").content.contains("UserService()"),
        )
    }

    @Test
    fun `renaming to an invalid name is refused before a plan exists`() = runBlocking {
        val viewModel = projectWith("rename-invalid")
        viewModel.openAndSettle("Controller.kt")
        viewModel.setCaret("Controller.kt", caller.indexOf("UserService()"))
        viewModel.beginRename()
        withTimeout(10_000) { viewModel.renameTarget.first { it != null } }

        viewModel.renameSymbol("not a name")

        withTimeout(10_000) { viewModel.message.first { it != null } }
        assertNull("no plan may be offered", viewModel.pendingRename.value)
        assertNotNull(viewModel.message.value)
        assertTrue(
            "the reason must be shown: ${viewModel.message.value}",
            viewModel.message.value!!.contains("not a valid identifier"),
        )
        assertEquals(service, diskText("rename-invalid", "UserService.kt"))
    }

    @Test
    fun `renaming with the caret on whitespace says so`() = runBlocking {
        val viewModel = projectWith("rename-nowhere")
        viewModel.openAndSettle("Controller.kt")
        viewModel.setCaret("Controller.kt", 0)

        viewModel.beginRename()

        withTimeout(10_000) { viewModel.message.first { it != null } }
        assertNull(viewModel.renameTarget.value)
        assertTrue(
            "the user must be told what to do: ${viewModel.message.value}",
            viewModel.message.value!!.contains("Put the caret on a symbol"),
        )
    }
}
