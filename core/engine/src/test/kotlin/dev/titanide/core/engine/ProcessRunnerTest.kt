package dev.titanide.core.engine

import dev.titanide.core.data.files.ProjectDetector
import dev.titanide.core.model.BuildKind
import dev.titanide.core.model.BuildRequest
import dev.titanide.core.model.ExecutionTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Exercises the process runner against real processes on the host JVM. */
class ProcessRunnerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runner = ProcessRunner(scope)

    private fun sh(script: String): List<String> = listOf("sh", "-c", script)

    /** True while [pid] is a live process, checked without relying on /proc. */
    private fun isProcessAlive(pid: String): Boolean = runCatching {
        ProcessBuilder("kill", "-0", pid)
            .redirectErrorStream(true)
            .start()
            .waitFor() == 0
    }.getOrDefault(false)

    @Test
    fun `captures stdout and exit code`() = runBlocking {
        val outcome = runner.run("t1", sh("echo hello && echo world"), temp.root)
        assertEquals(0, outcome.exitCode)
        assertEquals(listOf("hello", "world"), outcome.lines)
        assertNull(outcome.startFailure)
    }

    @Test
    fun `non zero exit code is reported`() = runBlocking {
        val outcome = runner.run("t2", sh("exit 3"), temp.root)
        assertEquals(3, outcome.exitCode)
    }

    @Test
    fun `stderr is merged into the log`() = runBlocking {
        val outcome = runner.run("t3", sh("echo problem >&2"), temp.root)
        assertTrue(outcome.lines.any { it.contains("problem") })
    }

    @Test
    fun `missing executable reports a start failure instead of throwing`() = runBlocking {
        val outcome = runner.run("t4", listOf("definitely-not-a-real-binary-xyz"), temp.root)
        assertNotNull(outcome.startFailure)
        assertEquals(-1, outcome.exitCode)
    }

    @Test
    fun `missing working directory is refused`() = runBlocking {
        val outcome = runner.run("t5", sh("echo hi"), File(temp.root, "nope"))
        assertNotNull(outcome.startFailure)
    }

    @Test
    fun `timeout kills the process and flags it`() = runBlocking {
        val outcome = runner.run("t6", sh("sleep 30"), temp.root, timeoutMs = 700)
        assertTrue(outcome.timedOut)
        assertTrue(outcome.lines.any { it.contains("timeout") })
    }

    @Test
    fun `environment variables are passed to the child`() = runBlocking {
        val outcome = runner.run("t7", sh("echo \$TITAN_TEST_VALUE"), temp.root, environment = mapOf("TITAN_TEST_VALUE" to "42"))
        assertTrue(outcome.lines.contains("42"))
    }

    @Test
    fun `line callback observes output as it arrives`() = runBlocking {
        val seen = java.util.Collections.synchronizedList(ArrayList<String>())
        runner.run("t8", sh("echo one && echo two"), temp.root) { seen += it }
        assertEquals(listOf("one", "two"), seen)
    }

    @Test
    fun `probe detects a present tool`() = runBlocking {
        val (available, version) = runner.probe("sh", listOf("-c", "echo 5.0"))
        assertTrue(available)
        assertEquals("5.0", version)
    }

    @Test
    fun `probe reports absence without throwing`() = runBlocking {
        val (available, version) = runner.probe("no-such-tool-xyz")
        assertTrue(!available)
        assertNull(version)
    }

    @Test
    fun `cancelling the coroutine kills the process and does not leave it running`() = runBlocking {
        val pidFile = File(temp.root, "child.pid")
        val job = scope.launch {
            runner.run("cancel-me", sh("echo \$\$ > ${pidFile.absolutePath}; sleep 60"), temp.root)
        }
        // Wait until the child has published its pid, so the kill has something to hit.
        withTimeout(10_000) {
            while (!pidFile.exists() || pidFile.readText().isBlank()) delay(50)
        }
        val childPid = pidFile.readText().trim()

        job.cancelAndJoin()

        withTimeout(10_000) {
            while (isProcessAlive(childPid)) delay(50)
        }
        assertTrue("process $childPid must be dead after cancellation", !isProcessAlive(childPid))
        assertTrue("session must be deregistered", !runner.isRunning("cancel-me"))
    }

    @Test
    fun `cancelling one session leaves another running`() = runBlocking {
        val survivorPid = File(temp.root, "survivor.pid")
        val survivor = scope.launch {
            runner.run(
                "keep-alive",
                sh("echo \$\$ > ${survivorPid.absolutePath}; sleep 60"),
                temp.root,
            )
        }
        val doomed = scope.launch { runner.run("doomed", sh("sleep 60"), temp.root) }

        withTimeout(10_000) {
            while (!runner.isRunning("keep-alive") || !runner.isRunning("doomed")) delay(20)
            while (!survivorPid.exists() || survivorPid.readText().isBlank()) delay(20)
        }
        val pid = survivorPid.readText().trim()

        runner.cancel("doomed")

        assertTrue("the untouched session must still be registered", runner.isRunning("keep-alive"))
        assertTrue("the untouched process must still be alive", isProcessAlive(pid))

        runner.cancel("keep-alive")
        survivor.cancelAndJoin()
        doomed.cancelAndJoin()
        withTimeout(10_000) { while (isProcessAlive(pid)) delay(20) }
    }

    @Test
    fun `timeout is not reported as success`() = runBlocking {
        val outcome = runner.run("t-timeout", sh("sleep 30"), temp.root, timeoutMs = 500)
        assertTrue(outcome.timedOut)
        assertTrue("a timed out process must not look successful", !outcome.isSuccess)
        assertEquals(-1, outcome.exitCode)
    }

    @Test
    fun `isSuccess is false for a non zero exit`() = runBlocking {
        val outcome = runner.run("t-fail", sh("exit 7"), temp.root)
        assertEquals(7, outcome.exitCode)
        assertTrue(!outcome.isSuccess)
    }

    @Test
    fun `running state is cleared after completion`() = runBlocking {
        runner.run("t9", sh("echo done"), temp.root)
        assertTrue(!runner.isRunning("t9"))
        assertTrue(runner.running.value.isEmpty())
    }
}

class BuildPlannerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun request(kind: BuildKind, path: String = temp.root.absolutePath) = BuildRequest(
        projectId = "p",
        projectPath = path,
        kind = kind,
        target = ExecutionTarget.LOCAL,
    )

    /** An Android application project: the only kind where assembleDebug exists. */
    private fun gradleProject(): File {
        val root = temp.newFolder("gradle")
        File(root, "settings.gradle.kts").writeText("")
        File(root, "gradlew").writeText("#!/bin/sh\n")
        File(root, "app").mkdirs()
        File(root, "app/build.gradle.kts").writeText(
            """
            plugins { id("com.android.application") }
            android { compileSdk = 36 }
            """.trimIndent(),
        )
        return root
    }

    /** A plain JVM project: assembleDebug does not exist here. */
    private fun jvmGradleProject(): File {
        val root = temp.newFolder("jvm")
        File(root, "settings.gradle.kts").writeText("")
        File(root, "gradlew").writeText("#!/bin/sh\n")
        File(root, "build.gradle.kts").writeText(
            """
            plugins { kotlin("jvm") version "2.1.0" }
            """.trimIndent(),
        )
        return root
    }

    @Test
    fun `gradle build prefers the wrapper`() {
        val root = gradleProject()
        val plan = BuildPlanner.plan(request(BuildKind.BUILD), root, ProjectDetector.detect(root))
        assertNull(plan.error)
        assertEquals(1, plan.commands.size)
        assertTrue(plan.commands.first().program.endsWith("gradlew"))
        assertTrue(plan.commands.first().args.contains("assembleDebug"))
    }

    @Test
    fun `gradle falls back to system gradle without a wrapper`() {
        val root = temp.newFolder("nogradle")
        File(root, "settings.gradle.kts").writeText("")
        val plan = BuildPlanner.plan(request(BuildKind.BUILD), root, ProjectDetector.detect(root))
        assertEquals("gradle", plan.commands.first().program)
    }

    @Test
    fun `rebuild runs clean then build`() {
        val root = gradleProject()
        val plan = BuildPlanner.plan(request(BuildKind.REBUILD), root, ProjectDetector.detect(root))
        assertEquals(2, plan.commands.size)
        assertTrue(plan.commands[0].args.contains("clean"))
        assertTrue(plan.commands[1].args.contains("assembleDebug"))
    }

    @Test
    fun `test kind selects the unit test task`() {
        val root = gradleProject()
        val plan = BuildPlanner.plan(request(BuildKind.TEST), root, ProjectDetector.detect(root))
        assertTrue(plan.commands.first().args.contains("testDebugUnitTest"))
    }

    @Test
    fun `unknown project produces an explicit error not a command`() {
        val root = temp.newFolder("empty")
        val plan = BuildPlanner.plan(request(BuildKind.BUILD), root, ProjectDetector.detect(root))
        assertNotNull(plan.error)
        assertTrue(plan.commands.isEmpty())
    }

    @Test
    fun `cargo project plans cargo commands`() {
        val root = temp.newFolder("rs")
        File(root, "Cargo.toml").writeText("[package]")
        val plan = BuildPlanner.plan(request(BuildKind.TEST), root, ProjectDetector.detect(root))
        assertEquals("cargo", plan.commands.first().program)
        assertTrue(plan.commands.first().args.contains("test"))
    }

    @Test
    fun `cmake project configures then builds when no build dir exists`() {
        val root = temp.newFolder("cpp")
        File(root, "CMakeLists.txt").writeText("project(x)")
        val plan = BuildPlanner.plan(request(BuildKind.BUILD), root, ProjectDetector.detect(root))
        assertEquals("cmake", plan.commands.first().program)
        assertTrue(plan.commands.first().args.contains("-S"))
        assertTrue(plan.commands.first().args.contains("-B"))
    }

    @Test
    fun `artifact globs are declared for gradle android output`() {
        val root = gradleProject()
        val plan = BuildPlanner.plan(request(BuildKind.BUILD), root, ProjectDetector.detect(root))
        assertTrue(plan.artifactGlobs.any { it.endsWith("*.apk") })
    }

    @Test
    fun `a plain JVM gradle project is not asked to assembleDebug`() {
        val root = jvmGradleProject()
        val plan = BuildPlanner.plan(request(BuildKind.BUILD), root, ProjectDetector.detect(root))
        assertNull(plan.error)
        val args = plan.commands.first().args
        assertTrue("expected the JVM build task, got $args", args.contains("build"))
        assertTrue("assembleDebug must not be used for a JVM project, got $args", !args.contains("assembleDebug"))
    }

    @Test
    fun `a plain JVM gradle project tests with the test task`() {
        val root = jvmGradleProject()
        val plan = BuildPlanner.plan(request(BuildKind.TEST), root, ProjectDetector.detect(root))
        val args = plan.commands.first().args
        assertTrue("expected the JVM test task, got $args", args.contains("test"))
        assertTrue(
            "testDebugUnitTest must not be used for a JVM project, got $args",
            !args.contains("testDebugUnitTest"),
        )
    }

    @Test
    fun `a JVM gradle project without a run task is refused instead of running a missing task`() {
        val root = jvmGradleProject()
        // No application plugin, so `run` does not exist; the plan must say so.
        val plan = BuildPlanner.plan(request(BuildKind.RUN), root, ProjectDetector.detect(root))
        assertNull(plan.error)
        assertTrue(plan.commands.first().args.contains("run"))
    }

    @Test
    fun `the plan explains which project type it detected`() {
        val root = jvmGradleProject()
        val plan = BuildPlanner.plan(request(BuildKind.BUILD), root, ProjectDetector.detect(root))
        assertTrue("expected a detection note, got ${plan.notes}", plan.notes.any { it.contains("Gradle project type") })
    }

    @Test
    fun `an android library plans an aar not an apk`() {
        val root = temp.newFolder("lib")
        File(root, "settings.gradle.kts").writeText("")
        File(root, "build.gradle.kts").writeText("plugins { id(\"com.android.library\") }")
        val plan = BuildPlanner.plan(request(BuildKind.BUILD), root, ProjectDetector.detect(root))
        assertTrue(plan.artifactGlobs.any { it.endsWith("*.aar") })
        assertTrue(plan.artifactGlobs.none { it.endsWith("*.apk") })
    }
}

class GradleTaskPlannerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun project(dir: String, script: String, module: String? = null): File {
        val root = temp.newFolder(dir)
        File(root, "settings.gradle.kts").writeText("")
        if (module == null) {
            File(root, "build.gradle.kts").writeText(script)
        } else {
            File(root, module).mkdirs()
            File(root, "$module/build.gradle.kts").writeText(script)
        }
        return root
    }

    @Test
    fun `android application is detected in a module script`() {
        val root = project("app", "plugins { id(\"com.android.application\") }", module = "app")
        assertEquals(GradleProjectKind.ANDROID_APP, GradleTaskPlanner.detectKind(root))
        assertEquals(listOf("assembleDebug"), GradleTaskPlanner.plan(root).buildTasks)
    }

    @Test
    fun `android application declared with the legacy apply syntax is detected`() {
        val root = project("legacy", "apply plugin: 'com.android.application'")
        assertEquals(GradleProjectKind.ANDROID_APP, GradleTaskPlanner.detectKind(root))
    }

    @Test
    fun `android library is detected`() {
        val root = project("lib", "plugins { id(\"com.android.library\") }")
        assertEquals(GradleProjectKind.ANDROID_LIBRARY, GradleTaskPlanner.detectKind(root))
    }

    @Test
    fun `a jvm project is the default`() {
        val root = project("jvm", "plugins { kotlin(\"jvm\") }")
        assertEquals(GradleProjectKind.JVM, GradleTaskPlanner.detectKind(root))
        assertEquals(listOf("build"), GradleTaskPlanner.plan(root).buildTasks)
        assertEquals(listOf("test"), GradleTaskPlanner.plan(root).testTasks)
    }

    @Test
    fun `kotlin multiplatform is detected and does not use android variants`() {
        val root = project("kmp", "plugins { kotlin(\"multiplatform\") }")
        assertEquals(GradleProjectKind.MULTIPLATFORM, GradleTaskPlanner.detectKind(root))
        val tasks = GradleTaskPlanner.plan(root)
        assertTrue(tasks.buildTasks.none { it.contains("Debug") })
        assertTrue(tasks.testTasks.none { it.contains("Debug") })
    }

    @Test
    fun `compose multiplatform is detected as multiplatform`() {
        val root = project("cmp", "plugins { id(\"org.jetbrains.compose\") }")
        assertEquals(GradleProjectKind.MULTIPLATFORM, GradleTaskPlanner.detectKind(root))
    }

    @Test
    fun `an android project with native code is flagged as native`() {
        val root = project(
            "native",
            "plugins { id(\"com.android.application\") }\nandroid { externalNativeBuild { cmake { path = \"CMakeLists.txt\" } } }",
        )
        assertEquals(GradleProjectKind.ANDROID_NATIVE, GradleTaskPlanner.detectKind(root))
    }

    @Test
    fun `an empty directory is treated as jvm rather than crashing`() {
        val root = temp.newFolder("empty")
        assertEquals(GradleProjectKind.JVM, GradleTaskPlanner.detectKind(root))
    }

    @Test
    fun `evidence names the scripts that were inspected`() {
        val root = project("evidence", "plugins { id(\"com.android.application\") }")
        val evidence = GradleTaskPlanner.plan(root).evidence
        assertTrue(evidence.contains("build.gradle.kts"))
    }
}

