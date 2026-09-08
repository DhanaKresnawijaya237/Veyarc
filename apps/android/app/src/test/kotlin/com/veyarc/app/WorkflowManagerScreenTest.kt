package com.veyarc.app

import com.veyarc.data.storage.WorkflowRunActivity
import com.veyarc.data.storage.WorkflowSummary
import com.veyarc.model.DisplayOrientation
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkflowManagerScreenTest {
    @Test
    fun filtersCollectionsAndSortsByRunActivity() {
        val alpha = workflow("alpha", "Alpha", folderId = null, favorite = true, runs = 2, lastRun = 20)
        val beta = workflow("beta", "Beta", folderId = "folder", favorite = false, runs = 5, lastRun = 10)

        assertEquals(
            listOf("alpha"),
            filterAndSortWorkflows(
                listOf(beta, alpha),
                FAVORITES_VIEW,
                "alp",
                WorkflowSortOption.MOST_RUN,
                WorkflowOrientationFilter.PORTRAIT,
            ).map { it.summary.workflowId },
        )
        assertEquals(
            listOf("beta", "alpha"),
            filterAndSortWorkflows(
                listOf(alpha, beta),
                ALL_VIEW,
                "",
                WorkflowSortOption.MOST_RUN,
                WorkflowOrientationFilter.ANY,
            ).map { it.summary.workflowId },
        )
    }

    private fun workflow(
        id: String,
        name: String,
        folderId: String?,
        favorite: Boolean,
        runs: Long,
        lastRun: Long,
    ) = ManagedWorkflow(
        summary = WorkflowSummary(id, name, 1, 0, 0, folderId, favorite),
        orientation = DisplayOrientation.PORTRAIT,
        activity = WorkflowRunActivity(runs, lastRun),
    )
}
