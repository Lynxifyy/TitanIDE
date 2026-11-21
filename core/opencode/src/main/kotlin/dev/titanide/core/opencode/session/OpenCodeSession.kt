package dev.titanide.core.opencode.session

import dev.titanide.core.opencode.model.CreateSessionBody
import dev.titanide.core.opencode.model.FileDiff
import dev.titanide.core.opencode.model.PermissionDecision
import dev.titanide.core.opencode.model.PermissionReplyBody
import dev.titanide.core.opencode.model.PermissionRequest
import dev.titanide.core.opencode.model.SessionInfo
import dev.titanide.core.opencode.net.OpenCodeClient
import dev.titanide.core.opencode.net.OpenCodeException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Connection lifecycle, shown in the status bar and the agent panel header. */
sealed interface OpenCodeConnection {
    data object Idle : OpenCodeConnection
    data class Connecting(val url: String) : OpenCodeConnection
    data class Connected(val version: String, val url: String) : OpenCodeConnection
    data class Failed(val reason: String) : OpenCodeConnection
}

/** Everything the agent panel renders, in one observable snapshot. */
data class OpenCodeState(
    val connection: OpenCodeConnection = OpenCodeConnection.Idle,
    val sessionId: String? = null,
    val sessionTitle: String = "",
    val activity: List<AgentActivity> = emptyList(),
    val running: Boolean = false,
    val pendingPermissions: List<PermissionRequest> = emptyList(),
    val changedFiles: List<FileDiff> = emptyList(),
    val lastError: String? = null,
) {
    val isConnected: Boolean get() = connection is OpenCodeConnection.Connected
    val toolCalls: List<AgentActivity.ToolCall> get() = activity.filterIsInstance<AgentActivity.ToolCall>()
    val currentTool: AgentActivity.ToolCall? get() = toolCalls.lastOrNull { it.isRunning }
}

/**
 * Owns one OpenCode conversation bound to one project directory.
 *
 * Two things make this a real agent rather than a chat box:
 *
 *  1. The conversation is polled from the server's own message list, so tool calls and
 *     shell output shown in the panel are the server's records, not local guesses.
 *  2. Approvals are forwarded to the server via `permission.reply`, so the server's own
 *     policy engine — not TITAN — decides whether a destructive action is allowed.
 *
 * The project directory is passed on session creation and is the same directory the build
 * engine uses, which is what makes "agent edits a file, then the build sees it" true.
 */
