package dev.titanide.core.data.repo

import android.content.Context
import dev.titanide.core.data.db.BuildDao
import dev.titanide.core.data.db.BuildRecordEntity
import dev.titanide.core.data.db.OpenTabEntity
import dev.titanide.core.data.db.ProjectDao
import dev.titanide.core.data.db.ProjectEntity
import dev.titanide.core.data.db.TabDao
import dev.titanide.core.data.files.FileNode
import dev.titanide.core.data.files.SafeFileSystem
import dev.titanide.core.model.Artifact
import dev.titanide.core.model.BuildResult
import dev.titanide.core.model.Diagnostic
import dev.titanide.core.model.BuildStatus
import dev.titanide.core.model.BuildSystem
import dev.titanide.core.model.Language
import dev.titanide.core.model.Project
import dev.titanide.core.model.ProjectCategory
import dev.titanide.core.model.ProjectFile
import dev.titanide.core.model.FileKind
import dev.titanide.core.model.SdkConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

/** App-private storage roots. Nothing is written outside these directories. */
class ProjectStorage(private val context: Context) {
    val projectsRoot: File get() = File(context.filesDir, "projects").apply { mkdirs() }
    val buildsRoot: File get() = File(context.filesDir, "builds").apply { mkdirs() }
    val logsRoot: File get() = File(context.filesDir, "logs").apply { mkdirs() }
    val artifactsRoot: File get() = File(context.filesDir, "artifacts").apply { mkdirs() }

    fun projectDir(id: String): File = File(projectsRoot, id)

    fun logFile(buildId: String): File = File(logsRoot, "$buildId.log")

    /** Total bytes used by projects, for the Home storage meter. */
    fun usedBytes(): Long = listOf(projectsRoot, buildsRoot, logsRoot, artifactsRoot)
        .sumOf { dir -> dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } }
}

class ProjectRepository(
    private val projectDao: ProjectDao,
    private val storage: ProjectStorage,
    private val fs: SafeFileSystem,
) {
    fun observeProjects(): Flow<List<Project>> = projectDao.observeAll().map { list -> list.map { it.toModel() } }

    fun observeProject(id: String): Flow<Project?> = projectDao.observeById(id).map { it?.toModel() }

    suspend fun getProject(id: String): Project? = projectDao.findById(id)?.toModel()

    suspend fun register(project: Project) {
        projectDao.upsert(project.toEntity())
    }

    suspend fun touch(id: String) = projectDao.touch(id, System.currentTimeMillis())

    suspend fun setFavorite(id: String, favorite: Boolean) = projectDao.setFavorite(id, favorite)

    suspend fun updateGit(projectId: String, branch: String?, remote: String?) =
        projectDao.updateGit(projectId, branch, remote)

    /**
     * Deletes project files then the row. Files are removed first so a failure leaves a
     * recoverable database entry rather than orphaned content on disk.
     */
    suspend fun deleteProject(id: String): Boolean = withContext(Dispatchers.IO) {
        val dir = storage.projectDir(id)
        val deleted = if (dir.exists()) dir.deleteRecursively() else true
        if (deleted) projectDao.delete(id)
        deleted
    }

    suspend fun projectFileCount(id: String): Int = withContext(Dispatchers.IO) {
        val dir = storage.projectDir(id)
        if (!dir.exists()) 0 else dir.walkTopDown().count { it.isFile }
    }

    fun rootOf(project: Project): File = File(project.rootPath)
}

class FileTreeRepository(private val fs: SafeFileSystem = SafeFileSystem()) {

    suspend fun list(project: Project, relativePath: String = ""): List<ProjectFile> = withContext(Dispatchers.IO) {
        fs.listTree(File(project.rootPath), relativePath).map { it.toProjectFile() }
    }

    suspend fun read(project: Project, relativePath: String): SafeFileSystem.Result<String> =
        withContext(Dispatchers.IO) { fs.readText(File(project.rootPath), relativePath) }

