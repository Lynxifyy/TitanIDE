package dev.titanide.core.data.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Runs the real migration SQL against a real SQLite database.
 *
 * A migration that compiles but does not run is worse than none: Room only finds the
 * mistake on a user's device during an upgrade, when their workspace is at stake. These
 * tests build a v1 database from the exported v1 schema, insert a row, open it through Room
 * with the v2 schema, and assert the data survived and the new columns work.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TitanMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    // Room resolves the name against the app's databases directory, so a plain name is used
    // by both the v1 helper and the v2 Room builder.
    private val dbName = "migration-${System.nanoTime()}.db"

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    /** Creates the v1 schema verbatim and seeds one build_history row. */
    private fun createV1Database() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("""CREATE TABLE IF NOT EXISTS `projects` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `packageName` TEXT NOT NULL, `rootPath` TEXT NOT NULL, `categoryId` TEXT NOT NULL, `templateId` TEXT NOT NULL, `languageIds` TEXT NOT NULL, `buildSystemId` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `lastOpenedAt` INTEGER NOT NULL, `gitRemote` TEXT, `gitBranch` TEXT, `favorite` INTEGER NOT NULL, `minSdk` INTEGER, `targetSdk` INTEGER, `compileSdk` INTEGER, `applicationId` TEXT, PRIMARY KEY(`id`))""")
                        db.execSQL("""CREATE TABLE IF NOT EXISTS `build_history` (`id` TEXT NOT NULL, `projectId` TEXT NOT NULL, `kind` TEXT NOT NULL, `target` TEXT NOT NULL, `status` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, `finishedAt` INTEGER, `exitCode` INTEGER, `errorCount` INTEGER NOT NULL, `warningCount` INTEGER NOT NULL, `testsTotal` INTEGER NOT NULL, `testsPassed` INTEGER NOT NULL, `testsFailed` INTEGER NOT NULL, `logPath` TEXT, `failureReason` TEXT, PRIMARY KEY(`id`))""")
                        db.execSQL("""CREATE TABLE IF NOT EXISTS `open_tabs` (`id` TEXT NOT NULL, `projectId` TEXT NOT NULL, `filePath` TEXT NOT NULL, `caretOffset` INTEGER NOT NULL, `scrollLine` INTEGER NOT NULL, `orderIndex` INTEGER NOT NULL, `pinned` INTEGER NOT NULL, PRIMARY KEY(`id`))""")
                        db.execSQL("""CREATE TABLE IF NOT EXISTS `extensions` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `version` TEXT NOT NULL, `publisher` TEXT NOT NULL, `description` TEXT NOT NULL, `kind` TEXT NOT NULL, `enabled` INTEGER NOT NULL, `installedAt` INTEGER NOT NULL, `manifestJson` TEXT NOT NULL, PRIMARY KEY(`id`))""")
                        db.execSQL("""CREATE TABLE IF NOT EXISTS `http_requests` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `method` TEXT NOT NULL, `url` TEXT NOT NULL, `headersJson` TEXT NOT NULL, `body` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))""")
                        db.execSQL(
                            "INSERT INTO build_history VALUES " +
                                "('b1','p1','BUILD','LOCAL','SUCCEEDED',1,2,0,3,4,5,6,1,'/logs/b1.log',NULL)",
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )
        helper.writableDatabase
        helper.close()
    }

    private fun openV2(): TitanDatabase =
        Room.databaseBuilder(context, TitanDatabase::class.java, dbName)
            .addMigrations(*TitanMigrations.ALL)
            .allowMainThreadQueries()
            .build()

    @Test
    fun `migrating 1 to 2 keeps existing build history`() {
        createV1Database()
        val db = openV2()
        try {
            val record = runBlocking { db.buildDao().findById("b1") }
            assertNotNull(record)
            assertEquals("p1", record!!.projectId)
            assertEquals("SUCCEEDED", record.status)
            assertEquals(3, record.errorCount)
            assertEquals("/logs/b1.log", record.logPath)
        } finally {
            db.close()
        }
    }

    @Test
    fun `migrated rows have null values in the new columns`() {
        createV1Database()
        val db = openV2()
        try {
            val record = runBlocking { db.buildDao().findById("b1") }!!
            assertNull(record.artifactsJson)
            assertNull(record.diagnosticsJson)
            assertNull(record.projectKind)
        } finally {
            db.close()
        }
    }

    @Test
    fun `new columns accept values after migration`() {
        createV1Database()
        val db = openV2()
        try {
            val existing = runBlocking { db.buildDao().findById("b1") }!!
            runBlocking {
                db.buildDao().upsert(
                    existing.copy(
                        artifactsJson = """[{"name":"app-debug.apk","path":"/tmp/app-debug.apk","sizeBytes":1024}]""",
                        diagnosticsJson = "[]",
                        projectKind = "ANDROID_APP",
                    ),
                )
            }
            val updated = runBlocking { db.buildDao().findById("b1") }!!
            assertNotNull(updated.artifactsJson)
            assertTrue(updated.artifactsJson!!.contains("app-debug.apk"))
            assertEquals("ANDROID_APP", updated.projectKind)
        } finally {
            db.close()
        }
    }

    @Test
    fun `a fresh v2 database creates every table`() {
        val db = openV2()
        try {
            runBlocking {
                db.projectDao().all()
                db.buildDao().all(1)
                db.tabDao().forProject("none")
                db.extensionDao().count()
                db.httpRequestDao().count()
            }
        } finally {
            db.close()
        }
    }
}
