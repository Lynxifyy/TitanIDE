package dev.titanide.core.engine

import dev.titanide.core.data.files.ProjectDetector
import dev.titanide.core.data.files.SafeFileSystem
import dev.titanide.core.data.repo.BuildHistoryRepository
import dev.titanide.core.model.Artifact
import dev.titanide.core.data.security.SecureCredentialStore
import dev.titanide.core.model.BuildKind
import dev.titanide.core.model.BuildRequest
import dev.titanide.core.model.BuildResult
import dev.titanide.core.model.BuildStage
import dev.titanide.core.model.BuildStatus
import dev.titanide.core.model.ExecutionTarget
import dev.titanide.core.model.TestSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** A build backend. Local and remote implementations share this contract. */
interface BuildBackend {
    val target: ExecutionTarget
    suspend fun execute(request: BuildRequest, onLog: (String) -> Unit): BackendOutcome
}

data class BackendOutcome(
    val status: BuildStatus,
    val exitCode: Int?,
    val logLines: List<String>,
    val artifacts: List<Artifact>,
    val stages: List<BuildStage>,
    val failureReason: String?,
    val executorLabel: String,
    /** Detected project shape, so the result records what was actually built. */
    val projectKind: String? = null,
)

/**
 * Runs builds on the device with the toolchain that is actually present. If the tool is
 * missing the build fails immediately with a clear reason instead of emitting a fake log.
 */
