package dev.titanide.core.engine

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs a real external process and streams its output. This is the only place in the app
 * that spawns child processes, so cancellation, timeouts and log retention are enforced
 * once rather than per call site.
 */
class ProcessRunner(private val scope: CoroutineScope) {

    data class Outcome(
        val exitCode: Int,
        val timedOut: Boolean,
        val cancelled: Boolean,
        val lines: List<String>,
        val durationMs: Long,
        val startFailure: String? = null,
    )

    sealed interface Event {
        data class Started(val command: List<String>, val workingDir: String) : Event
        data class Line(val text: String) : Event
        data class Finished(val exitCode: Int, val durationMs: Long) : Event
        data class Failed(val reason: String) : Event
    }

    private val _events = MutableSharedFlow<Event>(replay = 0, extraBufferCapacity = 512, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    private val _running = MutableStateFlow<Set<String>>(emptySet())
    val running: StateFlow<Set<String>> = _running.asStateFlow()

    private val active = HashMap<String, Process>()
    private val cancelled = HashMap<String, AtomicBoolean>()

    /** Runs [command] in [workingDir], collecting at most [maxLines] output lines. */
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
            return@withContext Outcome(-1, false, false, emptyList(), 0, "Working directory does not exist: $workingDir")
        }

        val started = System.currentTimeMillis()
        val cancelFlag = AtomicBoolean(false)
        cancelled[sessionId] = cancelFlag
        val lines = ArrayList<String>(512)

        val builder = ProcessBuilder(command)
            .directory(workingDir)
            .redirectErrorStream(true)
        builder.environment().putAll(environment)
        builder.environment()["TERM"] = "dumb"

        val process = try {
            builder.start()
        } catch (e: Exception) {
            cancelled.remove(sessionId)
            val message = "Cannot start ${command.first()}: ${e.message}"
            _events.tryEmit(Event.Failed(message))
            return@withContext Outcome(-1, false, false, listOf(message), 0, message)
        }

        active[sessionId] = process
        _running.value = _running.value + sessionId
        _events.tryEmit(Event.Started(command, workingDir.absolutePath))

        val reader = process.inputStream.bufferedReader()
        val pump = scope.launch(Dispatchers.IO) {
            try {
                reader.use { bufferedReader: BufferedReader ->
                    var line = bufferedReader.readLine()
                    while (line != null) {
                        if (lines.size < maxLines) {
                            synchronized(lines) { lines += line }
                        }
                        onLine?.invoke(line)
                        _events.tryEmit(Event.Line(line))
                        line = bufferedReader.readLine()
                    }
                }
            } catch (e: Exception) {
                if (!cancelFlag.get()) Log.w(TAG, "Log pump ended: ${e.message}")
            }
        }

        var timedOut = false
        val finished = try {
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                timedOut = true
                process.destroy()
                if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly()
                false
            } else {
                true
            }
        } catch (e: InterruptedException) {
            process.destroyForcibly()
            false
        }

        withTimeoutOrNull(5_000) { pump.join() }
        active.remove(sessionId)
        cancelled.remove(sessionId)
        _running.value = _running.value - sessionId

        val exitCode = if (finished && !timedOut) runCatching { process.exitValue() }.getOrDefault(-1) else -1
        val duration = System.currentTimeMillis() - started

        if (timedOut) {
            synchronized(lines) { lines += "Process exceeded the ${timeoutMs / 1000}s timeout and was terminated." }
        }
        if (cancelFlag.get()) {
            synchronized(lines) { lines += "Process cancelled by user." }
        }

        _events.tryEmit(Event.Finished(exitCode, duration))
        Outcome(
            exitCode = exitCode,
            timedOut = timedOut,
            cancelled = cancelFlag.get(),
            lines = synchronized(lines) { lines.toList() },
            durationMs = duration,
        )
    }

    /** Streams a command without waiting; the caller collects through [events]. */
    suspend fun startStreaming(
        sessionId: String,
        command: List<String>,
        workingDir: File,
        environment: Map<String, String> = emptyMap(),
        onLine: (String) -> Unit,
    ): Outcome = run(sessionId, command, workingDir, environment, onLine = onLine)

    fun cancel(sessionId: String) {
        cancelled[sessionId]?.set(true)
        active[sessionId]?.let { process ->
            runCatching {
                process.destroy()
                if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
            }
        }
    }

    fun cancelAll() {
        active.keys.toList().forEach { cancel(it) }
    }

    fun isRunning(sessionId: String): Boolean = active.containsKey(sessionId)

    /** Probes whether an executable exists and reports its first version line. */
    suspend fun probe(tool: String, args: List<String> = listOf("--version"), timeoutMs: Long = 8_000): Pair<Boolean, String?> {
        val result = run("probe-$tool", listOf(tool) + args, File("/"), timeoutMs = timeoutMs, maxLines = 20)
        if (result.startFailure != null) return false to null
        val versionLine = result.lines.firstOrNull { it.isNotBlank() }?.trim()
        return (result.exitCode == 0) to versionLine
    }

    companion object {
        private const val TAG = "ProcessRunner"
    }
}

/**
 * Streams a file that another process is appending to, used to follow a remote build log
 * without polling the network for the whole file each time.
 */
class LogTailer(private val scope: CoroutineScope) {

    fun tail(file: File, pollMs: Long = 500): SharedFlow<String> {
        val flow = MutableSharedFlow<String>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        scope.launch(Dispatchers.IO) {
            if (!file.exists()) return@launch
            file.bufferedReader().use { reader ->
                var position = 0L
                while (isActive) {
                    val length = file.length()
                    if (length < position) position = 0
                    if (length > position) {
                        reader.skip(0)
                        val chunk = StringBuilder()
                        var read = 0
                        while (read < (length - position)) {
                            val c = reader.read()
                            if (c == -1) break
                            chunk.append(c.toChar())
                            read++
                        }
                        position = length
                        chunk.toString().split('\n').filter { it.isNotEmpty() }.forEach { flow.tryEmit(it) }
                    }
                    delay(pollMs)
                }
            }
        }
        return flow
    }
}
