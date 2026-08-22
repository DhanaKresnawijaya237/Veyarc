package com.veyarc.data.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [WorkflowEntity::class, WorkflowRevisionEntity::class, RunRecordEntity::class],
    version = 1,
    exportSchema = false,
)
internal abstract class VeyarcDatabase : RoomDatabase() {
    abstract fun workflowDao(): WorkflowDao

    companion object {
        fun create(context: Context): VeyarcDatabase = Room.databaseBuilder(
            context.applicationContext,
            VeyarcDatabase::class.java,
            DATABASE_NAME,
        ).build()

        private const val DATABASE_NAME = "veyarc-metadata.db"
    }
}
