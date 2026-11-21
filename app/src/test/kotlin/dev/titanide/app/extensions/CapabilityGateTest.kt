package dev.titanide.app.extensions

import dev.titanide.app.di.TitanContainer
import dev.titanide.app.testing.CloseContainersRule
import dev.titanide.app.testing.TestContainers
import dev.titanide.core.data.db.ExtensionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The extensions screen persisted a toggle that nothing read, so switching "TITAN
 * Intelligence" off left AI working and the switch was pure decoration. These cases pin the
 * gate the features now consult: stored rows are honoured, and an extension that was never
 * touched stays on so a fresh install keeps every feature.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CapabilityGateTest {

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
        Dispatchers.resetMain()
    }

    @Test
    fun `an extension that was never toggled counts as enabled`() = runBlocking {
        assertTrue(
            "a fresh install must not silently lose a feature",
            container.capabilityGate.isEnabled(TitanCapability.AI),
        )
    }

    @Test
    fun `turning an extension off disables its capability`() = runBlocking {
        store(TitanCapability.AI.extensionId, enabled = false)

        val deadline = System.currentTimeMillis() + 5_000
        while (container.capabilityGate.isEnabled(TitanCapability.AI) &&
            System.currentTimeMillis() < deadline
        ) {
            Thread.sleep(25)
        }

        assertFalse(
            "switching TITAN Intelligence off must actually stop AI requests",
            container.capabilityGate.isEnabled(TitanCapability.AI),
        )
        assertTrue(
            "one extension must not disable another",
            container.capabilityGate.isEnabled(TitanCapability.REMOTE_BUILD),
        )
    }

    @Test
    fun `turning an extension back on restores its capability`() = runBlocking {
        store(TitanCapability.REMOTE_BUILD.extensionId, enabled = false)
        awaitCapability(TitanCapability.REMOTE_BUILD, expected = false)

        store(TitanCapability.REMOTE_BUILD.extensionId, enabled = true)
        awaitCapability(TitanCapability.REMOTE_BUILD, expected = true)

        assertTrue(container.capabilityGate.isEnabled(TitanCapability.REMOTE_BUILD))
    }

    private suspend fun store(id: String, enabled: Boolean) {
        container.database.extensionDao().upsert(
            ExtensionEntity(
                id = id,
                name = id,
                version = "1.0.0",
                publisher = "TITAN",
                description = "",
                kind = "test",
                enabled = enabled,
                installedAt = 0,
                manifestJson = "{}",
            ),
        )
    }

    private suspend fun awaitCapability(capability: TitanCapability, expected: Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (container.capabilityGate.isEnabled(capability) != expected &&
            System.currentTimeMillis() < deadline
        ) {
            Thread.sleep(25)
        }
    }
}
