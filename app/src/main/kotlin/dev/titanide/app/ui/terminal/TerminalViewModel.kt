package dev.titanide.app.ui.terminal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.titanide.app.di.TitanContainer
import dev.titanide.core.engine.ProcessRunner
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

data class TerminalLine(val text: String, val isCommand: Boolean = false, val isError: Boolean = false)

data class TerminalSessionState(
    val id: String,
    val title: String,
    val cwd: String,
    val lines: List<TerminalLine> = emptyList(),
    val running: Boolean = false,
    val exitCode: Int? = null,
)

data class TerminalState(
    val sessions: List<TerminalSessionState> = emptyList(),
    val activeIndex: Int = 0,
    val history: List<String> = emptyList(),
    val historyCursor: Int = -1,
    val shellAvailable: Boolean? = null,
    val shellPath: String? = null,
) {
    val active: TerminalSessionState? get() = sessions.getOrNull(activeIndex)
}

/**
 * A terminal over real child processes.
 *
 * Android ships `sh` in `/system/bin`, so a shell exists, but the environment is not a
 * desktop: there is no `/usr/bin`, no package manager, and SELinux restricts what an app
 * may execute. The UI states this plainly instead of implying a full Linux terminal.
 */
class TerminalViewModel(private val container: TitanContainer) : ViewModel() {

    private val _state = MutableStateFlow(TerminalState())
    val state: StateFlow<TerminalState> = _state.asStateFlow()

    private val jobs = HashMap<String, Job>()

    init {
        viewModelScope.launch {
            val (available, version) = container.processRunner.probe(
                "sh",
                listOf("-c", "echo \$0; uname -s 2>/dev/null || true"),
            )
            _state.value = _state.value.copy(
                shellAvailable = available,
                shellPath = if (available) "/system/bin/sh" else null,
            )
            newSession()
            appendSystem(
                if (available) {
                    "Shell available. Working directory is app-private storage. " +
                        "Desktop tools such as a JDK or Gradle are not present unless you installed them."
                } else {
                    "No shell binary could be executed on this device. The terminal cannot run commands here."
                },
            )
        }
    }

    fun newSession() {
        val id = "term-${System.currentTimeMillis()}"
        val cwd = container.storage.projectsRoot.absolutePath
        val session = TerminalSessionState(
            id = id,
            title = "session ${_state.value.sessions.size + 1}",
            cwd = cwd,
        )
        val sessions = _state.value.sessions + session
        _state.value = _state.value.copy(sessions = sessions, activeIndex = sessions.lastIndex)
    }

    fun closeSession(index: Int) {
        val session = _state.value.sessions.getOrNull(index) ?: return
        jobs.remove(session.id)?.cancel()
        container.processRunner.cancel(session.id)
        val sessions = _state.value.sessions.toMutableList()
        sessions.removeAt(index)
        _state.value = _state.value.copy(
            sessions = sessions,
            activeIndex = _state.value.activeIndex.coerceAtMost((sessions.size - 1).coerceAtLeast(0)),
        )
    }

    fun selectSession(index: Int) {
        if (index in _state.value.sessions.indices) {
            _state.value = _state.value.copy(activeIndex = index)
        }
    }

    fun previousHistory() {
        val history = _state.value.history
        if (history.isEmpty()) return
        val cursor = (_state.value.historyCursor + 1).coerceAtMost(history.lastIndex)
        _state.value = _state.value.copy(historyCursor = cursor)
    }

    fun nextHistory() {
        val cursor = (_state.value.historyCursor - 1).coerceAtMost(0)
        _state.value = _state.value.copy(historyCursor = cursor)
    }

    fun historyEntry(): String? {
        val cursor = _state.value.historyCursor
        return _state.value.history.getOrNull(cursor)
    }

