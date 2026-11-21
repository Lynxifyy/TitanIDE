package dev.titanide.app.ui.create

import androidx.test.core.app.ApplicationProvider
import dev.titanide.app.di.TitanContainer
import dev.titanide.core.model.ProjectCategory
import dev.titanide.core.templates.TemplateCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The wizard collected a location, passed it to the scaffolder, and the scaffolder ignored it:
 * the project always landed in the app's own projects folder, so picking a folder did nothing
 * and said nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CreateProjectLocationTest {

    private lateinit var container: TitanContainer

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        container = TitanContainer(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a chosen location is where the project is written`() {
        val parent = File(container.storage.projectsRoot.parentFile, "chosen-parent").apply { mkdirs() }
        val viewModel = CreateProjectViewModel(container)

        viewModel.setName("Located App")
        viewModel.selectTemplate(blankTemplate())
        viewModel.setPackageName("dev.titanide.sample")
        viewModel.setLocation(parent.absolutePath)
        viewModel.create()

        val projectId = awaitCreated(viewModel)
        val project = runBlocking { container.projectRepository.getProject(projectId!!) }
        assertNotNull(project)
        val root = File(project!!.rootPath)
        assertEquals(
            "the project must live under the folder the user picked",
            parent.absolutePath,
            root.parentFile?.absolutePath,
        )
        assertTrue("the project directory must exist on disk", root.isDirectory)
    }

    @Test
    fun `an empty location keeps the app projects folder`() {
        val viewModel = CreateProjectViewModel(container)

        viewModel.setName("Default App")
        viewModel.selectTemplate(blankTemplate())
        viewModel.setPackageName("dev.titanide.sample")
        viewModel.setLocation("   ")
        viewModel.create()

        val projectId = awaitCreated(viewModel)
        val project = runBlocking { container.projectRepository.getProject(projectId!!) }
        val root = File(project!!.rootPath)
        assertEquals(
            "with no location the project stays in app storage",
            container.storage.projectsRoot.absolutePath,
            root.parentFile?.absolutePath,
        )
    }

    /** The write happens on an IO dispatcher, so the state settles after create() returns. */
    private fun awaitCreated(viewModel: CreateProjectViewModel): String? {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val state = viewModel.state.value
            state.createdProjectId?.let { return it }
            if (!state.creating && state.error != null) {
                throw AssertionError("create failed: ${state.error}")
            }
            Thread.sleep(25)
        }
        throw AssertionError("create did not finish: ${viewModel.state.value}")
    }

    private fun blankTemplate() =
        TemplateCatalog.all.first { it.category == ProjectCategory.BLANK }
}
