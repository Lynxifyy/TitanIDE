package dev.titanide.app.ui.extensions

import dev.titanide.app.di.TitanContainer
import dev.titanide.app.extensions.TitanCapability
import dev.titanide.app.testing.CloseContainersRule
import dev.titanide.app.testing.TestContainers
import dev.titanide.app.testing.ViewModelTracker
import dev.titanide.app.testing.resetMainDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Extensions screen showed every optional extension as off on a fresh install, while the
 * capability gate treated it as on. The switch said a feature was off and the feature worked
 * anyway. The two must agree: an extension that was never toggled is enabled, and the switch
 * has to say so.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ExtensionToggleStateTest {

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

    /** The gate reads the database through its own collector, so it lags a write by a moment. */
    private fun awaitCapability(capability: TitanCapability, expected: Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (container.capabilityGate.isEnabled(capability) != expected &&
            System.currentTimeMillis() < deadline
        ) {
            Thread.sleep(25)
        }
    }

    private fun viewModel(): ExtensionsViewModel {
        val viewModel = ViewModelTracker.track(ExtensionsViewModel(container))
        runBlocking { withTimeout(10_000) { viewModel.state.first { !it.loading } } }
        return viewModel
    }

    @Test
    fun `an optional extension starts switched on, matching the gate`() {
        val state = viewModel().state.value

        assertEquals(
            "the switch must agree with the gate that decides whether the feature works",
            container.capabilityGate.isEnabled(TitanCapability.AI),
            state.enabled[TitanCapability.AI.extensionId],
        )
        assertEquals("TITAN Intelligence must read as enabled", true, state.enabled["titan.ai"])
    }

    @Test
    fun `turning an extension off is shown and disables the capability`() {
        val viewModel = viewModel()
        val extension = ExtensionsUiState.BUILT_INS.first { it.id == TitanCapability.AI.extensionId }

        viewModel.setEnabled(extension, false)

        runBlocking {
            withTimeout(10_000) { viewModel.state.first { it.enabled[extension.id] == false } }
        }
        awaitCapability(TitanCapability.AI, expected = false)
        assertTrue(
            "the gate must follow the switch",
            !container.capabilityGate.isEnabled(TitanCapability.AI),
        )
    }

    @Test
    fun `turning it back on restores the capability`() {
        val viewModel = viewModel()
        val extension = ExtensionsUiState.BUILT_INS.first { it.id == TitanCapability.AI.extensionId }
        viewModel.setEnabled(extension, false)
        runBlocking { withTimeout(10_000) { viewModel.state.first { it.enabled[extension.id] == false } } }

        viewModel.setEnabled(extension, true)

        runBlocking { withTimeout(10_000) { viewModel.state.first { it.enabled[extension.id] == true } } }
        awaitCapability(TitanCapability.AI, expected = true)
        assertTrue(container.capabilityGate.isEnabled(TitanCapability.AI))
    }

    @Test
    fun `always-on extensions cannot be switched off`() {
        val viewModel = viewModel()
        val extension = ExtensionsUiState.BUILT_INS.first { it.alwaysOn }

        viewModel.setEnabled(extension, false)

        assertEquals(
            "core code cannot be disabled",
            true,
            viewModel.state.value.enabled[extension.id],
        )
        assertTrue(viewModel.state.value.message!!.contains("cannot be disabled"))
    }
}
