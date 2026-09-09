package com.veyarc.data.storage

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.veyarc.model.DisplayOrientation
import com.veyarc.model.NormalizedCoordinate
import com.veyarc.model.PackageName
import com.veyarc.model.RunLimits
import com.veyarc.model.TapPoint
import com.veyarc.model.ViewportSpec
import com.veyarc.model.WorkflowDocument
import com.veyarc.model.bundle.WorkflowBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import javax.crypto.spec.SecretKeySpec

@RunWith(AndroidJUnit4::class)
class WorkflowRepositoryTest {
    @Test
    fun persistsEncryptedRevisionsAndRecoversInterruptedRuns() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, VeyarcDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val directory = File(context.cacheDir, "storage-test-${System.nanoTime()}").apply { mkdirs() }
        val keySource = SecretKeySource {
            SecretKeySpec(ByteArray(32) { 7 }, "AES")
        }
        val repository = WorkflowRepository(
            database = database,
            files = EncryptedFileStore(directory, AesGcmFileCodec(keySource)),
            nowMs = { 1000L },
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
            assertEquals(listOf(2, 1), repository.listRevisions(WORKFLOW_ID).map { it.revision })
            assertEquals(2, repository.loadRevision(WORKFLOW_ID, 2).document.revision)

            val runId = repository.beginRun(WORKFLOW_ID, 2, runId = RUN_ID, startedAtMs = 2000L)
            assertEquals(RUN_ID, runId)
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
            val dashboard = repository.loadRunDashboard()
            assertEquals(2L, dashboard.totalRuns)
            assertEquals(2L, dashboard.terminalRuns)
            assertEquals(0.0, dashboard.completionRate, 0.0)
            assertEquals(1000L, dashboard.averageFinishedRunDurationMs)
            assertEquals(listOf(DETAILED_RUN_ID, RUN_ID), dashboard.recentRuns.map { it.runId })
            val activity = repository.listWorkflowRunActivity().getValue(WORKFLOW_ID)
            assertEquals(2L, activity.runCount)
            assertEquals(4000L, activity.lastRunAtMs)

            repository.deleteWorkflowFolder(WORKFLOW_FOLDER_ID)
            assertTrue(repository.listWorkflowFolders().isEmpty())
            assertEquals(null, repository.listWorkflows().single().folderId)
            assertTrue(repository.listWorkflows().single().isFavorite)
            repository.deleteRunFolder(RUN_FOLDER_ID)
            repository.deleteRunFolder(SECOND_RUN_FOLDER_ID)
            assertTrue(repository.listRunFolders().isEmpty())
            assertEquals(null, repository.listRunHistory().last().folderId)

            repository.deleteRun(RUN_ID)
            repository.deleteRun(DETAILED_RUN_ID)
            assertTrue(repository.listRunHistory().isEmpty())
            repository.deleteWorkflow(WORKFLOW_ID)
            assertTrue(repository.listWorkflows().isEmpty())
            assertTrue(repository.listRevisions(WORKFLOW_ID).isEmpty())
            assertTrue(directory.listFiles().isNullOrEmpty())
        } finally {
            repository.close()
            directory.deleteRecursively()
        }
    }

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
    }
}
