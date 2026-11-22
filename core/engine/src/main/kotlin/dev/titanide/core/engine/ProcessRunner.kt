package dev.titanide.core.engine

import android.util.Log
import dev.titanide.core.data.security.SecureCredentialStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Runs real external processes and streams their output. This is the only place in the app
 * that spawns child processes, so cancellation, timeouts and log retention are enforced
 * once instead of at every call site.
 *
 * Two properties matter more than anything else here:
 *
 *  - **Cancelling the coroutine kills the process.** A Gradle build that keeps running
 *    after the user pressed Stop is worse than no Stop button, because it holds a lock on
 *    the project's `.gradle` directory and the next build blocks behind it.
 *  - **Cancellation is scoped to one session.** Several projects can build at once, so
 *    stopping one must never touch another.
 */
class ProcessRunner(private val scope: CoroutineScope) {

    data class Outcome(
        val exitCode: Int,
        val timedOut: Boolean,
        val cancelled: Boolean,
        val lines: List<String>,
        val durationMs: Long,
        val startFailure: String? = null,
    ) {
        /** True only when the process really ran to completion and reported success. */
        val isSuccess: Boolean get() = startFailure == null && !timedOut && !cancelled && exitCode == 0
    }

    sealed interface Event {
        data class Started(val sessionId: String, val command: List<String>, val workingDir: String) : Event
        data class Line(val sessionId: String, val text: String) : Event
        data class Finished(val sessionId: String, val exitCode: Int, val durationMs: Long) : Event
        data class Failed(val sessionId: String, val reason: String) : Event
    }

