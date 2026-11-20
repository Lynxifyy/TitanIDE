package dev.titanide.core.engine

import dev.titanide.core.data.files.ProjectDetector
import dev.titanide.core.model.BuildKind
import dev.titanide.core.model.BuildRequest
import dev.titanide.core.model.ExecutionTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

    private fun gradleProject(): File {
        val root = temp.newFolder("gradle")
        File(root, "settings.gradle.kts").writeText("")
        File(root, "gradlew").writeText("#!/bin/sh\n")
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
    fun `android sdk entry is always present`() = runBlocking {
        val report = ToolchainProbe(runner).probeAll()
        assertTrue(report.tools.any { it.id == "android_sdk" })
        assertTrue(report.tools.any { it.id == "android_ndk" })
    }
}
