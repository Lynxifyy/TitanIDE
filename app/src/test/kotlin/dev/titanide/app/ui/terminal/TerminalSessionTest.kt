package dev.titanide.app.ui.terminal

import androidx.test.core.app.ApplicationProvider
import dev.titanide.app.di.TitanContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Terminal session behaviour that does not need a shell binary.
 *
 * The device shell cannot be exercised in a unit test, but the session state around it can,
 * and that is where the defects were. `cd` in particular has to be handled by the view model
 * because each command runs in its own `sh -c` process.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TerminalSessionTest {

    private val container = TitanContainer(ApplicationProvider.getApplicationContext())

    @Before
    fun installMainDispatcher() {
        Dispatchers.setMain(Dispatchers.Default)
    }

    @After
    fun restoreMainDispatcher() {
        Dispatchers.resetMain()
    }

    /** Waits for the init block to publish a session, so tests do not race it. */
    private fun newViewModel(): TerminalViewModel {
        val viewModel = TerminalViewModel(container)
        runBlocking { withTimeout(10_000) { viewModel.state.first { it.sessions.isNotEmpty() } } }
        return viewModel
    }

    /** Submits a command and waits for it to finish, so the next submit is not rejected. */
    private fun TerminalViewModel.runToCompletion(command: String) {
        submit(command)
        runBlocking { withTimeout(20_000) { state.first { !it.active!!.running } } }
    }

    @Test
    fun `a new session starts in the projects root`() {
        val session = newViewModel().state.value.active!!
        assertEquals(container.storage.projectsRoot.absolutePath, session.cwd)
    }

    @Test
    fun `cd changes the session directory instead of running a throwaway process`() {
        val viewModel = newViewModel()
        val target = File(container.storage.projectsRoot, "cd-target").apply { mkdirs() }

        viewModel.submit("cd cd-target")

        assertEquals(
            "the next command must start in the new directory",
            target.canonicalPath,
            viewModel.state.value.active!!.cwd,
        )
    }

    @Test
    fun `cd into a missing directory reports an error and keeps the old cwd`() {
        val viewModel = newViewModel()
        val before = viewModel.state.value.active!!.cwd

        viewModel.submit("cd no-such-directory")

        assertEquals("a failed cd must not move the session", before, viewModel.state.value.active!!.cwd)
        assertTrue(
            "the failure must be visible, lines=${viewModel.state.value.active!!.lines.map { it.text }}",
            viewModel.state.value.active!!.lines.any { it.isError && it.text.contains("no such directory") },
        )
    }

    @Test
    fun `cd with no argument goes to the projects root`() {
        val viewModel = newViewModel()
        val nested = File(container.storage.projectsRoot, "nested").apply { mkdirs() }
        viewModel.changeDirectory(nested.absolutePath)
        assertEquals(nested.canonicalPath, viewModel.state.value.active!!.cwd)

        viewModel.submit("cd")

        assertEquals(container.storage.projectsRoot.absolutePath, viewModel.state.value.active!!.cwd)
    }

    @Test
    fun `cd is recorded in history like any other command`() {
        val viewModel = newViewModel()
        File(container.storage.projectsRoot, "hist-target").mkdirs()

        viewModel.submit("cd hist-target")

        assertEquals("cd hist-target", viewModel.state.value.history.first())
    }

    @Test
    fun `history walks from newest to oldest and back`() {
        val viewModel = newViewModel()
        viewModel.runToCompletion("echo one")
        viewModel.runToCompletion("echo two")

        assertEquals("echo two", viewModel.state.value.history.first())

        viewModel.previousHistory()
        assertEquals("echo two", viewModel.historyEntry())
        viewModel.previousHistory()
        assertEquals("echo one", viewModel.historyEntry())
        viewModel.previousHistory()
        assertEquals("the oldest entry is the limit", "echo one", viewModel.historyEntry())

        viewModel.nextHistory()
        assertEquals("stepping back down returns the newer entry", "echo two", viewModel.historyEntry())
    }

    @Test
    fun `typing resets history browsing`() {
        val viewModel = newViewModel()
        viewModel.runToCompletion("echo one")
        viewModel.previousHistory()
        assertEquals("echo one", viewModel.historyEntry())

        viewModel.resetHistoryCursor()

        assertEquals(-1, viewModel.state.value.historyCursor)
    }

    @Test
    fun `each session keeps its own directory`() {
        val viewModel = newViewModel()
        val first = viewModel.state.value.active!!.id
        val target = File(container.storage.projectsRoot, "session-a").apply { mkdirs() }
        viewModel.changeDirectory(target.absolutePath)

        viewModel.newSession()
        val second = viewModel.state.value.active!!
        assertNotEquals("a new session is a different session", first, second.id)
        assertEquals(
            "a new session starts at the root, not wherever the last one was",
            container.storage.projectsRoot.absolutePath,
            second.cwd,
        )

        viewModel.selectSession(0)
        assertEquals(target.canonicalPath, viewModel.state.value.active!!.cwd)
    }

    @Test
    fun `clear empties only the active session`() {
        val viewModel = newViewModel()
        viewModel.runToCompletion("echo one")
        val firstSessionLines = viewModel.state.value.active!!.lines.size
        assertTrue("there should be output to clear", firstSessionLines > 1)

        viewModel.newSession()
        viewModel.clear()

        assertEquals("the new session is already empty", 0, viewModel.state.value.active!!.lines.size)
        viewModel.selectSession(0)
        assertEquals(
            "clearing one session must not touch another",
            firstSessionLines,
            viewModel.state.value.active!!.lines.size,
        )
    }

    /**
     * Two sessions pump output on their own threads. Appending used to read the session list
     * outside the state update, so a line written by one session could be erased by the other
     * and simply never appear.
     */
    @Test
    fun `output from two sessions at once is never dropped`() {
        val viewModel = newViewModel()
        viewModel.newSession()
        val first = viewModel.state.value.sessions[0].id
        val second = viewModel.state.value.sessions[1].id
        val expected = 150

        // Both commands are started before either is awaited, so the pumps overlap.
        viewModel.selectSession(0)
        viewModel.submit("i=0; while [ \$i -lt $expected ]; do echo A\$i; i=\$((i+1)); done")
        viewModel.selectSession(1)
        viewModel.submit("i=0; while [ \$i -lt $expected ]; do echo B\$i; i=\$((i+1)); done")

        runBlocking {
            withTimeout(60_000) {
                viewModel.state.first { state ->
                    state.sessions.all { !it.running } &&
                        state.sessions.all { it.lines.count { line -> !line.isCommand } >= expected }
                }
            }
        }

        val sessions = viewModel.state.value.sessions
        val a = sessions.first { it.id == first }
        val b = sessions.first { it.id == second }
        assertEquals("session one lost output", expected, a.lines.count { it.text.startsWith("A") })
        assertEquals("session two lost output", expected, b.lines.count { it.text.startsWith("B") })
        assertTrue(
            "each session keeps its own output",
            a.lines.none { it.text.startsWith("B") } && b.lines.none { it.text.startsWith("A") },
        )
    }

    /**
     * Each session can hold a live shell process. Before the limit was enforced, holding the
     * "+" button created sessions until the app ran out of file descriptors.
     */
    @Test
    fun `sessions stop at the configured limit and say why`() = runBlocking {
        container.settings.updateTerminal { it.copy(maxSessions = 3) }
        val viewModel = newViewModel()
        repeat(2) { viewModel.newSession() }
        assertEquals("the limit allows three sessions", 3, viewModel.state.value.sessions.size)

        viewModel.newSession()

        assertEquals("a fourth session must be refused", 3, viewModel.state.value.sessions.size)
        assertTrue(
            "the refusal must be visible, lines=${viewModel.state.value.active!!.lines.map { it.text }}",
            viewModel.state.value.active!!.lines.any {
                it.isError && it.text.contains("Session limit reached")
            },
        )

        viewModel.closeSession(2)
        viewModel.newSession()
        assertEquals("closing one frees a slot", 3, viewModel.state.value.sessions.size)
    }

    /**
     * `clear` only empties the active session. It used to replace the whole session list with
     * a snapshot taken before the update, so any line another session wrote in between was
     * thrown away — output from a background command silently disappeared.
     */
    @Test
    fun `clearing one session never drops another session's output`() {
        val viewModel = newViewModel()
        viewModel.newSession()
        val first = viewModel.state.value.sessions[0].id
        val second = viewModel.state.value.sessions[1].id
        viewModel.selectSession(0)

        val expected = 2000
        val writer = kotlin.concurrent.thread {
            repeat(expected) {
                viewModel.appendTo(second, TerminalLine("bg $it"))
                if (it % 8 == 0) Thread.yield()
            }
        }
        repeat(2000) { viewModel.clear() }
        writer.join()

        assertEquals(
            "every line the background session wrote must still be there",
            expected,
            viewModel.state.value.sessions[1].lines.size,
        )
        assertEquals("clear must still empty the session it was called on", 0, viewModel.state.value.sessions[0].lines.size)
        assertTrue("the first session id must be stable", first != second)
    }
}
