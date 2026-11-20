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
 * The client sends commands rather than a tarball so the agent needs no access to the
 * phone's filesystem; both sides speak plain JSON over HTTPS.
 */
@Serializable
data class AgentJobRequest(
    @SerialName("project_id") val projectId: String,
    @SerialName("build_system") val buildSystem: String,
    @SerialName("commands") val commands: List<List<String>>,
    @SerialName("kind") val kind: String,
    @SerialName("timeout_seconds") val timeoutSeconds: Int = 1800,
    @SerialName("artifact_globs") val artifactGlobs: List<String> = emptyList(),
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
    private val baseUrl: String,
    private val token: String?,
    private val client: OkHttpClient = defaultClient(),
) : BuildBackend {

    override val target = ExecutionTarget.REMOTE_AGENT

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun execute(request: BuildRequest, onLog: (String) -> Unit): BackendOutcome =
        withContext(Dispatchers.IO) {
            if (baseUrl.isBlank()) {
                return@withContext failure("No remote agent URL is configured")
            }
            val root = File(request.projectPath)
            if (!root.isDirectory) return@withContext failure("Project directory is missing")

            val detected = ProjectDetector.detect(root)
            val plan = BuildPlanner.plan(request, root, detected)
            if (plan.error != null) return@withContext failure(plan.error)

            val payload = AgentJobRequest(
                projectId = request.projectId,
                buildSystem = detected.buildSystem,
                commands = plan.commands.map { listOf(it.program) + it.args },
                kind = request.kind.name,
                artifactGlobs = plan.artifactGlobs,
            )

            val submit = postJson("$baseUrl/v1/jobs", json.encodeToString(AgentJobRequest.serializer(), payload))
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
                val body = getJson("$baseUrl/v1/jobs/$jobId")
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
                        return@withContext BackendOutcome(
                            status = BuildStatus.SUCCEEDED,
                            exitCode = status.exitCode ?: 0,
                            logLines = status.log.split('\n').filter { it.isNotEmpty() },
                            artifacts = status.artifacts.map {
                                Artifact(name = it.name, path = it.url ?: it.name, sizeBytes = it.sizeBytes)
                            },
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

    private fun postJson(url: String, body: String): String? = runCatching {
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json".toMediaType()))
            .apply { token?.let { header("Authorization", "Bearer $it") } }
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) null else response.body?.string()
        }
    }.getOrNull()

    private fun getJson(url: String): String? = runCatching {
        val request = Request.Builder()
            .url(url)
            .get()
            .apply { token?.let { header("Authorization", "Bearer $it") } }
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) null else response.body?.string()
        }
    }.getOrNull()

    private fun failure(reason: String) = BackendOutcome(
        status = BuildStatus.FAILED,
        exitCode = null,
        logLines = listOf(reason),
        artifacts = emptyList(),
        stages = emptyList(),
        failureReason = reason,
        executorLabel = "Remote agent",
    )

    private fun String.hostOf(): String = runCatching { java.net.URI(this).host ?: this }.getOrDefault(this)

    companion object {
        private const val POLL_INTERVAL_MS = 1500L
        private const val MAX_POLLS = 1200

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
