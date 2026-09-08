package com.veyarc.data.storage

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "workflows",
    indices = [Index(value = ["folderId"])],
)
internal data class WorkflowEntity(
    @PrimaryKey val workflowId: String,
    val name: String,
    val latestRevision: Int,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    @ColumnInfo(defaultValue = "NULL")
    val folderId: String? = null,
    @ColumnInfo(defaultValue = "0")
    val isFavorite: Boolean = false,
)

@Entity(tableName = "workflow_folders")
internal data class WorkflowFolderEntity(
    @PrimaryKey val folderId: String,
    val name: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
)

@Entity(tableName = "run_folders")
internal data class RunFolderEntity(
    @PrimaryKey val folderId: String,
    val name: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
)

@Entity(
    tableName = "workflow_revisions",
    primaryKeys = ["workflowId", "revision"],
    foreignKeys = [
        ForeignKey(
            entity = WorkflowEntity::class,
            parentColumns = ["workflowId"],
            childColumns = ["workflowId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["workflowId", "createdAtMs"])],
)
internal data class WorkflowRevisionEntity(
    val workflowId: String,
    val revision: Int,
    val schemaVersion: Int,
    val encryptedFileName: String,
    val encryptedByteLength: Long,
    val createdAtMs: Long,
)

@Entity(
    tableName = "run_records",
    indices = [
        Index(value = ["workflowId", "startedAtMs"]),
        Index(value = ["folderId"]),
    ],
)
internal data class RunRecordEntity(
    @PrimaryKey val runId: String,
    val workflowId: String,
    val revision: Int,
    val status: String,
    val startedAtMs: Long,
    val finishedAtMs: Long?,
    val interruptionReason: String?,
    @ColumnInfo(defaultValue = "0")
    val completedSteps: Int = 0,
    @ColumnInfo(defaultValue = "NULL")
    val totalSteps: Long? = null,
    @ColumnInfo(defaultValue = "NULL")
    val eventHistoryJson: String? = null,
    @ColumnInfo(defaultValue = "NULL")
    val folderId: String? = null,
)
