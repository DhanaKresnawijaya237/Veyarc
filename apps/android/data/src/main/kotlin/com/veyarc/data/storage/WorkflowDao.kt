package com.veyarc.data.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert

@Dao
internal interface WorkflowDao {
    @Query("SELECT * FROM workflows ORDER BY updatedAtMs DESC, workflowId ASC")
    fun listWorkflows(): List<WorkflowEntity>

    @Query("SELECT * FROM workflows WHERE workflowId = :workflowId")
    fun findWorkflow(workflowId: String): WorkflowEntity?

    @Upsert
    fun upsertWorkflow(workflow: WorkflowEntity)

    @Query("UPDATE workflows SET folderId = :folderId WHERE workflowId = :workflowId")
    fun moveWorkflowToFolder(workflowId: String, folderId: String?): Int

    @Query("UPDATE workflows SET isFavorite = :favorite WHERE workflowId = :workflowId")
    fun setWorkflowFavorite(workflowId: String, favorite: Boolean): Int

    @Query("SELECT * FROM workflow_folders ORDER BY name COLLATE NOCASE, folderId")
    fun listWorkflowFolders(): List<WorkflowFolderEntity>

    @Query("SELECT * FROM workflow_folders WHERE folderId = :folderId")
    fun findWorkflowFolder(folderId: String): WorkflowFolderEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertWorkflowFolder(folder: WorkflowFolderEntity)

    @Query("UPDATE workflow_folders SET name = :name, updatedAtMs = :updatedAtMs WHERE folderId = :folderId")
    fun renameWorkflowFolder(folderId: String, name: String, updatedAtMs: Long): Int

    @Query("UPDATE workflows SET folderId = NULL WHERE folderId = :folderId")
    fun clearWorkflowFolderAssignments(folderId: String): Int

    @Query("DELETE FROM workflow_folders WHERE folderId = :folderId")
    fun deleteWorkflowFolder(folderId: String): Int

    @Query(
        "SELECT * FROM workflow_revisions " +
            "WHERE workflowId = :workflowId ORDER BY revision DESC",
    )
    fun listRevisions(workflowId: String): List<WorkflowRevisionEntity>

    @Query(
        "SELECT * FROM workflow_revisions " +
            "WHERE workflowId = :workflowId AND revision = :revision",
    )
    fun findRevision(workflowId: String, revision: Int): WorkflowRevisionEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertRevision(revision: WorkflowRevisionEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertRunRecord(record: RunRecordEntity)

    @Query("SELECT * FROM run_records WHERE runId = :runId")
    fun findRunRecord(runId: String): RunRecordEntity?

    @Query("UPDATE run_records SET folderId = :folderId WHERE runId = :runId AND status != :runningStatus")
    fun moveRunToFolder(runId: String, folderId: String?, runningStatus: String): Int

    @Query("SELECT * FROM run_folders ORDER BY name COLLATE NOCASE, folderId")
    fun listRunFolders(): List<RunFolderEntity>

    @Query("SELECT * FROM run_folders WHERE folderId = :folderId")
    fun findRunFolder(folderId: String): RunFolderEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertRunFolder(folder: RunFolderEntity)

    @Query("UPDATE run_folders SET name = :name, updatedAtMs = :updatedAtMs WHERE folderId = :folderId")
    fun renameRunFolder(folderId: String, name: String, updatedAtMs: Long): Int

    @Query("UPDATE run_records SET folderId = NULL WHERE folderId = :folderId")
    fun clearRunFolderAssignments(folderId: String): Int

    @Query("DELETE FROM run_folders WHERE folderId = :folderId")
    fun deleteRunFolder(folderId: String): Int

    @Query(
        "UPDATE run_records SET status = :status, finishedAtMs = :finishedAtMs, " +
            "interruptionReason = :interruptionReason, completedSteps = :completedSteps, " +
            "totalSteps = :totalSteps, eventHistoryJson = :eventHistoryJson " +
            "WHERE runId = :runId AND status = :runningStatus",
    )
    fun finishRun(
        runId: String,
        runningStatus: String,
        status: String,
        finishedAtMs: Long,
        interruptionReason: String?,
        completedSteps: Int,
        totalSteps: Long?,
        eventHistoryJson: String?,
    ): Int

    @Query(
        "UPDATE run_records SET status = :interruptedStatus, finishedAtMs = :recoveredAtMs, " +
            "interruptionReason = :reason WHERE status = :runningStatus",
    )
    fun markRunningAsInterrupted(
        runningStatus: String,
        interruptedStatus: String,
        recoveredAtMs: Long,
        reason: String,
    ): Int

    @Query("SELECT * FROM run_records WHERE status = :status ORDER BY startedAtMs DESC")
    fun listRunRecords(status: String): List<RunRecordEntity>

    @Query("SELECT * FROM run_records ORDER BY startedAtMs DESC LIMIT :limit")
    fun listAllRunRecords(limit: Int): List<RunRecordEntity>

    @Query("SELECT status, COUNT(*) AS count FROM run_records GROUP BY status")
    fun countRunsByStatus(): List<RunStatusCountRow>

    @Query(
        "SELECT AVG(finishedAtMs - startedAtMs) FROM run_records " +
            "WHERE finishedAtMs IS NOT NULL AND finishedAtMs >= startedAtMs",
    )
    fun averageFinishedRunDurationMs(): Double?

    @Query(
        "SELECT workflowId, COUNT(*) AS runCount, MAX(startedAtMs) AS lastRunAtMs " +
            "FROM run_records GROUP BY workflowId",
    )
    fun listWorkflowRunActivity(): List<WorkflowRunActivityRow>

    @Query(
        "SELECT COUNT(*) FROM run_records " +
            "WHERE workflowId = :workflowId AND status = :status",
    )
    fun countRuns(workflowId: String, status: String): Int

    @Query("DELETE FROM run_records WHERE workflowId = :workflowId")
    fun deleteRunsForWorkflow(workflowId: String): Int

    @Query("DELETE FROM workflow_revisions WHERE workflowId = :workflowId")
    fun deleteRevisionsForWorkflow(workflowId: String): Int

    @Query("DELETE FROM workflows WHERE workflowId = :workflowId")
    fun deleteWorkflow(workflowId: String): Int

    @Query(
        "DELETE FROM run_records " +
            "WHERE runId = :runId AND status != :runningStatus",
    )
    fun deleteRun(runId: String, runningStatus: String): Int
}
