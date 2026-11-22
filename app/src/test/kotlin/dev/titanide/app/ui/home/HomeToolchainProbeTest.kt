package dev.titanide.app.ui.home

import dev.titanide.app.di.TitanContainer
import dev.titanide.app.testing.CloseContainersRule
import dev.titanide.app.testing.TestContainers
import dev.titanide.app.testing.ViewModelTracker
import dev.titanide.app.testing.resetMainDispatcher
import dev.titanide.core.model.ExecutionTarget
import dev.titanide.core.model.ToolchainInfo
import dev.titanide.core.model.ToolchainReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The dashboard's Toolchain card said "0 available" when the probe had thrown.
 *
 * `probeToolchains` wrapped the probe in `runCatching { ... }.getOrNull()` and wrote
 * `report?.tools.orEmpty()`, so a failure was rendered as a bare device with nothing installed.
 * The dashboard is where a user first learns whether they can build locally, so the reason the
 * probe failed has to reach the card.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HomeToolchainProbeTest {

    @get:Rule
    val containers = CloseContainersRule()

    private lateinit var container: TitanContainer

    @OptIn(ExperimentalCoroutinesApi::class)
    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        container = TestContainers.create()
    }

    @After
    fun tearDown() {
        ViewModelTracker.clearAll()
        resetMainDispatcher()
    }

    private fun awaitIdle(viewModel: HomeViewModel): HomeState = runBlocking {
        withTimeout(10_000) {
            while (viewModel.state.value.probingToolchains) delay(20)
            viewModel.state.value
        }
    }

    @Test
    fun `a probe that throws is reported instead of looking like a bare device`() {
        val viewModel = ViewModelTracker.track(
            HomeViewModel(container, probe = { error("process table unavailable") }),
        )

        val state = awaitIdle(viewModel)

        assertFalse("the probe must have finished", state.probingToolchains)
        assertEquals("a failed probe finds no tools", emptyList<ToolchainInfo>(), state.toolchains)
        assertNotNull(
            "a failed probe must say so; an empty list is also what a bare device looks like",
            state.probeError,
        )
    }

    @Test
    fun `a probe that succeeds reports no error`() {
        val viewModel = ViewModelTracker.track(
            HomeViewModel(
                container,
                probe = {
                    ToolchainReport(
                        target = ExecutionTarget.LOCAL,
                        probedAt = 0,
                        tools = listOf(
                            ToolchainInfo(
                                id = "java",
                                displayName = "OpenJDK",
                                version = "21",
                                path = "/opt/jdk/bin/java",
                                available = true,
                            ),
                        ),
                    )
                },
            ),
        )

        val state = awaitIdle(viewModel)

        assertNull("a clean probe must not claim a failure", state.probeError)
        assertEquals(1, state.toolchains.size)
    }
}
