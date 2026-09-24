package com.veyarc.data.storage

import android.content.Context
import com.veyarc.model.AssetId
import com.veyarc.model.AssetKind
import java.io.File

internal const val ASSET_BLOB_KEY_ALIAS = "veyarc.asset-blobs.v1"

internal data class RevisionAssetInput(
    val assetId: AssetId,
    val displayName: String?,
    val role: StoredTemplateRole,
    val mimeType: String,
    val template: DecodedTemplate,
)

/** Shared encrypted image storage for manifest-backed workflow revisions. */
internal class WorkflowAssetBlobStore(
    private val database: VeyarcDatabase,
    private val files: EncryptedFileStore,
    private val identity: AssetBlobIdentity,
    private val payloadCodec: EncryptedTemplateAssetCodec,
    private val nowMs: () -> Long,
) {
    fun storeRevisionAssets(
        workflowId: String,
        revision: Int,
        inputs: List<RevisionAssetInput>,
        commitRevision: (List<StoredRevisionAssetDescriptor>) -> Unit = {},
    ): List<StoredRevisionAssetDescriptor> = synchronized(STORE_LOCK) {
        require(inputs.map { it.assetId }.distinct().size == inputs.size) {
            "Revision assets must have unique IDs"
        }
        val prepared = inputs.map { input ->
            val descriptor = StoredRevisionAssetDescriptor(
                assetId = input.assetId,
                kind = AssetKind.IMAGE_TEMPLATE,
                blobKey = identity.identify(input.template),
                role = input.role,
                mimeType = input.mimeType,
                widthPx = input.template.widthPx,
                heightPx = input.template.heightPx,
            )
            PreparedAsset(input, descriptor)
        }
        val mappings = prepared.map { it.mapping(workflowId, revision) }.sortedBy(RevisionAssetEntity::assetId)
        val existingMappings = database.workflowAssetDao().listRevisionAssets(workflowId, revision)
        if (existingMappings.isNotEmpty()) {
            require(existingMappings == mappings) {
                "Workflow revision already has different asset mappings"
            }
            return@synchronized prepared.map(PreparedAsset::descriptor)
        }

        val written = mutableListOf<AssetBlobEntity>()
        val writtenFiles = mutableMapOf<AssetBlobKey, String>()
        try {
            prepared.distinctBy { it.descriptor.blobKey }.forEach { asset ->
                val descriptor = asset.descriptor
                val existing = database.workflowAssetDao().findBlob(descriptor.blobKey.value)
                if (existing != null) {
                    require(existing.widthPx == descriptor.widthPx && existing.heightPx == descriptor.heightPx) {
                        "Stored asset blob metadata does not match its content identity"
                    }
                    require(files.exists(existing.encryptedFileName)) { "Stored asset blob file is missing" }
                } else {
                    val fileName = blobFileName(descriptor.blobKey)
                    if (files.exists(fileName)) files.delete(fileName)
                    val payload = payloadCodec.encodePayload(asset.input.template)
                    val encryptedByteLength = try {
                        files.writeNew(fileName, payload, blobAssociatedData(descriptor.blobKey))
                    } finally {
                        payload.fill(0)
                    }
                    writtenFiles[descriptor.blobKey] = fileName
                    written += AssetBlobEntity(
                        blobKey = descriptor.blobKey.value,
                        encryptedFileName = fileName,
                        encryptedByteLength = encryptedByteLength,
                        widthPx = descriptor.widthPx,
                        heightPx = descriptor.heightPx,
                        mimeType = STORED_TEMPLATE_MIME_TYPE,
                        formatVersion = ASSET_BLOB_FORMAT_VERSION,
                        createdAtMs = nowMs().also { require(it >= 0L) },
                    )
                }
            }
            database.runInTransaction {
                commitRevision(prepared.map(PreparedAsset::descriptor))
                require(database.workflowDao().findRevision(workflowId, revision) != null) {
                    "Workflow revision does not exist"
                }
                if (written.isNotEmpty()) database.workflowAssetDao().insertBlobs(written)
                if (mappings.isNotEmpty()) database.workflowAssetDao().insertRevisionAssets(mappings)
            }
            prepared.map(PreparedAsset::descriptor)
        } catch (error: Exception) {
            writtenFiles.forEach { (blobKey, fileName) ->
                if (database.workflowAssetDao().findBlob(blobKey.value) == null) {
                    runCatching { files.delete(fileName) }
                }
            }
            throw WorkflowStorageException("Could not store shared workflow assets", error)
        }
    }

    fun readTemplate(blobKey: AssetBlobKey): DecodedTemplate = synchronized(STORE_LOCK) {
        val blob = database.workflowAssetDao().findBlob(blobKey.value)
            ?: throw WorkflowStorageException("Stored asset blob does not exist")
        readTemplateFile(blob.encryptedFileName, blobAssociatedData(blobKey), blob.widthPx, blob.heightPx)
    }

    fun putPreviewIfAbsent(blobKey: AssetBlobKey, preview: DecodedTemplate) = synchronized(STORE_LOCK) {
        val blob = database.workflowAssetDao().findBlob(blobKey.value)
            ?: throw WorkflowStorageException("Stored asset blob does not exist")
        require(preview.widthPx <= blob.widthPx && preview.heightPx <= blob.heightPx) {
            "Asset preview cannot exceed the full-resolution template"
        }
        database.workflowAssetDao().findPreview(blobKey.value)?.let { existing ->
            require(files.exists(existing.encryptedFileName)) { "Stored asset preview file is missing" }
            return@synchronized
        }

        val fileName = previewFileName(blobKey)
        if (files.exists(fileName)) files.delete(fileName)
        val payload = payloadCodec.encodePayload(preview)
        val encryptedByteLength = try {
            files.writeNew(fileName, payload, previewAssociatedData(blobKey))
        } finally {
            payload.fill(0)
        }
        try {
            database.workflowAssetDao().insertPreview(
                AssetPreviewEntity(
                    blobKey = blobKey.value,
                    encryptedFileName = fileName,
                    encryptedByteLength = encryptedByteLength,
                    widthPx = preview.widthPx,
                    heightPx = preview.heightPx,
                    formatVersion = ASSET_BLOB_FORMAT_VERSION,
                ),
            )
        } catch (error: Exception) {
            runCatching { files.delete(fileName) }
            throw WorkflowStorageException("Could not store asset preview", error)
        }
    }

    fun putGeneratedPreviewIfAbsent(blobKey: AssetBlobKey, template: DecodedTemplate) = synchronized(STORE_LOCK) {
        if (database.workflowAssetDao().findPreview(blobKey.value) != null) return@synchronized
        val preview = template.downsample(PREVIEW_MAX_EDGE_PX)
        try {
            putPreviewIfAbsent(blobKey, preview)
        } finally {
            preview.clear()
        }
    }

    fun readPreview(blobKey: AssetBlobKey): DecodedTemplate? = synchronized(STORE_LOCK) {
        val preview = database.workflowAssetDao().findPreview(blobKey.value) ?: return@synchronized null
        readTemplateFile(
            preview.encryptedFileName,
            previewAssociatedData(blobKey),
            preview.widthPx,
            preview.heightPx,
        )
    }

    fun collectGarbage(limit: Int = DEFAULT_MAINTENANCE_LIMIT): Int = synchronized(STORE_LOCK) {
        require(limit > 0) { "Garbage-collection limit must be positive" }
        var deleted = 0
        database.workflowAssetDao().listUnreferencedBlobs(limit).forEach { blob ->
            if (database.workflowAssetDao().countBlobReferences(blob.blobKey) == 0) {
                database.workflowAssetDao().findPreview(blob.blobKey)?.let { preview ->
                    files.delete(preview.encryptedFileName)
                }
                files.delete(blob.encryptedFileName)
                check(database.workflowAssetDao().deleteBlobIfUnreferenced(blob.blobKey) == 1) {
                    "Could not remove unreferenced asset metadata"
                }
                deleted++
            }
        }
        deleted
    }

    fun reconcile(maxEntries: Int): StorageMaintenanceReport = synchronized(STORE_LOCK) {
        require(maxEntries > 0) { "Maintenance limit must be positive" }
        val dao = database.workflowAssetDao()
        val collected = collectGarbage(maxEntries)
        val missingReferenced = dao.listBlobsForMaintenance(maxEntries).count { blob ->
            !files.exists(blob.encryptedFileName) && dao.countBlobReferences(blob.blobKey) > 0
        }
        var invalidPreviews = 0
        dao.listPreviewsForMaintenance(maxEntries).forEach { preview ->
            val blob = dao.findBlob(preview.blobKey)
            val invalid = blob == null ||
                preview.formatVersion != ASSET_BLOB_FORMAT_VERSION ||
                preview.widthPx !in 1..blob.widthPx ||
                preview.heightPx !in 1..blob.heightPx ||
                !files.exists(preview.encryptedFileName)
            if (invalid) {
                if (files.exists(preview.encryptedFileName)) files.delete(preview.encryptedFileName)
                if (dao.deletePreview(preview.blobKey) == 1) invalidPreviews++
            }
        }
        var orphanedFiles = 0
        files.listCommittedFileNames(maxEntries).forEach { fileName ->
            val orphaned = when {
                BLOB_FILE_PATTERN.matches(fileName) -> dao.findBlobByFileName(fileName) == null
                PREVIEW_FILE_PATTERN.matches(fileName) -> dao.findPreviewByFileName(fileName) == null
                else -> false
            }
            if (orphaned) {
                files.delete(fileName)
                orphanedFiles++
            }
        }
        StorageMaintenanceReport(
            collectedBlobs = collected,
            missingReferencedBlobFiles = missingReferenced,
            invalidPreviewRows = invalidPreviews,
            orphanedFiles = orphanedFiles,
            staleTemporaryFiles = files.deleteStaleTemporaryFiles(maxEntries),
        )
    }

    private fun readTemplateFile(
        fileName: String,
        associatedData: ByteArray,
        expectedWidthPx: Int,
        expectedHeightPx: Int,
    ): DecodedTemplate {
        val payload = try {
            files.read(fileName, associatedData)
        } catch (error: Exception) {
            throw WorkflowStorageException("Could not read stored asset", error)
        }
        return try {
            val decoded = payloadCodec.decodePayload(payload)
            if (decoded.widthPx != expectedWidthPx || decoded.heightPx != expectedHeightPx) {
                decoded.clear()
                error("Stored asset dimensions do not match their metadata")
            }
            decoded
        } catch (error: Exception) {
            throw WorkflowStorageException("Stored asset payload is invalid", error)
        } finally {
            payload.fill(0)
        }
    }

    private data class PreparedAsset(
        val input: RevisionAssetInput,
        val descriptor: StoredRevisionAssetDescriptor,
    ) {
        fun mapping(workflowId: String, revision: Int) = RevisionAssetEntity(
            workflowId = workflowId,
            revision = revision,
            assetId = descriptor.assetId.value,
            blobKey = descriptor.blobKey.value,
            displayName = input.displayName,
            role = descriptor.role.name,
            kind = descriptor.kind.name,
            mimeType = descriptor.mimeType,
            widthPx = descriptor.widthPx,
            heightPx = descriptor.heightPx,
        )
    }

    companion object {
        private val STORE_LOCK = Any()
        private const val ASSET_DIRECTORY = "workflow-assets"
        private const val ASSET_BLOB_FORMAT_VERSION = 1
        private const val STORED_TEMPLATE_MIME_TYPE = "application/vnd.veyarc.template"
        private const val PREVIEW_MAX_EDGE_PX = 320
        private const val DEFAULT_MAINTENANCE_LIMIT = 256
        private val BLOB_FILE_PATTERN = Regex("blob-[0-9a-f]{64}\\.wfenc")
        private val PREVIEW_FILE_PATTERN = Regex("preview-[0-9a-f]{64}\\.wfenc")

        fun create(
            context: Context,
            database: VeyarcDatabase,
            nowMs: () -> Long = { System.currentTimeMillis() },
        ): WorkflowAssetBlobStore {
            val blobKeySource = AndroidKeystoreKeySource(ASSET_BLOB_KEY_ALIAS)
            return WorkflowAssetBlobStore(
                database = database,
                files = EncryptedFileStore(
                    File(context.applicationContext.filesDir, ASSET_DIRECTORY),
                    AesGcmFileCodec(blobKeySource),
                ),
                identity = AssetBlobIdentity(AndroidKeystoreHmacKeySource()::get),
                payloadCodec = EncryptedTemplateAssetCodec(blobKeySource),
                nowMs = nowMs,
            )
        }

        private fun blobFileName(blobKey: AssetBlobKey) = "blob-${blobKey.value}.wfenc"
        private fun previewFileName(blobKey: AssetBlobKey) = "preview-${blobKey.value}.wfenc"
        private fun blobAssociatedData(blobKey: AssetBlobKey) = "asset-blob:v1:${blobKey.value}".encodeToByteArray()
        private fun previewAssociatedData(blobKey: AssetBlobKey) = "asset-preview:v1:${blobKey.value}".encodeToByteArray()
    }
}

private fun DecodedTemplate.downsample(maxEdgePx: Int): DecodedTemplate {
    val source = copyRgbaBytes()
    if (maxOf(widthPx, heightPx) <= maxEdgePx) {
        return try {
            DecodedTemplate(widthPx, heightPx, source)
        } finally {
            source.fill(0)
        }
    }
    val width = maxOf(1, widthPx * maxEdgePx / maxOf(widthPx, heightPx))
    val height = maxOf(1, heightPx * maxEdgePx / maxOf(widthPx, heightPx))
    val scaled = ByteArray(width * height * 4)
    try {
        repeat(height) { y ->
            repeat(width) { x ->
                val sourceOffset = ((y * heightPx / height) * widthPx + (x * widthPx / width)) * 4
                val targetOffset = (y * width + x) * 4
                source.copyInto(scaled, targetOffset, sourceOffset, sourceOffset + 4)
            }
        }
        return DecodedTemplate(width, height, scaled)
    } finally {
        source.fill(0)
        scaled.fill(0)
    }
}
