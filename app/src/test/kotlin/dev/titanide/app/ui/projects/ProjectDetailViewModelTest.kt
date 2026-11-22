package dev.titanide.app.ui.projects

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
import kotlinx.coroutines.asCoroutineDispatcher
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The project detail screen reports a project's size and offers to delete it.
 *
 * Two things went wrong there. The size was measured by walking the project directory inside
 * `viewModelScope.launch`, which is the main dispatcher, so opening the screen blocked the UI
 * thread for the length of the walk. And `delete` ignored the repository's result: when the
 * files could not be removed the screen navigated back as if the project was gone, leaving a
 * project that reappears on the next visit with nothing said about it.
 *
 * The main dispatcher is a real single thread so the first test can tell the two apart.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ProjectDetailViewModelTest {

    @get:Rule
    val containers = CloseContainersRule()

    private lateinit var container: TitanContainer
    private lateinit var projectId: String

    private val mainExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "titan-test-main")
    }
    private lateinit var mainThread: Thread

    @OptIn(ExperimentalCoroutinesApi::class)
    @Before
    fun setUp() = runBlocking {
        mainThread = mainExecutor.submit<Thread> { Thread.currentThread() }.get()
        Dispatchers.setMain(mainExecutor.asCoroutineDispatcher())
        container = TestContainers.create()
        val wizard = ViewModelTracker.track(CreateProjectViewModel(container))
        wizard.setName("DetailScreen")
        wizard.setPackageName("dev.titanide.detail")
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
    fun tearDown() {
        ViewModelTracker.clearAll()
        resetMainDispatcher()
        mainExecutor.shutdownNow()
    }

    private suspend fun loadedViewModel(): ProjectDetailViewModel {
        val viewModel = ViewModelTracker.track(ProjectDetailViewModel(container, projectId))
        withTimeout(20_000) { viewModel.state.first { !it.loading } }
        return viewModel
    }

    @Test
    fun `the size walk does not run on the main thread`() = runBlocking {
        val ranOnMain = CopyOnWriteArrayList<Boolean>()
        container.projectSizeProbe = {
            ranOnMain += Thread.currentThread() === mainThread
            2048L
        }

        val viewModel = loadedViewModel()

        assertEquals("the probe must have run once", 1, ranOnMain.size)
        assertFalse(
            "the project size walk must not run on the main thread, but it did",
            ranOnMain.first(),
        )
        assertEquals(2048L, viewModel.state.value.sizeBytes)
    }

    @Test
    fun `a failed delete is reported instead of navigating away`() = runBlocking {
        val navigatedBack = AtomicBoolean(false)
        container.projectDeleter = { false }

        val viewModel = loadedViewModel()
        viewModel.delete { navigatedBack.set(true) }

        val deadline = System.currentTimeMillis() + 20_000
        while (viewModel.state.value.error == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(25)
        }
        assertFalse(
            "the screen must not leave a project that is still on disk",
            navigatedBack.get(),
        )
        assertNotNull(
            "a delete that did not happen must say so",
            viewModel.state.value.error,
        )
    }

    @Test
    fun `a successful delete navigates back without an error`() = runBlocking {
        val navigatedBack = AtomicBoolean(false)
        container.projectDeleter = { true }

        val viewModel = loadedViewModel()
        viewModel.delete { navigatedBack.set(true) }

        val deadline = System.currentTimeMillis() + 20_000
        while (!navigatedBack.get() && System.currentTimeMillis() < deadline) {
            Thread.sleep(25)
        }
        assertTrue("a successful delete must leave the screen", navigatedBack.get())
        assertNull("a successful delete must not report an error", viewModel.state.value.error)
    }

    @Test
    fun `deleting for real removes the row and the files`() = runBlocking {
        val viewModel = loadedViewModel()
        val root = File(container.projectRepository.getProject(projectId)!!.rootPath)
        assertTrue("precondition: the project directory exists", root.exists())

        val navigatedBack = AtomicBoolean(false)
        viewModel.delete { navigatedBack.set(true) }

        val deadline = System.currentTimeMillis() + 20_000
        while (!navigatedBack.get() && System.currentTimeMillis() < deadline) {
            Thread.sleep(25)
        }
        assertTrue("the delete must have completed", navigatedBack.get())
        assertNull(viewModel.state.value.error)
        assertFalse("the project directory must be gone", root.exists())
        assertNull("the project row must be gone", container.projectRepository.getProject(projectId))
    }
}