class LocalBuildBackend(
    private val runner: ProcessRunner,
    private val fs: SafeFileSystem = SafeFileSystem(),
    /** Called before a process starts so the engine can scope Stop to this project. */
    private val onSessionStarted: (projectId: String, sessionId: String) -> Unit = { _, _ -> },
) : BuildBackend {

    override val target = ExecutionTarget.LOCAL

    override suspend fun execute(request: BuildRequest, onLog: (String) -> Unit): BackendOutcome {
        val root = File(request.projectPath)
        if (!root.isDirectory) {
            return failed("Project directory is missing: ${request.projectPath}")
        }

        val detected = withContext(Dispatchers.IO) { ProjectDetector.detect(root) }
        val plan = BuildPlanner.plan(request, root, detected)
        if (plan.error != null) {
            return failed(plan.error)
        }

        val stages = ArrayList<BuildStage>()
        val allLines = ArrayList<String>()

        plan.notes.forEach { note ->
            allLines += note
            onLog(note)
        }

        for (command in plan.commands) {
            val toolAvailable = toolExists(command.program)
            if (!toolAvailable) {
                val reason = "Required tool '${command.program}' is not installed on this device. " +
                    "Configure a remote build agent in Settings to build this project."
                allLines += reason
                onLog(reason)
                stages += BuildStage(command.stageName, System.currentTimeMillis(), System.currentTimeMillis(), false)
                return BackendOutcome(
                    status = BuildStatus.FAILED,
                    exitCode = null,
                    logLines = allLines,
                    artifacts = emptyList(),
                    stages = stages,
                    failureReason = reason,
                    executorLabel = "On device (toolchain incomplete)",
                )
            }

            onLog("> ${command.display}")
            val stageStart = System.currentTimeMillis()
            val sessionId = request.projectId + "-" + command.stageName
            onSessionStarted(request.projectId, sessionId)
            val workingDir = command.workingDirUnder(root)
            if (workingDir == null) {
                val reason = "'${command.workDir}' is not inside the project, so " +
                    "${command.display} was not run."
                allLines += reason
                onLog(reason)
                stages += BuildStage(command.stageName, System.currentTimeMillis(), System.currentTimeMillis(), false)
                return BackendOutcome(
                    status = BuildStatus.FAILED,
                    exitCode = null,
                    logLines = allLines,
                    artifacts = emptyList(),
                    stages = stages,
                    failureReason = reason,
                    executorLabel = "On device",
                )
            }
            val outcome = runner.run(
                sessionId = sessionId,
                command = listOf(command.program) + command.args,
                workingDir = workingDir,
                environment = request.environment,
                timeoutMs = command.timeoutMs,
                onLine = { line ->
                    allLines += line
                    onLog(line)
                },
            )
            val ok = outcome.exitCode == 0 && !outcome.timedOut && !outcome.cancelled
            stages += BuildStage(command.stageName, stageStart, System.currentTimeMillis(), ok)

            if (outcome.startFailure != null) {
                allLines += outcome.startFailure
                return BackendOutcome(
                    status = BuildStatus.FAILED,
                    exitCode = outcome.exitCode,
                    logLines = allLines,
                    artifacts = emptyList(),
                    stages = stages,
                    failureReason = outcome.startFailure,
                    executorLabel = "On device",
                )
            }
            if (outcome.cancelled) {
                return BackendOutcome(
                    status = BuildStatus.CANCELLED,
                    exitCode = outcome.exitCode,
                    logLines = allLines,
                    artifacts = emptyList(),
                    stages = stages,
                    failureReason = "Cancelled by user",
                    executorLabel = "On device",
                )
            }
            if (outcome.timedOut) {
                return BackendOutcome(
                    status = BuildStatus.FAILED,
                    exitCode = outcome.exitCode,
                    logLines = allLines,
                    artifacts = emptyList(),
                    stages = stages,
                    failureReason = "Build timed out during ${command.stageName}",
                    executorLabel = "On device",
                )
            }
            if (!ok) {
                return BackendOutcome(
                    status = BuildStatus.FAILED,
                    exitCode = outcome.exitCode,
                    logLines = allLines,
                    artifacts = emptyList(),
                    stages = stages,
                    failureReason = "${command.stageName} failed with exit code ${outcome.exitCode}",
                    executorLabel = "On device",
                )
            }
        }

        val artifacts = collectArtifacts(root, plan.artifactGlobs)
        return BackendOutcome(
            status = BuildStatus.SUCCEEDED,
            exitCode = 0,
            logLines = allLines,
            artifacts = artifacts,
            stages = stages,
            failureReason = null,
            executorLabel = "On device",
            projectKind = plan.projectKind,
        )
    }

    private fun toolExists(program: String): Boolean {
        if (program.contains('/')) return File(program).let { it.isFile && it.canExecute() }
        val pathEnv = System.getenv("PATH") ?: return false
        return pathEnv.split(File.pathSeparator).any { File(it, program).let { f -> f.isFile && f.canExecute() } }
    }

    private fun collectArtifacts(root: File, globs: List<String>): List<Artifact> = withContextArtifacts(root, globs)

    private fun withContextArtifacts(root: File, globs: List<String>): List<Artifact> {
        val found = ArrayList<Artifact>()
        for (glob in globs) {
            val (dir, pattern) = glob.substringBeforeLast('/', "") to glob.substringAfterLast('/')
            val searchRoot = if (dir.isEmpty()) root else File(root, dir)
            if (!searchRoot.isDirectory) continue
            val regex = GlobMatcher.toRegex(pattern)
            searchRoot.walkTopDown()
                .maxDepth(6)
                .filter { it.isFile && regex.matches(it.name) }
                .take(20)
                .forEach { file ->
                    found += Artifact(
                        name = file.name,
                        path = file.absolutePath,
                        sizeBytes = file.length(),
                        mimeType = mimeOf(file.name),
                    )
                }
        }
        return found
    }

    private fun mimeOf(name: String): String? = when (name.substringAfterLast('.', "")) {
        "apk" -> "application/vnd.android.package-archive"
        "aar", "jar" -> "application/java-archive"
        "zip", "mcpack" -> "application/zip"
        "so" -> "application/x-sharedlib"
        "dll" -> "application/x-msdownload"
        "wasm" -> "application/wasm"
        else -> null
    }

    private fun failed(reason: String) = BackendOutcome(
        status = BuildStatus.FAILED,
        exitCode = null,
        logLines = listOf(reason),
        artifacts = emptyList(),
        stages = emptyList(),
        failureReason = reason,
        executorLabel = "On device",
    )
}

