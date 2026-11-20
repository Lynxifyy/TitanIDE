package dev.titanide.core.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    val packageName: String,
    val rootPath: String,
    val categoryId: String,
    val templateId: String,
    val languageIds: String,
    val buildSystemId: String,
    val createdAt: Long,
    val lastOpenedAt: Long,
    val gitRemote: String?,
    val gitBranch: String?,
    val favorite: Boolean,
    val minSdk: Int?,
    val targetSdk: Int?,
    val compileSdk: Int?,
    val applicationId: String?,
)

@Entity(tableName = "build_history")
data class BuildRecordEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val kind: String,
    val target: String,
    val status: String,
    val startedAt: Long,
    val finishedAt: Long?,
    val exitCode: Int?,
    val errorCount: Int,
    val warningCount: Int,
    val testsTotal: Int,
    val testsPassed: Int,
    val testsFailed: Int,
    val logPath: String?,
    val failureReason: String?,
)

@Entity(tableName = "open_tabs")
data class OpenTabEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val filePath: String,
    val caretOffset: Int,
    val scrollLine: Int,
    val orderIndex: Int,
    val pinned: Boolean,
)

@Entity(tableName = "extensions")
data class ExtensionEntity(
    @PrimaryKey val id: String,
    val name: String,
    val version: String,
    val publisher: String,
    val description: String,
    val kind: String,
    val enabled: Boolean,
    val installedAt: Long,
    val manifestJson: String,
)

@Entity(tableName = "http_requests")
data class HttpRequestEntity(
    @PrimaryKey val id: String,
    val name: String,
    val method: String,
    val url: String,
    val headersJson: String,
    val body: String,
    val updatedAt: Long,
)

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY lastOpenedAt DESC")
    fun observeAll(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :id")
    fun observeById(id: String): Flow<ProjectEntity?>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun findById(id: String): ProjectEntity?

    @Query("SELECT * FROM projects WHERE rootPath = :path LIMIT 1")
    suspend fun findByPath(path: String): ProjectEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(project: ProjectEntity)

    @Update
    suspend fun update(project: ProjectEntity)

    @Query("UPDATE projects SET lastOpenedAt = :timestamp WHERE id = :id")
    suspend fun touch(id: String, timestamp: Long)

    @Query("UPDATE projects SET favorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: String, favorite: Boolean)

    @Query("UPDATE projects SET gitBranch = :branch, gitRemote = :remote WHERE id = :id")
    suspend fun updateGit(id: String, branch: String?, remote: String?)

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM projects")
    suspend fun count(): Int
}

@Dao
interface BuildDao {
    @Query("SELECT * FROM build_history ORDER BY startedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 50): Flow<List<BuildRecordEntity>>

    @Query("SELECT * FROM build_history WHERE projectId = :projectId ORDER BY startedAt DESC")
    fun observeForProject(projectId: String): Flow<List<BuildRecordEntity>>

    @Query("SELECT * FROM build_history WHERE id = :id")
    suspend fun findById(id: String): BuildRecordEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: BuildRecordEntity)

    @Query("SELECT COUNT(*) FROM build_history WHERE status = 'SUCCEEDED'")
    suspend fun successCount(): Int

    @Query("SELECT COUNT(*) FROM build_history")
    suspend fun totalCount(): Int

    @Query("SELECT COUNT(*) FROM build_history WHERE status = 'SUCCEEDED' AND startedAt >= :since")
    suspend fun successCountSince(since: Long): Int

    @Query("SELECT COUNT(*) FROM build_history WHERE startedAt >= :since")
    suspend fun countSince(since: Long): Int

    @Query("DELETE FROM build_history WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM build_history WHERE startedAt < :before")
    suspend fun pruneOlderThan(before: Long)
}

@Dao
interface TabDao {
    @Query("SELECT * FROM open_tabs WHERE projectId = :projectId ORDER BY orderIndex ASC")
    suspend fun forProject(projectId: String): List<OpenTabEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(tabs: List<OpenTabEntity>)

    @Query("DELETE FROM open_tabs WHERE projectId = :projectId")
    suspend fun clearProject(projectId: String)
}

@Dao
interface ExtensionDao {
    @Query("SELECT * FROM extensions ORDER BY name ASC")
    fun observeAll(): Flow<List<ExtensionEntity>>

    @Query("SELECT * FROM extensions WHERE id = :id")
    suspend fun findById(id: String): ExtensionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(extension: ExtensionEntity)

    @Query("UPDATE extensions SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)

    @Query("DELETE FROM extensions WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface HttpRequestDao {
    @Query("SELECT * FROM http_requests ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<HttpRequestEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(request: HttpRequestEntity)

    @Query("DELETE FROM http_requests WHERE id = :id")
    suspend fun delete(id: String)
}

@Database(
    entities = [
        ProjectEntity::class,
        BuildRecordEntity::class,
        OpenTabEntity::class,
        ExtensionEntity::class,
        HttpRequestEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class TitanDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun buildDao(): BuildDao
    abstract fun tabDao(): TabDao
    abstract fun extensionDao(): ExtensionDao
    abstract fun httpRequestDao(): HttpRequestDao
}
