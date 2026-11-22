package dev.titanide.app.ui.git

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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The Git screen shows one success line and one error line. Operations used to write only their
 * own field, so an error from an earlier action stayed on screen next to a later success, and
 * nothing could clear either.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GitFeedbackTest {

    @get:Rule
    val containers = CloseContainersRule()

    private lateinit var container: TitanContainer
    private lateinit var projectId: String

    @Before
    fun setUp() = runBlocking {
        Dispatchers.setMain(Dispatchers.Default)
        container = TestContainers.create()
        val wizard = ViewModelTracker.track(CreateProjectViewModel(container))
        wizard.setName("GitFeedback")
        wizard.setPackageName("dev.titanide.gitfeedback")
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
    }

    /** A blank project is not a repository yet, so every case starts by creating one. */
    private suspend fun initialisedRepository(): GitViewModel {
        val viewModel = ViewModelTracker.track(GitViewModel(container, projectId))
        val deadline = System.currentTimeMillis() + 20_000
        while (viewModel.state.value.project == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(25)
        }
        val loaded = viewModel.state.value.project
            ?: throw AssertionError("the view model never loaded project $projectId")
        if (File(loaded.rootPath, ".git").isDirectory) {
            // Another case already initialised this repository.
            while (!viewModel.state.value.isRepository && System.currentTimeMillis() < deadline) {
                Thread.sleep(25)
            }
            return viewModel
        }
        viewModel.initRepository()
        while (!viewModel.state.value.isRepository && System.currentTimeMillis() < deadline) {
            Thread.sleep(25)
        }
        if (!viewModel.state.value.isRepository) {
            throw AssertionError("repository was not initialised: ${viewModel.state.value.error}")
        }
        return viewModel
    }

    private suspend fun awaitProject(viewModel: GitViewModel) {
        val deadline = System.currentTimeMillis() + 20_000
        while (viewModel.state.value.project == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(25)
        }
        if (viewModel.state.value.project == null) {
            throw AssertionError("the view model never loaded project $projectId")
        }
    }

    /** Git refuses an empty commit, so every case that commits writes and stages a file first. */
    private suspend fun stageSomething() {
        val project = container.projectRepository.getProject(projectId)!!
        val root = File(project.rootPath)
        File(root, "README.md").writeText("# changed\n")
        container.gitService.stage(root, listOf("README.md"))
    }

    private suspend fun awaitMessage(viewModel: GitViewModel): GitUiState {
        val deadline = System.currentTimeMillis() + 20_000
        while (viewModel.state.value.message == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(25)
        }
        return viewModel.state.value
    }

    @Test
    fun `a success message clears the previous error`() = runBlocking {
        val viewModel = initialisedRepository()

        // A commit with no message is rejected, which leaves an error on screen.
        viewModel.setCommitMessage("")
        viewModel.commit()
        assertNotNull("precondition: the commit must have been rejected", viewModel.state.value.error)

        stageSomething()
        viewModel.setCommitMessage("first commit")
        viewModel.commit()
        val state = awaitMessage(viewModel)

        assertEquals("the commit must be reported", "Committed", state.message?.take(9))
        assertNull("a stale error must not sit next to a newer success message", state.error)
    }

    @Test
    fun `a failure clears the previous success`() = runBlocking {
        val viewModel = initialisedRepository()

        stageSomething()
        viewModel.setCommitMessage("first commit")
        viewModel.commit()
        awaitMessage(viewModel)
        assertNotNull("precondition: the commit must have succeeded", viewModel.state.value.message)

        viewModel.setCommitMessage("   ")
        viewModel.commit()

        val rejected = viewModel.state.value
        assertEquals("the rejection must be reported", "A commit message is required", rejected.error)
        assertNull("a stale success must not sit next to a newer error", rejected.message)
    }

    @Test
    fun `feedback can be dismissed`() = runBlocking {
        val viewModel = initialisedRepository()

        viewModel.setCommitMessage("")
        viewModel.commit()
        assertNotNull(viewModel.state.value.error)

        viewModel.clearFeedback()

        val dismissed = viewModel.state.value
        assertNull("the error must be dismissable", dismissed.error)
        assertNull(dismissed.message)
    }

    @Test
    fun `an action with no loaded project reports it instead of doing nothing`() = runBlocking {
        // A missing project never loads, which is the same state as tapping a Git button before
        // the project finishes loading.
        val viewModel = ViewModelTracker.track(GitViewModel(container, "does-not-exist"))

        viewModel.push()

        assertEquals("This project is still loading", viewModel.state.value.error)
    }

    @Test
    fun `initialising a repository reports success without an error`() = runBlocking {
        val project = container.projectRepository.getProject(projectId)!!
        val viewModel = ViewModelTracker.track(GitViewModel(container, projectId))
        awaitProject(viewModel)
        // A blank project has no repository yet, so init is the operation under test.
        File(project.rootPath, ".git").deleteRecursively()

        viewModel.initRepository()
        val state = awaitMessage(viewModel)

        assertEquals("state was $state", "Repository initialised", state.message)
        assertNull(state.error)
    }

    /**
     * Staging is the one Git action whose failure was thrown away: the result was ignored and the
     * view simply refreshed, so a file that could not be staged reappeared as unstaged with no
     * explanation. Every other action reports its failure.
     */
    /**
     * The merge action existed on the service but nowhere in the UI, and the service reported a
     * conflicting merge as a plain success (`Ok(false)`). A conflict leaves the working tree
     * half-merged, so the screen has to say so.
     */
    @Test
    fun `a conflicting merge is reported instead of looking successful`() = runBlocking {
        val viewModel = initialisedRepository()
        val project = container.projectRepository.getProject(projectId)!!
        val root = File(project.rootPath)

        File(root, "Conflict.txt").writeText("base\n")
        container.gitService.stage(root, listOf("Conflict.txt"))
        container.gitService.commit(root, "base", null, null)

        container.gitService.createBranch(root, "feature", checkout = true)
        File(root, "Conflict.txt").writeText("feature\n")
        container.gitService.stage(root, listOf("Conflict.txt"))
        container.gitService.commit(root, "feature", null, null)
        container.gitService.checkout(root, "master")

        File(root, "Conflict.txt").writeText("main\n")
        container.gitService.stage(root, listOf("Conflict.txt"))
        container.gitService.commit(root, "main", null, null)

        viewModel.merge("feature")

        val deadline = System.currentTimeMillis() + 20_000
        while (viewModel.state.value.error == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(25)
        }
        val error = viewModel.state.value.error
        assertNotNull("a merge that stopped on a conflict must not look like a success", error)
        assertTrue(
            "the error must name the conflicted file, got: $error",
            error!!.contains("Conflict.txt"),
        )
    }

    @Test
    fun `merging the checked out branch is refused`() = runBlocking {
        val viewModel = initialisedRepository()
        val branch = viewModel.state.value.branch

        viewModel.merge(branch!!)

        assertEquals("$branch is already checked out", viewModel.state.value.error)
    }

    @Test
    fun `a failed stage is reported instead of being ignored`() = runBlocking {
        val viewModel = initialisedRepository()

        // A path that does not exist in the working tree cannot be staged.
        viewModel.stage("src/definitely-not-here.kt")

        val deadline = System.currentTimeMillis() + 20_000
        while (viewModel.state.value.error == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(25)
        }
        assertNotNull(
            "a stage that did not happen must say so",
            viewModel.state.value.error,
        )
    }
}
