package com.veyarc.data.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
internal interface WorkflowDao {
    @Query("SELECT * FROM workflows ORDER BY updatedAtMs DESC, workflowId ASC")
    fun listWorkflows(): List<WorkflowEntity>

    @Query("SELECT * FROM workflows WHERE workflowId = :workflowId")
    fun findWorkflow(workflowId: String): WorkflowEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertWorkflow(workflow: WorkflowEntity)

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
