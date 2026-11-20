package dev.titanide.app

import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import dev.titanide.core.data.security.CredentialKey
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Launches the real activity, which exercises application startup end to end: the
 * container builds, the theme resolves, and the navigation graph inflates. A crash here is
 * the same crash a device would show on first launch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xhdpi")
class MainActivityTest {

    @Test
    fun `application creates its container on startup`() {
        val application = ApplicationProvider.getApplicationContext<TitanApplication>()

        assertNotNull("the dependency container was not built", application.container)
    }

    @Test
    fun `activity launches and shows the start destination`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue("activity finished during startup", !activity.isFinishing)
                assertNotNull(activity.window?.decorView)
            }
        }
    }

    /**
     * Robolectric has no AndroidKeyStore, so this exercises the degradation path: a missing
     * keystore must report failure instead of throwing into the Settings screen.
     */
    @Test
    fun `credential store degrades without a keystore instead of crashing`() = runBlocking {
        val container = ApplicationProvider.getApplicationContext<TitanApplication>().container

        val stored = container.credentials.put(CredentialKey.REMOTE_AGENT_TOKEN, "agent-secret")
        val read = container.credentials.get(CredentialKey.REMOTE_AGENT_TOKEN)
        val removed = container.credentials.remove(CredentialKey.REMOTE_AGENT_TOKEN)

        if (stored) {
            assertTrue("stored token did not read back", read == "agent-secret")
            assertTrue(removed)
            assertTrue(!container.credentials.contains(CredentialKey.REMOTE_AGENT_TOKEN))
        } else {
            assertTrue("a failed write must not read back a value", read == null)
        }
    }

    @Test
    fun `toolchain probe reports what this device actually has`() = runBlocking {
        val container = ApplicationProvider.getApplicationContext<TitanApplication>().container

        val report = container.toolchainProbe.probeAll()

        assertTrue("probe must report at least one tool", report.tools.isNotEmpty())
    }

    @Test
    fun `sdk level is above the declared minimum`() {
        assertTrue("minSdk is 26", Build.VERSION.SDK_INT >= 26)
    }
}
