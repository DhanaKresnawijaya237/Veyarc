package com.veyarc.data.storage

import androidx.room.Room
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
    fun migrationTenToElevenPreservesWorkflowRunAndAssetRows() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "chat-migration-${System.nanoTime()}.db"
        val original = Room.databaseBuilder(context, VeyarcDatabase::class.java, name).allowMainThreadQueries().build()
        val tables = listOf("workflows", "workflow_revisions", "run_records", "asset_blobs", "revision_assets")
        fun rows(db: SupportSQLiteDatabase) = tables.associateWith { table ->
            db.query("SELECT * FROM $table").use { cursor ->
                buildList { while (cursor.moveToNext()) add((0 until cursor.columnCount).map { if (cursor.isNull(it)) null else cursor.getString(it) }) }
            }
        }
        lateinit var before: Map<String, List<List<String?>>>
        try {
            val db = original.openHelper.writableDatabase
            db.execSQL("INSERT INTO workflows (workflowId,name,latestRevision,createdAtMs,updatedAtMs) VALUES ('workflow','Existing',1,1,1)")
            db.execSQL("INSERT INTO workflow_revisions (workflowId,revision,schemaVersion,encryptedFileName,encryptedByteLength,createdAtMs) VALUES ('workflow',1,1,'revision.wfenc',100,1)")
            db.execSQL("INSERT INTO run_records (runId,workflowId,revision,status,startedAtMs) VALUES ('run','workflow',1,'COMPLETED',1)")
            db.execSQL("INSERT INTO asset_blobs VALUES ('blob','blob.wfenc',100,1,1,'image/png',1,1)")
            db.execSQL("INSERT INTO revision_assets VALUES ('workflow',1,'asset','blob',NULL,'CROPPED_TARGET','IMAGE_TEMPLATE','image/png',1,1)")
            before = rows(db)
            // Version 11 only adds this table. Recreate the exact version-10 schema and reopen through Room.
            db.execSQL("DROP TABLE chat_conversations")
            db.execSQL("DELETE FROM room_master_table")
            db.version = 10
        } finally {
            original.close()
        }
        val migrated = Room.databaseBuilder(context, VeyarcDatabase::class.java, name)
            .addMigrations(VeyarcDatabase.MIGRATION_10_11).allowMainThreadQueries().build()
        try {
            val db = migrated.openHelper.writableDatabase
            assertEquals(11, db.version)
            assertEquals(before, rows(db))
            listOf("workflows", "workflow_revisions", "run_records", "asset_blobs", "revision_assets").forEach { table ->
                db.query("SELECT COUNT(*) FROM $table").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(1, cursor.getInt(0))
                }
            }
            assertTrue(migrated.chatDao().list().isEmpty())
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }

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

    @Test
    fun migrationThreeToFourAddsRunFolderOrdering() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "migration-${System.nanoTime()}.db"
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(3) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL(
                                "CREATE TABLE run_folders (folderId TEXT NOT NULL PRIMARY KEY, " +
                                    "name TEXT NOT NULL, createdAtMs INTEGER NOT NULL, updatedAtMs INTEGER NOT NULL)",
                            )
                            db.execSQL("INSERT INTO run_folders VALUES ('folder', 'Saved', 10, 20)")
                        }

                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                    },
                )
                .build(),
        )
        try {
            val db = helper.writableDatabase
            VeyarcDatabase.MIGRATION_3_4.migrate(db)
            db.query("SELECT name, sortOrder FROM run_folders").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Saved", cursor.getString(0))
                assertEquals(0, cursor.getInt(1))
            }
        } finally {
            helper.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun migrationFourToFiveAddsUniqueOptionalWorkflowFolderColors() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "migration-${System.nanoTime()}.db"
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(4) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL(
                                "CREATE TABLE workflow_folders (folderId TEXT NOT NULL PRIMARY KEY, " +
                                    "name TEXT NOT NULL, createdAtMs INTEGER NOT NULL, updatedAtMs INTEGER NOT NULL)",
                            )
                            db.execSQL("INSERT INTO workflow_folders VALUES ('one', 'One', 10, 20)")
                            db.execSQL("INSERT INTO workflow_folders VALUES ('two', 'Two', 10, 20)")
                        }

                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                    },
                )
                .build(),
        )
        try {
            val db = helper.writableDatabase
            VeyarcDatabase.MIGRATION_4_5.migrate(db)
            db.query("SELECT colorArgb FROM workflow_folders").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.isNull(0))
                assertTrue(cursor.moveToNext())
                assertTrue(cursor.isNull(0))
            }
            db.execSQL("UPDATE workflow_folders SET colorArgb = 123 WHERE folderId = 'one'")
            assertTrue(
                runCatching {
                    db.execSQL("UPDATE workflow_folders SET colorArgb = 123 WHERE folderId = 'two'")
                }.isFailure,
            )
        } finally {
            helper.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun migrationFiveToSixAddsWorkflowOrderingAndRunFolderColors() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "migration-${System.nanoTime()}.db"
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(5) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL(
                                "CREATE TABLE workflow_folders (folderId TEXT NOT NULL PRIMARY KEY, " +
                                    "name TEXT NOT NULL, createdAtMs INTEGER NOT NULL, updatedAtMs INTEGER NOT NULL, " +
                                    "colorArgb INTEGER DEFAULT NULL)",
                            )
                            db.execSQL(
                                "CREATE TABLE run_folders (folderId TEXT NOT NULL PRIMARY KEY, " +
                                    "name TEXT NOT NULL, createdAtMs INTEGER NOT NULL, updatedAtMs INTEGER NOT NULL, " +
                                    "sortOrder INTEGER NOT NULL DEFAULT 0)",
                            )
                            db.execSQL("INSERT INTO workflow_folders VALUES ('workflow-folder', 'One', 10, 20, NULL)")
                            db.execSQL("INSERT INTO run_folders VALUES ('run-folder', 'Two', 10, 20, 0)")
                        }

                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                    },
                )
                .build(),
        )
        try {
            val db = helper.writableDatabase
            VeyarcDatabase.MIGRATION_5_6.migrate(db)
            db.query("SELECT sortOrder FROM workflow_folders").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
            db.query("SELECT colorArgb FROM run_folders").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.isNull(0))
            }
        } finally {
            helper.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun migrationSixToSevenAddsOptionalRunNames() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "migration-${System.nanoTime()}.db"
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(6) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL(
                                "CREATE TABLE run_records (runId TEXT NOT NULL PRIMARY KEY, " +
                                    "workflowId TEXT NOT NULL, revision INTEGER NOT NULL, status TEXT NOT NULL, " +
                                    "startedAtMs INTEGER NOT NULL, finishedAtMs INTEGER, interruptionReason TEXT, " +
                                    "completedSteps INTEGER NOT NULL DEFAULT 0, totalSteps INTEGER DEFAULT NULL, " +
                                    "eventHistoryJson TEXT DEFAULT NULL, folderId TEXT DEFAULT NULL)",
                            )
                            db.execSQL(
                                "INSERT INTO run_records (runId, workflowId, revision, status, startedAtMs) " +
                                    "VALUES ('run', 'workflow', 1, 'COMPLETED', 30)",
                            )
                        }

                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                    },
                )
                .build(),
        )
        try {
            val db = helper.writableDatabase
            VeyarcDatabase.MIGRATION_6_7.migrate(db)
            db.query("SELECT name FROM run_records").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.isNull(0))
            }
        } finally {
            helper.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun migrationSevenToEightAddsOptionalWorkflowProducer() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "migration-${System.nanoTime()}.db"
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(7) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL(
                                "CREATE TABLE workflows (workflowId TEXT NOT NULL PRIMARY KEY, " +
                                    "name TEXT NOT NULL, latestRevision INTEGER NOT NULL, " +
                                    "createdAtMs INTEGER NOT NULL, updatedAtMs INTEGER NOT NULL, " +
                                    "folderId TEXT DEFAULT NULL, isFavorite INTEGER NOT NULL DEFAULT 0)",
                            )
                            db.execSQL(
                                "INSERT INTO workflows (workflowId, name, latestRevision, createdAtMs, updatedAtMs) " +
                                    "VALUES ('workflow', 'Workflow', 1, 10, 20)",
                            )
                        }

                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                    },
                )
                .build(),
        )
        try {
            val db = helper.writableDatabase
            VeyarcDatabase.MIGRATION_7_8.migrate(db)
            db.query("SELECT producerVersion FROM workflows").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.isNull(0))
            }
        } finally {
            helper.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun migrationEightToNineAddsOptionalWorkflowOrientation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "migration-${System.nanoTime()}.db"
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(8) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL(
                                "CREATE TABLE workflows (workflowId TEXT NOT NULL PRIMARY KEY, " +
                                    "name TEXT NOT NULL, latestRevision INTEGER NOT NULL, " +
                                    "createdAtMs INTEGER NOT NULL, updatedAtMs INTEGER NOT NULL, " +
                                    "folderId TEXT DEFAULT NULL, isFavorite INTEGER NOT NULL DEFAULT 0, " +
                                    "producerVersion TEXT DEFAULT NULL)",
                            )
                            db.execSQL(
                                "INSERT INTO workflows (workflowId, name, latestRevision, createdAtMs, updatedAtMs) " +
                                    "VALUES ('workflow', 'Workflow', 1, 10, 20)",
                            )
                        }

                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                    },
                )
                .build(),
        )
        try {
            val db = helper.writableDatabase
            VeyarcDatabase.MIGRATION_8_9.migrate(db)
            db.query("SELECT recordedOrientation FROM workflows").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.isNull(0))
            }
        } finally {
            helper.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun migrationNineToTenAddsSharedAssetStorageWithoutChangingLegacyRevisions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "migration-${System.nanoTime()}.db"
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(9) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL(
                                "CREATE TABLE workflow_revisions (" +
                                    "workflowId TEXT NOT NULL, revision INTEGER NOT NULL, " +
                                    "schemaVersion INTEGER NOT NULL, encryptedFileName TEXT NOT NULL, " +
                                    "encryptedByteLength INTEGER NOT NULL, createdAtMs INTEGER NOT NULL, " +
                                    "PRIMARY KEY(workflowId, revision))",
                            )
                            db.execSQL(
                                "INSERT INTO workflow_revisions VALUES " +
                                    "('workflow', 1, 1, 'legacy.wfenc', 100, 10)",
                            )
                        }

                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                    },
                )
                .build(),
        )
        try {
            val db = helper.writableDatabase
            db.setForeignKeyConstraintsEnabled(true)
            VeyarcDatabase.MIGRATION_9_10.migrate(db)

            db.query("SELECT storageFormat FROM workflow_revisions").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(RevisionStorageFormat.LEGACY_BUNDLE.name, cursor.getString(0))
            }
            assertTableExists(db, "asset_blobs")
            assertTableExists(db, "asset_previews")
            assertTableExists(db, "revision_assets")
            db.execSQL(
                "INSERT INTO asset_blobs VALUES " +
                    "('blob', 'blob.wfenc', 100, 1, 1, 'application/vnd.veyarc.template', 1, 20)",
            )
            db.execSQL(
                "INSERT INTO revision_assets VALUES " +
                    "('workflow', 1, 'asset', 'blob', NULL, 'CROPPED_TARGET', " +
                    "'IMAGE_TEMPLATE', 'image/png', 1, 1)",
            )
            db.execSQL("DELETE FROM workflow_revisions WHERE workflowId = 'workflow' AND revision = 1")
            db.query("SELECT COUNT(*) FROM revision_assets").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
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