    /** Runs [command] in the active session and streams its output into that session. */
    fun submit(command: String) {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return
        val session = _state.value.active ?: return
        if (session.running) {
            appendLine("A command is still running. Wait or press Stop.", isError = true)
            return
        }

        _state.value = _state.value.copy(
            history = listOf(trimmed) + _state.value.history,
            historyCursor = -1,
        )
        appendLine("$ ${session.cwd.substringAfterLast('/')} $ $trimmed", isCommand = true)
        setRunning(session.id, true)

        val job = viewModelScope.launch {
            val workingDir = File(session.cwd).takeIf { it.isDirectory }
                ?: container.storage.projectsRoot
            val outcome = container.processRunner.run(
                sessionId = session.id,
                command = listOf("sh", "-c", trimmed),
                workingDir = workingDir,
                environment = mapOf(
                    "HOME" to container.storage.projectsRoot.parentFile?.absolutePath.orEmpty(),
                    "PWD" to workingDir.absolutePath,
                    "PATH" to "/system/bin:/system/xbin:/vendor/bin",
                ),
                timeoutMs = 120_000,
                onLine = { line -> appendTo(session.id, TerminalLine(line)) },
            )
            outcome.startFailure?.let { failure ->
                appendTo(session.id, TerminalLine(failure, isError = true))
            }
            appendTo(
                session.id,
                TerminalLine(
                    when {
                        outcome.cancelled -> "[cancelled]"
                        outcome.timedOut -> "[timed out after 120s]"
                        else -> "[exit ${outcome.exitCode}]"
                    },
                    isError = outcome.exitCode != 0,
                ),
            )
            setRunning(session.id, false, outcome.exitCode)
            jobs.remove(session.id)
        }
        jobs[session.id] = job
    }

    fun cancel() {
        val session = _state.value.active ?: return
        container.processRunner.cancel(session.id)
        jobs[session.id]?.cancel()
        setRunning(session.id, false)
    }

    fun clear() {
        val session = _state.value.active ?: return
        val sessions = _state.value.sessions.toMutableList()
        val index = sessions.indexOfFirst { it.id == session.id }
        if (index >= 0) {
            sessions[index] = sessions[index].copy(lines = emptyList())
            _state.value = _state.value.copy(sessions = sessions)
        }
    }

    fun changeDirectory(path: String) {
        val session = _state.value.active ?: return
        val target: File = if (path.startsWith("/")) File(path) else File(session.cwd, path)
        val canonical: File = try {
            target.canonicalFile
        } catch (error: java.io.IOException) {
            appendLine("cd: ${error.message}", isError = true)
            return
        }
        val sessions = _state.value.sessions.toMutableList()
        val index = sessions.indexOfFirst { it.id == session.id }
        if (index < 0) return
        if (!canonical.isDirectory) {
            appendLine("cd: no such directory: $path", isError = true)
            return
        }
        sessions[index] = sessions[index].copy(cwd = canonical.absolutePath)
        _state.value = _state.value.copy(sessions = sessions)
    }

    private fun appendSystem(text: String) {
        val session = _state.value.active ?: return
        appendTo(session.id, TerminalLine(text))
    }

    private fun appendLine(text: String, isCommand: Boolean = false, isError: Boolean = false) {
        val session = _state.value.active ?: return
        appendTo(session.id, TerminalLine(text, isCommand, isError))
    }

    private fun appendTo(sessionId: String, line: TerminalLine) {
        val sessions = _state.value.sessions.toMutableList()
        val index = sessions.indexOfFirst { it.id == sessionId }
        if (index < 0) return
        val session = sessions[index]
        val lines = (session.lines + line).takeLast(MAX_LINES)
        sessions[index] = session.copy(lines = lines)
        _state.value = _state.value.copy(sessions = sessions)
    }

    private fun setRunning(sessionId: String, running: Boolean, exitCode: Int? = null) {
        val sessions = _state.value.sessions.toMutableList()
        val index = sessions.indexOfFirst { it.id == sessionId }
        if (index < 0) return
        sessions[index] = sessions[index].copy(running = running, exitCode = exitCode)
        _state.value = _state.value.copy(sessions = sessions)
    }

    override fun onCleared() {
        super.onCleared()
        jobs.values.forEach { it.cancel() }
        container.processRunner.cancelAll()
    }

    companion object {
        private const val MAX_LINES = 2000
    }
}