object GlobMatcher {
    fun toRegex(pattern: String): Regex {
        val sb = StringBuilder("^")
        for (c in pattern) {
            when (c) {
                '*' -> sb.append("[^/]*")
                '?' -> sb.append("[^/]")
                '.' -> sb.append("\\.")
                else -> if (c.isLetterOrDigit() || c == '_' || c == '-') sb.append(c) else sb.append("\\$c")
            }
        }
        sb.append("$")
        return Regex(sb.toString())
    }
}

/** A single planned command, derived from the detected build system. */
data class PlannedCommand(
    val stageName: String,
    val program: String,
    val args: List<String>,
    val timeoutMs: Long = 20 * 60 * 1000,
    /** Directory to run in, relative to the project root. Empty means the root itself. */
    val workDir: String = "",
) {
    val display: String get() = (listOf(program) + args).joinToString(" ")

    /**
     * Resolves [workDir] against the project root, or null if it escapes it. The value comes
     * from project files, so it must not be able to point outside the project.
     */
    fun workingDirUnder(root: File): File? {
        if (workDir.isBlank()) return root
        val candidate = File(root, workDir).canonicalFile
        val canonicalRoot = root.canonicalFile
        val inside = candidate.path == canonicalRoot.path ||
            candidate.path.startsWith(canonicalRoot.path + File.separator)
        return candidate.takeIf { inside && it.isDirectory }
    }
}

data class BuildPlan(
    val commands: List<PlannedCommand>,
    val artifactGlobs: List<String>,
    val error: String? = null,
    /** Explanation of how the plan was chosen, shown at the top of the build log. */
    val notes: List<String> = emptyList(),
    /** Detected project shape for Gradle projects; null for other build systems. */
    val projectKind: String? = null,
)

/**
 * Chooses the commands for a request. Only real, supported build systems produce
 * commands; anything else returns an explicit error so the UI can say why.
 */
object BuildPlanner {