    suspend fun write(project: Project, relativePath: String, content: String): SafeFileSystem.Result<File> =
        withContext(Dispatchers.IO) { fs.writeText(File(project.rootPath), relativePath, content) }

    suspend fun createDirectory(project: Project, relativePath: String): SafeFileSystem.Result<File> =
        withContext(Dispatchers.IO) { fs.createDirectory(File(project.rootPath), relativePath) }

    suspend fun delete(project: Project, relativePath: String): SafeFileSystem.Result<Unit> =
        withContext(Dispatchers.IO) { fs.delete(File(project.rootPath), relativePath) }

    suspend fun move(project: Project, from: String, to: String): SafeFileSystem.Result<File> =
        withContext(Dispatchers.IO) { fs.move(File(project.rootPath), from, to) }

    suspend fun copy(project: Project, from: String, to: String): SafeFileSystem.Result<File> =
        withContext(Dispatchers.IO) { fs.copy(File(project.rootPath), from, to) }

    /** Loads up to [limit] text files for project-wide search and AI context. */
    suspend fun loadTextFiles(
        project: Project,
        limit: Int = 400,
        maxFileBytes: Long = 256 * 1024,
    ): Map<String, String> = withContext(Dispatchers.IO) {
        val root = File(project.rootPath)
        val out = LinkedHashMap<String, String>()
        if (!root.exists()) return@withContext out
        val nodes = fs.listTree(root, maxEntries = 4000)
        for (node in nodes) {
            if (out.size >= limit) break
            if (node.isDirectory) continue
            if (node.sizeBytes > maxFileBytes) continue
            val language = Language.fromFileName(node.name)
            if (language == Language.PLAINTEXT && !node.name.endsWith(".txt")) continue
            val result = fs.readText(root, node.relativePath, maxFileBytes.toInt())
            if (result is SafeFileSystem.Result.Ok) out[node.relativePath] = result.value
        }
        out
    }
}

class BuildHistoryRepository(
    private val buildDao: BuildDao,
    private val storage: ProjectStorage,
    private val json: kotlinx.serialization.json.Json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    },
) {
    fun observeRecent(limit: Int = 50): Flow<List<BuildRecordEntity>> = buildDao.observeRecent(limit)

    fun observeForProject(projectId: String): Flow<List<BuildRecordEntity>> =
        buildDao.observeForProject(projectId)

    suspend fun successRate(): Double = withContext(Dispatchers.IO) {
        val total = buildDao.totalCount()
        if (total == 0) 0.0 else buildDao.successCount().toDouble() / total
    }

    suspend fun successRateSince(since: Long): Double = withContext(Dispatchers.IO) {
        val total = buildDao.countSince(since)
        if (total == 0) 0.0 else buildDao.successCountSince(since).toDouble() / total
    }

    /** Persists a finished build and writes its full log to disk. */
    suspend fun record(result: BuildResult): Unit = withContext(Dispatchers.IO) {
        val logFile = storage.logFile(result.id)
        runCatching { logFile.writeText(result.logLines.joinToString("\n")) }
        buildDao.upsert(
            BuildRecordEntity(
                id = result.id,
                projectId = result.request.projectId,
                kind = result.request.kind.name,
                target = result.request.target.id,
                status = result.status.name,
                startedAt = result.startedAt,
                finishedAt = result.finishedAt,
                exitCode = result.exitCode,
                errorCount = result.errorCount,
                warningCount = result.warningCount,
                testsTotal = result.testSummary?.total ?: 0,
                testsPassed = result.testSummary?.passed ?: 0,
                testsFailed = result.testSummary?.failed ?: 0,
                logPath = if (logFile.exists()) logFile.absolutePath else null,
                failureReason = result.failureReason,
                artifactsJson = json.encodeToString(
                    kotlinx.serialization.builtins.ListSerializer(Artifact.serializer()),
                    result.artifacts,
                ),
                diagnosticsJson = json.encodeToString(
                    kotlinx.serialization.builtins.ListSerializer(Diagnostic.serializer()),
                    result.diagnostics,
                ),
                projectKind = result.projectKind,
            ),
        )
    }

    /**
     * Rebuilds the artifacts and diagnostics stored with a finished build, so history can
     * offer an installable APK and the error list without re-running the build. Malformed
     * JSON yields an empty list rather than an exception: a corrupt history row must not
     * break the History screen.
     */
    fun artifactsOf(record: BuildRecordEntity): List<Artifact> =
        record.artifactsJson?.let { raw ->
            runCatching {
                json.decodeFromString(
                    kotlinx.serialization.builtins.ListSerializer(Artifact.serializer()),
                    raw,
                )
            }.getOrDefault(emptyList())
        }.orEmpty().filter { File(it.path).exists() }

    fun diagnosticsOf(record: BuildRecordEntity): List<Diagnostic> =
        record.diagnosticsJson?.let { raw ->
            runCatching {
                json.decodeFromString(
                    kotlinx.serialization.builtins.ListSerializer(Diagnostic.serializer()),
                    raw,
                )
            }.getOrDefault(emptyList())
        }.orEmpty()

    suspend fun readLog(buildId: String): List<String> = withContext(Dispatchers.IO) {
        val file = storage.logFile(buildId)
        if (!file.exists()) emptyList() else file.readLines()
    }

    suspend fun prune(keepDays: Int) = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - keepDays * 86_400_000L
        buildDao.pruneOlderThan(cutoff)
    }
}

