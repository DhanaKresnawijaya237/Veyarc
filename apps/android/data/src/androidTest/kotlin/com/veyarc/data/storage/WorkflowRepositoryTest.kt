package com.veyarc.data.storage

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.veyarc.model.AssetId
import com.veyarc.model.AssetKind
import com.veyarc.model.AssetReference
import com.veyarc.model.DisplayOrientation
import com.veyarc.model.NormalizedCoordinate
import com.veyarc.model.PackageName
import com.veyarc.model.RunLimits
import com.veyarc.model.TapPoint
import com.veyarc.model.ViewportSpec
import com.veyarc.model.WorkflowDocument
import com.veyarc.model.bundle.BundleAsset
import com.veyarc.model.bundle.VeyarcBundle
import com.veyarc.model.bundle.WorkflowBundle
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import javax.crypto.spec.SecretKeySpec

@RunWith(AndroidJUnit4::class)
class WorkflowRepositoryTest {
    @Test
    fun legacyRevisionMigratesLazilyAndExportsPortableBundle() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, VeyarcDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val directory = File(context.cacheDir, "storage-loading-test-${System.nanoTime()}").apply { mkdirs() }
        val assetDirectory = File(context.cacheDir, "storage-loading-assets-${System.nanoTime()}").apply { mkdirs() }
        val keySource = SecretKeySource { SecretKeySpec(ByteArray(32) { 4 }, "AES") }
        val templateCodec = EncryptedTemplateAssetCodec(keySource)
        val revisionFiles = EncryptedFileStore(directory, AesGcmFileCodec(keySource))
        val assetStore = testAssetStore(database, assetDirectory, 5)
        val repository = WorkflowRepository(
            database = database,
            files = revisionFiles,
            nowMs = { 1000L },
            templateCodec = templateCodec,
            assetStore = assetStore,
        )
        val assetId = AssetId("editor-template")
        val pixels = byteArrayOf(1, 2, 3, 4)
        val decoded = DecodedTemplate(1, 1, pixels)
        val asset = try {
            BundleAsset(
                assetId = assetId,
                kind = AssetKind.IMAGE_TEMPLATE,
                mimeType = "image/png",
                bytes = templateCodec.encrypt(assetId, decoded),
                encrypted = true,
                widthPx = 1,
                heightPx = 1,
            )
        } finally {
            decoded.clear()
        }
        val base = workflow(revision = 1)
        val bundle = base.copy(
            document = base.document.copy(assets = listOf(AssetReference(assetId, asset.kind, "Reference"))),
            assets = listOf(asset),
        )
        try {
            database.workflowDao().upsertWorkflow(WorkflowEntity(WORKFLOW_ID, bundle.document.name, 1, 1, 1))
            val legacyBytes = VeyarcBundle.export(bundle)
            val legacyLength = revisionFiles.writeNew("legacy.wfenc", legacyBytes, revisionAssociatedData(1))
            database.workflowDao().insertRevision(
                WorkflowRevisionEntity(
                    workflowId = WORKFLOW_ID,
                    revision = 1,
                    schemaVersion = bundle.document.schemaVersion,
                    encryptedFileName = "legacy.wfenc",
                    encryptedByteLength = legacyLength,
                    createdAtMs = 1,
                ),
            )
            repository.saveRevision(bundle.copy(document = bundle.document.copy(revision = 2)))

            assertEquals(RevisionStorageFormat.LEGACY_BUNDLE.name, database.workflowDao().findRevision(WORKFLOW_ID, 1)!!.storageFormat)
            assertEquals(RevisionStorageFormat.ASSET_MANIFEST.name, database.workflowDao().findRevision(WORKFLOW_ID, 2)!!.storageFormat)
            assertEquals(1, database.workflowAssetDao().listBlobs().size)

            val editorData = repository.loadRevisionForEditor(WORKFLOW_ID, 1)
            val loaded = repository.loadRevisionAsset(WORKFLOW_ID, 1, assetId)
            val preview = repository.loadRevisionAssetPreview(WORKFLOW_ID, 1, assetId)
            val exported = VeyarcBundle.import(repository.exportRevision(WORKFLOW_ID, 1))
            val loadedPixels = templateCodec.decrypt(assetId, loaded.bytes)
            val previewPixels = templateCodec.decrypt(assetId, preview.bytes)
            val exportedPixels = templateCodec.decrypt(assetId, exported.assets.single().bytes)
            val migrated = database.workflowDao().findRevision(WORKFLOW_ID, 1)!!

            assertEquals(base.document.id, editorData.revision.document.id)
            assertEquals("Reference", editorData.assets.single().displayName)
            assertEquals(1, editorData.assets.single().widthPx)
            assertArrayEquals(pixels, loadedPixels.copyRgbaBytes())
            assertArrayEquals(pixels, previewPixels.copyRgbaBytes())
            assertArrayEquals(pixels, exportedPixels.copyRgbaBytes())
            assertEquals(bundle.document, exported.document)
            assertEquals(RevisionStorageFormat.ASSET_MANIFEST.name, migrated.storageFormat)
            assertEquals(1, database.workflowAssetDao().listRevisionAssets(WORKFLOW_ID, 1).size)
            assertEquals(1, database.workflowAssetDao().listBlobs().size)
            assertTrue(database.workflowAssetDao().findPreview(database.workflowAssetDao().listBlobs().single().blobKey) != null)
            assertTrue(!revisionFiles.exists("legacy.wfenc"))
            loadedPixels.clear()
            previewPixels.clear()
            exportedPixels.clear()
        } finally {
            repository.close()
            directory.deleteRecursively()
            assetDirectory.deleteRecursively()
        }
    }

    @Test
    fun interruptedLegacyMigrationLeavesLegacyRevisionAuthoritative() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, VeyarcDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val directory = File(context.cacheDir, "migration-failure-${System.nanoTime()}").apply { mkdirs() }
        val assetDirectory = File(context.cacheDir, "migration-failure-assets-${System.nanoTime()}").apply { mkdirs() }
        val keySource = SecretKeySource { SecretKeySpec(ByteArray(32) { 8 }, "AES") }
        val templateCodec = EncryptedTemplateAssetCodec(keySource)
        val revisionFiles = EncryptedFileStore(directory, AesGcmFileCodec(keySource))
        val repository = WorkflowRepository(
            database = database,
            files = revisionFiles,
            nowMs = { 1000L },
            templateCodec = templateCodec,
            assetStore = testAssetStore(database, assetDirectory, 9, nowMs = { -1L }),
        )
        val assetId = AssetId("migration-template")
        val template = DecodedTemplate(1, 1, byteArrayOf(1, 2, 3, 4))
        val base = workflow(1)
        val bundle = try {
            base.copy(
                document = base.document.copy(
                    assets = listOf(AssetReference(assetId, AssetKind.IMAGE_TEMPLATE, "Migration")),
                ),
                assets = listOf(
                    BundleAsset(
                        assetId,
                        AssetKind.IMAGE_TEMPLATE,
                        "image/png",
                        templateCodec.encrypt(assetId, template),
                        true,
                        1,
                        1,
                    ),
                ),
            )
        } finally {
            template.clear()
        }
        try {
            database.workflowDao().upsertWorkflow(WorkflowEntity(WORKFLOW_ID, bundle.document.name, 1, 1, 1))
            val bytes = VeyarcBundle.export(bundle)
            val length = revisionFiles.writeNew("legacy.wfenc", bytes, revisionAssociatedData(1))
            database.workflowDao().insertRevision(
                WorkflowRevisionEntity(
                    WORKFLOW_ID,
                    1,
                    bundle.document.schemaVersion,
                    "legacy.wfenc",
                    length,
                    1,
                ),
            )

            assertTrue(runCatching { repository.loadRevisionForEditor(WORKFLOW_ID, 1) }.isFailure)
            val stored = database.workflowDao().findRevision(WORKFLOW_ID, 1)!!
            assertEquals(RevisionStorageFormat.LEGACY_BUNDLE.name, stored.storageFormat)
            assertEquals("legacy.wfenc", stored.encryptedFileName)
            assertTrue(revisionFiles.exists("legacy.wfenc"))
            assertTrue(database.workflowAssetDao().listBlobs().isEmpty())
            assertTrue(database.workflowAssetDao().listRevisionAssets(WORKFLOW_ID, 1).isEmpty())
        } finally {
            repository.close()
            directory.deleteRecursively()
            assetDirectory.deleteRecursively()
        }
    }

    @Test
    fun manifestRevisionLoadsDescriptorPreviewAndFullAssetIndependently() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, VeyarcDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val revisionDirectory = File(context.cacheDir, "manifest-revision-${System.nanoTime()}").apply { mkdirs() }
        val assetDirectory = File(context.cacheDir, "manifest-assets-${System.nanoTime()}").apply { mkdirs() }
        val revisionKey = SecretKeySpec(ByteArray(32) { 5 }, "AES")
        val assetKey = SecretKeySpec(ByteArray(32) { 6 }, "AES")
        val hmacKey = SecretKeySpec(ByteArray(32) { 7 }, "HmacSHA256")
        val revisionFiles = EncryptedFileStore(revisionDirectory, AesGcmFileCodec { revisionKey })
        val assetCodec = EncryptedTemplateAssetCodec(SecretKeySource { assetKey })
        val assetStore = WorkflowAssetBlobStore(
            database = database,
            files = EncryptedFileStore(assetDirectory, AesGcmFileCodec { assetKey }),
            identity = AssetBlobIdentity { hmacKey },
            payloadCodec = assetCodec,
            nowMs = { 1000L },
        )
        val fileName = "manifest.wfenc"
        val assetId = AssetId("manifest-template")
        val unchangedAssetId = AssetId("manifest-template-unchanged")
        val pixels = byteArrayOf(9, 8, 7, 6)
        val unchangedPixels = byteArrayOf(6, 7, 8, 9)
        val document = workflow(1).document.copy(
            assets = listOf(
                AssetReference(assetId, AssetKind.IMAGE_TEMPLATE, "Reference"),
                AssetReference(unchangedAssetId, AssetKind.IMAGE_TEMPLATE, "Unchanged"),
            ),
        )
        try {
            database.workflowDao().upsertWorkflow(
                WorkflowEntity(WORKFLOW_ID, document.name, 1, 1, 1),
            )
            database.workflowDao().insertRevision(
                WorkflowRevisionEntity(
                    workflowId = WORKFLOW_ID,
                    revision = 1,
                    schemaVersion = document.schemaVersion,
                    encryptedFileName = fileName,
                    encryptedByteLength = 0,
                    createdAtMs = 1,
                    storageFormat = RevisionStorageFormat.ASSET_MANIFEST.name,
                ),
            )
            val descriptors = assetStore.storeRevisionAssets(
                WORKFLOW_ID,
                1,
                listOf(
                    RevisionAssetInput(
                        assetId,
                        "Reference",
                        StoredTemplateRole.CROPPED_TARGET,
                        "image/png",
                        DecodedTemplate(1, 1, pixels),
                    ),
                    RevisionAssetInput(
                        unchangedAssetId,
                        "Unchanged",
                        StoredTemplateRole.CROPPED_TARGET,
                        "image/png",
                        DecodedTemplate(1, 1, unchangedPixels),
                    ),
                ),
            )
            assetStore.putPreviewIfAbsent(descriptors.first().blobKey, DecodedTemplate(1, 1, byteArrayOf(1, 1, 1, 1)))
            revisionFiles.writeNew(
                fileName,
                StoredRevisionManifestCodec.encode(
                    StoredRevisionManifest(document = document, producerVersion = "test", assets = descriptors),
                ),
                "workflow:$WORKFLOW_ID:revision:1".encodeToByteArray(),
            )
            val repository = WorkflowRepository(
                database = database,
                files = revisionFiles,
                nowMs = { 1000L },
                templateCodec = assetCodec,
                assetStore = assetStore,
            )

            val editorData = repository.loadRevisionForEditor(WORKFLOW_ID, 1)
            val preview = repository.loadRevisionAssetPreview(WORKFLOW_ID, 1, assetId)
            val full = repository.loadRevisionAsset(WORKFLOW_ID, 1, assetId)
            val preparedRun = repository.prepareRunAssets(WORKFLOW_ID, 1, setOf(assetId), 8 * 1024L)
            val preparedTemplate = preparedRun.assets.getValue(assetId).template
            val decodedPreview = assetCodec.decrypt(assetId, preview.bytes)
            val decodedFull = assetCodec.decrypt(assetId, full.bytes)

            assertEquals("Reference", editorData.assets.first { it.assetId == assetId }.displayName)
            assertArrayEquals(byteArrayOf(1, 1, 1, 1), decodedPreview.copyRgbaBytes())
            assertArrayEquals(pixels, decodedFull.copyRgbaBytes())
            assertEquals(WorkflowAssetRole.CROPPED_TARGET, preparedRun.assets.getValue(assetId).role)
            assertArrayEquals(pixels, preparedTemplate.copyRgbaBytes())
            assertTrue(runCatching { repository.prepareRunAssets(WORKFLOW_ID, 1, setOf(assetId), 1L) }.isFailure)
            preparedRun.close()
            assertArrayEquals(ByteArray(pixels.size), preparedTemplate.copyRgbaBytes())
            repository.saveRevisionFromExistingAssets(
                sourceWorkflowId = WORKFLOW_ID,
                sourceRevision = 1,
                document = document.copy(revision = 2, name = "Renamed without loading pixels"),
            )
            assertEquals(2, database.workflowAssetDao().listBlobs().size)
            assertEquals("Renamed without loading pixels", repository.loadRevisionDocument(WORKFLOW_ID, 2).document.name)

            val replacementPixels = byteArrayOf(4, 3, 2, 1)
            val replacement = DecodedTemplate(1, 1, replacementPixels)
            val replacementAsset = try {
                BundleAsset(
                    assetId = assetId,
                    kind = AssetKind.IMAGE_TEMPLATE,
                    mimeType = "image/png",
                    bytes = assetCodec.encrypt(assetId, replacement),
                    encrypted = true,
                    widthPx = 1,
                    heightPx = 1,
                )
            } finally {
                replacement.clear()
            }
            repository.saveRevisionFromAssetChanges(
                sourceWorkflowId = WORKFLOW_ID,
                sourceRevision = 2,
                document = document.copy(revision = 3, name = "Changed one asset"),
                changedAssets = listOf(replacementAsset),
            )
            val revisionTwoMappings = database.workflowAssetDao().listRevisionAssets(WORKFLOW_ID, 2)
            val revisionThreeMappings = database.workflowAssetDao().listRevisionAssets(WORKFLOW_ID, 3)
            assertEquals(3, database.workflowAssetDao().listBlobs().size)
            assertEquals(
                revisionTwoMappings.first { it.assetId == unchangedAssetId.value }.blobKey,
                revisionThreeMappings.first { it.assetId == unchangedAssetId.value }.blobKey,
            )
            assertTrue(
                revisionTwoMappings.first { it.assetId == assetId.value }.blobKey !=
                    revisionThreeMappings.first { it.assetId == assetId.value }.blobKey,
            )
            val replaced = assetCodec.decrypt(assetId, repository.loadRevisionAsset(WORKFLOW_ID, 3, assetId).bytes)
            assertArrayEquals(replacementPixels, replaced.copyRgbaBytes())
            replaced.clear()
            decodedPreview.clear()
            decodedFull.clear()
            repository.close()
        } finally {
            if (database.isOpen) database.close()
            revisionDirectory.deleteRecursively()
            assetDirectory.deleteRecursively()
        }
    }

    @Test
    fun persistsEncryptedRevisionsAndRecoversInterruptedRuns() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, VeyarcDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val directory = File(context.cacheDir, "storage-test-${System.nanoTime()}").apply { mkdirs() }
        val assetDirectory = File(context.cacheDir, "storage-test-assets-${System.nanoTime()}").apply { mkdirs() }
        val keySource = SecretKeySource {
            SecretKeySpec(ByteArray(32) { 7 }, "AES")
        }
        val repository = WorkflowRepository(
            database = database,
            files = EncryptedFileStore(directory, AesGcmFileCodec(keySource)),
            nowMs = { 1000L },
            assetStore = testAssetStore(database, assetDirectory, 10),
        )
        try {
            val first = workflow(revision = 1)
            val second = workflow(revision = 2)

            val workflowFolder = repository.createWorkflowFolder(" Personal ", WORKFLOW_FOLDER_ID)
            assertEquals("Personal", workflowFolder.name)
            assertEquals(1, repository.saveRevision(first, WORKFLOW_FOLDER_ID).revision)
            repository.setWorkflowFavorite(WORKFLOW_ID, true)
            assertEquals(2, repository.saveRevision(second).revision)
            val storedWorkflow = repository.listWorkflows().single()
            assertEquals(2, storedWorkflow.latestRevision)
            assertEquals(WORKFLOW_FOLDER_ID, storedWorkflow.folderId)
            assertTrue(storedWorkflow.isFavorite)
            repository.renameWorkflowFolder(WORKFLOW_FOLDER_ID, "Daily")
            assertEquals("Daily", repository.listWorkflowFolders().single().name)
            repository.setWorkflowFolderColor(WORKFLOW_FOLDER_ID, 0xFF336699.toInt())
            assertEquals(0xFF336699.toInt(), repository.listWorkflowFolders().single().colorArgb)
            val otherFolder = repository.createWorkflowFolder("Other", SECOND_WORKFLOW_FOLDER_ID)
            assertEquals(null, otherFolder.colorArgb)
            repository.reorderWorkflowFolders(listOf(SECOND_WORKFLOW_FOLDER_ID, WORKFLOW_FOLDER_ID))
            assertEquals(
                listOf(SECOND_WORKFLOW_FOLDER_ID, WORKFLOW_FOLDER_ID),
                repository.listWorkflowFolders().map { it.folderId },
            )
            assertTrue(
                runCatching {
                    repository.setWorkflowFolderColor(SECOND_WORKFLOW_FOLDER_ID, 0xFF336699.toInt())
                }.isFailure,
            )
            repository.setWorkflowFolderColor(WORKFLOW_FOLDER_ID, null)
            repository.setWorkflowFolderColor(SECOND_WORKFLOW_FOLDER_ID, 0xFF336699.toInt())
            assertEquals(listOf(2, 1), repository.listRevisions(WORKFLOW_ID).map { it.revision })
            assertEquals(2, repository.loadRevisionBundle(WORKFLOW_ID, 2).document.revision)

            val orphanedRevision = File(directory, "workflow-$WORKFLOW_ID-revision-9.wfenc").apply {
                writeBytes(byteArrayOf(1))
            }
            val staleTemporary = File(directory, ".workflow-$WORKFLOW_ID-revision-9.wfenc.interrupted.tmp").apply {
                writeBytes(byteArrayOf(1))
            }
            val maintenance = repository.reconcileStorage()
            assertEquals(1, maintenance.orphanedFiles)
            assertEquals(1, maintenance.staleTemporaryFiles)
            assertFalse(orphanedRevision.exists())
            assertFalse(staleTemporary.exists())

            val runId = repository.beginRun(WORKFLOW_ID, 2, runId = RUN_ID, startedAtMs = 2000L)
            assertEquals(RUN_ID, runId)
            assertEquals(0, repository.clearRunHistory())
            assertEquals(StoredRunStatus.RUNNING, repository.listRunHistory().single().status)
            assertTrue(runCatching { repository.deleteRun(RUN_ID) }.isFailure)
            assertTrue(runCatching { repository.deleteWorkflow(WORKFLOW_ID) }.isFailure)
            assertEquals(1, repository.recoverInterruptedRuns(recoveredAtMs = 3000L))
            val interrupted = repository.listInterruptedRuns().single()
            assertEquals(RUN_ID, interrupted.runId)
            assertEquals(StoredRunStatus.INTERRUPTED, interrupted.status)
            assertTrue(interrupted.interruptionReason!!.isNotBlank())
            assertEquals(listOf(RUN_ID), repository.listRunHistory().map { it.runId })
            val runFolder = repository.createRunFolder("Archive", RUN_FOLDER_ID)
            assertEquals(RUN_FOLDER_ID, runFolder.folderId)
            repository.createRunFolder("Later", SECOND_RUN_FOLDER_ID)
            repository.setRunFolderColor(RUN_FOLDER_ID, 0xFF336699.toInt())
            assertEquals(0xFF336699.toInt(), repository.listRunFolders().first { it.folderId == RUN_FOLDER_ID }.colorArgb)
            repository.reorderRunFolders(listOf(SECOND_RUN_FOLDER_ID, RUN_FOLDER_ID))
            assertEquals(
                listOf(SECOND_RUN_FOLDER_ID, RUN_FOLDER_ID),
                repository.listRunFolders().map { it.folderId },
            )
            repository.moveRunToFolder(RUN_ID, RUN_FOLDER_ID)
            assertEquals(RUN_FOLDER_ID, repository.listRunHistory().single().folderId)

            repository.beginRun(WORKFLOW_ID, 2, runId = DETAILED_RUN_ID, startedAtMs = 4000L)
            repository.finishRun(
                runId = DETAILED_RUN_ID,
                status = StoredRunStatus.CANCELLED,
                finishedAtMs = 5000L,
                interruptionReason = "Stopped by user",
                completedSteps = 2,
                totalSteps = 4,
                eventHistory = listOf(
                    StoredRunEvent(4000L, "Run started"),
                    StoredRunEvent(4100L, "Step completed"),
                ),
            )
            val detailed = repository.listRunHistory().first()
            assertEquals(DETAILED_RUN_ID, detailed.runId)
            assertEquals(2, detailed.completedSteps)
            assertEquals(4L, detailed.totalSteps)
            assertEquals("Run started", detailed.eventHistory.first().message)
            assertTrue(detailed.hasStoredDetails)
            val summaries = repository.listRunSummaries()
            assertTrue(summaries.first().eventHistory.isEmpty())
            assertTrue(summaries.first().hasStoredDetails)
            assertEquals("Run started", repository.loadRunDetails(DETAILED_RUN_ID).eventHistory.first().message)
            val dashboard = repository.loadRunDashboard()
            assertEquals(2L, dashboard.totalRuns)
            assertEquals(2L, dashboard.terminalRuns)
            assertEquals(0.0, dashboard.completionRate, 0.0)
            assertEquals(1000L, dashboard.averageFinishedRunDurationMs)
            assertEquals(listOf(DETAILED_RUN_ID, RUN_ID), dashboard.recentRuns.map { it.runId })
            assertTrue(dashboard.recentRuns.all { it.eventHistory.isEmpty() })
            val activity = repository.listWorkflowRunActivity().getValue(WORKFLOW_ID)
            assertEquals(2L, activity.runCount)
            assertEquals(4000L, activity.lastRunAtMs)

            repository.deleteWorkflowFolder(WORKFLOW_FOLDER_ID)
            repository.deleteWorkflowFolder(SECOND_WORKFLOW_FOLDER_ID)
            assertTrue(repository.listWorkflowFolders().isEmpty())
            assertEquals(null, repository.listWorkflows().single().folderId)
            assertTrue(repository.listWorkflows().single().isFavorite)
            repository.deleteRunFolder(RUN_FOLDER_ID)
            repository.deleteRunFolder(SECOND_RUN_FOLDER_ID)
            assertTrue(repository.listRunFolders().isEmpty())
            assertEquals(null, repository.listRunHistory().last().folderId)

            repository.deleteRun(RUN_ID)
            assertEquals(1, repository.clearRunHistory())
            assertTrue(repository.listRunHistory().isEmpty())
            repository.deleteWorkflow(WORKFLOW_ID)
            assertTrue(repository.listWorkflows().isEmpty())
            assertTrue(repository.listRevisions(WORKFLOW_ID).isEmpty())
            assertTrue(directory.listFiles().isNullOrEmpty())
            assertTrue(assetDirectory.listFiles().isNullOrEmpty())
        } finally {
            repository.close()
            directory.deleteRecursively()
            assetDirectory.deleteRecursively()
        }
    }

    private fun testAssetStore(
        database: VeyarcDatabase,
        directory: File,
        keySeed: Int,
        nowMs: () -> Long = { 1000L },
    ): WorkflowAssetBlobStore {
        val assetKey = SecretKeySpec(ByteArray(32) { keySeed.toByte() }, "AES")
        val hmacKey = SecretKeySpec(ByteArray(32) { (keySeed + 1).toByte() }, "HmacSHA256")
        return WorkflowAssetBlobStore(
            database = database,
            files = EncryptedFileStore(directory, AesGcmFileCodec { assetKey }),
            identity = AssetBlobIdentity { hmacKey },
            payloadCodec = EncryptedTemplateAssetCodec(SecretKeySource { assetKey }),
            nowMs = nowMs,
        )
    }

    private fun revisionAssociatedData(revision: Int) = "workflow:$WORKFLOW_ID:revision:$revision".encodeToByteArray()

    private fun workflow(revision: Int): WorkflowBundle = WorkflowBundle(
        document = WorkflowDocument(
            schemaVersion = WorkflowDocument.CURRENT_SCHEMA_VERSION,
            id = WORKFLOW_ID,
            revision = revision,
            name = "Storage test",
            allowedPackages = listOf(PackageName("com.example.app")),
            viewport = ViewportSpec(
                recordedWidthPx = 1080,
                recordedHeightPx = 2392,
                recordedDensityDpi = 420,
                recordedOrientation = DisplayOrientation.PORTRAIT,
            ),
            runLimits = RunLimits(
                maxTotalSteps = 10,
                maxRuntimeMs = 10_000,
                maxLoopIterations = 3,
                maxNestingDepth = 2,
                maxPathPoints = 8,
                maxStepDurationMs = 2_000,
            ),
            variables = emptyMap(),
            assets = emptyList(),
            steps = listOf(TapPoint(STEP_ID, NormalizedCoordinate(0.1, 0.1))),
        ),
        assets = emptyList(),
        producerVersion = "test",
    )

    private companion object {
        const val WORKFLOW_ID = "00000000-0000-4000-8000-000000000301"
        const val STEP_ID = "00000000-0000-4000-8000-000000000302"
        const val RUN_ID = "00000000-0000-4000-8000-000000000303"
        const val DETAILED_RUN_ID = "00000000-0000-4000-8000-000000000304"
        const val WORKFLOW_FOLDER_ID = "00000000-0000-4000-8000-000000000305"
        const val RUN_FOLDER_ID = "00000000-0000-4000-8000-000000000306"
        const val SECOND_RUN_FOLDER_ID = "00000000-0000-4000-8000-000000000307"
        const val SECOND_WORKFLOW_FOLDER_ID = "00000000-0000-4000-8000-000000000308"
    }
}