    private val _events = MutableSharedFlow<Event>(
        replay = 0,
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<Event> = _events.asSharedFlow()

    private val _running = MutableStateFlow<Set<String>>(emptySet())
    val running: StateFlow<Set<String>> = _running.asStateFlow()

    private class Session(
        @Volatile var process: Process? = null,
        val cancelled: AtomicBoolean = AtomicBoolean(false),
    )

    private val sessions = ConcurrentHashMap<String, Session>()

    /**
     * Runs [command] in [workingDir], collecting at most [maxLines] output lines.
     *
     * Returns an [Outcome] for every ordinary failure mode (missing tool, missing
     * directory, non-zero exit, timeout, user cancellation) and throws only for coroutine
     * cancellation, after the process has been killed.
     */
    suspend fun run(
        sessionId: String,
        command: List<String>,
        workingDir: File,
        environment: Map<String, String> = emptyMap(),
        timeoutMs: Long = 15 * 60 * 1000,
        maxLines: Int = 20_000,
        onLine: ((String) -> Unit)? = null,
    ): Outcome = withContext(Dispatchers.IO) {
        if (command.isEmpty()) {
            return@withContext Outcome(-1, false, false, emptyList(), 0, "Empty command")
        }
        if (!workingDir.exists()) {
            return@withContext Outcome(
                -1, false, false, emptyList(), 0,
                "Working directory does not exist: $workingDir",
            )
        }

        val started = System.currentTimeMillis()
        val session = Session()
        // Reusing a session id replaces the old run; kill it so it cannot keep running
        // invisibly and holding file locks.
        sessions.put(sessionId, session)?.let { previous ->
            previous.cancelled.set(true)
            kill(previous.process)
        }

        val lines = ArrayList<String>(512)
        // Everything a process prints passes through here: the returned lines, the caller's
        // callback and the event stream. A build log is rendered in the app and handed to the
        // AI as context, so a token a tool echoes must be masked before anyone can read it.
        fun record(raw: String) {
            val line = SecureCredentialStore.scrub(raw)
            synchronized(lines) { if (lines.size < maxLines) lines += line }
            onLine?.invoke(line)
            _events.tryEmit(Event.Line(sessionId, line))
        }

        val builder = ProcessBuilder(command)
            .directory(workingDir)
            .redirectErrorStream(true)
        builder.environment().putAll(environment)
        builder.environment()["TERM"] = "dumb"

        val process = try {
            builder.start()
        } catch (e: Exception) {
            sessions.remove(sessionId, session)
            val message = "Cannot start ${command.first()}: ${e.message}"
            record(message)
            _events.tryEmit(Event.Failed(sessionId, message))
            return@withContext Outcome(-1, false, false, listOf(message), 0, message)
        }

        session.process = process
        _running.value = _running.value + sessionId
        _events.tryEmit(Event.Started(sessionId, command, workingDir.absolutePath))

        val pump = scope.launch(Dispatchers.IO) {
            try {
                process.inputStream.bufferedReader().use { reader ->
                    var line = reader.readLine()
                    while (line != null) {
                        record(line)
                        line = reader.readLine()
                    }
                }
            } catch (e: Exception) {
                if (!session.cancelled.get()) Log.w(TAG, "Log pump ended: ${e.message}")
            }
        }

        var timedOut = false
        val exited = try {
            // Wait for the process, but abandon the wait as soon as the caller's coroutine
            // is cancelled; the cancellation handler does the killing.
            val completed = suspendCancellableCoroutine { cont ->
                val waiter = scope.launch(Dispatchers.IO) {
                    val done = try {
                        process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
                    } catch (e: InterruptedException) {
                        false
                    }
                    if (cont.isActive) cont.resume(done)
                }
                cont.invokeOnCancellation {
                    waiter.cancel()
                    session.cancelled.set(true)
                    kill(process)
                }
            }
            if (!completed) {
                timedOut = true
                kill(process)
            }
            completed
        } catch (e: kotlinx.coroutines.CancellationException) {
            session.cancelled.set(true)
            kill(process)
            withContext(kotlinx.coroutines.NonCancellable) {
                pump.cancel()
                cleanup(sessionId, session)
            }
            throw e
        }

        pump.join()
        cleanup(sessionId, session)

        val cancelled = session.cancelled.get()
        val exitCode = if (exited && !timedOut) runCatching { process.exitValue() }.getOrDefault(-1) else -1
        val duration = System.currentTimeMillis() - started

        if (timedOut) record("Process exceeded the ${timeoutMs / 1000}s timeout and was terminated.")
        if (cancelled) record("Process cancelled by user.")

        _events.tryEmit(Event.Finished(sessionId, exitCode, duration))
        Outcome(
            exitCode = exitCode,
            timedOut = timedOut,
            cancelled = cancelled,
            lines = synchronized(lines) { lines.toList() },
            durationMs = duration,
        )
    }

    private fun cleanup(sessionId: String, session: Session) {
        sessions.remove(sessionId, session)
        _running.value = _running.value - sessionId
    }

    /** Streams a command without waiting; the caller collects through [events]. */
    suspend fun startStreaming(
        sessionId: String,
        command: List<String>,
        workingDir: File,
        environment: Map<String, String> = emptyMap(),
        onLine: (String) -> Unit,
    ): Outcome = run(sessionId, command, workingDir, environment, onLine = onLine)

    /** Stops one session and everything it spawned. Other sessions are untouched. */
    fun cancel(sessionId: String) {
        sessions[sessionId]?.let { session ->
            session.cancelled.set(true)
            kill(session.process)
        }
    }

    /** Stops every session. For app teardown only, not for a single build's Stop button. */
    fun cancelAll() {
        sessions.values.forEach { session ->
            session.cancelled.set(true)
            kill(session.process)
        }
    }

    fun isRunning(sessionId: String): Boolean = sessions.containsKey(sessionId)

    /** Probes whether an executable exists and reports its first version line. */
    suspend fun probe(
        tool: String,
        args: List<String> = listOf("--version"),
        timeoutMs: Long = 8_000,
    ): Pair<Boolean, String?> {
        val result = run("probe-$tool", listOf(tool) + args, File("/"), timeoutMs = timeoutMs, maxLines = 20)
        if (result.startFailure != null) return false to null
        val versionLine = result.lines.firstOrNull { it.isNotBlank() }?.trim()
        return (result.exitCode == 0) to versionLine
    }

    companion object {
        private const val TAG = "ProcessRunner"

        /**
         * Kills a process and its children.
         *
         * A build tool such as Gradle forks a daemon and compiler workers. Destroying only
         * the parent leaves those children alive, still holding the project lock, so the
         * whole process group is signalled. Android has no
         * `ProcessHandle.descendants` below API 34, hence the `ps` walk.
         */
        fun kill(process: Process?) {
            val target = process ?: return
            if (!target.isAlive) return
            val pid = pidOf(target)
            if (pid != null) killProcessGroup(pid)
            runCatching { target.destroy() }
            if (runCatching { target.waitFor(2, TimeUnit.SECONDS) }.getOrDefault(false)) return
            runCatching { target.destroyForcibly() }
            runCatching { target.waitFor(2, TimeUnit.SECONDS) }
        }

        /**
         * The OS process id behind [process], or null when it cannot be determined.
         *
         * `java.lang.Process.pid()` is not in the Android SDK stub, and Android's libcore
         * implementation exposes the id under a different name than the JDK does, so both
         * shapes are tried. A null result degrades to killing the parent only, which is no
         * worse than not attempting a group kill at all.
         */
        private fun pidOf(process: Process): Long? {
            val cls = process.javaClass
            for (name in listOf("pid", "getPid")) {
                runCatching {
                    val value = cls.getMethod(name).invoke(process)
                    val pid = when (value) {
                        is Int -> value.toLong()
                        is Long -> value
                        else -> null
                    }
                    if (pid != null && pid > 0) return pid
                }
            }
            for (name in listOf("pid", "mPid")) {
                runCatching {
                    val field = cls.getDeclaredField(name).apply { isAccessible = true }
                    val pid = when (val value = field.get(process)) {
                        is Int -> value.toLong()
                        is Long -> value
                        else -> null
                    }
                    if (pid != null && pid > 0) return pid
                }
            }
            return null
        }

        private fun killProcessGroup(pid: Long) {
            val children = descendantPids(pid)
            runCatching { execKill(listOf("-TERM", "-$pid")) }
            children.asReversed().forEach { runCatching { execKill(listOf("-TERM", it.toString())) } }
            runCatching { Thread.sleep(300) }
            runCatching { execKill(listOf("-KILL", "-$pid")) }
            children.forEach { runCatching { execKill(listOf("-KILL", it.toString())) } }
        }

        private fun execKill(args: List<String>) {
            ProcessBuilder(listOf("kill") + args)
                .redirectErrorStream(true)
                .start()
                .also { it.waitFor(2, TimeUnit.SECONDS) }
        }

        /** Direct and transitive children of [pid], best effort. */
        private fun descendantPids(pid: Long): List<Long> {
            val parents = HashMap<Long, Long>()
            runCatching {
                ProcessBuilder("ps", "-A", "-o", "PID=,PPID=")
                    .redirectErrorStream(true)
                    .start()
                    .inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            val parts = line.trim().split(Regex("\\s+"))
                            val child = parts.getOrNull(0)?.toLongOrNull()
                            val parent = parts.getOrNull(1)?.toLongOrNull()
                            if (child != null && parent != null) parents[child] = parent
                        }
                    }
            }
            val result = ArrayList<Long>()
            val frontier = ArrayDeque<Long>().apply { add(pid) }
            while (frontier.isNotEmpty()) {
                val current = frontier.removeFirst()
                parents.filterValues { it == current }.keys.forEach { child ->
                    if (child !in result) {
                        result += child
                        frontier.addLast(child)
                    }
                }
            }
            return result
        }
    }
}

/**
 * Follows a file that another process is appending to, so a remote build log can be shown
 * without re-fetching the whole file.
 */
class LogTailer(private val scope: CoroutineScope) {

    fun tail(file: File, pollMs: Long = 500): SharedFlow<String> {
        val flow = MutableSharedFlow<String>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        scope.launch(Dispatchers.IO) {
            if (!file.exists()) return@launch
            var position = 0L
            while (isActive) {
                val length = runCatching { file.length() }.getOrDefault(0L)
                if (length < position) position = 0L
                if (length > position) {
                    runCatching {
                        java.io.RandomAccessFile(file, "r").use { handle ->
                            handle.seek(position)
                            val chunk = ByteArray((length - position).toInt())
                            val read = handle.read(chunk)
                            if (read > 0) {
                                position += read
                                String(chunk, 0, read).split('\n')
                                    .filter { it.isNotEmpty() }
                                    .forEach { flow.tryEmit(it) }
                            }
                        }
                    }.onFailure { Log.w("LogTailer", "Tail read failed for ${file.name}", it) }
                }
                delay(pollMs)
            }
        }
        return flow
    }
}
