package dev.titanide.app.ui.ai

import androidx.test.core.app.ApplicationProvider
import dev.titanide.app.di.TitanContainer
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

    private val container = TitanContainer(ApplicationProvider.getApplicationContext())
    private val args = JsonObject(mapOf("path" to JsonPrimitive("Main.kt")))

    @Before
    fun installMainDispatcher() {
        Dispatchers.setMain(Dispatchers.Default)
    }

    @After
    fun restoreMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a write blocks and the pending tool is shown`() = runBlocking {
        val viewModel = AiViewModel(container)
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
        val viewModel = AiViewModel(container)
        val answer = async { viewModel.awaitToolApproval("delete_file", args) }

        withTimeout(5_000) { viewModel.state.first { it.pendingApproval != null } }
        viewModel.denyPendingTool()
        assertEquals(false, withTimeout(5_000) { answer.await() })
    }

    @Test
    fun `a read is allowed without asking`() = runBlocking {
        val viewModel = AiViewModel(container)
        val allowed = withTimeout(5_000) { viewModel.awaitToolApproval("read_file", args) }
        assertTrue("reading cannot damage anything", allowed)
        assertEquals("nothing should have been surfaced", null, viewModel.state.value.pendingApproval)
    }

    @Test
    fun `stopping the agent releases a blocked approval`() = runBlocking {
        val viewModel = AiViewModel(container)
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
