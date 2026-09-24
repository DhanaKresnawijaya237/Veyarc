package com.veyarc.data.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
internal interface WorkflowAssetDao {
    @Query("SELECT * FROM asset_blobs WHERE blobKey = :blobKey")
    fun findBlob(blobKey: String): AssetBlobEntity?

    @Query("SELECT * FROM asset_blobs ORDER BY blobKey")
    fun listBlobs(): List<AssetBlobEntity>

    @Query("SELECT * FROM asset_blobs ORDER BY blobKey LIMIT :limit")
    fun listBlobsForMaintenance(limit: Int): List<AssetBlobEntity>

    @Query("SELECT * FROM asset_blobs WHERE encryptedFileName = :fileName")
    fun findBlobByFileName(fileName: String): AssetBlobEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertBlobs(blobs: List<AssetBlobEntity>)

    @Query("SELECT * FROM asset_previews WHERE blobKey = :blobKey")
    fun findPreview(blobKey: String): AssetPreviewEntity?

    @Query("SELECT * FROM asset_previews ORDER BY blobKey LIMIT :limit")
    fun listPreviewsForMaintenance(limit: Int): List<AssetPreviewEntity>

    @Query("SELECT * FROM asset_previews WHERE encryptedFileName = :fileName")
    fun findPreviewByFileName(fileName: String): AssetPreviewEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertPreview(preview: AssetPreviewEntity)

    @Query("DELETE FROM asset_previews WHERE blobKey = :blobKey")
    fun deletePreview(blobKey: String): Int

    @Query(
        "SELECT * FROM revision_assets " +
            "WHERE workflowId = :workflowId AND revision = :revision ORDER BY assetId",
    )
    fun listRevisionAssets(workflowId: String, revision: Int): List<RevisionAssetEntity>

    @Query(
        "SELECT * FROM revision_assets " +
            "WHERE workflowId = :workflowId AND revision = :revision AND assetId = :assetId",
    )
    fun findRevisionAsset(workflowId: String, revision: Int, assetId: String): RevisionAssetEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertRevisionAssets(assets: List<RevisionAssetEntity>)

    @Query(
        "SELECT asset_blobs.* FROM asset_blobs " +
            "LEFT JOIN revision_assets ON revision_assets.blobKey = asset_blobs.blobKey " +
            "WHERE revision_assets.blobKey IS NULL ORDER BY asset_blobs.blobKey LIMIT :limit",
    )
    fun listUnreferencedBlobs(limit: Int): List<AssetBlobEntity>

    @Query("SELECT COUNT(*) FROM revision_assets WHERE blobKey = :blobKey")
    fun countBlobReferences(blobKey: String): Int

    @Query(
        "DELETE FROM asset_blobs WHERE blobKey = :blobKey " +
            "AND NOT EXISTS (SELECT 1 FROM revision_assets WHERE revision_assets.blobKey = :blobKey)",
    )
    fun deleteBlobIfUnreferenced(blobKey: String): Int
}
