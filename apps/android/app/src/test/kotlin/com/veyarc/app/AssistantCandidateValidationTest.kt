package com.veyarc.app

import com.veyarc.data.storage.WorkflowAssetDescriptor
import com.veyarc.feature.assistant.AssistantCandidateValidator
import com.veyarc.feature.assistant.AssistantPreviewContext
import com.veyarc.feature.assistant.AssistantPreviewState
import com.veyarc.feature.assistant.PreviewScenario
import com.veyarc.feature.editor.WorkflowDraft
import com.veyarc.model.AssetId
import com.veyarc.model.AssetKind
import com.veyarc.model.AssetReference
import com.veyarc.model.DurationMillis
import com.veyarc.model.ImagePresentCondition
import com.veyarc.model.Jump
import com.veyarc.model.NormalizedRect
import com.veyarc.model.PackageName
import com.veyarc.model.Repeat
import com.veyarc.model.Wait
import com.veyarc.model.WaitUntil
import com.veyarc.model.WorkflowDocument
import com.veyarc.model.WorkflowJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class AssistantCandidateValidationTest {
    private fun id() = UUID.randomUUID().toString()
    private val draft = WorkflowDraft.newDefault()
    private val base = draft.buildBundle().document!!
    private fun validate(candidate: WorkflowDocument, source: WorkflowDraft = draft) = AssistantCandidateValidator.validate(source.captureAssistantContext().snapshot, WorkflowJson.encode(candidate), ::assistantCompilationIssues)

    @Test
    fun validEditsPreserveUnloadedImagesAndDoNotMutateDraftOrAllocateARevision() {
        val imageId = AssetId("existing_image")
        val source = draft.copy(
            lastSavedRevision = 3,
            latestKnownRevision = 5,
            assetDescriptors = listOf(WorkflowAssetDescriptor(imageId, AssetKind.IMAGE_TEMPLATE, "Existing image", "image/png", 20, 30)),
            recordingJournalFileName = "local-journal-only",
            steps = listOf(WaitUntil(id(), ImagePresentCondition(imageId, NormalizedRect(0.0, 0.0, 1.0, 1.0), 0.9), DurationMillis(1000), DurationMillis(100))),
        )
        val original = source.buildBundle().document!!
        assertNull(source.buildBundle().bundle)
        val result = validate(original.copy(name = "Reviewed later"), source)
        assertTrue(result.isValid)
        val retained = WorkflowJson.decode(result.candidateJson!!)
        assertEquals(original.assets, retained.assets)
        assertEquals(6, retained.revision)
        assertEquals(3, source.lastSavedRevision)
        assertTrue(source.assets.isEmpty())
        assertEquals("local-journal-only", source.recordingJournalFileName)
        assertFalse(result.candidateJson!!.contains("local-journal"))
        assertFalse(validate(original.copy(assets = emptyList()), source).isValid)
        val missingName = validate(original.copy(assets = listOf(AssetReference(imageId, AssetKind.IMAGE_TEMPLATE))), source)
        assertTrue(missingName.issues.single().contains("image catalog"))
        assertFalse(validate(original.copy(assets = listOf(AssetReference(imageId, AssetKind.IMAGE_TEMPLATE, "Renamed image"))), source).isValid)
    }

    @Test
    fun protectsLockedFieldsAndCompilerWhileAllowingNameRepairs() {
        listOf(
            base.copy(id = id()),
            base.copy(schemaVersion = 2),
            base.copy(revision = base.revision + 1),
            base.copy(allowedPackages = listOf(PackageName("com.example.changed"))),
            base.copy(runLimits = base.runLimits.copy(maxTotalSteps = 200)),
            base.copy(viewport = base.viewport.copy(recordedWidthPx = base.viewport.recordedWidthPx + 1)),
            base.copy(assets = listOf(AssetReference(AssetId("new_image"), AssetKind.IMAGE_TEMPLATE))),
        ).forEach { assertFalse("Locked candidate accepted: $it", validate(it).isValid) }
        assertTrue(validate(base, draft.copy(name = "")).isValid)
        assertFalse(validate(base, draft.copy(allowedPackagesText = "not a package")).isValid)
        assertFalse(AssistantCandidateValidator.validate(null, WorkflowJson.encode(base), ::assistantCompilationIssues).isValid)
        assertFalse(AssistantCandidateValidator.validate(draft.captureAssistantContext().snapshot, WorkflowJson.encode(base), null).isValid)
    }

    @Test
    fun rejectsBrokenReferencesReidentifiedBlocksAndExecutionBounds() {
        assertFalse(validate(base.copy(steps = base.steps + base.steps.first())).isValid)
        assertFalse(validate(base.copy(steps = base.steps + Jump(id(), id(), 2))).isValid)
        val missingImage = WaitUntil(id(), ImagePresentCondition(AssetId("missing"), NormalizedRect(0.0, 0.0, 1.0, 1.0), 0.9), DurationMillis(1000), DurationMillis(100))
        assertFalse(validate(base.copy(steps = listOf(missingImage))).isValid)
        val wait = base.steps.filterIsInstance<Wait>().single()
        assertTrue(validate(base.copy(steps = base.steps.map { if (it == wait) wait.copy(id = id()) else it })).issues.single().contains("new ID"))
        val boundedLoop = Repeat(id(), base.runLimits.maxLoopIterations + 1, listOf(Wait(id(), DurationMillis(1000))))
        val loopResult = validate(base.copy(steps = listOf(boundedLoop)))
        assertTrue(loopResult.issues.toString(), loopResult.issues.any { it.contains("LIMIT_EXCEEDED") })
        val first = Wait(id(), DurationMillis(1))
        val jumpSource = draft.copy(runLimits = draft.runLimits.copy(maxLoopIterations = Int.MAX_VALUE))
        val jumpBase = jumpSource.buildBundle().document!!
        assertTrue(validate(jumpBase.copy(steps = listOf(first, Jump(id(), first.id, Int.MAX_VALUE))), jumpSource).isValid)
        assertEquals(base, draft.buildBundle().document)
    }

    @Test
    fun rejectsUnsupportedMalformedDuplicateAndOversizedCandidateData() {
        val json = WorkflowJson.encode(base)
        val snapshot = draft.captureAssistantContext().snapshot
        listOf(
            json.dropLast(1),
            json.replace("\"tapPoint\"", "\"runCode\""),
            json.replace("\"revision\":1", "\"revision\":\"1\""),
            json.replace("\"revision\":1", "\"revision\":1,\"revision\":1"),
            json.replace("\"name\":\"New workflow\"", "\"name\":null"),
            json.dropLast(1) + ",\"recordingJournalFileName\":\"provider-chosen\"}",
            " ".repeat(32769),
            "[".repeat(1000),
        ).forEach { assertFalse(AssistantCandidateValidator.validate(snapshot, it, ::assistantCompilationIssues).isValid) }
    }

    @Test
    fun localCandidateFixturesAreValidatedBeforeBeingRetained() {
        val state = AssistantPreviewState(compileCandidate = ::assistantCompilationIssues) { draft.captureAssistantContext() }
        state.open(AssistantPreviewContext(draft.workflowId))
        state.scenario = PreviewScenario.VALIDATED_PROPOSAL
        state.compose("Sample proposal")
        state.send()
        state.advance(state.requestId!!)
        assertTrue(state.conversation!!.turns.last().proposal!!.isValid)
        state.scenario = PreviewScenario.INVALID_CANDIDATE
        state.compose("Sample invalid proposal")
        state.send()
        state.advance(state.requestId!!)
        val invalid = state.conversation!!.turns.last().proposal!!
        assertFalse(invalid.isValid)
        assertNull(invalid.candidateJson)
        assertEquals(base, draft.buildBundle().document)
    }
}