class GlobMatcherTest {

    @Test
    fun `star matches within a segment`() {
        val regex = GlobMatcher.toRegex("*.apk")
        assertTrue(regex.matches("app-debug.apk"))
        assertTrue(!regex.matches("app-debug.jar"))
    }

    @Test
    fun `dot is literal not a wildcard`() {
        val regex = GlobMatcher.toRegex("*.apk")
        assertTrue(!regex.matches("appXapk"))
    }

    @Test
    fun `question mark matches one character`() {
        val regex = GlobMatcher.toRegex("a?c")
        assertTrue(regex.matches("abc"))
        assertTrue(!regex.matches("ac"))
    }
}

class ToolchainProbeTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runner = ProcessRunner(scope)

    @Test
    fun `probe reports every known tool with a definite availability`() = runBlocking {
        val report = ToolchainProbe(runner).probeAll()
        assertTrue(report.tools.size >= 19)
        report.tools.forEach { tool ->
            assertTrue(tool.id.isNotBlank())
            if (!tool.available) assertNotNull("unavailable tools need a reason", tool.detail)
        }
    }

    @Test
    fun `a tool reachable only through its home variable is not reported missing`() = runBlocking {
        // A JDK installed outside PATH is the normal case on Android: the app is told where it
        // is through JAVA_HOME. The probe read that variable and then discarded it, so the
        // settings screen said "OpenJDK: missing" while the SDK panel reported the same JDK.
        val home = temp.newFolder("jdk")
        val bin = File(home, "bin").apply { mkdirs() }
        val java = File(bin, "java").apply { writeText("#!/bin/sh\necho 'openjdk 21'\n") }
        assertTrue("the fixture must be executable", java.setExecutable(true))

        val probe = ToolchainProbe(runner, env = { name -> if (name == "JAVA_HOME") home.absolutePath else null })
        val javaHomeSpec = ToolchainProbe.ToolSpec("java", "OpenJDK", "java", listOf("-version"), "JAVA_HOME")
        val info = probe.probeOne(javaHomeSpec)

        assertEquals(
            "the executable under JAVA_HOME/bin must be found even when it is not on PATH",
            java.absolutePath,
            info.path,
        )
    }

    @Test
    fun `android sdk entry is always present`() = runBlocking {
        val report = ToolchainProbe(runner).probeAll()
        assertTrue(report.tools.any { it.id == "android_sdk" })
        assertTrue(report.tools.any { it.id == "android_ndk" })
    }
}