    fun plan(
        request: BuildRequest,
        root: File,
        detected: ProjectDetector.Detected,
    ): BuildPlan {
        val gradleWrapper = File(root, "gradlew")
        val useWrapper = gradleWrapper.exists()

        fun gradleCommand(task: String) = if (useWrapper) {
            PlannedCommand("gradle $task", gradleWrapper.absolutePath, listOf(task, "--console=plain", "--no-daemon"))
        } else {
            PlannedCommand("gradle $task", "gradle", listOf(task, "--console=plain", "--no-daemon"))
        }

        val system = detected.buildSystem
        if (system == "none") {
            return BuildPlan(
                commands = emptyList(),
                artifactGlobs = emptyList(),
                error = "No build system was detected in this project. " +
                    detected.notes.firstOrNull().orEmpty(),
            )
        }

        // Gradle needs the project's real shape: assembleDebug does not exist in a plain
        // JVM or Compose Multiplatform build, so the task names are resolved from the
        // build scripts rather than assumed to be Android.
        val gradleTasks = if (system == "gradle") GradleTaskPlanner.plan(root) else null

        if (gradleTasks != null && request.kind == BuildKind.RUN && gradleTasks.runTask == null) {
            return BuildPlan(
                commands = emptyList(),
                artifactGlobs = gradleTasks.artifactGlobs,
                error = "This Gradle project has no runnable entry point " +
                    "(${gradleTasks.evidence}). Install the built artifact instead.",
            )
        }

        val tasks = when (request.kind) {
            BuildKind.CLEAN -> listOf(gradleTasks?.cleanTask ?: cleanTask(system))
            BuildKind.BUILD -> listOf(gradleTasks?.buildTasks?.firstOrNull() ?: primaryTask(system))
            BuildKind.REBUILD -> listOf(
                gradleTasks?.cleanTask ?: cleanTask(system),
                gradleTasks?.buildTasks?.firstOrNull() ?: primaryTask(system),
            )
            BuildKind.TEST -> listOf(gradleTasks?.testTasks?.firstOrNull() ?: testTask(system))
            BuildKind.RUN -> {
                if (gradleTasks != null) {
                    listOfNotNull(gradleTasks.runTask)
                } else {
                    val target = RunTargetResolver.resolve(root, system)
                    if (target == null) {
                        return BuildPlan(
                            commands = emptyList(),
                            artifactGlobs = artifactGlobs(system),
                            error = "Nothing in this project is runnable: " +
                                "no start script, entry point or run target was found. " +
                                "Build it and use the artifact instead.",
                        )
                    }
                    // A resolved target already carries its own program and arguments, so it
                    // bypasses the per-system task translation below.
                    return BuildPlan(
                        commands = listOf(
                            PlannedCommand(
                                stageName = target.display,
                                program = target.program,
                                args = target.args,
                                workDir = target.workDir,
                            ),
                        ),
                        artifactGlobs = artifactGlobs(system),
                    )
                }
            }
            BuildKind.STOP -> return BuildPlan(emptyList(), emptyList(), "STOP is handled by cancelling the running build")
        }

        val commands = tasks.mapNotNull { task ->
            when (system) {
                "gradle" -> if (task.startsWith(".") || task.contains("gradlew")) null else gradleCommand(task)
                "npm" -> PlannedCommand(task, "npm", task.split(' '))
                "cargo" -> PlannedCommand(task, "cargo", task.split(' '))
                "go" -> PlannedCommand(task, "go", task.split(' '))
                "cmake" -> PlannedCommand(task, "cmake", cmakeArgs(root, task))
                "make" -> PlannedCommand(task, "make", task.split(' '))
                "pip" -> PlannedCommand(task, "python3", pipArgs(task))
                "composer" -> PlannedCommand(task, "composer", task.split(' '))
                "bundler" -> PlannedCommand(task, "bundle", task.split(' '))
                "pub" -> PlannedCommand(task, "dart", task.split(' '))
                "dotnet" -> PlannedCommand(task, "dotnet", task.split(' '))
                "maven" -> PlannedCommand(task, "mvn", task.split(' '))
                else -> null
            }
        }

        if (commands.isEmpty()) {
            return BuildPlan(emptyList(), emptyList(), "No runnable command for $system/${request.kind}")
        }

        return BuildPlan(
            commands = commands,
            artifactGlobs = gradleTasks?.artifactGlobs ?: artifactGlobs(system),
            // Carried into the build log so the chosen tasks are explained, not guessed at.
            notes = listOfNotNull(gradleTasks?.evidence),
            projectKind = gradleTasks?.kind?.name,
        )
    }

    private fun primaryTask(system: String): String = when (system) {
        "gradle" -> "assembleDebug"
        "npm" -> "run build"
        "cargo" -> "build"
        "go" -> "build ./..."
        "cmake" -> "build"
        "make" -> "all"
        "pip" -> "build"
        "composer" -> "install"
        "bundler" -> "install"
        "pub" -> "pub get"
        "dotnet" -> "build"
        "maven" -> "package"
        else -> "build"
    }

    private fun cleanTask(system: String): String = when (system) {
        "gradle" -> "clean"
        "npm" -> "run clean"
        "cargo" -> "clean"
        "go" -> "clean -cache"
        "cmake" -> "clean"
        "make" -> "clean"
        "pip" -> "clean"
        "composer" -> "clear-cache"
        "bundler" -> "clean"
        "pub" -> "pub cache repair"
        "dotnet" -> "clean"
        "maven" -> "clean"
        else -> "clean"
    }

    private fun testTask(system: String): String = when (system) {
        "gradle" -> "testDebugUnitTest"
        "npm" -> "test"
        "cargo" -> "test"
        "go" -> "test ./..."
        "cmake" -> "ctest"
        "make" -> "test"
        "pip" -> "pytest"
        "composer" -> "test"
        "bundler" -> "test"
        "pub" -> "test"
        "dotnet" -> "test"
        "maven" -> "test"
        else -> "test"
    }

