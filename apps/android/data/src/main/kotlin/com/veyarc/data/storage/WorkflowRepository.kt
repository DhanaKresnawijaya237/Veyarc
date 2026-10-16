package com.veyarc.data.storage

import android.content.Context
import com.veyarc.model.AssetId
import com.veyarc.model.AssetKind
import com.veyarc.model.DisplayOrientation
import com.veyarc.model.WorkflowDocument
import com.veyarc.model.WorkflowIds
import com.veyarc.model.bundle.BundleAsset
import com.veyarc.model.bundle.VeyarcBundle
import com.veyarc.model.bundle.WorkflowBundle
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.util.Collections

data class WorkflowSummary(
    val workflowId: String,
    val name: String,
    val latestRevision: Int,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val folderId: String?,
    val isFavorite: Boolean,
    val producerVersion: String? = null,
    val recordedOrientation: DisplayOrientation? = null,
)

data class FolderSummary(
    val folderId: String,
    val name: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val colorArgb: Int? = null,
    val sortOrder: Int = 0,
)

data class RevisionSummary(
    val workflowId: String,
    val revision: Int,
    val schemaVersion: Int,
    val createdAtMs: Long,
)

data class WorkflowAssetDescriptor(
    val assetId: AssetId,
    val kind: AssetKind,
    val displayName: String?,
    val mimeType: String,
    val widthPx: Int?,
    val heightPx: Int?,
    val addedAtMs: Long? = null,
    val byteCount: Long? = null,
)

enum class WorkflowAssetRole {
    FULL_SCREEN,
    CROPPED_TARGET,
}

data class PreparedRunAsset(
    val role: WorkflowAssetRole,
    val template: DecodedTemplate,
)

/** Run-owned decoded templates. Closing clears every unique pixel buffer. */
class PreparedRunAssets internal constructor(
    assets: Map<AssetId, PreparedRunAsset>,
) : Closeable {
    val assets: Map<AssetId, PreparedRunAsset> = Collections.unmodifiableMap(assets.toMap())
    private var closed = false

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        assets.values.map(PreparedRunAsset::template).distinct().forEach(DecodedTemplate::clear)
    }
}

data class StorageMaintenanceReport(
    val collectedBlobs: Int,
    val missingReferencedBlobFiles: Int,
    val invalidPreviewRows: Int,
    val orphanedFiles: Int,
    val staleTemporaryFiles: Int,
)

data class WorkflowRevisionDocument(
    val document: WorkflowDocument,
    val producerVersion: String,
)

data class WorkflowRevisionEditorData(
    val revision: WorkflowRevisionDocument,
    val assets: List<WorkflowAssetDescriptor>,
)

data class WorkflowRunActivity(
    val runCount: Long,
    val lastRunAtMs: Long?,
)

enum class StoredRunStatus {
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
    STOPPED,
    INTERRUPTED,
}

data class StoredRunRecord(
    val runId: String,
    val workflowId: String,
    val revision: Int,
    val status: StoredRunStatus,
    val startedAtMs: Long,
    val finishedAtMs: Long?,
    val interruptionReason: String?,
    val completedSteps: Int,
    val totalSteps: Long?,
    val eventHistory: List<StoredRunEvent>,
    val hasStoredDetails: Boolean,
    val folderId: String?,
    val name: String? = null,
)

data class StoredRunEvent(
    val atMs: Long,
    val message: String,
)

const val STORED_RUN_EVENT_LIMIT = 500

data class RunDashboardSummary(
    val statusCounts: Map<StoredRunStatus, Long>,
    val averageFinishedRunDurationMs: Long?,
    val recentRuns: List<StoredRunRecord>,
) {
    val totalRuns: Long = statusCounts.values.sum()
    val terminalRuns: Long = totalRuns - statusCounts.getOrDefault(StoredRunStatus.RUNNING, 0L)
    val completionRate: Double =
        if (terminalRuns == 0L) {
            0.0
        } else {
            statusCounts.getOrDefault(StoredRunStatus.COMPLETED, 0L).toDouble() / terminalRuns
        }
}

