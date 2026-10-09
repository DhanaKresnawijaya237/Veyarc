package com.veyarc.app

import com.veyarc.feature.assistant.AssistantCandidateValidator
import com.veyarc.feature.assistant.AssistantContextCapture
import com.veyarc.feature.assistant.AssistantPreviewContext
import com.veyarc.feature.assistant.AssistantPreviewState
import com.veyarc.feature.assistant.AssistantProposalCheck
import com.veyarc.feature.assistant.AssistantProposalReview
import com.veyarc.feature.assistant.AssistantReviewStatus
import com.veyarc.feature.assistant.PreviewConversation
import com.veyarc.feature.assistant.PreviewScenario
import com.veyarc.feature.editor.WorkflowDraft
import com.veyarc.model.DurationMillis
import com.veyarc.model.NumberValue
import com.veyarc.model.Repeat
import com.veyarc.model.VariableDeclaration
import com.veyarc.model.VariableType
import com.veyarc.model.Wait
import com.veyarc.model.WorkflowDocument
import com.veyarc.model.WorkflowJson
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class AssistantProposalReviewTest {
    private fun id() = UUID.randomUUID().toString()
    private val wait = Wait(id(), DurationMillis(100))
    private val removed = Wait(id(), DurationMillis(200))
    private val nested = Wait(id(), DurationMillis(300))
    private val loop = Repeat(id(), 2, listOf(nested))
    private val source = WorkflowDraft.newDefault().copy(steps = listOf(wait, removed, loop), variables = mapOf("count" to VariableDeclaration(VariableType.NUMBER, NumberValue(1.0))))
    private val base = source.captureAssistantContext().snapshot!!
    private val document = source.buildBundle().document!!
    private fun proposal(candidate: WorkflowDocument) = AssistantCandidateValidator.validate(base, WorkflowJson.encode(candidate), ::assistantCompilationIssues).also { assertTrue(it.issues.toString(), it.isValid) }

    @Test
    fun showsExactNamesVariablesNestedMovesRemovalsAdditionsAndChangedValues() {
        val added = Wait(id(), DurationMillis(400))
        val candidate = document.copy(name = "Changed name", variables = mapOf("count" to VariableDeclaration(VariableType.NUMBER, NumberValue(2.0))), steps = listOf(loop.copy(bodySteps = listOf(wait.copy(durationMs = DurationMillis(500)), nested)), added))
        val diff = AssistantProposalReview.changes(base, proposal(candidate))
        assertTrue(diff.any { it.label == "Workflow name" && it.before == "\"New workflow\"" && it.after == "\"Changed name\"" })
        assertTrue(diff.any { it.label.contains("Variables / count / initialValue / value") && it.before == "1.0" && it.after == "2.0" })
        assertTrue(diff.any { it.label == "Moved block ${wait.id}" && it.before == "Steps / 1" && it.after == "Steps / 1 / bodySteps / 1" })
        assertTrue(diff.any { it.label.contains(wait.id) && it.label.endsWith("durationMs") && it.before == "100" && it.after == "500" })
        assertTrue(diff.any { it.label.startsWith("Removed block ${removed.id}") && it.after == null })
        assertTrue(diff.any { it.label.startsWith("Added block ${added.id}") && it.before == null && it.after!!.contains("400") })
        assertFalse(diff.any { it.label == "Moved block ${nested.id}" })
        assertEquals(document, source.buildBundle().document)
    }

    @Test
    fun detectsReorderingButIgnoresIndexShiftsAndUnchangedDocuments() {
        assertTrue(AssistantProposalReview.changes(base, proposal(document)).isEmpty())
        val inserted = document.copy(steps = listOf(Wait(id(), DurationMillis(4))) + document.steps)
        val diff = AssistantProposalReview.changes(base, proposal(inserted))
        assertEquals(1, diff.size)
        assertTrue(diff.single().label.startsWith("Added block"))
        val reordered = document.copy(steps = listOf(loop, removed, wait))
        assertEquals(2, AssistantProposalReview.changes(base, proposal(reordered)).count { it.label.startsWith("Moved block") })
        val deletionAndMove = AssistantProposalReview.changes(base, proposal(document.copy(steps = listOf(loop, removed))))
        assertFalse(deletionAndMove.any { it.label == "Moved block ${removed.id}" })
    }

    @Test
    fun changedOrUnavailableCurrentDraftIsStaleAndNeverChangesCapturedDiff() {
        val candidate = proposal(document.copy(name = "Proposed"))
        val expected = AssistantProposalReview.changes(base, candidate)
        assertEquals(AssistantReviewStatus.READY, AssistantProposalReview.status(base, source.captureAssistantContext(), candidate, false))
        listOf(source.copy(name = "Unsaved edit"), source.copy(latestKnownRevision = 9), source.copy(allowedPackagesText = "com.example.other"), source.copy(workflowId = id()), source.copy(steps = listOf(wait))).forEach {
            assertEquals(AssistantReviewStatus.STALE, AssistantProposalReview.status(base, it.captureAssistantContext(), candidate, false))
            assertEquals(expected, AssistantProposalReview.changes(base, candidate))
        }
        assertEquals(AssistantReviewStatus.STALE, AssistantProposalReview.status(base, AssistantContextCapture(error = "unavailable"), candidate, false))
        assertEquals(AssistantReviewStatus.STALE, AssistantProposalReview.status(base, AssistantContextCapture(), candidate, false))
        assertEquals(AssistantReviewStatus.INVALID, AssistantProposalReview.status(base, source.captureAssistantContext(), AssistantProposalCheck(issues = listOf("invalid")), false))
    }

    @Test
    fun dismissPersistsInChatWithoutChangingCandidateOrWorkflow() {
        val state = AssistantPreviewState(compileCandidate = ::assistantCompilationIssues) { source.captureAssistantContext() }
        state.open(AssistantPreviewContext(source.workflowId))
        state.scenario = PreviewScenario.VALIDATED_PROPOSAL
        state.compose("Propose a change")
        state.send()
        state.advance(state.requestId!!)
        val turn = state.conversation!!.turns.single()
        state.dismissProposal(turn.id)
        state.dismissProposal(turn.id)
        val restored = Json.decodeFromString(PreviewConversation.serializer(), Json.encodeToString(PreviewConversation.serializer(), state.conversation!!)).turns.single()
        assertTrue(restored.proposalDismissed)
        assertEquals(turn.proposal, restored.proposal)
        assertEquals(AssistantReviewStatus.DISMISSED, AssistantProposalReview.status(base, source.captureAssistantContext(), restored.proposal!!, restored.proposalDismissed))
        assertEquals(document, source.buildBundle().document)
    }
}
