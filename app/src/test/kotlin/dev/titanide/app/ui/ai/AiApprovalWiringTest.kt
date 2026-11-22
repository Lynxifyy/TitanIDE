package dev.titanide.app.ui.ai

import dev.titanide.app.di.TitanContainer
import dev.titanide.app.testing.CloseContainersRule
import dev.titanide.app.testing.TestContainers
import dev.titanide.app.testing.ViewModelTracker
import dev.titanide.app.testing.resetMainDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
 * The gate used to answer every request with a constant, so the agent could never write a
 * file and the "edits files" promise was unreachable. These cases pin the gate itself: a
 * write must stop and wait, an allow must release it, a deny must refuse it, and stopping
 * must never leave the agent waiting forever.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AiApprovalWiringTest {

    @get:Rule
    val containers = CloseContainersRule()

    private val container = TestContainers.create()
    private val args = JsonObject(mapOf("path" to JsonPrimitive("Main.kt")))

    @Before
    fun installMainDispatcher() {
        Dispatchers.setMain(Dispatchers.Default)
    }

    @After
    fun restoreMainDispatcher() {
        ViewModelTracker.clearAll()
        resetMainDispatcher()
    }

    @Test
    fun `a write blocks and the pending tool is shown`() = runBlocking {
        val viewModel = ViewModelTracker.track(AiViewModel(container))
        val answer = async { viewModel.awaitToolApproval("write_file", args) }

        withTimeout(5_000) {
            viewModel.state.first { it.pendingApproval != null }
        }
        assertEquals("write_file", viewModel.state.value.pendingApproval?.tool)
        assertTrue("the agent must not have been answered yet", answer.isActive)

        viewModel.approvePendingTool()
        assertTrue("allowing the tool must let it run", withTimeout(5_000) { answer.await() })
    }

    @Test
    fun `denying a write refuses it`() = runBlocking {
        val viewModel = ViewModelTracker.track(AiViewModel(container))
        val answer = async { viewModel.awaitToolApproval("delete_file", args) }

        withTimeout(5_000) { viewModel.state.first { it.pendingApproval != null } }
        viewModel.denyPendingTool()
        assertEquals(false, withTimeout(5_000) { answer.await() })
    }

    @Test
    fun `a read is allowed without asking`() = runBlocking {
        val viewModel = ViewModelTracker.track(AiViewModel(container))
        val allowed = withTimeout(5_000) { viewModel.awaitToolApproval("read_file", args) }
        assertTrue("reading cannot damage anything", allowed)
        assertEquals("nothing should have been surfaced", null, viewModel.state.value.pendingApproval)
    }

    @Test
    fun `stopping the agent releases a blocked approval`() = runBlocking {
        val viewModel = ViewModelTracker.track(AiViewModel(container))
        val answer = async { viewModel.awaitToolApproval("write_file", args) }
        withTimeout(5_000) { viewModel.state.first { it.pendingApproval != null } }

        viewModel.stopAgent()
        assertEquals(
            "a stopped agent must be told no, not left waiting",
            false,
            withTimeout(5_000) { answer.await() },
        )
    }
}

/**
 * "Run tests" in AI Studio must say why a run failed. On a device with no JDK or Gradle the
 * failure is the normal case, and "Tests finished: FAILED" leaves the user with nothing to act
 * on. The reason is already in the build result, so it belongs in the message.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AiRunTestsMessageTest {

    @get:Rule
    val containers = CloseContainersRule()

    private val container = TestContainers.create()

    @Before
    fun installMainDispatcher() {
        Dispatchers.setMain(Dispatchers.Default)
    }

    @After
    fun restoreMainDispatcher() {
        ViewModelTracker.clearAll()
        resetMainDispatcher()
    }

    private fun scaffold(): dev.titanide.core.model.Project = runBlocking {
        val wizard = ViewModelTracker.track(
            dev.titanide.app.ui.create.CreateProjectViewModel(container),
        )
        wizard.setName("AiTests")
        wizard.setPackageName("dev.titanide.aitests")
        wizard.selectTemplate(
            dev.titanide.core.templates.TemplateCatalog.all.first {
                it.category == dev.titanide.core.model.ProjectCategory.BLANK
            },
        )
        wizard.create()
        val deadline = System.currentTimeMillis() + 20_000
        while (wizard.state.value.createdProjectId == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(25)
        }
        val id = wizard.state.value.createdProjectId
            ?: throw AssertionError("scaffold failed: ${wizard.state.value.error}")
        container.projectRepository.getProject(id)!!
    }

    @Test
    fun `a failed test run reports the reason, not just the status`() = runBlocking {
        val project = scaffold()
        val viewModel = ViewModelTracker.track(AiViewModel(container))
        viewModel.selectProject(project.id)
        withTimeout(20_000) { viewModel.state.first { it.project?.id == project.id } }

        viewModel.runTests()

        val message = withTimeout(180_000) {
            viewModel.state.first {
                it.message != null && !it.message!!.startsWith("Running tests")
            }.message!!
        }
        val result = container.buildEngine.lastResult.value
            ?: throw AssertionError("the run left no build result to explain")
        val reason = result.failureReason
            ?: throw AssertionError("a ${result.status} run must record why it failed")
        assertTrue(
            "the message must repeat the reason the build gave, but it was: $message",
            message.contains(reason),
        )
    }
}
