package dev.titanide.core.engine

import dev.titanide.core.model.ExecutionTarget
import dev.titanide.core.model.ToolchainInfo
import dev.titanide.core.model.ToolchainReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Probes what the device can actually run. Nothing here is assumed: each entry is
 * marked available only when the executable responded. On a stock Android device most
 * of these are absent, which is exactly the information the UI needs to route builds
 * to a remote agent instead of pretending a local build is possible.
 */
class ToolchainProbe(private val runner: ProcessRunner) {

    data class ToolSpec(
        val id: String,
        val displayName: String,
        val command: String,
        val versionArgs: List<String> = listOf("--version"),
        val envVar: String? = null,
    )

    private val specs = listOf(
        ToolSpec("java", "OpenJDK", "java", listOf("-version"), "JAVA_HOME"),
        ToolSpec("javac", "Java compiler", "javac", listOf("-version")),
        ToolSpec("gradle", "Gradle", "gradle", listOf("--version")),
        ToolSpec("kotlin", "Kotlin compiler", "kotlinc", listOf("-version")),
        ToolSpec("python", "Python", "python3", listOf("--version")),
        ToolSpec("node", "Node.js", "node", listOf("--version")),
        ToolSpec("npm", "npm", "npm", listOf("--version")),
        ToolSpec("cargo", "Cargo", "cargo", listOf("--version")),
        ToolSpec("rustc", "rustc", "rustc", listOf("--version")),
        ToolSpec("go", "Go", "go", listOf("version")),
        ToolSpec("cmake", "CMake", "cmake", listOf("--version")),
        ToolSpec("ninja", "Ninja", "ninja", listOf("--version")),
        ToolSpec("clang", "Clang", "clang", listOf("--version")),
        ToolSpec("make", "Make", "make", listOf("--version")),
        ToolSpec("git", "Git", "git", listOf("--version")),
        ToolSpec("dotnet", ".NET", "dotnet", listOf("--version")),
        ToolSpec("dart", "Dart", "dart", listOf("--version")),
        ToolSpec("php", "PHP", "php", listOf("--version")),
        ToolSpec("ruby", "Ruby", "ruby", listOf("--version")),
    )

    suspend fun probeAll(target: ExecutionTarget = ExecutionTarget.LOCAL): ToolchainReport = coroutineScope {
        val results = specs.map { spec ->
            async(Dispatchers.IO) { probeOne(spec) }
        }.map { it.await() }

        val android = probeAndroidSdk()
        ToolchainReport(
            target = target,
            probedAt = System.currentTimeMillis(),
            tools = results + android,
        )
    }

    private suspend fun probeOne(spec: ToolSpec): ToolchainInfo {
        val envPath = spec.envVar?.let { System.getenv(it) }
        val (available, version) = runner.probe(spec.command, spec.versionArgs)
        val path = if (available) which(spec.command) else null
        return ToolchainInfo(
            id = spec.id,
            displayName = spec.displayName,
            version = version?.take(120),
            path = path,
            available = available,
            detail = if (!available) "Not found on this device" else null,
        )
    }

    /** Android SDK detection: the SDK is a directory tree, not an executable. */
    private suspend fun probeAndroidSdk(): List<ToolchainInfo> = withContext(Dispatchers.IO) {
        val candidates = listOfNotNull(
            System.getenv("ANDROID_HOME"),
            System.getenv("ANDROID_SDK_ROOT"),
            "/sdcard/Android/sdk",
            "/storage/emulated/0/Android/sdk",
        )
        val sdkDir = candidates.map { File(it) }.firstOrNull { it.isDirectory && hasPlatforms(it) }
        val list = ArrayList<ToolchainInfo>()
        list += ToolchainInfo(
            id = "android_sdk",
            displayName = "Android SDK",
            version = sdkDir?.let { readSdkVersion(it) },
            path = sdkDir?.absolutePath,
            available = sdkDir != null,
            detail = if (sdkDir == null) "No Android SDK found on this device" else null,
        )
        val ndk = sdkDir?.let { File(it, "ndk") }?.takeIf { it.isDirectory }?.listFiles()?.firstOrNull()
        list += ToolchainInfo(
            id = "android_ndk",
            displayName = "Android NDK",
            version = ndk?.name,
            path = ndk?.absolutePath,
            available = ndk != null,
            detail = if (ndk == null) "No NDK installed" else null,
        )
        list
    }

    private fun hasPlatforms(dir: File): Boolean =
        File(dir, "platforms").isDirectory || File(dir, "platform-tools").isDirectory

    private fun readSdkVersion(dir: File): String? {
        val platforms = File(dir, "platforms").listFiles()?.map { it.name }?.sorted()
        return platforms?.lastOrNull()
    }

    private fun which(command: String): String? {
        val pathEnv = System.getenv("PATH") ?: return null
        return pathEnv.split(File.pathSeparator)
            .map { File(it, command) }
            .firstOrNull { it.isFile && it.canExecute() }
            ?.absolutePath
    }
}
