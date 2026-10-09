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
    @ColumnInfo(defaultValue = "NULL")
    val producerVersion: String? = null,
    @ColumnInfo(defaultValue = "NULL")
    val recordedOrientation: String? = null,
)

@Entity(
    tableName = "workflow_folders",
    indices = [Index(value = ["colorArgb"], unique = true)],
)
internal data class WorkflowFolderEntity(
    @PrimaryKey val folderId: String,
    val name: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    @ColumnInfo(defaultValue = "NULL")
    val colorArgb: Int? = null,
    @ColumnInfo(defaultValue = "0")
    val sortOrder: Int = 0,
)

@Entity(
    tableName = "run_folders",
    indices = [Index(value = ["colorArgb"], unique = true)],
)
internal data class RunFolderEntity(
    @PrimaryKey val folderId: String,
    val name: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    @ColumnInfo(defaultValue = "0")
    val sortOrder: Int = 0,
    @ColumnInfo(defaultValue = "NULL")
    val colorArgb: Int? = null,
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
    @ColumnInfo(defaultValue = "'LEGACY_BUNDLE'")
    val storageFormat: String = RevisionStorageFormat.LEGACY_BUNDLE.name,
)

internal enum class RevisionStorageFormat {
    LEGACY_BUNDLE,
    ASSET_MANIFEST,
}

@Entity(
    tableName = "asset_blobs",
    indices = [Index(value = ["encryptedFileName"], unique = true)],
)
internal data class AssetBlobEntity(
    @PrimaryKey val blobKey: String,
    val encryptedFileName: String,
    val encryptedByteLength: Long,
    val widthPx: Int,
    val heightPx: Int,
    val mimeType: String,
    val formatVersion: Int,
    val createdAtMs: Long,
)

@Entity(
    tableName = "asset_previews",
    foreignKeys = [
        ForeignKey(
            entity = AssetBlobEntity::class,
            parentColumns = ["blobKey"],
            childColumns = ["blobKey"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["encryptedFileName"], unique = true)],
)
internal data class AssetPreviewEntity(
    @PrimaryKey val blobKey: String,
    val encryptedFileName: String,
    val encryptedByteLength: Long,
    val widthPx: Int,
    val heightPx: Int,
    val formatVersion: Int,
)

@Entity(
    tableName = "revision_assets",
    primaryKeys = ["workflowId", "revision", "assetId"],
    foreignKeys = [
        ForeignKey(
            entity = WorkflowRevisionEntity::class,
            parentColumns = ["workflowId", "revision"],
            childColumns = ["workflowId", "revision"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = AssetBlobEntity::class,
            parentColumns = ["blobKey"],
            childColumns = ["blobKey"],
        ),
    ],
    indices = [Index(value = ["blobKey"])],
)
internal data class RevisionAssetEntity(
    val workflowId: String,
    val revision: Int,
    val assetId: String,
    val blobKey: String,
    val displayName: String?,
    val role: String,
    val kind: String,
    val mimeType: String,
    val widthPx: Int,
    val heightPx: Int,
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
    @ColumnInfo(defaultValue = "NULL")
    val name: String? = null,
)

internal data class RunStatusCountRow(
    val status: String,
    val count: Long,
)

/** Browsing history must not load the potentially large event log. */
internal data class RunSummaryRow(
    val runId: String,
    val workflowId: String,
    val revision: Int,
    val status: String,
    val startedAtMs: Long,
    val finishedAtMs: Long?,
    val interruptionReason: String?,
    val completedSteps: Int,
    val totalSteps: Long?,
    val hasStoredDetails: Boolean,
    val folderId: String?,
    val name: String?,
)

internal data class WorkflowRunActivityRow(
    val workflowId: String,
    val runCount: Long,
    val lastRunAtMs: Long?,
)
