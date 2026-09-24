package com.veyarc.data.storage

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.veyarc.model.AssetId
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import javax.crypto.spec.SecretKeySpec

@RunWith(AndroidJUnit4::class)
class WorkflowAssetBlobStoreTest {
    @Test
    fun deduplicatesBlobsStoresPreviewsAndCollectsAfterRevisionDeletion() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, VeyarcDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val directory = File(context.cacheDir, "asset-store-${System.nanoTime()}").apply { mkdirs() }
        val aesKey = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
        val hmacKey = SecretKeySpec(ByteArray(32) { (it + 2).toByte() }, "HmacSHA256")
        val files = EncryptedFileStore(directory, AesGcmFileCodec { aesKey })
        val store = WorkflowAssetBlobStore(
            database = database,
            files = files,
            identity = AssetBlobIdentity { hmacKey },
            payloadCodec = EncryptedTemplateAssetCodec({ aesKey }),
            nowMs = { 100L },
        )
        val pixels = byteArrayOf(1, 2, 3, 4)
        try {
            insertRevision(database)
            val descriptors = store.storeRevisionAssets(
                workflowId = WORKFLOW_ID,
                revision = 1,
                inputs = listOf(
                    input("template-one", "One", pixels),
                    input("template-two", "Two", pixels),
                ),
            )

            assertEquals(descriptors[0].blobKey, descriptors[1].blobKey)
            assertEquals(1, database.workflowAssetDao().listBlobs().size)
            assertEquals(2, database.workflowAssetDao().listRevisionAssets(WORKFLOW_ID, 1).size)
            val stored = store.readTemplate(descriptors.first().blobKey)
            assertArrayEquals(pixels, stored.copyRgbaBytes())
            stored.clear()

            store.putPreviewIfAbsent(
                descriptors.first().blobKey,
                DecodedTemplate(1, 1, byteArrayOf(5, 6, 7, 8)),
            )
            val preview = store.readPreview(descriptors.first().blobKey)
            assertArrayEquals(byteArrayOf(5, 6, 7, 8), preview!!.copyRgbaBytes())
            preview.clear()
            assertEquals(0, store.collectGarbage())

            assertEquals(1, database.workflowDao().deleteRevisionsForWorkflow(WORKFLOW_ID))
            assertEquals(1, store.collectGarbage())
            assertTrue(database.workflowAssetDao().listBlobs().isEmpty())
            assertTrue(directory.listFiles().isNullOrEmpty())
        } finally {
            database.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun failedMetadataCommitRemovesNewBlobFile() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, VeyarcDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val directory = File(context.cacheDir, "asset-rollback-${System.nanoTime()}").apply { mkdirs() }
        val aesKey = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
        val store = WorkflowAssetBlobStore(
            database = database,
            files = EncryptedFileStore(directory, AesGcmFileCodec { aesKey }),
            identity = AssetBlobIdentity {
                SecretKeySpec(ByteArray(32) { (it + 2).toByte() }, "HmacSHA256")
            },
            payloadCodec = EncryptedTemplateAssetCodec({ aesKey }),
            nowMs = { -1L },
        )
        try {
            insertRevision(database)

            val result = runCatching {
                store.storeRevisionAssets(
                    workflowId = WORKFLOW_ID,
                    revision = 1,
                    inputs = listOf(input("template", "Template", byteArrayOf(1, 2, 3, 4))),
                )
            }

            assertTrue(result.isFailure)
            assertTrue(database.workflowAssetDao().listBlobs().isEmpty())
            assertTrue(database.workflowAssetDao().listRevisionAssets(WORKFLOW_ID, 1).isEmpty())
            assertTrue(directory.listFiles().isNullOrEmpty())
        } finally {
            database.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun generatedPreviewBoundsTheLongestEdge() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, VeyarcDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val directory = File(context.cacheDir, "asset-preview-${System.nanoTime()}").apply { mkdirs() }
        val aesKey = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
        val store = WorkflowAssetBlobStore(
            database = database,
            files = EncryptedFileStore(directory, AesGcmFileCodec { aesKey }),
            identity = AssetBlobIdentity {
                SecretKeySpec(ByteArray(32) { (it + 2).toByte() }, "HmacSHA256")
            },
            payloadCodec = EncryptedTemplateAssetCodec(SecretKeySource { aesKey }),
            nowMs = { 100L },
        )
        val template = DecodedTemplate(400, 2, ByteArray(400 * 2 * 4) { it.toByte() })
        try {
            insertRevision(database)
            val descriptor = store.storeRevisionAssets(
                WORKFLOW_ID,
                1,
                listOf(
                    RevisionAssetInput(
                        AssetId("wide-template"),
                        "Wide",
                        StoredTemplateRole.CROPPED_TARGET,
                        "image/png",
                        template,
                    ),
                ),
            ).single()

            store.putGeneratedPreviewIfAbsent(descriptor.blobKey, template)
            val preview = store.readPreview(descriptor.blobKey)!!
            assertEquals(320, preview.widthPx)
            assertEquals(1, preview.heightPx)
            preview.clear()
        } finally {
            template.clear()
            database.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun reconciliationCleansCachesAndOrphansWithoutDeletingReferencedMetadata() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, VeyarcDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val directory = File(context.cacheDir, "asset-reconcile-${System.nanoTime()}").apply { mkdirs() }
        val aesKey = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
        val files = EncryptedFileStore(directory, AesGcmFileCodec { aesKey })
        val store = WorkflowAssetBlobStore(
            database = database,
            files = files,
            identity = AssetBlobIdentity {
                SecretKeySpec(ByteArray(32) { (it + 2).toByte() }, "HmacSHA256")
            },
            payloadCodec = EncryptedTemplateAssetCodec(SecretKeySource { aesKey }),
            nowMs = { 100L },
        )
        try {
            insertRevision(database)
            val descriptor = store.storeRevisionAssets(
                WORKFLOW_ID,
                1,
                listOf(input("reconcile-template", "Template", byteArrayOf(1, 2, 3, 4))),
            ).single()
            store.putPreviewIfAbsent(descriptor.blobKey, DecodedTemplate(1, 1, byteArrayOf(5, 6, 7, 8)))
            val blob = database.workflowAssetDao().findBlob(descriptor.blobKey.value)!!
            val preview = database.workflowAssetDao().findPreview(descriptor.blobKey.value)!!
            files.delete(blob.encryptedFileName)
            files.delete(preview.encryptedFileName)
            val orphanName = "blob-${"f".repeat(64)}.wfenc"
            files.writeNew(orphanName, byteArrayOf(9), byteArrayOf(8))
            File(directory, ".$orphanName.interrupted.tmp").writeBytes(byteArrayOf(1))

            val report = store.reconcile(32)

            assertEquals(0, report.collectedBlobs)
            assertEquals(1, report.missingReferencedBlobFiles)
            assertEquals(1, report.invalidPreviewRows)
            assertEquals(1, report.orphanedFiles)
            assertEquals(1, report.staleTemporaryFiles)
            assertTrue(database.workflowAssetDao().findBlob(descriptor.blobKey.value) != null)
            assertEquals(1, database.workflowAssetDao().countBlobReferences(descriptor.blobKey.value))
            assertEquals(null, database.workflowAssetDao().findPreview(descriptor.blobKey.value))
            assertTrue(!File(directory, orphanName).exists())
        } finally {
            database.close()
            directory.deleteRecursively()
        }
    }

    private fun input(id: String, name: String, pixels: ByteArray) = RevisionAssetInput(
        assetId = AssetId(id),
        displayName = name,
        role = StoredTemplateRole.CROPPED_TARGET,
        mimeType = "image/png",
        template = DecodedTemplate(1, 1, pixels),
    )

    private fun insertRevision(database: VeyarcDatabase) {
        database.workflowDao().upsertWorkflow(
            WorkflowEntity(
                workflowId = WORKFLOW_ID,
                name = "Asset test",
                latestRevision = 1,
                createdAtMs = 1,
                updatedAtMs = 1,
            ),
        )
        database.workflowDao().insertRevision(
            WorkflowRevisionEntity(
                workflowId = WORKFLOW_ID,
                revision = 1,
                schemaVersion = 1,
                encryptedFileName = "legacy.wfenc",
                encryptedByteLength = 1,
                createdAtMs = 1,
            ),
        )
    }

    private companion object {
        const val WORKFLOW_ID = "00000000-0000-4000-8000-000000000501"
    }
}
