package dev.titanide.app.engine

import android.content.Context
import android.content.pm.PackageInstaller
import androidx.test.core.app.ApplicationProvider
import dev.titanide.core.engine.ApkInstaller
import dev.titanide.core.engine.InstallOutcome
import dev.titanide.core.engine.describeInstallStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Covers the guards that run before an install session is opened.
 *
 * The session itself cannot be exercised here: Robolectric has no PackageInstaller shadow,
 * so the commit and its broadcast result only exist on a real device. These tests therefore
 * assert the cases that must be rejected *before* touching the package manager, which is
 * where a wrong APK path or an empty artifact would otherwise reach the system dialog.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ApkInstallerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val installer = ApkInstaller(context)

    @Test
    fun `a missing file is rejected without touching the installer`() {
        val outcome = runBlocking { installer.install(File(context.cacheDir, "absent.apk")) }
        assertTrue("expected InvalidArtifact, got $outcome", outcome is InstallOutcome.InvalidArtifact)
        assertTrue((outcome as InstallOutcome.InvalidArtifact).reason.contains("missing or empty"))
    }

    @Test
    fun `an empty apk is rejected`() {
        val apk = File(context.cacheDir, "empty.apk").apply { createNewFile() }
        val outcome = runBlocking { installer.install(apk) }
        assertTrue("expected InvalidArtifact, got $outcome", outcome is InstallOutcome.InvalidArtifact)
    }

    @Test
    fun `a non apk file is rejected by extension`() {
        val jar = File(context.cacheDir, "library.jar").apply { writeText("not an apk") }
        val outcome = runBlocking { installer.install(jar) }
        assertTrue("expected InvalidArtifact, got $outcome", outcome is InstallOutcome.InvalidArtifact)
        assertTrue((outcome as InstallOutcome.InvalidArtifact).reason.contains("Not an APK"))
    }

    @Test
    fun `a file that android cannot parse as an apk is rejected`() {
        val fake = File(context.cacheDir, "broken.apk").apply { writeText("this is not a zip") }
        val outcome = runBlocking { installer.install(fake) }
        assertTrue(
            "expected InvalidArtifact for an unparseable APK, got $outcome",
            outcome is InstallOutcome.InvalidArtifact,
        )
    }

    @Test
    fun `an apk name check is case insensitive`() {
        val upper = File(context.cacheDir, "APP.APK").apply { writeText("x") }
        val outcome = runBlocking { installer.install(upper) }
        // It must fail for being unparseable, not for the extension check.
        assertTrue(outcome is InstallOutcome.InvalidArtifact)
        assertFalse((outcome as InstallOutcome.InvalidArtifact).reason.contains("Not an APK"))
    }

    @Test
    fun `permission state is the package manager's own answer`() {
        // Not hardcoded: whatever the platform reports must be what the installer uses.
        assertEquals(
            context.packageManager.canRequestPackageInstalls(),
            installer.canRequestInstalls(),
        )
    }

    @Test
    fun `a corrupt apk is reported as corrupt even when the install permission is missing`() {
        // Ordering matters for the user: granting a permission cannot fix a broken file.
        val fake = File(context.cacheDir, "corrupt.apk").apply { writeText("not a zip") }
        val outcome = runBlocking { installer.install(fake) }
        assertTrue(
            "a corrupt APK must not be reported as a permission problem, got $outcome",
            outcome is InstallOutcome.InvalidArtifact,
        )
    }

    @Test
    fun `status codes are translated into actionable sentences`() {
        assertEquals(
            "The install was cancelled.",
            describeInstallStatus(PackageInstaller.STATUS_FAILURE_ABORTED),
        )
        assertEquals(
            "The APK is corrupt or not signed correctly.",
            describeInstallStatus(PackageInstaller.STATUS_FAILURE_INVALID),
        )
        assertTrue(
            describeInstallStatus(4242).contains("4242"),
        )
    }
}