    private fun cmakeArgs(root: File, task: String): List<String> = when (task) {
        "build" -> if (File(root, "build").exists()) {
            listOf("--build", "build")
        } else {
            listOf("-S", ".", "-B", "build")
        }
        "clean" -> listOf("--build", "build", "--target", "clean")
        "ctest" -> listOf("--test-dir", "build", "--output-on-failure")
        "run" -> listOf("--build", "build", "--target", "run")
        else -> listOf("--build", "build")
    }

    private fun pipArgs(task: String): List<String> = when (task) {
        "build" -> listOf("-m", "build")
        "pytest" -> listOf("-m", "pytest", "-q")
        "install" -> listOf("-m", "pip", "install", "-e", ".")
        "clean" -> listOf("-m", "pip", "cache", "purge")
        else -> listOf("-m", task)
    }

    private fun artifactGlobs(system: String): List<String> = when (system) {
        "gradle" -> listOf(
            "app/build/outputs/apk/debug/*.apk",
            "app/build/outputs/apk/release/*.apk",
            "app/build/outputs/aar/*.aar",
            "lib/build/outputs/aar/*.aar",
            "build/libs/*.jar",
            "*/build/libs/*.jar",
        )
        "npm" -> listOf("dist/*.js", "dist/*.tgz")
        "cargo" -> listOf("target/debug/*", "target/release/*")
        "go" -> listOf("*.exe", "*")
        "cmake" -> listOf("build/*.so", "build/*", "build/**/*.so")
        "make" -> listOf("*.so", "*.a")
        "pip" -> listOf("dist/*.whl", "dist/*.tar.gz")
        "dotnet" -> listOf("bin/**/*.dll")
        "maven" -> listOf("target/*.jar")
        else -> emptyList()
    }
}

/**
 * Orchestrates builds: picks the backend, tracks live state, parses logs into
 * diagnostics and tests, and persists the result. State is exposed so the UI can show a
 * real progress view instead of a spinner with no meaning.
 */
