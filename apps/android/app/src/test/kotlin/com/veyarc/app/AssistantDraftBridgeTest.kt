package com.veyarc.app

import com.veyarc.feature.assistant.AssistantPreviewState
import com.veyarc.feature.assistant.PreviewScenario
import com.veyarc.feature.assistant.ProposalApplyState
import com.veyarc.feature.editor.WorkflowDraft
import com.veyarc.feature.editor.WorkflowEditorSession
import com.veyarc.model.WorkflowJson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantDraftBridgeTest {
    private fun editor() = WorkflowEditorSession().apply { open(WorkflowDraft.newDefault().buildBundle().bundle!!) }

    @Test fun applySavesExactlyOneRevisionAndFollowingRequestUsesIt() {
        val editor = editor()
        val bridge = savedRevisionTestBridge(editor)
        val state = AssistantPreviewState(compileCandidate = ::assistantCompilationIssues, applyCandidate = bridge::apply, contextSource = bridge::capture)
        state.open(editor.assistantContext())
        state.scenario = PreviewScenario.VALIDATED_PROPOSAL
        state.compose("Rename workflow")
        state.send()
        state.advance(state.requestId!!)
        val turn = state.conversation!!.turns.single()
        state.applyProposal(turn.id)
        assertEquals(2, editor.draft!!.lastSavedRevision)
        assertFalse(editor.isDirty)
        assertEquals(2, state.conversation!!.turns.single().appliedRevision)
        assertEquals(ProposalApplyState.APPLIED, state.conversation!!.turns.single().applyState)
        state.applyProposal(turn.id)
        assertEquals(2, editor.draft!!.lastSavedRevision)
        assertFalse(editor.canUndo)
        assertEquals(bridge.capture().snapshot, state.previewContext().snapshot)
    }

    @Test fun collapseDoesNotInvalidateProposalButActualEditsDo() = runBlocking {
        val editor = editor()
        val bridge = savedRevisionTestBridge(editor)
        val captured = bridge.capture()
        val candidate = WorkflowJson.encode(editor.draft!!.buildBundle().document!!.copy(name = "New name"))
        editor.update(editor.draft!!.copy(collapsedStepIds = emptySet()))
        assertEquals(captured, bridge.capture())
        editor.update(editor.draft!!.copy(name = "My newer name"))
        assertNull(bridge.apply(captured.localBindingId!!, captured.snapshot!!, candidate))
        assertEquals("My newer name", editor.draft!!.name)
    }

    @Test fun savingFailureKeepsOriginalDraftAndRecordsNoRevision() {
        val editor = editor()
        val before = editor.draft
        val bridge = AssistantDraftBridge(editor) { _, _ -> error("Storage unavailable") }
        val state = AssistantPreviewState(compileCandidate = ::assistantCompilationIssues, applyCandidate = bridge::apply, contextSource = bridge::capture)
        state.open(editor.assistantContext())
        state.scenario = PreviewScenario.VALIDATED_PROPOSAL
        state.compose("Rename workflow")
        state.send()
        state.advance(state.requestId!!)
        state.applyProposal(state.conversation!!.turns.single().id)
        assertEquals(before, editor.draft)
        assertEquals(ProposalApplyState.REJECTED, state.conversation!!.turns.single().applyState)
        assertNull(state.conversation!!.turns.single().appliedRevision)
        assertNotNull(state.conversation!!.turns.single().applyError)
        assertFalse(state.isApplyingProposal)
    }

    @Test fun newWorkflowGetsRevisionOneAndInvalidOrDuplicateCandidatesNeverSave() = runBlocking {
        val editor = WorkflowEditorSession().apply { create(WorkflowDraft.newDefault().viewport) }
        val bridge = savedRevisionTestBridge(editor)
        val captured = bridge.capture()
        val candidate = WorkflowJson.encode(editor.draft!!.buildBundle().document!!.copy(name = "Created"))
        assertNull(bridge.apply(captured.localBindingId!!, captured.snapshot!!, "{}"))
        assertEquals(1, bridge.apply(captured.localBindingId!!, captured.snapshot!!, candidate))
        assertNull(bridge.apply(captured.localBindingId!!, captured.snapshot!!, candidate))
        assertEquals(1, editor.draft!!.lastSavedRevision)
    }

    @Test fun acceptedSaveCompletesWhenChatClosesAndKeepsRevisionNumber() = runBlocking {
        val editor = editor()
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val bridge = AssistantDraftBridge(editor) { _, candidate ->
            started.complete(Unit)
            finish.await()
            val revision = candidate.nextRevision
            editor.markSaved(candidate.copy(lastSavedRevision = revision, latestKnownRevision = revision))
            revision
        }
        val owner = SupervisorJob()
        val state = AssistantPreviewState(scope = CoroutineScope(owner + Dispatchers.Unconfined), compileCandidate = ::assistantCompilationIssues, applyCandidate = bridge::apply, contextSource = bridge::capture)
        state.open(editor.assistantContext())
        state.scenario = PreviewScenario.VALIDATED_PROPOSAL
        state.compose("Rename workflow")
        state.send()
        state.advance(state.requestId!!)
        state.applyProposal(state.conversation!!.turns.single().id)
        started.await()
        assertTrue(state.isApplyingProposal)
        state.close()
        finish.complete(Unit)
        assertEquals(2, state.conversation!!.turns.single().appliedRevision)
        assertFalse(state.isApplyingProposal)
        owner.cancel()
    }
}

internal fun savedRevisionTestBridge(editor: WorkflowEditorSession) = AssistantDraftBridge(editor) { expected, candidate ->
    check(editor.draft === expected)
    val revision = candidate.nextRevision
    editor.markSaved(candidate.copy(lastSavedRevision = revision, latestKnownRevision = revision))
    revision
}
