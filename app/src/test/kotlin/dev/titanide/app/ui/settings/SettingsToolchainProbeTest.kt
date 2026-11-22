package dev.titanide.app.ui.settings

import dev.titanide.app.di.TitanContainer
import dev.titanide.app.testing.CloseContainersRule
import dev.titanide.app.testing.TestContainers
import dev.titanide.app.testing.ViewModelTracker
import dev.titanide.app.testing.resetMainDispatcher
import dev.titanide.core.model.ToolchainInfo
import dev.titanide.core.model.ToolchainReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A failed toolchain probe has to be distinguishable from a device with nothing installed.
 *
 * `probeToolchains` wrapped the probe in `runCatching { ... }.getOrNull()` and wrote
 * `report?.tools.orEmpty()`. When the probe threw, the list simply stayed empty, so the screen
 * showed no rows and no explanation - the same picture as a bare device. The reason a build
 * cannot run locally is exactly what this panel exists to tell the user.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SettingsToolchainProbeTest {

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

    private fun awaitIdle(viewModel: SettingsViewModel): SettingsUiState = runBlocking {
        withTimeout(10_000) {
            while (viewModel.state.value.probing) delay(20)
            viewModel.state.value
        }
    }

    @Test
    fun `a probe that throws is reported instead of looking like an empty device`() {
        val viewModel = ViewModelTracker.track(
            SettingsViewModel(container, probe = { error("process table unavailable") }),
        )

        val state = awaitIdle(viewModel)

        assertFalse("the probe must have finished", state.probing)
        assertEquals("a failed probe finds no tools", emptyList<ToolchainInfo>(), state.toolchains)
        assertNotNull(
            "a failed probe must say so; an empty list is also what a bare device looks like",
            state.probeError,
        )
    }

    @Test
    fun `a probe that succeeds clears an earlier failure`() {
        var fail = true
        val viewModel = ViewModelTracker.track(
            SettingsViewModel(
                container,
                probe = {
                    if (fail) error("process table unavailable")
                    ToolchainReport(
                        target = dev.titanide.core.model.ExecutionTarget.LOCAL,
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
        assertNotNull("precondition: the first probe failed", awaitIdle(viewModel).probeError)

        fail = false
        viewModel.probeToolchains()
        val state = awaitIdle(viewModel)

        assertEquals("the tools must have been picked up", 1, state.toolchains.size)
        assertEquals(
            "a stale failure must not sit under a list that loaded",
            null,
            state.probeError,
        )
    }
}
