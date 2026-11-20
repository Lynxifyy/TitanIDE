package dev.titanide.core.engine.remote

import dev.titanide.core.data.files.ProjectDetector
import dev.titanide.core.engine.BackendOutcome
import dev.titanide.core.engine.BuildBackend
import dev.titanide.core.engine.BuildPlanner
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
 * Builds on GitHub-hosted runners by dispatching a workflow.
 *
 * This backend cannot upload a working tree: GitHub Actions runs against a repository
 * ref. It therefore requires the project to have been committed and pushed first, and it
 * says so plainly when that is not the case rather than reporting a fake build.
 */
class GitHubActionsBackend(
    private val owner: String,
    private val repo: String,
    private val workflowFile: String,
    private val tokenProvider: suspend () -> String?,
    private val branchProvider: suspend (String) -> String?,
    private val client: OkHttpClient = defaultClient(),
) : BuildBackend {

    override val target = ExecutionTarget.GITHUB_ACTIONS

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class DispatchBody(
        @SerialName("ref") val ref: String,
        @SerialName("inputs") val inputs: Map<String, String>,
    )

    @Serializable
    private data class RunDto(
        @SerialName("id") val id: Long,
        @SerialName("status") val status: String,
        @SerialName("conclusion") val conclusion: String? = null,
        @SerialName("html_url") val htmlUrl: String? = null,
        @SerialName("head_branch") val headBranch: String? = null,
        @SerialName("created_at") val createdAt: String? = null,
    )

    @Serializable
    private data class RunsPage(@SerialName("workflow_runs") val runs: List<RunDto> = emptyList())

    override suspend fun execute(request: BuildRequest, onLog: (String) -> Unit): BackendOutcome =
        withContext(Dispatchers.IO) {
            if (owner.isBlank() || repo.isBlank()) {
                return@withContext failure(
                    "GitHub Actions builds need a repository. Set owner and repository in Settings, " +
                        "then push the project before building.",
                )
            }
            val token = tokenProvider()
                ?: return@withContext failure("Sign in to GitHub to use GitHub Actions builds.")

            val branch = branchProvider(request.projectId)
                ?: return@withContext failure(
                    "This project has no pushed branch yet. Commit and push it to $owner/$repo first; " +
                        "GitHub Actions runs on a repository ref, not on local files.",
                )

            val root = File(request.projectPath)
            val detected = if (root.isDirectory) ProjectDetector.detect(root) else null
            val plan = if (root.isDirectory) {
                BuildPlanner.plan(request, root, detected!!)
            } else null

            onLog("Dispatching $workflowFile on $owner/$repo@$branch")
            onLog("Task: ${request.kind} ${request.task.orEmpty()}".trim())

            val dispatchBody = json.encodeToString(
                DispatchBody.serializer(),
                DispatchBody(
                    ref = branch,
                    inputs = mapOf(
                        "kind" to request.kind.name,
                        "task" to (request.task ?: plan?.commands?.firstOrNull()?.display ?: ""),
                        "build_system" to (detected?.buildSystem ?: "none"),
                    ),
                ),
            )

            val dispatched = post(
                "https://api.github.com/repos/$owner/$repo/actions/workflows/$workflowFile/dispatches",
                dispatchBody,
                token,
            )
            if (!dispatched.first) {
                return@withContext failure(
                    "GitHub refused the workflow dispatch (HTTP ${dispatched.second}). " +
                        "Check that $workflowFile exists on $branch and accepts workflow_dispatch.",
                )
            }

            val stages = ArrayList<BuildStage>()
            val stageStart = System.currentTimeMillis()
            var runId: Long? = null
            var polls = 0

            while (polls < MAX_POLLS) {
                polls++
                delay(POLL_INTERVAL_MS)
                val runsJson = get(
                    "https://api.github.com/repos/$owner/$repo/actions/workflows/$workflowFile/runs?branch=$branch&per_page=1",
                    token,
                ) ?: continue

                val page = runCatching { json.decodeFromString(RunsPage.serializer(), runsJson) }.getOrNull()
                val run = page?.runs?.firstOrNull() ?: continue
                if (runId == null) {
                    runId = run.id
                    onLog("Tracking workflow run ${run.id}: ${run.htmlUrl.orEmpty()}")
                }
                if (run.id != runId) continue

                when (run.status) {
                    "completed" -> {
                        val success = run.conclusion == "success"
                        val log = fetchRunLog(owner, repo, run.id, token)
                        log.forEach(onLog)
                        stages += BuildStage("github-actions", stageStart, System.currentTimeMillis(), success)
                        return@withContext BackendOutcome(
                            status = if (success) BuildStatus.SUCCEEDED else BuildStatus.FAILED,
                            exitCode = if (success) 0 else 1,
                            logLines = log,
                            artifacts = fetchArtifacts(owner, repo, run.id, token),
                            stages = stages,
                            failureReason = if (success) null else "Workflow concluded: ${run.conclusion}",
                            executorLabel = "GitHub Actions ($owner/$repo)",
                        )
                    }
                    else -> {
                        if (polls % 8 == 0) onLog("Run ${run.id} is ${run.status}...")
                    }
                }
            }
            failure("GitHub Actions run did not finish in time")
        }

    private fun fetchRunLog(owner: String, repo: String, runId: Long, token: String): List<String> {
        val text = get("https://api.github.com/repos/$owner/$repo/actions/runs/$runId/logs", token) ?: return emptyList()
        return text.split('\n').filter { it.isNotBlank() }.takeLast(2000)
    }

    private fun fetchArtifacts(owner: String, repo: String, runId: Long, token: String): List<Artifact> {
        val body = get("https://api.github.com/repos/$owner/$repo/actions/runs/$runId/artifacts", token) ?: return emptyList()
        return runCatching {
            val node = json.parseToJsonElement(body)
            val arr = node.asObjectOrNull()?.get("artifacts")?.asArrayOrNull() ?: return emptyList()
            arr.mapNotNull { element ->
                val obj = element.asObjectOrNull() ?: return@mapNotNull null
                val name = obj["name"]?.toString()?.trim('"') ?: return@mapNotNull null
                val size = obj["size_in_bytes"]?.toString()?.toLongOrNull() ?: 0L
                Artifact(name = name, path = "", sizeBytes = size)
            }
        }.getOrDefault(emptyList())
    }

    private fun post(url: String, body: String, token: String): Pair<Boolean, Int> = runCatching {
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()
        client.newCall(request).execute().use { response -> response.isSuccessful to response.code }
    }.getOrDefault(false to -1)

    private fun get(url: String, token: String): String? = runCatching {
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
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
        executorLabel = "GitHub Actions",
    )

    companion object {
        private const val POLL_INTERVAL_MS = 4000L
        private const val MAX_POLLS = 300

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}

private fun kotlinx.serialization.json.JsonElement.asObjectOrNull(): kotlinx.serialization.json.JsonObject? =
    this as? kotlinx.serialization.json.JsonObject

private fun kotlinx.serialization.json.JsonElement.asArrayOrNull(): kotlinx.serialization.json.JsonArray? =
    this as? kotlinx.serialization.json.JsonArray
