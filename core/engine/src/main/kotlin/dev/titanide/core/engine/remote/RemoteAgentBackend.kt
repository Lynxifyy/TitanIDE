package dev.titanide.core.engine.remote

import dev.titanide.core.engine.BackendOutcome
import dev.titanide.core.engine.BuildBackend
import dev.titanide.core.engine.BuildPlanner
import dev.titanide.core.data.files.ProjectDetector
import dev.titanide.core.model.Artifact
import dev.titanide.core.model.BuildRequest
import dev.titanide.core.model.BuildStage
import dev.titanide.core.model.BuildStatus
import dev.titanide.core.model.ExecutionTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Wire protocol shared with the reference build agent in `backend/agent`.
 *
 * The agent exposes:
 *   POST /v1/jobs             -> submit a job, returns a job id
 *   GET  /v1/jobs/{id}        -> poll state, log and artifacts
 *   POST /v1/jobs/{id}/cancel
 *
 * The client uploads a zip of the project together with the planned commands, so
 * the agent never needs filesystem access to the device. Both sides speak plain JSON over
 * HTTPS. Archives are capped by [MAX_ARCHIVE_BYTES] and the agent must reject unsafe paths.
 */
@Serializable
data class AgentJobRequest(
    @SerialName("project_id") val projectId: String,
    @SerialName("build_system") val buildSystem: String,
    @SerialName("commands") val commands: List<List<String>>,
    @SerialName("kind") val kind: String,
    @SerialName("timeout_seconds") val timeoutSeconds: Int = 1800,
    @SerialName("artifact_globs") val artifactGlobs: List<String> = emptyList(),
    /** Base64 zip of the project tree, paths relative to the project directory. */
    @SerialName("source_archive") val sourceArchive: String = "",
)

@Serializable
data class AgentJobStatus(
    @SerialName("job_id") val jobId: String,
    @SerialName("state") val state: String,
    @SerialName("exit_code") val exitCode: Int? = null,
    @SerialName("log") val log: String = "",
    @SerialName("log_offset") val logOffset: Int = 0,
    @SerialName("failure_reason") val failureReason: String? = null,
    @SerialName("artifacts") val artifacts: List<AgentArtifact> = emptyList(),
)

@Serializable
data class AgentArtifact(
    @SerialName("name") val name: String,
    @SerialName("url") val url: String? = null,
    @SerialName("size_bytes") val sizeBytes: Long = 0,
)

@Serializable
data class AgentSubmitResponse(@SerialName("job_id") val jobId: String)

/**
 * Talks to a self-hosted build agent. Every state transition is driven by the agent's
 * response; a network failure surfaces as a failed build, never as a success.
 */
