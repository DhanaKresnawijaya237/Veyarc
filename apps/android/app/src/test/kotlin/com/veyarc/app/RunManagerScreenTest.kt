package com.veyarc.app

import com.veyarc.data.storage.StoredRunRecord
import com.veyarc.data.storage.StoredRunStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class RunManagerScreenTest {
    @Test
    fun filtersRunViewsAndNeverKeepsActiveRunsSelected() {
        val completed = run("complete", "workflow-a", "Alpha", StoredRunStatus.COMPLETED, 900, 1_900, "folder", 2)
        val active = run("active", "workflow-a", "Alpha", StoredRunStatus.RUNNING, 1_000, null, null, 2)
        val failed = run("failed", "workflow-b", "Beta", StoredRunStatus.FAILED, 800, 3_800, null, 3)

        assertEquals(
            listOf("complete"),
            filterAndSortRuns(
                runs = listOf(active, failed, completed),
                selectedView = "folder:folder",
                query = "alpha",
                status = StoredRunStatus.COMPLETED,
                workflowId = "workflow-a",
                date = RunDateFilter.WEEK,
                revision = 2,
                sort = RunSortOption.NEWEST,
                nowMs = 4_000,
            ).map { it.record.runId },
        )
        assertEquals(
            setOf("complete"),
            terminalRunIds(setOf("complete", "active", "missing"), listOf(completed, active, failed)),
        )
    }

    private fun run(
        id: String,
        workflowId: String,
        workflowName: String,
        status: StoredRunStatus,
        startedAtMs: Long,
        finishedAtMs: Long?,
        folderId: String?,
        revision: Int,
    ) = ManagedRun(
        record = StoredRunRecord(
            runId = id,
            workflowId = workflowId,
            revision = revision,
            status = status,
            startedAtMs = startedAtMs,
            finishedAtMs = finishedAtMs,
            interruptionReason = null,
            completedSteps = 0,
            totalSteps = 0,
            eventHistory = emptyList(),
            hasStoredDetails = true,
            folderId = folderId,
        ),
        workflowName = workflowName,
    )
}
