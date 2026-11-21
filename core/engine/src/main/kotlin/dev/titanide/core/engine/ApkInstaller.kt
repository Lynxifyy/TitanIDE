package dev.titanide.core.engine

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import java.io.File

/**
 * Outcome of an install attempt. Every case is something the user has to act on, so the
 * reasons are kept distinct rather than collapsed into "install failed".
 */
sealed interface InstallOutcome {
    /** The package manager accepted the APK and reported success. */
    data class Installed(val packageName: String, val versionName: String?) : InstallOutcome

    /** The APK is valid but this device does not allow installs from TITAN. */
    data class NeedsUserConsent(val reason: String) : InstallOutcome

    /** The package manager rejected the APK. [message] is its own text, not ours. */
    data class Rejected(val statusCode: Int, val message: String) : InstallOutcome

    /** The file is missing, unreadable, or not an APK. */
    data class InvalidArtifact(val reason: String) : InstallOutcome
}

/**
 * Installs a built APK on this device.
 *
 * Android has no silent install for ordinary apps: [PackageInstaller] shows the system
 * confirmation dialog and the result arrives asynchronously. This class therefore drives a
 * real session and reports the real status code, so the UI can never claim an install
 * succeeded when the user cancelled it.
 *
 * The APK must live in app-private storage that the FileProvider exposes; passing a
 * `content://` URI from another app's directory would fail the session write.
 */
class ApkInstaller(private val context: Context) {

    private companion object {
        const val TAG = "ApkInstaller"
    }

    /**
     * True when this app is allowed to offer APK installs at all. Without
     * `REQUEST_INSTALL_PACKAGES` the system refuses before the user ever sees a prompt.
     */
    fun canRequestInstalls(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    /**
     * Starts an install session and waits for the package manager's verdict.
     *
     * Returns [InstallOutcome.NeedsUserConsent] when the "install unknown apps" setting is
     * off, so the caller can send the user to the settings screen with an explanation
     * instead of showing a bare failure.
     */
    suspend fun install(apk: File): InstallOutcome {
        if (!apk.isFile || apk.length() == 0L) {
            return InstallOutcome.InvalidArtifact("The APK is missing or empty: ${apk.absolutePath}")
        }
        if (!apk.name.endsWith(".apk", ignoreCase = true)) {
            return InstallOutcome.InvalidArtifact("Not an APK file: ${apk.name}")
        }
        // The APK is validated before the permission is checked: a corrupt file would
        // otherwise be reported as "grant the install permission", which cannot fix it.
        val archiveInfo = runCatching {
            context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
        }.getOrNull()
        if (archiveInfo == null) {
            return InstallOutcome.InvalidArtifact("Android could not read ${apk.name} as an APK.")
        }

        if (!canRequestInstalls()) {
            return InstallOutcome.NeedsUserConsent(
                "Android requires permission to install unknown apps. Enable it for TITAN, then retry.",
            )
        }

        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        archiveInfo.applicationInfo?.let { info ->
            info.sourceDir = apk.absolutePath
            info.publicSourceDir = apk.absolutePath
            val size = runCatching { context.packageManager.getApplicationLabel(info) }.getOrNull()
            Log.d(TAG, "Installing ${size ?: archiveInfo.packageName} from ${apk.name}")
        }

        val installer = context.packageManager.packageInstaller
        val sessionId = runCatching { installer.createSession(params) }.getOrElse { error ->
            return InstallOutcome.Rejected(-1, "Could not open an install session: ${error.message}")
        }

        try {
            installer.openSession(sessionId).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("titan.apk", 0, apk.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                session.commit(InstallResultReceiver.prepare(context, sessionId).intentSender)
            }
        } catch (error: Exception) {
            runCatching { installer.abandonSession(sessionId) }
            InstallResultReceiver.release(sessionId)
            Log.w(TAG, "Install session $sessionId failed while writing the APK", error)
            return InstallOutcome.Rejected(-1, "Writing the APK to the installer failed: ${error.message}")
        }

        val result = InstallResultReceiver.await(sessionId)
            ?: return InstallOutcome.Rejected(
                -1,
                "The installer did not report a result. Check the device for a pending install prompt.",
            )

        return when (result.status) {
            PackageInstaller.STATUS_SUCCESS -> InstallOutcome.Installed(
                packageName = archiveInfo.packageName,
                versionName = archiveInfo.versionName,
            )
            PackageInstaller.STATUS_PENDING_USER_ACTION ->
                InstallOutcome.NeedsUserConsent("Android is waiting for you to confirm the install.")
            else -> InstallOutcome.Rejected(
                result.status,
                result.message ?: describeInstallStatus(result.status),
            )
        }
    }

    /**
     * Launches an installed package. Returns false when there is no launcher activity,
     * which is normal for a library or a test-only APK.
     */
    fun launch(packageName: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }
            .onFailure { Log.w(TAG, "Could not launch $packageName", it) }
            .isSuccess
    }

    /** Opens the per-app "install unknown apps" settings screen for TITAN. */
    fun openInstallPermissionSettings(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val intent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(android.net.Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }
            .onFailure { Log.w(TAG, "Could not open the unknown-sources settings screen", it) }
            .isSuccess
    }

}

/** Package manager status codes, spelled out so the UI never shows a bare number. */
public fun describeInstallStatus(status: Int): String = when (status) {
    PackageInstaller.STATUS_FAILURE_ABORTED -> "The install was cancelled."
    PackageInstaller.STATUS_FAILURE_BLOCKED -> "Android blocked this install."
    PackageInstaller.STATUS_FAILURE_CONFLICT ->
        "A different build of this app is already installed. Uninstall it first."
    PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
        "This APK is not compatible with this device or Android version."
    PackageInstaller.STATUS_FAILURE_INVALID -> "The APK is corrupt or not signed correctly."
    PackageInstaller.STATUS_FAILURE_STORAGE ->
        "There is not enough free storage to install this APK."
    else -> "The installer reported status $status."
}