class RemoteAgentBackend(
    /** Read per execution so a Settings change applies to the next build, not the next launch. */
    private val baseUrlProvider: () -> String,
    private val tokenProvider: suspend () -> String?,
    private val client: OkHttpClient = defaultClient(),
    /**
     * Where downloaded artifacts are written. Artifacts must land on disk: a URL alone is
     * not a usable build result, because installing or sharing an APK needs the bytes.
     */
    private val artifactDir: (projectId: String) -> File = { projectId ->
        File(System.getProperty("java.io.tmpdir") ?: "/tmp", "titan-artifacts/$projectId")
    },
) : BuildBackend {

    override val target = ExecutionTarget.REMOTE_AGENT

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun execute(request: BuildRequest, onLog: (String) -> Unit): BackendOutcome =
        withContext(Dispatchers.IO) {
            val baseUrl = baseUrlProvider().trim().trimEnd('/')
            val token = tokenProvider()
            if (baseUrl.isBlank()) {
                return@withContext failure("No remote agent URL is configured")
            }
            val root = File(request.projectPath)
            if (!root.isDirectory) return@withContext failure("Project directory is missing")

            val detected = ProjectDetector.detect(root)
            val plan = BuildPlanner.plan(request, root, detected)
            if (plan.error != null) return@withContext failure(plan.error)

            onLog("Packing ${root.name} for upload")
            val archive = runCatching { archiveProject(root) }.getOrElse { error ->
                return@withContext failure("Could not pack the project: ${error.message}")
            }
            if (archive.size > MAX_ARCHIVE_BYTES) {
                return@withContext failure(
                    "Project archive is ${archive.size / 1024 / 1024} MB, above the " +
                        "${MAX_ARCHIVE_BYTES / 1024 / 1024} MB remote build limit",
                )
            }

            val payload = AgentJobRequest(
                projectId = request.projectId,
                buildSystem = detected.buildSystem,
                commands = plan.commands.map { listOf(it.program) + it.args },
                kind = request.kind.name,
                artifactGlobs = plan.artifactGlobs,
                sourceArchive = java.util.Base64.getEncoder().encodeToString(archive),
            )

            val submit = postJson("$baseUrl/v1/jobs", json.encodeToString(AgentJobRequest.serializer(), payload), token)
                ?: return@withContext failure("Could not reach the build agent at $baseUrl")

            val jobId = runCatching {
                json.decodeFromString(AgentSubmitResponse.serializer(), submit).jobId
            }.getOrElse {
                return@withContext failure("Build agent returned an unexpected response: ${submit.take(200)}")
            }

            onLog("Submitted job $jobId to $baseUrl")
            var lastOffset = 0
            val stages = ArrayList<BuildStage>()
            val stageStart = System.currentTimeMillis()

            var polls = 0
            while (polls < MAX_POLLS) {
                polls++
                delay(POLL_INTERVAL_MS)
                val body = getJson("$baseUrl/v1/jobs/$jobId", token)
                    ?: return@withContext failure("Lost contact with the build agent while polling job $jobId")

                val status = runCatching {
                    json.decodeFromString(AgentJobStatus.serializer(), body)
                }.getOrElse {
                    return@withContext failure("Malformed status for job $jobId")
                }

                if (status.log.isNotEmpty() && status.log.length > lastOffset) {
                    status.log.substring(lastOffset).split('\n').filter { it.isNotEmpty() }.forEach(onLog)
                    lastOffset = status.log.length
                }

                when (status.state.lowercase()) {
                    "succeeded" -> {
                        stages += BuildStage("remote", stageStart, System.currentTimeMillis(), true)
                        val artifacts = downloadArtifacts(
                            remote = status.artifacts,
                            baseUrl = baseUrl,
                            token = token,
                            projectId = request.projectId,
                            onLog = onLog,
                        )
                        return@withContext BackendOutcome(
                            status = BuildStatus.SUCCEEDED,
                            exitCode = status.exitCode ?: 0,
                            logLines = status.log.split('\n').filter { it.isNotEmpty() },
                            artifacts = artifacts,
                            stages = stages,
                            failureReason = null,
                            executorLabel = "Remote agent (${baseUrl.hostOf()})",
                        )
                    }
                    "failed" -> {
                        stages += BuildStage("remote", stageStart, System.currentTimeMillis(), false)
                        return@withContext BackendOutcome(
                            status = BuildStatus.FAILED,
                            exitCode = status.exitCode,
                            logLines = status.log.split('\n').filter { it.isNotEmpty() },
                            artifacts = emptyList(),
                            stages = stages,
                            failureReason = status.failureReason ?: "Remote build failed",
                            executorLabel = "Remote agent (${baseUrl.hostOf()})",
                        )
                    }
                    "cancelled" -> {
                        return@withContext BackendOutcome(
                            status = BuildStatus.CANCELLED,
                            exitCode = status.exitCode,
                            logLines = status.log.split('\n').filter { it.isNotEmpty() },
                            artifacts = emptyList(),
                            stages = stages,
                            failureReason = "Cancelled",
                            executorLabel = "Remote agent",
                        )
                    }
                    else -> Unit
                }
            }
            failure("Remote build did not finish within ${MAX_POLLS * POLL_INTERVAL_MS / 1000}s")
        }

    private fun postJson(url: String, body: String, token: String?): String? = runCatching {
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json".toMediaType()))
            .apply { token?.let { header("Authorization", "Bearer $it") } }
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) null else response.body?.string()
        }
    }.getOrNull()

    private fun getJson(url: String, token: String?): String? = runCatching {
        val request = Request.Builder()
            .url(url)
            .get()
            .apply { token?.let { header("Authorization", "Bearer $it") } }
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) null else response.body?.string()
        }
    }.getOrNull()

    /**
     * Downloads every artifact the agent produced and returns local file references.
     *
     * The agent reports a relative `url` such as `/v1/jobs/<id>/artifacts/app-debug.apk`.
     * That path is resolved against the agent base URL, the bytes are streamed to disk
     * rather than buffered in memory (an APK can be hundreds of megabytes), and the size
     * the agent declared is verified. An artifact that cannot be fetched is dropped from
     * the result and logged, so the UI never offers a file that is not on disk.
     */
    private suspend fun downloadArtifacts(
        remote: List<AgentArtifact>,
        baseUrl: String,
        token: String?,
        projectId: String,
        onLog: (String) -> Unit,
    ): List<Artifact> = withContext(Dispatchers.IO) {
        if (remote.isEmpty()) return@withContext emptyList()
        val destination = artifactDir(projectId)
        if (!destination.isDirectory && !destination.mkdirs()) {
            onLog("Could not create the artifact directory ${destination.absolutePath}")
            return@withContext emptyList()
        }

        val collected = ArrayList<Artifact>()
        for (item in remote) {
            val reference = item.url?.takeIf { it.isNotBlank() }
            if (reference == null) {
                onLog("Artifact ${item.name} has no download URL")
                continue
            }
            val absolute = resolveArtifactUrl(baseUrl, reference)
            val safeName = item.name.substringAfterLast('/').ifBlank { "artifact" }
            val target = File(destination, safeName)

            val ok = runCatching {
                val request = Request.Builder()
                    .url(absolute)
                    .apply { token?.let { header("Authorization", "Bearer $it") } }
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        onLog("Artifact ${item.name} download failed with HTTP ${response.code}")
                        return@use false
                    }
                    val body = response.body ?: return@use false
                    // Stream to disk: buffering an APK in a byte array risks an OOM on a phone.
                    target.outputStream().use { sink -> body.byteStream().copyTo(sink) }
                    true
                }
            }.getOrElse { error ->
                onLog("Artifact ${item.name} download failed: ${error.message}")
                false
            }

            if (!ok) {
                runCatching { target.delete() }
                continue
            }

            val size = target.length()
            if (item.sizeBytes > 0 && size != item.sizeBytes) {
                onLog(
                    "Artifact ${item.name} was ${size}B but the agent reported ${item.sizeBytes}B; " +
                        "discarding the incomplete download",
                )
                runCatching { target.delete() }
                continue
            }

            onLog("Downloaded ${item.name} (${size / 1024} KB)")
            collected += Artifact(
                name = safeName,
                path = target.absolutePath,
                sizeBytes = size,
                mimeType = mimeFor(safeName),
            )
        }
        collected
    }

    /** Resolves an agent-relative artifact path against the agent base URL. */
    private fun resolveArtifactUrl(baseUrl: String, reference: String): String = when {
        reference.startsWith("http://") || reference.startsWith("https://") -> reference
        reference.startsWith("/") -> baseUrl + reference
        else -> "$baseUrl/$reference"
    }

    private fun mimeFor(name: String): String? = when (name.substringAfterLast('.', "")) {
        "apk" -> "application/vnd.android.package-archive"
        "aar", "jar" -> "application/java-archive"
        "zip" -> "application/zip"
        else -> null
    }

    private fun failure(reason: String) = BackendOutcome(
        status = BuildStatus.FAILED,
        exitCode = null,
        logLines = listOf(reason),
        artifacts = emptyList(),
        stages = emptyList(),
        failureReason = reason,
        executorLabel = "Remote agent",
    )

    /**
     * Zip of [root], skipping VCS metadata, build output and dependency caches.
     *
     * Uses the JDK zip writer rather than a tar library so the engine keeps its existing
     * dependency set. Entry names are relative and forward-slashed; the agent must still
     * validate them against path traversal.
     */
    private fun archiveProject(root: File): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(output).use { zip ->
            root.walkTopDown()
                .onEnter { it.name !in SKIP_DIRECTORIES }
                .filter { it.isFile }
                .forEach { file ->
                    val relative = file.relativeTo(root).path.replace(File.separatorChar, '/')
                    zip.putNextEntry(java.util.zip.ZipEntry(relative))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
        }
        return output.toByteArray()
    }

    private fun String.hostOf(): String = runCatching { java.net.URI(this).host ?: this }.getOrDefault(this)

    companion object {
        private const val POLL_INTERVAL_MS = 1500L
        private const val MAX_POLLS = 1200
        private const val MAX_ARCHIVE_BYTES = 96L * 1024 * 1024
        private val SKIP_DIRECTORIES = setOf(
            ".git", ".gradle", "build", "node_modules", ".idea", "target", "dist", ".dart_tool",
        )

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