class OpenCodeSession(
    private val scope: CoroutineScope,
    private val clientFactory: (String, String?) -> OpenCodeClient = { url, token -> OpenCodeClient(url, token) },
    private val pollIntervalMs: Long = 700,
) {

    private val _state = MutableStateFlow(OpenCodeState())
    val state: StateFlow<OpenCodeState> = _state.asStateFlow()

    private var client: OpenCodeClient? = null
    private var pollJob: Job? = null
    private var serverUrl: String = ""

    /**
     * Connects and opens (or reuses) a session for [directory].
     *
     * [directory] must be the absolute path of the project on the machine running the
     * OpenCode server. TITAN cannot translate an Android sandbox path to a remote path, so
     * the caller supplies it explicitly and the UI shows it back to the user.
     */
    fun connect(url: String, token: String?, directory: String, reuseSessionId: String? = null) {
        if (url.isBlank()) {
            _state.value = _state.value.copy(
                connection = OpenCodeConnection.Failed("No OpenCode server URL configured. Set one in Settings."),
            )
            return
        }
        serverUrl = url
        _state.value = _state.value.copy(connection = OpenCodeConnection.Connecting(url))
        val newClient = clientFactory(url, token)
        client = newClient

        scope.launch {
            try {
                val info = newClient.info()
                _state.value = _state.value.copy(
                    connection = OpenCodeConnection.Connected(
                        version = info.version.ifBlank { "unknown" },
                        url = url,
                    ),
                )
                openSession(newClient, directory, reuseSessionId)
                startPolling(newClient)
            } catch (e: OpenCodeException) {
                _state.value = _state.value.copy(
                    connection = OpenCodeConnection.Failed(e.message ?: "Connection failed"),
                    lastError = e.message,
                )
            }
        }
    }

    /**
     * Records a setup problem the user must fix on the server machine, without pretending
     * a connection exists. Discovery calls this when no usable server is found.
     */
    fun reportSetupProblem(message: String) {
        pollJob?.cancel()
        client = null
        _state.value = _state.value.copy(
            connection = OpenCodeConnection.Failed(message),
            running = false,
            lastError = message,
        )
    }

    private suspend fun openSession(client: OpenCodeClient, directory: String, reuseSessionId: String?) {
        val existing = reuseSessionId?.let { id ->
            runCatching { client.getSession(id) }.getOrNull()
        }
        val session: SessionInfo = existing ?: client.createSession(
            CreateSessionBody(title = "TITAN: ${directory.substringAfterLast('/')}"),
        )
        _state.value = _state.value.copy(
            sessionId = session.id,
            sessionTitle = session.title ?: directory.substringAfterLast('/'),
            lastError = null,
        )
        refresh(client, session.id)
    }

    private fun startPolling(client: OpenCodeClient) {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                val id = _state.value.sessionId ?: break
                runCatching { refresh(client, id) }
                    .onFailure { e ->
                        if (e is OpenCodeException) {
                            _state.value = _state.value.copy(lastError = e.message)
                        }
                    }
                delay(pollIntervalMs)
            }
        }
    }

    private suspend fun refresh(client: OpenCodeClient, sessionId: String) {
        val messages = client.messages(sessionId)
        val activity = MessageParser.parseAll(messages)

        // The server has no "is running" flag on the session, so derive it from the last
        // assistant message: an unfinished turn means work is still in flight.
        val lastAssistantIncomplete = activity
            .filterIsInstance<AgentActivity.AssistantText>()
            .lastOrNull()
            ?.streaming == true
        val anyToolRunning = activity.filterIsInstance<AgentActivity.ToolCall>().any { it.isRunning }

        val permissions = runCatching { client.permissions(sessionId) }.getOrDefault(emptyList())
        val diffs = runCatching { client.diff(sessionId) }.getOrDefault(emptyList())

        _state.value = _state.value.copy(
            activity = activity,
            running = lastAssistantIncomplete || anyToolRunning || permissions.isNotEmpty(),
            pendingPermissions = permissions,
            changedFiles = diffs,
            lastError = null,
        )
    }

    /** Sends a user turn. Does not block until the agent finishes; the poll picks it up. */
    fun send(text: String) {
        val client = client ?: return fail("Not connected")
        val sessionId = _state.value.sessionId ?: return fail("No session")
        if (text.isBlank()) return

        scope.launch {
            try {
                client.prompt(sessionId, text)
                _state.value = _state.value.copy(running = true, lastError = null)
                refresh(client, sessionId)
            } catch (e: OpenCodeException) {
                fail(e.message ?: "Prompt failed")
            }
        }
    }

    fun stop() {
        val client = client ?: return
        val sessionId = _state.value.sessionId ?: return
        scope.launch {
            runCatching { client.interrupt(sessionId) }
            _state.value = _state.value.copy(running = false)
        }
    }

    fun answerPermission(requestId: String, decision: PermissionDecision) {
        val client = client ?: return
        val sessionId = _state.value.sessionId ?: return
        scope.launch {
            try {
                client.replyPermission(sessionId, requestId, PermissionReplyBody(decision))
                val remaining = _state.value.pendingPermissions.filterNot { it.id == requestId }
                _state.value = _state.value.copy(pendingPermissions = remaining)
            } catch (e: OpenCodeException) {
                fail(e.message ?: "Could not answer the permission request")
            }
        }
    }

    /** Re-reads the session immediately, e.g. after a build so new diffs show up. */
    fun refreshNow() {
        val client = client ?: return
        val sessionId = _state.value.sessionId ?: return
        scope.launch { runCatching { refresh(client, sessionId) } }
    }

    fun disconnect() {
        pollJob?.cancel()
        pollJob = null
        client = null
        _state.value = OpenCodeState()
    }

    fun currentServerUrl(): String = serverUrl

    private fun fail(message: String) {
        _state.value = _state.value.copy(lastError = message)
    }
}
