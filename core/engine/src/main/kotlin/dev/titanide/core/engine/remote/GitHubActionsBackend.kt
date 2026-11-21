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
    /** Read per execution so Settings changes apply without restarting the app. */
    private val ownerProvider: () -> String,
    private val repoProvider: () -> String,
    private val workflowProvider: () -> String,
    private val tokenProvider: suspend () -> String?,
    private val branchProvider: suspend (String) -> String?,
    private val client: OkHttpClient = defaultClient(),
    /** Where workflow artifacts are extracted, so an APK becomes a real local file. */
    private val artifactDir: (projectId: String) -> File = { projectId ->
        File(System.getProperty("java.io.tmpdir") ?: "/tmp", "titan-artifacts/$projectId")
    },
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
            val owner = ownerProvider().trim()
            val repo = repoProvider().trim()
            val workflowFile = workflowProvider().trim()
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

            // Everything created before this instant is an older run. Without this guard the
            // poller adopts the previous run on the branch and reports its result as ours.
            val dispatchedAt = java.time.Instant.now().minusSeconds(5)
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
                val candidate = page?.runs?.firstOrNull() ?: continue

                if (runId == null) {
                    // Only adopt a run that was created after the dispatch, on our branch.
                    val created = candidate.createdAt?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() }
                    if (created != null && created.isBefore(dispatchedAt)) continue
                    if (candidate.headBranch != null && candidate.headBranch != branch) continue
                    runId = candidate.id
                    onLog("Tracking workflow run ${candidate.id}: ${candidate.htmlUrl.orEmpty()}")
                }
                val run = page.runs.firstOrNull { it.id == runId } ?: continue

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
                            artifacts = fetchArtifacts(owner, repo, run.id, token, request.projectId, onLog),
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

    /**
     * Fetches a run's logs.
     *
     * The logs endpoint answers with a **zip archive** of one `.txt` per job, not with
     * text. Treating the response as text produced a wall of binary garbage in the build
     * log, so the archive is unpacked and the job files are concatenated.
     */
    private fun fetchRunLog(owner: String, repo: String, runId: Long, token: String): List<String> {
        val bytes = getBytes("https://api.github.com/repos/$owner/$repo/actions/runs/$runId/logs", token)
            ?: return listOf("GitHub did not return logs for run $runId.")
        val lines = ArrayList<String>()
        val unpacked = runCatching {
            java.util.zip.ZipInputStream(bytes.inputStream()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        lines += "===== ${entry.name} ====="
                        zip.readBytes().toString(Charsets.UTF_8).split('\n')
                            .filter { it.isNotBlank() }
                            .forEach { lines += it }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            true
        }.getOrDefault(false)
        if (!unpacked) {
            // Some deployments answer with plain text; fall back to that rather than fail.
            lines += bytes.toString(Charsets.UTF_8).split('\n').filter { it.isNotBlank() }
        }
        return lines.takeLast(MAX_LOG_LINES)
    }

    private fun getBytes(url: String, token: String): ByteArray? = runCatching {
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) null else response.body?.bytes()
        }
    }.getOrNull()

    /**
     * Lists a run's artifacts, downloads each archive, and extracts any APK/JAR it holds.
     *
     * GitHub returns artifacts as zip archives even when they contain a single file, and
     * the list endpoint only gives a name plus a download URL. Offering that name in the UI
     * without fetching the bytes would be a build result the user cannot install, so the
     * archive is streamed to disk and unpacked here.
     */
    private suspend fun fetchArtifacts(
        owner: String,
        repo: String,
        runId: Long,
        token: String,
        projectId: String,
        onLog: (String) -> Unit,
    ): List<Artifact> = withContext(Dispatchers.IO) {
        val body = get("https://api.github.com/repos/$owner/$repo/actions/runs/$runId/artifacts", token)
            ?: return@withContext emptyList()
        val entries = runCatching {
            val node = json.parseToJsonElement(body)
            node.asObjectOrNull()?.get("artifacts")?.asArrayOrNull() ?: return@withContext emptyList()
        }.getOrDefault(return@withContext emptyList())

        val destination = artifactDir(projectId)
        if (!destination.isDirectory && !destination.mkdirs()) {
            onLog("Could not create the artifact directory ${destination.absolutePath}")
            return@withContext emptyList()
        }

        val collected = ArrayList<Artifact>()
        for (element in entries) {
            val obj = element.asObjectOrNull() ?: continue
            val name = obj["name"]?.toString()?.trim('"') ?: continue
            val declaredSize = obj["size_in_bytes"]?.toString()?.toLongOrNull() ?: 0L
            val archiveUrl = obj["archive_download_url"]?.toString()?.trim('"') ?: continue

            val archive = File(destination, "$name.zip")
            val downloaded = runCatching {
                val request = Request.Builder()
                    .url(archiveUrl)
                    .header("Authorization", "Bearer $token")
                    .header("Accept", "application/vnd.github+json")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        onLog("Artifact $name download failed with HTTP ${response.code}")
                        return@use false
                    }
                    val stream = response.body ?: return@use false
                    archive.outputStream().use { sink -> stream.byteStream().copyTo(sink) }
                    true
                }
            }.getOrElse { error ->
                onLog("Artifact $name download failed: ${error.message}")
                false
            }
            if (!downloaded) continue

            // GitHub's size_in_bytes is the unpacked size, so only sanity-check that the
            // archive is non-empty rather than comparing the two directly.
            if (archive.length() == 0L) {
                onLog("Artifact $name downloaded empty; discarding")
                runCatching { archive.delete() }
                continue
            }

            val extracted: List<Artifact> = runCatching { extractArchives(archive, destination) }
                .getOrElse { error ->
                    onLog("Artifact $name could not be unpacked: ${error.message}")
                    emptyList()
                }
            if (extracted.isEmpty()) {
                onLog("Artifact $name contained no APK or JAR")
            }
            extracted.forEach { artifact ->
                collected += artifact
                onLog("Extracted ${artifact.name} (${artifact.sizeBytes / 1024} KB, $declaredSize B declared)")
            }
            runCatching { archive.delete() }
        }
        collected
    }

    /** Unpacks [archive], returning the APK/JAR/AAR files it contains. */
    private fun extractArchives(archive: File, destination: File): List<Artifact> {
        val found = ArrayList<File>()
        java.util.zip.ZipFile(archive).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                val extension = entry.name.substringAfterLast('.', "").lowercase()
                if (extension !in INTERESTING_EXTENSIONS) continue
                // Flatten into the destination, keeping the artifact name for context.
                val target = File(destination, entry.name.substringAfterLast('/'))
                zip.getInputStream(entry).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                found += target
            }
        }
        return found.map { Artifact(name = it.name, path = it.absolutePath, sizeBytes = it.length(), mimeType = mimeFor(it.name)) }
    }

    private fun mimeFor(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
        "apk" -> "application/vnd.android.package-archive"
        "jar", "aar" -> "application/java-archive"
        "zip" -> "application/zip"
        else -> null
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
        private const val MAX_LOG_LINES = 4000
        private val INTERESTING_EXTENSIONS = setOf("apk", "jar", "aar", "zip")

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
