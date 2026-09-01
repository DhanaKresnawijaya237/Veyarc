package com.veyarc.data.storage

import android.content.Context
import com.veyarc.model.WorkflowIds
import com.veyarc.model.bundle.VeyarcBundle
import com.veyarc.model.bundle.WorkflowBundle
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class WorkflowSummary(
    val workflowId: String,
    val name: String,
    val latestRevision: Int,
    val createdAtMs: Long,
    val updatedAtMs: Long,
)

data class RevisionSummary(
    val workflowId: String,
    val revision: Int,
    val schemaVersion: Int,
    val createdAtMs: Long,
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
)

data class StoredRunEvent(
    val atMs: Long,
    val message: String,
)

class WorkflowStorageException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Local encrypted workflow storage with Room-backed metadata and revision history. */
class WorkflowRepository internal constructor(
    private val database: VeyarcDatabase,
    private val files: EncryptedFileStore,
    private val nowMs: () -> Long,
) {
    constructor(
        context: Context,
        nowMs: () -> Long = { System.currentTimeMillis() },
    ) : this(
        database = VeyarcDatabase.create(context),
        files = EncryptedFileStore(
            directory = File(context.applicationContext.filesDir, WORKFLOW_DIRECTORY),
            codec = AesGcmFileCodec(AndroidKeystoreKeySource()),
        ),
        nowMs = nowMs,
    )

    fun saveRevision(bundle: WorkflowBundle): RevisionSummary = synchronized(this) {
        val document = bundle.document
        val existingWorkflow = database.workflowDao().findWorkflow(document.id)
        val existingRevision = database.workflowDao().findRevision(document.id, document.revision)
        require(existingRevision == null) {
            "Workflow ${document.id} revision ${document.revision} already exists"
        }
        if (existingWorkflow != null) {
            require(document.revision > existingWorkflow.latestRevision) {
                "Workflow revisions must increase monotonically"
            }
        }

        val fileName = revisionFileName(document.id, document.revision)
        val encryptedBytes = try {
            VeyarcBundle.export(bundle)
        } catch (error: Exception) {
            throw WorkflowStorageException("Could not serialize workflow revision", error)
        }
        val writtenByteLength = try {
            files.writeNew(fileName, encryptedBytes, associatedData(document.id, document.revision))
        } catch (error: Exception) {
            throw WorkflowStorageException("Could not encrypt workflow revision", error)
        }
        val timestamp = nowMs().also { require(it >= 0L) { "Storage clock must be nonnegative" } }

        try {
            database.runInTransaction {
                val createdAt = existingWorkflow?.createdAtMs ?: timestamp
                database.workflowDao().upsertWorkflow(
                    WorkflowEntity(
                        workflowId = document.id,
                        name = document.name,
                        latestRevision = document.revision,
                        createdAtMs = createdAt,
                        updatedAtMs = timestamp,
                    ),
                )
                database.workflowDao().insertRevision(
                    WorkflowRevisionEntity(
                        workflowId = document.id,
                        revision = document.revision,
                        schemaVersion = document.schemaVersion,
                        encryptedFileName = fileName,
                        encryptedByteLength = writtenByteLength,
                        createdAtMs = timestamp,
                    ),
                )
            }
        } catch (error: Exception) {
            runCatching { files.delete(fileName) }
            throw WorkflowStorageException("Could not commit workflow revision metadata", error)
        }

        RevisionSummary(document.id, document.revision, document.schemaVersion, timestamp)
    }

    fun loadRevision(workflowId: String, revision: Int): WorkflowBundle = synchronized(this) {
        val metadata = database.workflowDao().findRevision(workflowId, revision)
            ?: throw WorkflowStorageException("Workflow revision does not exist")
        val encryptedBytes = try {
            files.read(metadata.encryptedFileName, associatedData(workflowId, revision))
        } catch (error: Exception) {
            throw WorkflowStorageException("Could not decrypt workflow revision", error)
        }
        val bundle = try {
            VeyarcBundle.import(encryptedBytes)
        } catch (error: Exception) {
            throw WorkflowStorageException("Stored workflow revision is invalid", error)
        }
        require(bundle.document.id == workflowId && bundle.document.revision == revision) {
            "Stored workflow revision identity does not match its metadata"
        }
        bundle
    }

    fun listWorkflows(): List<WorkflowSummary> = database.workflowDao().listWorkflows().map { workflow ->
        WorkflowSummary(
            workflowId = workflow.workflowId,
            name = workflow.name,
            latestRevision = workflow.latestRevision,
            createdAtMs = workflow.createdAtMs,
            updatedAtMs = workflow.updatedAtMs,
        )
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
        require(eventHistory.size <= MAX_STORED_RUN_EVENTS) {
            "Run event history cannot exceed $MAX_STORED_RUN_EVENTS entries"
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

    /** Deletes a terminal run record. Running records stay protected until they finish. */
    fun deleteRun(runId: String) = synchronized(this) {
        val deleted = database.workflowDao().deleteRun(
            runId = runId,
            runningStatus = StoredRunStatus.RUNNING.name,
        )
        check(deleted == 1) { "Run record is missing or still running" }
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
        try {
            database.runInTransaction {
                dao.deleteRunsForWorkflow(workflowId)
                dao.deleteRevisionsForWorkflow(workflowId)
                check(dao.deleteWorkflow(workflowId) == 1) {
                    "Workflow disappeared while it was being deleted"
                }
            }
            revisions.forEach { revision -> files.delete(revision.encryptedFileName) }
        } catch (error: Exception) {
            throw WorkflowStorageException("Could not delete workflow", error)
        }
    }

    fun close() {
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
    )

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

    private companion object {
        const val WORKFLOW_DIRECTORY = "workflows"
        const val DEFAULT_INTERRUPTION_REASON = "Run was interrupted when the process ended"
        const val MAX_REASON_LENGTH = 256
        const val MAX_HISTORY_LIMIT = 1_000
        const val MAX_STORED_RUN_EVENTS = 50

        fun revisionFileName(workflowId: String, revision: Int): String {
            require(revision > 0) { "Workflow revision must be positive" }
            require(workflowId.matches(UUID_PATTERN)) { "Workflow id must be a UUID" }
            return "workflow-$workflowId-revision-$revision.wfenc"
        }

        fun associatedData(workflowId: String, revision: Int): ByteArray = "workflow:$workflowId:revision:$revision".encodeToByteArray()

        val UUID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    }
}
