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
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Opening a project from its detail screen must open that project. The workbench used to pick
 * whichever project had the newest lastOpenedAt, which depends on a fire-and-forget touch
 * racing the navigation, so the wrong project could open.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OpenChosenProjectTest {

    private lateinit var container: TitanContainer
    private lateinit var recentId: String
    private lateinit var olderId: String

    private fun scaffold(name: String): String = runBlocking {
        val wizard = CreateProjectViewModel(container)
        wizard.setName(name)
        wizard.setPackageName("dev.titanide.${name.lowercase()}")
        wizard.selectTemplate(TemplateCatalog.all.first { it.category == ProjectCategory.BLANK })
        wizard.create()
        val deadline = System.currentTimeMillis() + 20_000
        while (wizard.state.value.createdProjectId == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(25)
        }
        wizard.state.value.createdProjectId
            ?: throw AssertionError("scaffold failed: ${wizard.state.value.error}")
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Default)
        container = TitanContainer(ApplicationProvider.getApplicationContext())
        olderId = scaffold("ChosenOlder")
        recentId = scaffold("ChosenRecent")
        runBlocking { container.projectRepository.touch(recentId) }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private suspend fun awaitProject(viewModel: WorkbenchViewModel, id: String) {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            if (viewModel.state.value.projectName != null) return
            Thread.sleep(25)
        }
    }

    @Test
    fun `opening a project switches to it even when another was opened more recently`() = runBlocking {
        val viewModel = WorkbenchViewModel(container)
        // Let the default selection settle first, so the switch is what is being measured.
        awaitProject(viewModel, recentId)
        assertNotEquals("precondition: the two projects must be distinguishable", olderId, recentId)

        viewModel.openProject(olderId)

        val deadline = System.currentTimeMillis() + 20_000
        var name: String? = null
        while (System.currentTimeMillis() < deadline) {
            name = viewModel.state.value.projectName
            if (name == "ChosenOlder") break
            Thread.sleep(25)
        }
        assertEquals("the project the user chose must be the one that opens", "ChosenOlder", name)
    }

    @Test
    fun `opening a project records it as opened`() = runBlocking {
        val viewModel = WorkbenchViewModel(container)
        awaitProject(viewModel, recentId)

        viewModel.openProject(olderId)
        val deadline = System.currentTimeMillis() + 20_000
        var opened = false
        while (System.currentTimeMillis() < deadline) {
            val project = container.projectRepository.getProject(olderId)
            val recent = container.projectRepository.getProject(recentId)
            if (project != null && recent != null && project.lastOpenedAt >= recent.lastOpenedAt) {
                opened = true
                break
            }
            Thread.sleep(25)
        }
        assertEquals("opening a project must update its lastOpenedAt", true, opened)
    }
}
