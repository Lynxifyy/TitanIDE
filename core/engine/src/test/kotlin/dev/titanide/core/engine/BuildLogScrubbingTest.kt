package dev.titanide.core.engine

import dev.titanide.core.model.BuildKind
import dev.titanide.core.model.BuildRequest
import dev.titanide.core.model.BuildStatus
import dev.titanide.core.model.ExecutionTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A build log is shown in the app and handed to the AI as project context, so a token that a
 * tool prints must not survive into it.
 *
 * `SecureCredentialStore.scrub` exists for exactly this and nothing called it: the engine
 * stored every line a process produced verbatim. A Gradle failure that echoes the authenticated
 * remote URL, or a script that prints its environment, therefore put a live credential on screen
 * and into the prompt sent to the AI provider.
 */
class BuildLogScrubbingTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runner = ProcessRunner(scope)

    @After
    fun tearDown() {
        runner.cancelAll()
    }

    private fun request(root: File) = BuildRequest(
        projectId = "p1",
        projectPath = root.absolutePath,
        kind = BuildKind.BUILD,
        target = ExecutionTarget.LOCAL,
    )

    @Test
    fun `a token echoed by a build tool does not reach the log`() = runBlocking {
        val root = temp.newFolder("proj")
        // Stands in for a tool that prints its remote URL or its environment.
        File(root, "build.sh").apply {
            writeText("#!/bin/sh\necho 'remote: https://x-access-token:ghp_${"a".repeat(36)}@github.com/o/r.git'\n")
            setExecutable(true)
        }
        // A plain project that the planner will run through the shell script.
        File(root, "Makefile").writeText("all:\n\t@sh build.sh\n")

        val lines = ArrayList<String>()
        val outcome = LocalBuildBackend(runner).execute(request(root)) { lines += it }

        val everything = (outcome.logLines + lines).joinToString("\n")
        assertFalse(
            "a live token must not be left in a build log; the AI reads this log as context",
            everything.contains("ghp_"),
        )
        assertTrue(
            "the line must still be there, just with the secret masked",
            everything.contains("remote:"),
        )
    }

    @Test
    fun `the streamed log is scrubbed while the build is still running`() = runBlocking {
        val root = temp.newFolder("proj3")
        File(root, "Makefile").writeText(
            "all:\n\t@echo 'token ghp_${"c".repeat(36)} seen'\n",
        )

        val streamed = ArrayList<String>()
        LocalBuildBackend(runner).execute(request(root)) { streamed += it }

        assertTrue("the line must have been streamed", streamed.any { it.contains("seen") })
        assertFalse(
            "a token must not appear in the live log either",
            streamed.joinToString("\n").contains("ghp_"),
        )
    }

    @Test
    fun `a build failure reason carries no token either`() = runBlocking {
        val root = temp.newFolder("proj2")
        File(root, "Makefile").writeText(
            "all:\n\t@echo 'fatal: could not read https://oauth2:gho_${"b".repeat(36)}@github.com/o/r' && exit 1\n",
        )

        val outcome = LocalBuildBackend(runner).execute(request(root)) {}

        assertEquals(BuildStatus.FAILED, outcome.status)
        val reported = (outcome.logLines + outcome.failureReason.orEmpty()).joinToString("\n")
        assertFalse("the failure path must be scrubbed too", reported.contains("gho_"))
    }
}