class TabRepository(private val tabDao: TabDao) {
    suspend fun forProject(projectId: String): List<OpenTabEntity> = tabDao.forProject(projectId)

    suspend fun save(projectId: String, tabs: List<OpenTabEntity>) = withContext(Dispatchers.IO) {
        tabDao.clearProject(projectId)
        if (tabs.isNotEmpty()) tabDao.upsertAll(tabs)
    }
}

// ------------------------------------------------------------------- mappers

fun ProjectEntity.toModel(): Project = Project(
    id = id,
    name = name,
    packageName = packageName,
    rootPath = rootPath,
    category = ProjectCategory.fromId(categoryId),
    templateId = templateId,
    languages = languageIds.split(',').filter { it.isNotBlank() }.map { Language.fromId(it) },
    buildSystem = BuildSystem.fromId(buildSystemId),
    createdAt = createdAt,
    lastOpenedAt = lastOpenedAt,
    gitRemote = gitRemote,
    gitBranch = gitBranch,
    favorite = favorite,
    sdkConfig = if (minSdk != null) {
        SdkConfig(
            minSdk = minSdk,
            targetSdk = targetSdk ?: 36,
            compileSdk = compileSdk ?: 36,
            applicationId = applicationId ?: packageName,
        )
    } else null,
)

fun Project.toEntity(): ProjectEntity = ProjectEntity(
    id = id,
    name = name,
    packageName = packageName,
    rootPath = rootPath,
    categoryId = category.id,
    templateId = templateId,
    languageIds = languages.joinToString(",") { it.id },
    buildSystemId = buildSystem.id,
    createdAt = createdAt,
    lastOpenedAt = lastOpenedAt,
    gitRemote = gitRemote,
    gitBranch = gitBranch,
    favorite = favorite,
    minSdk = sdkConfig?.minSdk,
    targetSdk = sdkConfig?.targetSdk,
    compileSdk = sdkConfig?.compileSdk,
    applicationId = sdkConfig?.applicationId,
)

fun FileNode.toProjectFile(): ProjectFile = ProjectFile(
    name = name,
    path = relativePath,
    kind = if (isDirectory) FileKind.DIRECTORY else FileKind.FILE,
    sizeBytes = sizeBytes,
    lastModified = lastModified,
    language = Language.fromFileName(name),
)

fun BuildStatus.isTerminal(): Boolean =
    this == BuildStatus.SUCCEEDED || this == BuildStatus.FAILED || this == BuildStatus.CANCELLED
