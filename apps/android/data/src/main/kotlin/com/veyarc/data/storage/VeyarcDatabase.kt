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
        RunFolderEntity::class,
        RunRecordEntity::class,
    ],
    version = 4,
    exportSchema = false,
)
internal abstract class VeyarcDatabase : RoomDatabase() {
    abstract fun workflowDao(): WorkflowDao

    companion object {
        fun create(context: Context): VeyarcDatabase = Room.databaseBuilder(
            context.applicationContext,
            VeyarcDatabase::class.java,
            DATABASE_NAME,
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build()

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

        private const val DATABASE_NAME = "veyarc-metadata.db"
    }
}
