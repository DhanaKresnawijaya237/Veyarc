package com.veyarc.data.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        WorkflowEntity::class,
        WorkflowFolderEntity::class,
        WorkflowRevisionEntity::class,
        AssetBlobEntity::class,
        AssetPreviewEntity::class,
        RevisionAssetEntity::class,
        RunFolderEntity::class,
        RunRecordEntity::class,
    ],
    version = 10,
    exportSchema = false,
)
internal abstract class VeyarcDatabase : RoomDatabase() {
    abstract fun workflowDao(): WorkflowDao
    abstract fun workflowAssetDao(): WorkflowAssetDao

    companion object {
        fun create(context: Context): VeyarcDatabase = Room.databaseBuilder(
            context.applicationContext,
            VeyarcDatabase::class.java,
            DATABASE_NAME,
        ).addMigrations(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8,
            MIGRATION_8_9,
            MIGRATION_9_10,
        ).build()

        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE run_records ADD COLUMN completedSteps INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE run_records ADD COLUMN totalSteps INTEGER DEFAULT NULL",
                )
                db.execSQL(
                    "ALTER TABLE run_records ADD COLUMN eventHistoryJson TEXT DEFAULT NULL",
                )
            }
        }

        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS workflow_folders (" +
                        "folderId TEXT NOT NULL, name TEXT NOT NULL, createdAtMs INTEGER NOT NULL, " +
                        "updatedAtMs INTEGER NOT NULL, PRIMARY KEY(folderId))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS run_folders (" +
                        "folderId TEXT NOT NULL, name TEXT NOT NULL, createdAtMs INTEGER NOT NULL, " +
                        "updatedAtMs INTEGER NOT NULL, PRIMARY KEY(folderId))",
                )
                db.execSQL("ALTER TABLE workflows ADD COLUMN folderId TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE workflows ADD COLUMN isFavorite INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE run_records ADD COLUMN folderId TEXT DEFAULT NULL")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_workflows_folderId ON workflows(folderId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_run_records_folderId ON run_records(folderId)")
            }
        }

        internal val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE run_folders ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0")
            }
        }

        internal val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE workflow_folders ADD COLUMN colorArgb INTEGER DEFAULT NULL")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_workflow_folders_colorArgb " +
                        "ON workflow_folders(colorArgb)",
                )
            }
        }

        internal val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE workflow_folders ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE run_folders ADD COLUMN colorArgb INTEGER DEFAULT NULL")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_run_folders_colorArgb " +
                        "ON run_folders(colorArgb)",
                )
            }
        }

        internal val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE run_records ADD COLUMN name TEXT DEFAULT NULL")
            }
        }

        internal val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE workflows ADD COLUMN producerVersion TEXT DEFAULT NULL")
            }
        }

        internal val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE workflows ADD COLUMN recordedOrientation TEXT DEFAULT NULL")
            }
        }

        internal val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE workflow_revisions ADD COLUMN storageFormat TEXT NOT NULL " +
                        "DEFAULT 'LEGACY_BUNDLE'",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS asset_blobs (" +
                        "blobKey TEXT NOT NULL, encryptedFileName TEXT NOT NULL, " +
                        "encryptedByteLength INTEGER NOT NULL, widthPx INTEGER NOT NULL, " +
                        "heightPx INTEGER NOT NULL, mimeType TEXT NOT NULL, " +
                        "formatVersion INTEGER NOT NULL, createdAtMs INTEGER NOT NULL, " +
                        "PRIMARY KEY(blobKey))",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_asset_blobs_encryptedFileName " +
                        "ON asset_blobs(encryptedFileName)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS asset_previews (" +
                        "blobKey TEXT NOT NULL, encryptedFileName TEXT NOT NULL, " +
                        "encryptedByteLength INTEGER NOT NULL, widthPx INTEGER NOT NULL, " +
                        "heightPx INTEGER NOT NULL, formatVersion INTEGER NOT NULL, " +
                        "PRIMARY KEY(blobKey), FOREIGN KEY(blobKey) REFERENCES asset_blobs(blobKey) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_asset_previews_encryptedFileName " +
                        "ON asset_previews(encryptedFileName)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS revision_assets (" +
                        "workflowId TEXT NOT NULL, revision INTEGER NOT NULL, assetId TEXT NOT NULL, " +
                        "blobKey TEXT NOT NULL, displayName TEXT, role TEXT NOT NULL, kind TEXT NOT NULL, " +
                        "mimeType TEXT NOT NULL, widthPx INTEGER NOT NULL, heightPx INTEGER NOT NULL, " +
                        "PRIMARY KEY(workflowId, revision, assetId), " +
                        "FOREIGN KEY(workflowId, revision) REFERENCES workflow_revisions(workflowId, revision) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE, " +
                        "FOREIGN KEY(blobKey) REFERENCES asset_blobs(blobKey) " +
                        "ON UPDATE NO ACTION ON DELETE NO ACTION)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_revision_assets_blobKey ON revision_assets(blobKey)",
                )
            }
        }

        private const val DATABASE_NAME = "veyarc-metadata.db"
    }
}
