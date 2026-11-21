package dev.titanide.core.git.api

import dev.titanide.core.model.GitBranch
import dev.titanide.core.model.GitCommit
import dev.titanide.core.model.GitRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** GitHub REST v3 client. Every call is a real request; failures surface as [Result]. */
class GitHubApiClient(
    private val tokenProvider: suspend () -> String?,
    private val http: OkHttpClient = defaultClient(),
    /** Overridable so the client can be exercised against a local server in tests. */
    private val apiBase: String = "https://api.github.com",
) {

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class RepoDto(
        @SerialName("id") val id: Long,
        @SerialName("name") val name: String,
        @SerialName("full_name") val fullName: String,
        @SerialName("description") val description: String? = null,
        @SerialName("language") val language: String? = null,
        @SerialName("default_branch") val defaultBranch: String = "main",
        @SerialName("private") val isPrivate: Boolean = false,
        @SerialName("updated_at") val updatedAt: String = "",
        @SerialName("stargazers_count") val stars: Int = 0,
        @SerialName("clone_url") val cloneUrl: String = "",
        @SerialName("html_url") val htmlUrl: String = "",
        @SerialName("owner") val owner: OwnerDto? = null,
    )

    @Serializable
    private data class OwnerDto(@SerialName("login") val login: String = "")

    @Serializable
    private data class BranchDto(
        @SerialName("name") val name: String,
        @SerialName("commit") val commit: CommitRefDto,
    )

    @Serializable
    private data class CommitRefDto(@SerialName("sha") val sha: String)

    @Serializable
    private data class CommitDto(
        @SerialName("sha") val sha: String,
        @SerialName("commit") val commit: CommitInnerDto,
    )

    @Serializable
    private data class CommitInnerDto(
        @SerialName("message") val message: String,
        @SerialName("author") val author: AuthorDto? = null,
    )

    @Serializable
    private data class AuthorDto(
        @SerialName("name") val name: String = "",
        @SerialName("email") val email: String = "",
        @SerialName("date") val date: String = "",
    )

    @Serializable
    private data class UserDto(@SerialName("login") val login: String, @SerialName("name") val name: String? = null)

    @Serializable
    private data class ContentDto(
        @SerialName("name") val name: String,
        @SerialName("path") val path: String,
        @SerialName("type") val type: String,
        @SerialName("size") val size: Long = 0,
        @SerialName("content") val content: String? = null,
        @SerialName("encoding") val encoding: String? = null,
    )

    suspend fun currentUser(): Result<String> = call("$apiBase/user") { body ->
        json.decodeFromString(UserDto.serializer(), body).login
    }

    suspend fun listRepositories(
        page: Int = 1,
        perPage: Int = 50,
        sort: String = "updated",
    ): Result<List<GitRepository>> = call(
        "$apiBase/user/repos?per_page=$perPage&page=$page&sort=$sort&affiliation=owner,collaborator,organization_member",
    ) { body ->
        json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(RepoDto.serializer()), body)
            .map { it.toModel() }
    }

    /**
     * One repository by name.
     *
     * Needed because a repository can be opened from a link or from search results, and it is
     * then not necessarily in the first page of the user's own repositories. Substituting
     * whatever happened to be in that list showed the user another project's name, branch and
     * language under the one they asked for.
     */
    suspend fun getRepository(owner: String, repo: String): Result<GitRepository> = call(
        "$apiBase/repos/$owner/$repo",
    ) { body ->
        json.decodeFromString(RepoDto.serializer(), body).toModel()
    }

    suspend fun searchRepositories(query: String, perPage: Int = 30): Result<List<GitRepository>> = call(
        "$apiBase/search/repositories?q=${urlEncode(query)}&per_page=$perPage",
    ) { body ->
        val node = json.parseToJsonElement(body).jsonObject["items"] ?: return@call emptyList()
        json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(RepoDto.serializer()),
            node.toString(),
        ).map { it.toModel() }
    }

    suspend fun listBranches(owner: String, repo: String): Result<List<GitBranch>> = call(
        "$apiBase/repos/$owner/$repo/branches?per_page=100",
    ) { body ->
        json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(BranchDto.serializer()), body)
            .map { GitBranch(name = it.name, isDefault = false, sha = it.commit.sha) }
    }

    suspend fun listCommits(owner: String, repo: String, branch: String? = null, perPage: Int = 30): Result<List<GitCommit>> {
        val ref = if (branch != null) "?sha=$branch&per_page=$perPage" else "?per_page=$perPage"
        return call("$apiBase/repos/$owner/$repo/commits$ref") { body ->
            json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(CommitDto.serializer()), body)
                .map { dto ->
                    GitCommit(
                        sha = dto.sha,
                        message = dto.commit.message,
                        author = dto.commit.author?.name.orEmpty(),
                        authorEmail = dto.commit.author?.email.orEmpty(),
                        timestamp = parseIso(dto.commit.author?.date),
                    )
                }
        }
    }

    suspend fun listContents(owner: String, repo: String, path: String = "", ref: String? = null): Result<List<GitEntry>> {
        val suffix = if (ref != null) "?ref=$ref" else ""
        val encodedPath = path.split('/').filter { it.isNotEmpty() }.joinToString("/") { urlEncode(it) }
        return call("$apiBase/repos/$owner/$repo/contents/$encodedPath$suffix") { body ->
            json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(ContentDto.serializer()), body)
                .map { GitEntry(it.name, it.path, it.type == "dir", it.size) }
        }
    }

    suspend fun readFile(owner: String, repo: String, path: String, ref: String? = null): Result<String> {
        val suffix = if (ref != null) "?ref=$ref" else ""
        val encodedPath = path.split('/').filter { it.isNotEmpty() }.joinToString("/") { urlEncode(it) }
        return call("$apiBase/repos/$owner/$repo/contents/$encodedPath$suffix") { body ->
            val dto = json.decodeFromString(ContentDto.serializer(), body)
            val content = dto.content ?: return@call ""
            if (dto.encoding == "base64") {
                String(android.util.Base64.decode(content.replace("\n", ""), android.util.Base64.DEFAULT))
            } else {
                content
            }
        }
    }

    /** Public tarball URL for a ref; used for one-tap import without a git clone. */
    fun tarballUrl(owner: String, repo: String, ref: String): String =
        "$apiBase/repos/$owner/$repo/tarball/$ref"

    suspend fun downloadTarball(owner: String, repo: String, ref: String, destination: java.io.File): Result<java.io.File> =
        withContext(Dispatchers.IO) {
            val token = tokenProvider()
                ?: return@withContext Result.failure(IllegalStateException("Not signed in to GitHub"))
            val request = Request.Builder()
                .url(tarballUrl(owner, repo, ref))
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .build()
            runCatching {
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("Download failed: HTTP ${response.code}")
                    val body = response.body ?: error("Empty response body")
                    destination.parentFile?.mkdirs()
                    destination.outputStream().use { out -> body.byteStream().copyTo(out) }
                    destination
                }
            }
        }

    suspend fun createPullRequest(
        owner: String,
        repo: String,
        title: String,
        head: String,
        base: String,
        body: String,
        draft: Boolean = false,
    ): Result<Int> = withContext(Dispatchers.IO) {
        val token = tokenProvider()
            ?: return@withContext Result.failure(IllegalStateException("Not signed in to GitHub"))
        val payload = buildString {
            append("{")
            append("\"title\":").append(jsonString(title)).append(",")
            append("\"head\":").append(jsonString(head)).append(",")
            append("\"base\":").append(jsonString(base)).append(",")
            append("\"body\":").append(jsonString(body)).append(",")
            append("\"draft\":").append(draft)
            append("}")
        }
        val request = Request.Builder()
            .url("$apiBase/repos/$owner/$repo/pulls")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        runCatching {
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) error("GitHub returned HTTP ${response.code}: ${text.take(200)}")
                json.parseToJsonElement(text).jsonObject["number"]?.toString()?.toIntOrNull()
                    ?: error("No pull request number in response")
            }
        }
    }

    suspend fun rateLimit(): Result<Int> = call("$apiBase/rate_limit") { body ->
        json.parseToJsonElement(body).jsonObject["rate"]?.jsonObject?.get("remaining")?.toString()?.toIntOrNull() ?: -1
    }

    private suspend fun <T> call(url: String, parse: (String) -> T): Result<T> = withContext(Dispatchers.IO) {
        val token = tokenProvider()
            ?: return@withContext Result.failure(IllegalStateException("Not signed in to GitHub"))
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()
        runCatching {
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                when {
                    response.code == 401 -> error("GitHub rejected the token (401). Sign in again.")
                    response.code == 403 -> error("GitHub rate limit or permission problem (403)")
                    response.code == 404 -> error("Not found (404)")
                    !response.isSuccessful -> error("GitHub returned HTTP ${response.code}")
                    else -> parse(body)
                }
            }
        }
    }

    private fun RepoDto.toModel() = GitRepository(
        id = id,
        name = name,
        fullName = fullName,
        owner = owner?.login ?: fullName.substringBefore('/'),
        description = description,
        language = language,
        defaultBranch = defaultBranch,
        isPrivate = isPrivate,
        updatedAt = updatedAt,
        stars = stars,
        cloneUrl = cloneUrl,
        htmlUrl = htmlUrl,
    )

    private fun parseIso(date: String?): Long {
        if (date.isNullOrBlank()) return 0
        return runCatching {
            java.time.Instant.parse(date).toEpochMilli()
        }.getOrDefault(0)
    }

    private fun urlEncode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun jsonString(value: String): String = buildString {
        append('"')
        value.forEach { c ->
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

data class GitEntry(val name: String, val path: String, val isDirectory: Boolean, val sizeBytes: Long)

private val kotlinx.serialization.json.JsonElement.jsonObject
    get() = this as kotlinx.serialization.json.JsonObject
