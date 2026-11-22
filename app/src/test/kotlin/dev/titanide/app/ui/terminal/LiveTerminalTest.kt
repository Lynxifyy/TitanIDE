package dev.titanide.app.ui.terminal

import dev.titanide.app.testing.CloseContainersRule
import dev.titanide.app.testing.TestContainers
import dev.titanide.app.testing.ViewModelTracker
import dev.titanide.app.testing.resetMainDispatcher
import kotlinx.coroutines.Dispatchers
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
import java.io.File

/**
 * The terminal against a real shell process. The JVM host has /bin/sh, so the whole path -
 * submit, spawn, stream, exit status - can be exercised instead of only the session state
 * around it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LiveTerminalTest {

    @get:Rule
    val containers = CloseContainersRule()

    private val container = TestContainers.create()

    @Before
    fun installMainDispatcher() {
        Dispatchers.setMain(Dispatchers.Default)
    }

    @After
    fun restoreMainDispatcher() {
        ViewModelTracker.clearAll()
        resetMainDispatcher()
    }

    private fun newViewModel(): TerminalViewModel {
        val viewModel = ViewModelTracker.track(TerminalViewModel(container))
        runBlocking { withTimeout(10_000) { viewModel.state.first { it.sessions.isNotEmpty() } } }
        return viewModel
    }

    private fun TerminalViewModel.runAndCollect(command: String): List<TerminalLine> {
        submit(command)
        val finished = runBlocking {
            withTimeout(30_000) { state.first { !it.active!!.running && it.active!!.lines.any { l -> l.text.startsWith("[exit") } } }
        }
        return finished.active!!.lines
    }

    @Test
    fun `a command runs and its output and exit status appear`() {
        val lines = newViewModel().runAndCollect("echo titan-live-check")

        assertTrue(
            "the command's own output must be in the session, got: ${lines.map { it.text }}",
            lines.any { it.text.contains("titan-live-check") },
        )
        assertTrue("a real exit status must be shown", lines.last().text == "[exit 0]")
    }

    @Test
    fun `a failing command reports its non-zero status`() {
        val lines = newViewModel().runAndCollect("exit 3")

        assertTrue(
            "the real exit code must be shown, got: ${lines.map { it.text }}",
            lines.any { it.text == "[exit 3]" },
        )
    }

    @Test
    fun `the working directory is the session cwd`() {
        val viewModel = newViewModel()
        val target = File(container.storage.projectsRoot, "live-cwd").apply { mkdirs() }
        viewModel.submit("cd live-cwd")
        val lines = viewModel.runAndCollect("pwd")

        assertTrue(
            "pwd must report the session directory ${target.absolutePath}, got: ${lines.map { it.text }}",
            lines.any { it.text.contains(target.absolutePath) },
        )
    }

    @Test
    fun `the echoed command reads as a normal shell prompt`() {
        val lines = newViewModel().runAndCollect("echo prompt-check")
        val echo = lines.first { it.isCommand }.text

        assertTrue(
            "a command line must show the directory and the command once, got: $echo",
            echo.endsWith("$ echo prompt-check") && echo.count { it == '$' } == 1,
        )
    }

    @Test
    fun `a missing command fails honestly instead of pretending to succeed`() {
        val lines = newViewModel().runAndCollect("titan-no-such-command-xyz")

        assertTrue(
            "the shell's own error must be visible, got: ${lines.map { it.text }}",
            lines.any { it.text.contains("not found", ignoreCase = true) },
        )
        assertEquals("a missing command is a failure", "[exit 127]", lines.last().text)
    }
}