class WorkflowStorageException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Local encrypted workflow storage with Room-backed metadata and revision history. */
class WorkflowRepository internal constructor(
    private val database: VeyarcDatabase,
    private val files: EncryptedFileStore,
    private val nowMs: () -> Long,
    private val templateCodec: EncryptedTemplateAssetCodec = EncryptedTemplateAssetCodec(
        AndroidKeystoreKeySource(TEMPLATE_ASSET_KEY_ALIAS),
    ),
    private val assetStore: WorkflowAssetBlobStore? = null,
    private val chatFiles: EncryptedFileStore? = null,
) {
    private val previewCache = object : LinkedHashMap<String, BundleAsset>(PREVIEW_CACHE_SIZE + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, BundleAsset>?): Boolean = size > PREVIEW_CACHE_SIZE
    }

    constructor(
        context: Context,
        nowMs: () -> Long = { System.currentTimeMillis() },
    ) : this(context.applicationContext, nowMs, VeyarcDatabase.create(context))

    private constructor(
        context: Context,
        nowMs: () -> Long,
        database: VeyarcDatabase,
    ) : this(
        database = database,
        files = EncryptedFileStore(
            directory = File(context.applicationContext.filesDir, WORKFLOW_DIRECTORY),
            codec = AesGcmFileCodec(AndroidKeystoreKeySource()),
        ),
        nowMs = nowMs,
        assetStore = WorkflowAssetBlobStore.create(context, database, nowMs),
        chatFiles = ChatRepository.createFiles(context),
    )

    fun saveRevision(
        bundle: WorkflowBundle,
        destinationFolderId: String? = null,
    ): RevisionSummary = synchronized(this) {
        val preparedBundle = prepareAssets(bundle)
        val document = preparedBundle.document
        val store = requireNotNull(assetStore) { "Shared workflow asset store is unavailable" }
        val existingWorkflow = database.workflowDao().findWorkflow(document.id)
        if (existingWorkflow == null && destinationFolderId != null) {
            requireValidFolderId(destinationFolderId)
            require(database.workflowDao().findWorkflowFolder(destinationFolderId) != null) {
                "Workflow folder does not exist"
            }
        }
        val existingRevision = database.workflowDao().findRevision(document.id, document.revision)
        require(existingRevision == null) {
            "Workflow ${document.id} revision ${document.revision} already exists"
        }
        if (existingWorkflow != null) {
            require(document.revision > existingWorkflow.latestRevision) {
                "Workflow revisions must increase monotonically"
            }
        }

        val fileName = revisionManifestFileName(document.id, document.revision)
        val timestamp = nowMs().also { require(it >= 0L) { "Storage clock must be nonnegative" } }
        val inputs = decodeRevisionAssets(preparedBundle)

        try {
            if (files.exists(fileName)) files.delete(fileName)
            val descriptors = store.storeRevisionAssets(document.id, document.revision, inputs) { descriptors ->
                val manifest = StoredRevisionManifest(
                    document = document,
                    producerVersion = preparedBundle.producerVersion,
                    assets = descriptors,
                )
                val writtenByteLength = writeAndVerifyManifest(fileName, manifest)
                val createdAt = existingWorkflow?.createdAtMs ?: timestamp
                database.workflowDao().run {
                    upsertWorkflow(
                        WorkflowEntity(
                            workflowId = document.id,
                            name = document.name,
                            latestRevision = document.revision,
                            createdAtMs = createdAt,
                            updatedAtMs = timestamp,
                            folderId = existingWorkflow?.folderId ?: destinationFolderId,
                            isFavorite = existingWorkflow?.isFavorite ?: false,
                            producerVersion = preparedBundle.producerVersion,
                            recordedOrientation = document.viewport.recordedOrientation.name,
                        ),
                    )
                    insertRevision(
                        WorkflowRevisionEntity(
                            workflowId = document.id,
                            revision = document.revision,
                            schemaVersion = document.schemaVersion,
                            encryptedFileName = fileName,
                            encryptedByteLength = writtenByteLength,
                            createdAtMs = timestamp,
                            storageFormat = RevisionStorageFormat.ASSET_MANIFEST.name,
                        ),
                    )
                }
            }
            storePreviews(store, inputs, descriptors)
        } catch (error: Exception) {
            if (database.workflowDao().findRevision(document.id, document.revision) == null) {
                runCatching { files.delete(fileName) }
            }
            throw WorkflowStorageException("Could not commit workflow revision metadata", error)
        } finally {
            inputs.forEach { it.template.clear() }
        }

        RevisionSummary(document.id, document.revision, document.schemaVersion, timestamp)
    }

    /** Saves document-only changes while reusing an existing revision's shared asset mappings. */
    fun saveRevisionFromExistingAssets(
        sourceWorkflowId: String,
        sourceRevision: Int,
        document: WorkflowDocument,
        destinationFolderId: String? = null,
    ): RevisionSummary = synchronized(this) {
        val sourceMetadata = ensureManifestRevision(requireRevision(sourceWorkflowId, sourceRevision))
        val sourceManifest = readStoredManifest(sourceMetadata)
        val sourceMappings = database.workflowAssetDao().listRevisionAssets(sourceWorkflowId, sourceRevision)
        val manifest = StoredRevisionManifest(
            document = document,
            producerVersion = sourceManifest.producerVersion,
            assets = sourceManifest.assets,
        )
        val dao = database.workflowDao()
        val existingWorkflow = dao.findWorkflow(document.id)
        require(dao.findRevision(document.id, document.revision) == null) {
            "Workflow ${document.id} revision ${document.revision} already exists"
        }
        if (existingWorkflow != null) {
            require(document.revision > existingWorkflow.latestRevision) {
                "Workflow revisions must increase monotonically"
            }
        } else if (destinationFolderId != null) {
            requireValidFolderId(destinationFolderId)
            require(dao.findWorkflowFolder(destinationFolderId) != null) { "Workflow folder does not exist" }
        }

        val fileName = revisionManifestFileName(document.id, document.revision)
        val timestamp = storageTimestamp()
        try {
            if (files.exists(fileName)) files.delete(fileName)
            val byteLength = writeAndVerifyManifest(fileName, manifest)
            database.runInTransaction {
                dao.upsertWorkflow(
                    WorkflowEntity(
                        workflowId = document.id,
                        name = document.name,
                        latestRevision = document.revision,
                        createdAtMs = existingWorkflow?.createdAtMs ?: timestamp,
                        updatedAtMs = timestamp,
                        folderId = existingWorkflow?.folderId ?: destinationFolderId,
                        isFavorite = existingWorkflow?.isFavorite ?: false,
                        producerVersion = sourceManifest.producerVersion,
                        recordedOrientation = document.viewport.recordedOrientation.name,
                    ),
                )
                dao.insertRevision(
                    WorkflowRevisionEntity(
                        workflowId = document.id,
                        revision = document.revision,
                        schemaVersion = document.schemaVersion,
                        encryptedFileName = fileName,
                        encryptedByteLength = byteLength,
                        createdAtMs = timestamp,
                        storageFormat = RevisionStorageFormat.ASSET_MANIFEST.name,
                    ),
                )
                if (sourceMappings.isNotEmpty()) {
                    database.workflowAssetDao().insertRevisionAssets(
                        sourceMappings.map { mapping ->
                            mapping.copy(workflowId = document.id, revision = document.revision)
                        },
                    )
                }
            }
        } catch (error: Exception) {
            if (dao.findRevision(document.id, document.revision) == null) runCatching { files.delete(fileName) }
            throw WorkflowStorageException("Could not commit workflow revision metadata", error)
        }
        RevisionSummary(document.id, document.revision, document.schemaVersion, timestamp)
    }

    /** Saves only new or replaced images and reuses the remaining source-revision mappings. */
    fun saveRevisionFromAssetChanges(
        sourceWorkflowId: String,
        sourceRevision: Int,
        document: WorkflowDocument,
        changedAssets: List<BundleAsset>,
        destinationFolderId: String? = null,
    ): RevisionSummary = synchronized(this) {
        val sourceMetadata = ensureManifestRevision(requireRevision(sourceWorkflowId, sourceRevision))
        val sourceManifest = readStoredManifest(sourceMetadata)
        val sourceMappings = database.workflowAssetDao()
            .listRevisionAssets(sourceWorkflowId, sourceRevision)
            .associateBy(RevisionAssetEntity::assetId)
        val declarations = document.assets.associateBy { it.assetId }
        require(declarations.size == document.assets.size) { "Workflow assets must have unique IDs" }
        require(changedAssets.map(BundleAsset::assetId).distinct().size == changedAssets.size) {
            "Changed workflow assets must have unique IDs"
        }
        require(changedAssets.all { it.assetId in declarations }) {
            "Changed workflow assets must be declared by the workflow"
        }

        val changedIds = changedAssets.mapTo(mutableSetOf(), BundleAsset::assetId)
        val reusedMappings = document.assets
            .filterNot { it.assetId in changedIds }
            .map { declaration ->
                val source = sourceMappings[declaration.assetId.value]
                    ?: throw WorkflowStorageException("Workflow asset ${declaration.assetId.value} is unavailable")
                source.copy(
                    workflowId = document.id,
                    revision = document.revision,
                    displayName = declaration.displayName,
                    role = storedRole(source.widthPx, source.heightPx, document).name,
                )
            }
        val preparedChanges = prepareAssets(
            WorkflowBundle(document, changedAssets, sourceManifest.producerVersion),
        )
        val inputs = decodeRevisionAssets(preparedChanges)
        val dao = database.workflowDao()
        val existingWorkflow = dao.findWorkflow(document.id)
        require(dao.findRevision(document.id, document.revision) == null) {
            "Workflow ${document.id} revision ${document.revision} already exists"
        }
        if (existingWorkflow != null) {
            require(document.revision > existingWorkflow.latestRevision) {
                "Workflow revisions must increase monotonically"
            }
        } else if (destinationFolderId != null) {
            requireValidFolderId(destinationFolderId)
            require(dao.findWorkflowFolder(destinationFolderId) != null) { "Workflow folder does not exist" }
        }

        val fileName = revisionManifestFileName(document.id, document.revision)
        val timestamp = storageTimestamp()
        val store = requireNotNull(assetStore) { "Shared workflow asset store is unavailable" }
        try {
            if (files.exists(fileName)) files.delete(fileName)
            val changedDescriptors = store.storeRevisionAssets(document.id, document.revision, inputs) { storedChanges ->
                val descriptors = (reusedMappings.map { it.toStoredDescriptor() } + storedChanges)
                    .associateBy(StoredRevisionAssetDescriptor::assetId)
                val manifest = StoredRevisionManifest(
                    document = document,
                    producerVersion = sourceManifest.producerVersion,
                    assets = document.assets.map { declaration ->
                        requireNotNull(descriptors[declaration.assetId]) {
                            "Workflow asset ${declaration.assetId.value} could not be mapped"
                        }
                    },
                )
                val byteLength = writeAndVerifyManifest(fileName, manifest)
                dao.upsertWorkflow(
                    WorkflowEntity(
                        workflowId = document.id,
                        name = document.name,
                        latestRevision = document.revision,
                        createdAtMs = existingWorkflow?.createdAtMs ?: timestamp,
                        updatedAtMs = timestamp,
                        folderId = existingWorkflow?.folderId ?: destinationFolderId,
                        isFavorite = existingWorkflow?.isFavorite ?: false,
                        producerVersion = sourceManifest.producerVersion,
                        recordedOrientation = document.viewport.recordedOrientation.name,
                    ),
                )
                dao.insertRevision(
                    WorkflowRevisionEntity(
                        workflowId = document.id,
                        revision = document.revision,
                        schemaVersion = document.schemaVersion,
                        encryptedFileName = fileName,
                        encryptedByteLength = byteLength,
                        createdAtMs = timestamp,
                        storageFormat = RevisionStorageFormat.ASSET_MANIFEST.name,
                    ),
                )
                if (reusedMappings.isNotEmpty()) database.workflowAssetDao().insertRevisionAssets(reusedMappings)
            }
            storePreviews(store, inputs, changedDescriptors)
        } catch (error: Exception) {
            if (dao.findRevision(document.id, document.revision) == null) runCatching { files.delete(fileName) }
            throw WorkflowStorageException("Could not commit workflow revision metadata", error)
        } finally {
            inputs.forEach { it.template.clear() }
        }
        RevisionSummary(document.id, document.revision, document.schemaVersion, timestamp)
    }

    fun loadRevisionBundle(workflowId: String, revision: Int): WorkflowBundle = synchronized(this) {
        val metadata = ensureManifestRevision(requireRevision(workflowId, revision))
        val manifest = readStoredManifest(metadata)
        WorkflowBundle(
            manifest.document,
            manifest.assets.map { descriptor -> loadManifestAsset(metadata, descriptor.assetId) },
            manifest.producerVersion,
        ).also { bundle ->
            verifyRevisionIdentity(workflowId, revision, bundle.document)
            updateLatestOrientation(workflowId, revision, bundle.document)
        }
    }

    fun exportRevision(workflowId: String, revision: Int): ByteArray = synchronized(this) {
        VeyarcBundle.export(loadRevisionBundle(workflowId, revision))
    }

    fun loadRevisionDocument(workflowId: String, revision: Int): WorkflowRevisionDocument = synchronized(this) {
        loadEditorData(workflowId, revision).revision
    }

    fun listRevisionAssetDescriptors(workflowId: String, revision: Int): List<WorkflowAssetDescriptor> = synchronized(this) {
        loadEditorData(workflowId, revision).assets
    }

    /** Loads document and descriptors together so legacy archives are scanned only once. */
    fun loadRevisionForEditor(workflowId: String, revision: Int): WorkflowRevisionEditorData = synchronized(this) {
        loadEditorData(workflowId, revision)
    }

    fun loadRevisionAsset(
        workflowId: String,
        revision: Int,
        assetId: AssetId,
    ): BundleAsset = synchronized(this) {
        loadManifestAsset(ensureManifestRevision(requireRevision(workflowId, revision)), assetId)
    }

    fun loadRevisionAssetPreview(
        workflowId: String,
        revision: Int,
        assetId: AssetId,
    ): BundleAsset = synchronized(this) {
        val cacheKey = "$workflowId:$revision:${assetId.value}"
        previewCache[cacheKey]?.let { return@synchronized it }
        val metadata = ensureManifestRevision(requireRevision(workflowId, revision))
        val mapping = requireManifestAssetMapping(metadata, assetId)
        val decoded = readOrRebuildPreview(AssetBlobKey(mapping.blobKey))
        val preview = try {
            BundleAsset(
                assetId = assetId,
                kind = AssetKind.valueOf(mapping.kind),
                mimeType = mapping.mimeType,
                bytes = templateCodec.encrypt(assetId, decoded),
                encrypted = true,
                widthPx = decoded.widthPx,
                heightPx = decoded.heightPx,
            )
        } finally {
            decoded.clear()
        }
        previewCache[cacheKey] = preview
        preview
    }

    /** Returns a bounded decoded preview so editor UI does not encrypt and decrypt it again. */
    fun loadRevisionAssetPreviewDecoded(
        workflowId: String,
        revision: Int,
        assetId: AssetId,
    ): DecodedTemplate = synchronized(this) {
        val metadata = ensureManifestRevision(requireRevision(workflowId, revision))
        val mapping = requireManifestAssetMapping(metadata, assetId)
        readOrRebuildPreview(AssetBlobKey(mapping.blobKey))
    }

    fun prepareRunAssets(
        workflowId: String,
        revision: Int,
        assetIds: Set<AssetId>,
        maxDecodedBytes: Long,
    ): PreparedRunAssets = synchronized(this) {
        require(maxDecodedBytes > 0L) { "Run template memory budget must be positive" }
        if (assetIds.isEmpty()) return@synchronized PreparedRunAssets(emptyMap())

        val metadata = ensureManifestRevision(requireRevision(workflowId, revision))
        val mappings = assetIds.sortedBy(AssetId::value).map { assetId ->
            requireManifestAssetMapping(metadata, assetId)
        }
        val uniqueMappings = mappings.distinctBy(RevisionAssetEntity::blobKey)
        val estimatedBytes = uniqueMappings.sumOf { mapping ->
            Math.multiplyExact(Math.multiplyExact(mapping.widthPx.toLong(), mapping.heightPx.toLong()), 4L) +
                MATCHER_OVERHEAD_BYTES_PER_TEMPLATE
        }
        if (estimatedBytes > maxDecodedBytes) {
            throw WorkflowStorageException(
                "Workflow templates need ${formatMiB(estimatedBytes)} MiB of memory, " +
                    "above this device's ${formatMiB(maxDecodedBytes)} MiB preparation budget",
            )
        }

        val store = requireNotNull(assetStore) { "Shared workflow asset store is unavailable" }
        val templatesByBlob = linkedMapOf<String, DecodedTemplate>()
        try {
            uniqueMappings.forEach { mapping ->
                templatesByBlob[mapping.blobKey] = store.readTemplate(AssetBlobKey(mapping.blobKey))
            }
            PreparedRunAssets(
                mappings.associate { mapping ->
                    AssetId(mapping.assetId) to PreparedRunAsset(
                        role = WorkflowAssetRole.valueOf(mapping.role),
                        template = requireNotNull(templatesByBlob[mapping.blobKey]),
                    )
                },
            )
        } catch (error: Exception) {
            templatesByBlob.values.forEach(DecodedTemplate::clear)
            throw WorkflowStorageException("Could not prepare workflow image templates", error)
        }
    }

    fun reconcileStorage(maxEntries: Int = DEFAULT_MAINTENANCE_LIMIT): StorageMaintenanceReport = synchronized(this) {
        require(maxEntries > 0) { "Maintenance limit must be positive" }
        val report = requireNotNull(assetStore) { "Shared workflow asset store is unavailable" }.reconcile(maxEntries)
        val dao = database.workflowDao()
        val orphanedRevisionFiles = files.listCommittedFileNames(maxEntries).count { fileName ->
            if (!REVISION_FILE_PATTERN.matches(fileName) || dao.findRevisionByFileName(fileName) != null) {
                false
            } else {
                files.delete(fileName)
                true
            }
        }
        report.copy(
            orphanedFiles = report.orphanedFiles + orphanedRevisionFiles,
            staleTemporaryFiles = report.staleTemporaryFiles + files.deleteStaleTemporaryFiles(maxEntries),
        )
    }

    fun hasRevision(workflowId: String, revision: Int): Boolean = database.workflowDao().findRevision(workflowId, revision) != null

    fun listRevisionKeys(): Set<Pair<String, Int>> = database.workflowDao().listRevisionKeys()
        .mapTo(mutableSetOf()) { it.workflowId to it.revision }

    fun listWorkflowSummaries(): List<WorkflowSummary> = database.workflowDao().listWorkflows().map { workflow ->
        WorkflowSummary(
            workflowId = workflow.workflowId,
            name = workflow.name,
            latestRevision = workflow.latestRevision,
            createdAtMs = workflow.createdAtMs,
            updatedAtMs = workflow.updatedAtMs,
            folderId = workflow.folderId,
            isFavorite = workflow.isFavorite,
            producerVersion = workflow.producerVersion,
            recordedOrientation = workflow.recordedOrientation?.let { value ->
                runCatching { DisplayOrientation.valueOf(value) }.getOrNull()
            },
        )
    }

    fun listWorkflows(): List<WorkflowSummary> = listWorkflowSummaries()

    fun createWorkflowFolder(
        name: String,
        folderId: String = WorkflowIds.newUuid(),
    ): FolderSummary = synchronized(this) {
        val timestamp = storageTimestamp()
        val folder = WorkflowFolderEntity(
            folderId,
            validFolderName(name),
            timestamp,
            timestamp,
            sortOrder = database.workflowDao().nextWorkflowFolderSortOrder(),
        )
        requireValidFolderId(folderId)
        database.workflowDao().insertWorkflowFolder(folder)
        folder.toSummary()
    }

    fun listWorkflowFolders(): List<FolderSummary> = database.workflowDao().listWorkflowFolders().map { it.toSummary() }

    fun listWorkflowRunActivity(): Map<String, WorkflowRunActivity> = database.workflowDao().listWorkflowRunActivity().associate { row ->
        row.workflowId to WorkflowRunActivity(row.runCount, row.lastRunAtMs)
    }

    fun countRuns(): Long = database.workflowDao().countAllRuns()

    fun renameWorkflowFolder(folderId: String, name: String) = synchronized(this) {
        requireValidFolderId(folderId)
        check(
            database.workflowDao().renameWorkflowFolder(folderId, validFolderName(name), storageTimestamp()) == 1,
        ) { "Workflow folder does not exist" }
    }

    fun setWorkflowFolderColor(folderId: String, colorArgb: Int?) = synchronized(this) {
        requireValidFolderId(folderId)
        val dao = database.workflowDao()
        require(dao.findWorkflowFolder(folderId) != null) { "Workflow folder does not exist" }
        val owner = colorArgb?.let(dao::findWorkflowFolderByColor)
        require(owner == null || owner.folderId == folderId) { "That color is already used by another folder" }
        check(dao.setWorkflowFolderColor(folderId, colorArgb, storageTimestamp()) == 1) {
            "Workflow folder does not exist"
        }
    }

    fun reorderWorkflowFolders(folderIds: List<String>) = synchronized(this) {
        val dao = database.workflowDao()
        require(folderIds.toSet() == dao.listWorkflowFolders().mapTo(mutableSetOf()) { it.folderId }) {
            "Folder order must contain every workflow folder exactly once"
        }
        val timestamp = storageTimestamp()
        database.runInTransaction {
            folderIds.forEachIndexed { index, folderId ->
                check(dao.setWorkflowFolderSortOrder(folderId, index, timestamp) == 1) {
                    "Workflow folder does not exist"
                }
            }
        }
    }

    fun deleteWorkflowFolder(folderId: String) = synchronized(this) {
        requireValidFolderId(folderId)
        database.runInTransaction {
            database.workflowDao().clearWorkflowFolderAssignments(folderId)
            check(database.workflowDao().deleteWorkflowFolder(folderId) == 1) {
                "Workflow folder does not exist"
            }
        }
    }

    fun moveWorkflowToFolder(workflowId: String, folderId: String?) = synchronized(this) {
        val dao = database.workflowDao()
        require(dao.findWorkflow(workflowId) != null) { "Workflow does not exist" }
        if (folderId != null) {
            requireValidFolderId(folderId)
            require(dao.findWorkflowFolder(folderId) != null) { "Workflow folder does not exist" }
        }
        check(dao.moveWorkflowToFolder(workflowId, folderId) == 1) { "Workflow does not exist" }
    }

    fun renameRun(runId: String, name: String) = synchronized(this) {
        val validName = name.trim()
        require(validName.isNotEmpty() && validName.length <= 128) { "Run name must be 1 to 128 characters" }
        check(database.workflowDao().renameRun(runId, validName, StoredRunStatus.RUNNING.name) == 1) {
            "Run does not exist or is still active"
        }
    }

    fun setWorkflowFavorite(workflowId: String, favorite: Boolean) = synchronized(this) {
        check(database.workflowDao().setWorkflowFavorite(workflowId, favorite) == 1) {
            "Workflow does not exist"
        }
    }

    fun createRunFolder(
        name: String,
        folderId: String = WorkflowIds.newUuid(),
    ): FolderSummary = synchronized(this) {
        val timestamp = storageTimestamp()
        val folder = RunFolderEntity(
            folderId,
            validFolderName(name),
            timestamp,
            timestamp,
            database.workflowDao().nextRunFolderSortOrder(),
        )
        requireValidFolderId(folderId)
        database.workflowDao().insertRunFolder(folder)
        folder.toSummary()
    }

    fun listRunFolders(): List<FolderSummary> = database.workflowDao().listRunFolders().map { it.toSummary() }

    fun renameRunFolder(folderId: String, name: String) = synchronized(this) {
        requireValidFolderId(folderId)
        check(database.workflowDao().renameRunFolder(folderId, validFolderName(name), storageTimestamp()) == 1) {
            "Run folder does not exist"
        }
    }

    fun setRunFolderColor(folderId: String, colorArgb: Int?) = synchronized(this) {
        requireValidFolderId(folderId)
        val dao = database.workflowDao()
        require(dao.findRunFolder(folderId) != null) { "Run folder does not exist" }
        val owner = colorArgb?.let(dao::findRunFolderByColor)
        require(owner == null || owner.folderId == folderId) { "That color is already used by another run folder" }
        check(dao.setRunFolderColor(folderId, colorArgb, storageTimestamp()) == 1) {
            "Run folder does not exist"
        }
    }

    fun reorderRunFolders(folderIds: List<String>) = synchronized(this) {
        val dao = database.workflowDao()
        require(folderIds.toSet().size == folderIds.size) { "Run folders must be unique" }
        require(folderIds.toSet() == dao.listRunFolders().mapTo(mutableSetOf()) { it.folderId }) {
            "Run folder order must contain every folder"
        }
        val timestamp = storageTimestamp()
        database.runInTransaction {
            folderIds.forEachIndexed { index, folderId ->
                check(dao.setRunFolderSortOrder(folderId, index, timestamp) == 1) {
                    "Run folder does not exist"
                }
            }
        }
    }

    fun deleteRunFolder(folderId: String) = synchronized(this) {
        requireValidFolderId(folderId)
        database.runInTransaction {
            database.workflowDao().clearRunFolderAssignments(folderId)
            check(database.workflowDao().deleteRunFolder(folderId) == 1) { "Run folder does not exist" }
        }
    }

    fun moveRunToFolder(runId: String, folderId: String?) = synchronized(this) {
        val dao = database.workflowDao()
        val run = requireNotNull(dao.findRunRecord(runId)) { "Run record does not exist" }
        require(run.status != StoredRunStatus.RUNNING.name) { "Cannot move an active run" }
        if (folderId != null) {
            requireValidFolderId(folderId)
            require(dao.findRunFolder(folderId) != null) { "Run folder does not exist" }
        }
        check(dao.moveRunToFolder(runId, folderId, StoredRunStatus.RUNNING.name) == 1) {
            "Run record does not exist or is still running"
        }
    }

    fun listRevisions(workflowId: String): List<RevisionSummary> = database.workflowDao()
        .listRevisions(workflowId)
        .map { revision ->
            RevisionSummary(
                workflowId = revision.workflowId,
                revision = revision.revision,
                schemaVersion = revision.schemaVersion,
                createdAtMs = revision.createdAtMs,
            )
        }

    fun beginRun(
        workflowId: String,
        revision: Int,
        runId: String = WorkflowIds.newUuid(),
        startedAtMs: Long = nowMs(),
    ): String = synchronized(this) {
        require(database.workflowDao().findRevision(workflowId, revision) != null) {
            "Cannot start a run for an unknown workflow revision"
        }
        require(startedAtMs >= 0L) { "Run clock must be nonnegative" }
        database.workflowDao().insertRunRecord(
            RunRecordEntity(
                runId = runId,
                workflowId = workflowId,
                revision = revision,
                status = StoredRunStatus.RUNNING.name,
                startedAtMs = startedAtMs,
                finishedAtMs = null,
                interruptionReason = null,
            ),
        )
        runId
    }

    fun finishRun(
        runId: String,
        status: StoredRunStatus,
        finishedAtMs: Long = nowMs(),
        interruptionReason: String? = null,
        completedSteps: Int = 0,
        totalSteps: Long? = null,
        eventHistory: List<StoredRunEvent> = emptyList(),
    ) = synchronized(this) {
        require(status != StoredRunStatus.RUNNING) { "A running record must finish with a terminal status" }
        require(finishedAtMs >= 0L) { "Run clock must be nonnegative" }
        require(completedSteps >= 0) { "Completed step count must be nonnegative" }
        require(totalSteps == null || totalSteps >= completedSteps) {
            "Total step count must not be less than completed steps"
        }
        require(eventHistory.size <= STORED_RUN_EVENT_LIMIT) {
            "Run event history cannot exceed $STORED_RUN_EVENT_LIMIT entries"
        }
        val updated = database.workflowDao().finishRun(
            runId = runId,
            runningStatus = StoredRunStatus.RUNNING.name,
            status = status.name,
            finishedAtMs = finishedAtMs,
            interruptionReason = interruptionReason,
            completedSteps = completedSteps,
            totalSteps = totalSteps,
            eventHistoryJson = encodeRunEvents(eventHistory),
        )
        check(updated == 1) { "Run record is missing or already finished" }
    }

    fun recoverInterruptedRuns(
        recoveredAtMs: Long = nowMs(),
        reason: String = DEFAULT_INTERRUPTION_REASON,
    ): Int = synchronized(this) {
        require(recoveredAtMs >= 0L) { "Recovery clock must be nonnegative" }
        require(reason.isNotBlank() && reason.length <= MAX_REASON_LENGTH) {
            "Interruption reason must be 1 to $MAX_REASON_LENGTH characters"
        }
        database.workflowDao().markRunningAsInterrupted(
            runningStatus = StoredRunStatus.RUNNING.name,
            interruptedStatus = StoredRunStatus.INTERRUPTED.name,
            recoveredAtMs = recoveredAtMs,
            reason = reason,
        )
    }

    fun listInterruptedRuns(): List<StoredRunRecord> = database.workflowDao()
        .listRunRecords(StoredRunStatus.INTERRUPTED.name)
        .map(::toStoredRunRecord)

    fun listRunHistory(limit: Int = 100): List<StoredRunRecord> {
        require(limit in 1..MAX_HISTORY_LIMIT) {
            "Run history limit must be between 1 and $MAX_HISTORY_LIMIT"
        }
        return database.workflowDao().listAllRunRecords(limit).map(::toStoredRunRecord)
    }

    fun listRunSummaries(limit: Int = 100, offset: Int = 0): List<StoredRunRecord> {
        require(limit in 1..MAX_HISTORY_LIMIT) {
            "Run history limit must be between 1 and $MAX_HISTORY_LIMIT"
        }
        require(offset >= 0) { "Run history offset must be nonnegative" }
        return database.workflowDao().listRunSummaries(limit, offset).map(::toStoredRunSummary)
    }

    fun loadRunDetails(runId: String): StoredRunRecord = database.workflowDao().findRunRecord(runId)
        ?.let(::toStoredRunRecord)
        ?: throw WorkflowStorageException("Run record does not exist")

    fun listEarlierRunSummaries(before: StoredRunRecord, limit: Int): List<StoredRunRecord> {
        require(limit in 1..MAX_HISTORY_LIMIT) { "Run history limit must be between 1 and $MAX_HISTORY_LIMIT" }
        return database.workflowDao().listEarlierRunSummaries(before.startedAtMs, before.runId, limit).map(::toStoredRunSummary)
    }

    fun exportRun(runId: String): ByteArray = synchronized(this) {
        val record = loadRunDetails(runId)
        require(record.status != StoredRunStatus.RUNNING) { "Finish the run before exporting it." }
        val workflow = database.workflowDao().findWorkflow(record.workflowId)
        RunArchive.export(record, workflow?.name ?: record.name ?: "Workflow run")
    }

    /** Imports history only; workflow transfers remain separate and existing runs aren't overwritten. */
    fun importRun(input: InputStream): Boolean = synchronized(this) {
        val archive = RunArchive.import(input)
        val record = archive.record
        val dao = database.workflowDao()
        if (dao.findRunRecord(record.runId) != null) return@synchronized false
        dao.insertRunRecord(
            RunRecordEntity(
                runId = record.runId, workflowId = record.workflowId, revision = record.revision, status = record.status.name,
                startedAtMs = record.startedAtMs, finishedAtMs = record.finishedAtMs, interruptionReason = record.interruptionReason,
                completedSteps = record.completedSteps, totalSteps = record.totalSteps,
                eventHistoryJson = if (record.hasStoredDetails) encodeRunEvents(record.eventHistory) else null,
                name = record.name ?: archive.workflowName.takeIf { dao.findWorkflow(record.workflowId) == null },
            ),
        )
        true
    }

    fun loadRunDashboard(recentLimit: Int = 20): RunDashboardSummary = synchronized(this) {
        require(recentLimit in 1..MAX_HISTORY_LIMIT) {
            "Recent run limit must be between 1 and $MAX_HISTORY_LIMIT"
        }
        database.runInTransaction<RunDashboardSummary> {
            val dao = database.workflowDao()
            RunDashboardSummary(
                statusCounts = dao.countRunsByStatus().associate { runStatus(it.status) to it.count },
                averageFinishedRunDurationMs = dao.averageFinishedRunDurationMs()?.toLong(),
                recentRuns = dao.listRunSummaries(recentLimit).map(::toStoredRunSummary),
            )
        }
    }

    fun loadAggregateStatistics(): AggregateStatistics = database.runInTransaction<AggregateStatistics> {
        AggregateStatistics(listWorkflowSummaries(), database.workflowDao().storageStatistics(), database.workflowDao().runAggregates(), database.assistantUsageDao().summaries())
    }

    /** Deletes a terminal run record. Running records stay protected until they finish. */
    fun deleteRun(runId: String) = synchronized(this) {
        val deleted = database.workflowDao().deleteRun(
            runId = runId,
            runningStatus = StoredRunStatus.RUNNING.name,
        )
        check(deleted == 1) { "Run record is missing or still running" }
    }

    /** Clears finished history without mutating a run that is still active. */
    fun clearRunHistory(): Int = synchronized(this) {
        database.workflowDao().deleteTerminalRuns(StoredRunStatus.RUNNING.name)
    }

    /** Deletes a workflow, its revisions, encrypted files, and all associated run records. */
    fun deleteWorkflow(workflowId: String) = synchronized(this) {
        val dao = database.workflowDao()
        if (dao.findWorkflow(workflowId) == null) {
            throw WorkflowStorageException("Workflow does not exist")
        }
        require(
            dao.countRuns(workflowId, StoredRunStatus.RUNNING.name) == 0,
        ) { "Cannot delete a workflow while a run is active" }

        val revisions = dao.listRevisions(workflowId)
        val chats = database.chatDao().list().filter { it.scopeId == workflowId }
        try {
            database.runInTransaction {
                dao.deleteRunsForWorkflow(workflowId)
                database.chatDao().deleteForWorkflow(workflowId)
                dao.deleteRevisionsForWorkflow(workflowId)
                check(dao.deleteWorkflow(workflowId) == 1) {
                    "Workflow disappeared while it was being deleted"
                }
            }
            revisions.forEach { revision -> files.delete(revision.encryptedFileName) }
            chats.forEach { chat -> runCatching { chatFiles?.delete(chat.encryptedFileName) } }
            previewCache.keys.removeAll { it.startsWith("$workflowId:") }
            runCatching { assetStore?.collectGarbage() }
        } catch (error: Exception) {
            throw WorkflowStorageException("Could not delete workflow", error)
        }
    }

    fun close() {
        previewCache.clear()
        database.close()
    }

    private fun toStoredRunRecord(record: RunRecordEntity): StoredRunRecord = StoredRunRecord(
        runId = record.runId,
        workflowId = record.workflowId,
        revision = record.revision,
        status = runStatus(record.status),
        startedAtMs = record.startedAtMs,
        finishedAtMs = record.finishedAtMs,
        interruptionReason = record.interruptionReason,
        completedSteps = record.completedSteps,
        totalSteps = record.totalSteps,
        eventHistory = decodeRunEvents(record.eventHistoryJson),
        hasStoredDetails = record.eventHistoryJson != null,
        folderId = record.folderId,
        name = record.name,
    )

    private fun toStoredRunSummary(record: RunSummaryRow): StoredRunRecord = StoredRunRecord(
        runId = record.runId,
        workflowId = record.workflowId,
        revision = record.revision,
        status = runStatus(record.status),
        startedAtMs = record.startedAtMs,
        finishedAtMs = record.finishedAtMs,
        interruptionReason = record.interruptionReason,
        completedSteps = record.completedSteps,
        totalSteps = record.totalSteps,
        eventHistory = emptyList(),
        hasStoredDetails = record.hasStoredDetails,
        folderId = record.folderId,
        name = record.name,
    )

    private fun WorkflowFolderEntity.toSummary(): FolderSummary = FolderSummary(folderId, name, createdAtMs, updatedAtMs, colorArgb, sortOrder)

    private fun RunFolderEntity.toSummary(): FolderSummary = FolderSummary(folderId, name, createdAtMs, updatedAtMs, colorArgb, sortOrder)

    private fun storageTimestamp(): Long = nowMs().also { require(it >= 0L) { "Storage clock must be nonnegative" } }

    private fun validFolderName(name: String): String = name.trim().also {
        require(it.isNotEmpty() && it.length <= MAX_FOLDER_NAME_LENGTH) {
            "Folder name must be 1 to $MAX_FOLDER_NAME_LENGTH characters"
        }
    }

    private fun requireValidFolderId(folderId: String) {
        require(folderId.matches(UUID_PATTERN)) { "Folder id must be a UUID" }
    }

    private fun encodeRunEvents(events: List<StoredRunEvent>): String = JSONArray().apply {
        events.forEach { event ->
            put(
                JSONObject()
                    .put("atMs", event.atMs)
                    .put("message", event.message),
            )
        }
    }.toString()

    private fun decodeRunEvents(encoded: String?): List<StoredRunEvent> {
        if (encoded == null) return emptyList()
        return runCatching {
            val array = JSONArray(encoded)
            buildList(array.length()) {
                repeat(array.length()) { index ->
                    val item = array.getJSONObject(index)
                    add(
                        StoredRunEvent(
                            atMs = item.getLong("atMs"),
                            message = item.getString("message"),
                        ),
                    )
                }
            }
        }.getOrElse { emptyList() }
    }

    private fun runStatus(value: String): StoredRunStatus = runCatching {
        StoredRunStatus.valueOf(value)
    }.getOrElse { throw WorkflowStorageException("Unknown stored run status") }

    private fun loadEditorData(workflowId: String, revision: Int): WorkflowRevisionEditorData {
        val metadata = ensureManifestRevision(requireRevision(workflowId, revision))
        val manifest = readStoredManifest(metadata)
        val details = database.workflowAssetDao().listRevisionAssetDetails(workflowId, revision)
        val mappings = details.map { it.asset }
        validateManifestMappings(manifest, mappings)
        val result = WorkflowRevisionEditorData(
            WorkflowRevisionDocument(manifest.document, manifest.producerVersion),
            details.map { it.asset.toDescriptor().copy(addedAtMs = it.addedAtMs, byteCount = it.byteCount) },
        )
        verifyRevisionIdentity(workflowId, revision, result.revision.document)
        updateLatestOrientation(workflowId, revision, result.revision.document)
        return result
    }

    private fun requireRevision(workflowId: String, revision: Int): WorkflowRevisionEntity = database.workflowDao().findRevision(workflowId, revision)
        ?: throw WorkflowStorageException("Workflow revision does not exist")

    private fun WorkflowRevisionEntity.storageFormat(): RevisionStorageFormat = runCatching {
        RevisionStorageFormat.valueOf(storageFormat)
    }.getOrElse { throw WorkflowStorageException("Unknown workflow revision storage format") }

    private fun readRevisionBytes(metadata: WorkflowRevisionEntity): ByteArray = try {
        files.read(metadata.encryptedFileName, associatedData(metadata.workflowId, metadata.revision))
    } catch (error: Exception) {
        throw WorkflowStorageException("Could not decrypt workflow revision", error)
    }

    private fun importLegacyBundle(metadata: WorkflowRevisionEntity): WorkflowBundle {
        val bytes = readRevisionBytes(metadata)
        return try {
            VeyarcBundle.import(bytes)
        } catch (error: Exception) {
            throw WorkflowStorageException("Stored workflow revision is invalid", error)
        } finally {
            bytes.fill(0)
        }
    }

    private fun ensureManifestRevision(metadata: WorkflowRevisionEntity): WorkflowRevisionEntity = synchronized(MIGRATION_LOCK) {
        val authoritative = requireRevision(metadata.workflowId, metadata.revision)
        if (authoritative.storageFormat() == RevisionStorageFormat.ASSET_MANIFEST) return@synchronized authoritative

        val bundle = prepareAssets(importLegacyBundle(authoritative))
        verifyRevisionIdentity(authoritative.workflowId, authoritative.revision, bundle.document)
        val store = requireNotNull(assetStore) { "Shared workflow asset store is unavailable" }
        val inputs = decodeRevisionAssets(bundle)
        val manifestFileName = revisionManifestFileName(authoritative.workflowId, authoritative.revision)
        try {
            if (files.exists(manifestFileName)) files.delete(manifestFileName)
            val descriptors = store.storeRevisionAssets(authoritative.workflowId, authoritative.revision, inputs) { descriptors ->
                val manifest = StoredRevisionManifest(
                    document = bundle.document,
                    producerVersion = bundle.producerVersion,
                    assets = descriptors,
                )
                // Preserve old revisions so invalid image areas can still be opened and repaired.
                val byteLength = writeAndVerifyManifest(manifestFileName, manifest, validateImageBounds = false)
                check(
                    database.workflowDao().replaceRevisionStorage(
                        workflowId = authoritative.workflowId,
                        revision = authoritative.revision,
                        encryptedFileName = manifestFileName,
                        encryptedByteLength = byteLength,
                        storageFormat = RevisionStorageFormat.ASSET_MANIFEST.name,
                        expectedStorageFormat = RevisionStorageFormat.LEGACY_BUNDLE.name,
                    ) == 1,
                ) { "Workflow revision changed while it was being migrated" }
            }
            storePreviews(store, inputs, descriptors)
        } catch (error: Exception) {
            val current = requireRevision(authoritative.workflowId, authoritative.revision)
            if (current.storageFormat() == RevisionStorageFormat.LEGACY_BUNDLE) {
                runCatching { files.delete(manifestFileName) }
            }
            throw WorkflowStorageException("Could not migrate legacy workflow revision", error)
        } finally {
            inputs.forEach { it.template.clear() }
        }

        val migrated = requireRevision(authoritative.workflowId, authoritative.revision)
        val manifest = readStoredManifest(migrated)
        validateManifestMappings(
            manifest,
            database.workflowAssetDao().listRevisionAssets(authoritative.workflowId, authoritative.revision),
        )
        runCatching { files.delete(authoritative.encryptedFileName) }
        return migrated
    }

    private fun decodeRevisionAssets(bundle: WorkflowBundle): List<RevisionAssetInput> {
        val displayNames = bundle.document.assets.associate { it.assetId to it.displayName }
        val inputs = mutableListOf<RevisionAssetInput>()
        try {
            bundle.assets.forEach { asset ->
                val template = templateCodec.decrypt(asset.assetId, asset.bytes)
                inputs += RevisionAssetInput(
                    assetId = asset.assetId,
                    displayName = displayNames[asset.assetId],
                    role = if (
                        template.widthPx == bundle.document.viewport.recordedWidthPx &&
                        template.heightPx == bundle.document.viewport.recordedHeightPx
                    ) {
                        StoredTemplateRole.FULL_SCREEN
                    } else {
                        StoredTemplateRole.CROPPED_TARGET
                    },
                    mimeType = asset.mimeType,
                    template = template,
                )
            }
            return inputs
        } catch (error: Exception) {
            inputs.forEach { it.template.clear() }
            throw WorkflowStorageException("Could not decode workflow assets for storage", error)
        }
    }

    private fun storePreviews(
        store: WorkflowAssetBlobStore,
        inputs: List<RevisionAssetInput>,
        descriptors: List<StoredRevisionAssetDescriptor>,
    ) {
        val inputsById = inputs.associateBy { it.assetId }
        descriptors.distinctBy { it.blobKey }.forEach { descriptor ->
            runCatching {
                store.putGeneratedPreviewIfAbsent(
                    descriptor.blobKey,
                    requireNotNull(inputsById[descriptor.assetId]).template,
                )
            }
        }
    }

    private fun writeAndVerifyManifest(
        fileName: String,
        manifest: StoredRevisionManifest,
        validateImageBounds: Boolean = true,
    ): Long {
        if (validateImageBounds) {
            val dimensions = manifest.assets.associateBy { it.assetId }
            val verified = manifest.document.copy(
                assets = manifest.document.assets.map { reference ->
                    val asset = requireNotNull(dimensions[reference.assetId])
                    require(reference.widthPx == null || (reference.widthPx == asset.widthPx && reference.heightPx == asset.heightPx)) { "Image dimensions do not match stored pixels" }
                    reference.copy(widthPx = asset.widthPx, heightPx = asset.heightPx)
                },
            )
            val issues = com.veyarc.model.WorkflowValidator.validate(verified)
            require(issues.isEmpty()) { issues.joinToString { it.message } }
        }
        val canonical = manifest.copy(assets = manifest.assets.sortedBy { it.assetId.value })
        val encoded = StoredRevisionManifestCodec.encode(canonical)
        val byteLength = try {
            files.writeNew(
                fileName,
                encoded,
                associatedData(manifest.document.id, manifest.document.revision),
            )
        } finally {
            encoded.fill(0)
        }
        val verification = files.read(
            fileName,
            associatedData(manifest.document.id, manifest.document.revision),
        )
        try {
            check(StoredRevisionManifestCodec.decode(verification) == canonical) {
                "Stored workflow manifest failed verification"
            }
        } finally {
            verification.fill(0)
        }
        return byteLength
    }

    private fun readStoredManifest(metadata: WorkflowRevisionEntity): StoredRevisionManifest {
        val bytes = readRevisionBytes(metadata)
        return try {
            StoredRevisionManifestCodec.decode(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    private fun loadManifestAsset(metadata: WorkflowRevisionEntity, assetId: AssetId): BundleAsset {
        val mapping = requireManifestAssetMapping(metadata, assetId)
        val decoded = requireNotNull(assetStore) { "Shared workflow asset store is unavailable" }
            .readTemplate(AssetBlobKey(mapping.blobKey))
        return try {
            BundleAsset(
                assetId = assetId,
                kind = AssetKind.valueOf(mapping.kind),
                mimeType = mapping.mimeType,
                bytes = templateCodec.encrypt(assetId, decoded),
                encrypted = true,
                widthPx = decoded.widthPx,
                heightPx = decoded.heightPx,
            )
        } finally {
            decoded.clear()
        }
    }

    private fun readOrRebuildPreview(blobKey: AssetBlobKey): DecodedTemplate {
        val store = requireNotNull(assetStore) { "Shared workflow asset store is unavailable" }
        return store.readPreview(blobKey) ?: store.readTemplate(blobKey).let { full ->
            try {
                store.putGeneratedPreviewIfAbsent(blobKey, full)
            } finally {
                full.clear()
            }
            requireNotNull(store.readPreview(blobKey)) { "Stored asset preview could not be rebuilt" }
        }
    }

    private fun requireManifestAssetMapping(
        metadata: WorkflowRevisionEntity,
        assetId: AssetId,
    ): RevisionAssetEntity = database.workflowAssetDao()
        .findRevisionAsset(metadata.workflowId, metadata.revision, assetId.value)
        ?: throw WorkflowStorageException("Workflow revision does not declare asset ${assetId.value}")

    private fun validateManifestMappings(
        manifest: StoredRevisionManifest,
        mappings: List<RevisionAssetEntity>,
    ) {
        val expected = manifest.assets.associateBy { it.assetId.value }
        val expectedNames = manifest.document.assets.associate { it.assetId.value to it.displayName }
        require(
            expected.size == mappings.size && mappings.all { mapping ->
                val descriptor = expected[mapping.assetId]
                descriptor != null &&
                    mapping.displayName == expectedNames[mapping.assetId] &&
                    descriptor.blobKey.value == mapping.blobKey &&
                    descriptor.kind.name == mapping.kind &&
                    descriptor.role.name == mapping.role &&
                    descriptor.mimeType == mapping.mimeType &&
                    descriptor.widthPx == mapping.widthPx &&
                    descriptor.heightPx == mapping.heightPx
            },
        ) { "Stored workflow asset mappings do not match the revision manifest" }
    }

    private fun RevisionAssetEntity.toDescriptor() = WorkflowAssetDescriptor(
        assetId = AssetId(assetId),
        kind = AssetKind.valueOf(kind),
        displayName = displayName,
        mimeType = mimeType,
        widthPx = widthPx,
        heightPx = heightPx,
    )

    private fun RevisionAssetEntity.toStoredDescriptor() = StoredRevisionAssetDescriptor(
        assetId = AssetId(assetId),
        kind = AssetKind.valueOf(kind),
        blobKey = AssetBlobKey(blobKey),
        role = StoredTemplateRole.valueOf(role),
        mimeType = mimeType,
        widthPx = widthPx,
        heightPx = heightPx,
    )

    private fun storedRole(widthPx: Int, heightPx: Int, document: WorkflowDocument): StoredTemplateRole = if (widthPx == document.viewport.recordedWidthPx && heightPx == document.viewport.recordedHeightPx) {
        StoredTemplateRole.FULL_SCREEN
    } else {
        StoredTemplateRole.CROPPED_TARGET
    }

    private fun verifyRevisionIdentity(workflowId: String, revision: Int, document: WorkflowDocument) {
        require(document.id == workflowId && document.revision == revision) {
            "Stored workflow revision identity does not match its metadata"
        }
    }

    private fun updateLatestOrientation(workflowId: String, revision: Int, document: WorkflowDocument) {
        database.workflowDao().findWorkflow(workflowId)
            ?.takeIf { it.latestRevision == revision && it.recordedOrientation != document.viewport.recordedOrientation.name }
            ?.let { database.workflowDao().setWorkflowOrientation(workflowId, document.viewport.recordedOrientation.name) }
    }

    private fun prepareAssets(bundle: WorkflowBundle): WorkflowBundle {
        try {
            val prepared = bundle.assets.map(::prepareAsset)
            return if (prepared.indices.all { prepared[it] === bundle.assets[it] }) bundle else bundle.copy(assets = prepared)
        } catch (error: Exception) {
            throw WorkflowStorageException("Workflow asset integrity check failed", error)
        }
    }

    private fun prepareAsset(asset: BundleAsset): BundleAsset {
        require(asset.kind == AssetKind.IMAGE_TEMPLATE && asset.encrypted) {
            "Workflow assets must be encrypted image templates"
        }
        val verification = templateCodec.verify(asset.assetId, asset.bytes)
        return if (
            verification.upgradedBytes == null &&
            asset.widthPx == verification.widthPx &&
            asset.heightPx == verification.heightPx
        ) {
            asset
        } else {
            BundleAsset(
                assetId = asset.assetId,
                kind = asset.kind,
                mimeType = asset.mimeType,
                bytes = verification.upgradedBytes ?: asset.bytes,
                encrypted = true,
                widthPx = verification.widthPx,
                heightPx = verification.heightPx,
            )
        }
    }

    private companion object {
        const val WORKFLOW_DIRECTORY = "workflows"
        const val DEFAULT_INTERRUPTION_REASON = "Run was interrupted when the process ended"
        const val MAX_REASON_LENGTH = 256
        const val MAX_HISTORY_LIMIT = 1_000
        const val MAX_FOLDER_NAME_LENGTH = 80
        const val PREVIEW_CACHE_SIZE = 16
        const val MATCHER_OVERHEAD_BYTES_PER_TEMPLATE = (16L * 16L + 16L) * 4L * Int.SIZE_BYTES
        const val DEFAULT_MAINTENANCE_LIMIT = 256
        val MIGRATION_LOCK = Any()

        fun formatMiB(bytes: Long): String = "%.1f".format(java.util.Locale.ROOT, bytes / (1024.0 * 1024.0))

        fun revisionFileName(workflowId: String, revision: Int): String {
            require(revision > 0) { "Workflow revision must be positive" }
            require(workflowId.matches(UUID_PATTERN)) { "Workflow id must be a UUID" }
            return "workflow-$workflowId-revision-$revision.wfenc"
        }

        fun revisionManifestFileName(workflowId: String, revision: Int): String {
            require(revision > 0) { "Workflow revision must be positive" }
            require(workflowId.matches(UUID_PATTERN)) { "Workflow id must be a UUID" }
            return "workflow-$workflowId-revision-$revision-manifest.wfenc"
        }

        fun associatedData(workflowId: String, revision: Int): ByteArray = "workflow:$workflowId:revision:$revision".encodeToByteArray()

        val UUID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        val REVISION_FILE_PATTERN = Regex(
            "workflow-${UUID_PATTERN.pattern}-revision-[1-9][0-9]*(-manifest)?\\.wfenc",
        )
    }
}