class BuildEngine(
    private val scope: CoroutineScope,
    private val localBackend: LocalBuildBackend,
    private val remoteBackends: Map<ExecutionTarget, BuildBackend>,
    private val history: BuildHistoryRepository,
    private val runner: ProcessRunner,
) {

    private val _active = MutableStateFlow<Map<String, BuildResult>>(emptyMap())
    val active: StateFlow<Map<String, BuildResult>> = _active.asStateFlow()

    private val _logs = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val logs: StateFlow<Map<String, List<String>>> = _logs.asStateFlow()

    private val _lastResult = MutableStateFlow<BuildResult?>(null)
    val lastResult: StateFlow<BuildResult?> = _lastResult.asStateFlow()

    /** Guarded by its own lock: start/stop can be called from different threads. */
    private val jobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    /** Session ids owned by each project, so Stop only cancels that project's process. */
    private val sessionsByProject = java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>>()

    /** Records a session against a project so [stop] can cancel exactly that process. */
    fun registerSession(projectId: String, sessionId: String) {
        sessionsByProject
            .getOrPut(projectId) { java.util.concurrent.ConcurrentHashMap.newKeySet() }
            .add(sessionId)
    }

    fun backendFor(target: ExecutionTarget): BuildBackend? = when (target) {
        ExecutionTarget.LOCAL -> localBackend
        else -> remoteBackends[target]
    }

    fun isRunning(projectId: String): Boolean = _active.value.containsKey(projectId)

    /**
     * Starts a build and returns immediately with the build id. Progress is observable
     * through [active] and [logs].
     */
    fun start(request: BuildRequest): String {
        val buildId = UUID.randomUUID().toString()
        val backend = backendFor(request.target)
        val startedAt = System.currentTimeMillis()

        if (backend == null) {
            val result = BuildResult(
                id = buildId,
                request = request,
                status = BuildStatus.FAILED,
                startedAt = startedAt,
                finishedAt = startedAt,
                failureReason = "No build backend is configured for ${request.target.label}. " +
                    "Add a remote agent URL in Settings.",
                executorLabel = request.target.label,
            )
            _lastResult.value = result
            scope.launch { history.record(result) }
            return buildId
        }

        _active.value = _active.value + (request.projectId to BuildResult(
            id = buildId,
            request = request,
            status = BuildStatus.PREPARING,
            startedAt = startedAt,
            executorLabel = request.target.label,
        ))
        _logs.value = _logs.value + (buildId to emptyList())

        val job = scope.launch(Dispatchers.IO) {
            // The backend calls onLog from a process-reader thread, so the buffer must be
            // guarded; a plain ArrayList here can lose or corrupt lines under load.
            val collected = java.util.Collections.synchronizedList(ArrayList<String>())
            val outcome = try {
                backend.execute(request) { line ->
                    collected.add(line)
                    _logs.value = _logs.value + (buildId to synchronized(collected) { collected.toList() })
                    _active.value[request.projectId]?.let { current ->
                        _active.value = _active.value + (request.projectId to current.copy(status = BuildStatus.RUNNING))
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                BackendOutcome(
                    status = BuildStatus.FAILED,
                    exitCode = null,
                    logLines = synchronized(collected) { collected.toList() } + "Build crashed: ${e.message}",
                    artifacts = emptyList(),
                    stages = emptyList(),
                    failureReason = "Build crashed: ${e.message}",
                    executorLabel = request.target.label,
                )
            }

            val parsed = LogParser.parse(outcome.logLines, outcome.exitCode)
            val reading = reportedTestSummary(request.kind, parsed.testSummary, request.projectPath)
            // Process output is already scrubbed where it is read, but the remote backends
            // collect their log over HTTP, so the choke point every backend passes through
            // scrubs once more rather than trusting each one to remember.
            val cleanLog = outcome.logLines.map(SecureCredentialStore::scrub)
            val logLines = if (reading.notes.isEmpty()) {
                cleanLog
            } else {
                cleanLog + reading.notes
            }
            val finishedAt = System.currentTimeMillis()
            val result = BuildResult(
                id = buildId,
                request = request,
                status = outcome.status,
                startedAt = startedAt,
                finishedAt = finishedAt,
                exitCode = outcome.exitCode,
                stages = outcome.stages,
                logLines = logLines,
                diagnostics = parsed.diagnostics,
                artifacts = outcome.artifacts,
                testSummary = parsed.testSummary ?: reading.summary,
                failureReason = (outcome.failureReason ?: parsed.failureReason)
                    ?.let(SecureCredentialStore::scrub),
                executorLabel = outcome.executorLabel,
                projectKind = outcome.projectKind,
            )

            _active.value = _active.value - request.projectId
            _logs.value = _logs.value + (buildId to logLines)
            _lastResult.value = result
            history.record(result)
            jobs.remove(request.projectId)
        }
        jobs[request.projectId] = job
        return buildId
    }

    fun stop(projectId: String) {
        jobs.remove(projectId)?.cancel()
        // Cancel only this project's processes. Cancelling every session would kill an
        // unrelated project's build, which is exactly what a scoped Stop must not do.
        sessionsByProject.remove(projectId)?.forEach { sessionId -> runner.cancel(sessionId) }
        _active.value[projectId]?.let { current ->
            _active.value = _active.value - projectId
            _lastResult.value = current.copy(
                status = BuildStatus.CANCELLED,
                finishedAt = System.currentTimeMillis(),
                failureReason = "Cancelled by user",
            )
        }
    }

    suspend fun runAndWait(request: BuildRequest): BuildResult {
        val id = start(request)
        while (true) {
            val result = _lastResult.value
            if (result != null && result.id == id) return result
            kotlinx.coroutines.delay(120)
        }
    }
}

/**
 * The test results to report for a build.
 *
 * Gradle prints only the task name, so a real test run has no summary in the log at all. The
 * reports it wrote to disk are then the record of what actually ran. A run whose log already
 * carries a summary keeps it: the log is what the user just watched.
 */
internal fun reportedTestSummary(
    kind: BuildKind,
    fromLog: TestSummary?,
    projectPath: String,
): JUnitReportReader.Reading =
    if (kind == BuildKind.TEST && fromLog == null) {
        JUnitReportReader.readAll(File(projectPath))
    } else {
        JUnitReportReader.Reading(fromLog)
    }
