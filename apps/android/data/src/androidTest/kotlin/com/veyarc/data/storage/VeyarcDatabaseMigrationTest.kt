package com.veyarc.data.storage

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VeyarcDatabaseMigrationTest {
    @Test
    fun migrationTwoToThreePreservesRecordsAndAddsOrganizationMetadata() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "migration-${System.nanoTime()}.db"
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(2) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL(
                                "CREATE TABLE workflows (workflowId TEXT NOT NULL PRIMARY KEY, " +
                                    "name TEXT NOT NULL, latestRevision INTEGER NOT NULL, " +
                                    "createdAtMs INTEGER NOT NULL, updatedAtMs INTEGER NOT NULL)",
                            )
                            db.execSQL(
                                "CREATE TABLE run_records (runId TEXT NOT NULL PRIMARY KEY, " +
                                    "workflowId TEXT NOT NULL, revision INTEGER NOT NULL, status TEXT NOT NULL, " +
                                    "startedAtMs INTEGER NOT NULL, finishedAtMs INTEGER, interruptionReason TEXT, " +
                                    "completedSteps INTEGER NOT NULL DEFAULT 0, totalSteps INTEGER DEFAULT NULL, " +
                                    "eventHistoryJson TEXT DEFAULT NULL)",
                            )
                            db.execSQL(
                                "INSERT INTO workflows VALUES ('workflow', 'Existing', 1, 10, 20)",
                            )
                            db.execSQL(
                                "INSERT INTO run_records " +
                                    "(runId, workflowId, revision, status, startedAtMs, completedSteps) " +
                                    "VALUES ('run', 'workflow', 1, 'COMPLETED', 30, 1)",
                            )
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                )
                .build(),
        )
        try {
            val db = helper.writableDatabase
            VeyarcDatabase.MIGRATION_2_3.migrate(db)

            db.query("SELECT name, folderId, isFavorite FROM workflows").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Existing", cursor.getString(0))
                assertTrue(cursor.isNull(1))
                assertEquals(0, cursor.getInt(2))
            }
            db.query("SELECT folderId FROM run_records").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.isNull(0))
            }
            assertTableExists(db, "workflow_folders")
            assertTableExists(db, "run_folders")
        } finally {
            helper.close()
            context.deleteDatabase(databaseName)
        }
    }

    private fun assertTableExists(db: SupportSQLiteDatabase, tableName: String) {
        db.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(tableName))
            .use { cursor -> assertTrue(cursor.moveToFirst()) }
    }
}
