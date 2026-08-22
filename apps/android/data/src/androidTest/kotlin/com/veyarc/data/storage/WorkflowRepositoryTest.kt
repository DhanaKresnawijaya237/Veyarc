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

            assertEquals(1, repository.saveRevision(first).revision)
            assertEquals(2, repository.saveRevision(second).revision)
            assertEquals(2, repository.listWorkflows().single().latestRevision)
            assertEquals(listOf(2, 1), repository.listRevisions(WORKFLOW_ID).map { it.revision })
            assertEquals(2, repository.loadRevision(WORKFLOW_ID, 2).document.revision)

            val runId = repository.beginRun(WORKFLOW_ID, 2, runId = RUN_ID, startedAtMs = 2000L)
            assertEquals(RUN_ID, runId)
            assertEquals(1, repository.recoverInterruptedRuns(recoveredAtMs = 3000L))
            val interrupted = repository.listInterruptedRuns().single()
            assertEquals(RUN_ID, interrupted.runId)
            assertEquals(StoredRunStatus.INTERRUPTED, interrupted.status)
            assertTrue(interrupted.interruptionReason!!.isNotBlank())
            assertEquals(listOf(RUN_ID), repository.listRunHistory().map { it.runId })
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
    }
}
