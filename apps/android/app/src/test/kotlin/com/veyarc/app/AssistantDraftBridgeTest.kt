package com.veyarc.app

import com.veyarc.data.storage.WorkflowAssetDescriptor
import com.veyarc.feature.assistant.AssistantPreviewContext
import com.veyarc.feature.assistant.AssistantPreviewState
import com.veyarc.feature.assistant.PreviewScenario
import com.veyarc.feature.assistant.ProposalApplyState
import com.veyarc.feature.editor.WorkflowDraft
import com.veyarc.feature.editor.WorkflowEditorSession
import com.veyarc.model.AssetId
import com.veyarc.model.AssetKind
import com.veyarc.model.WorkflowJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantDraftBridgeTest {
    private fun editor() = WorkflowEditorSession().apply { open(WorkflowDraft.newDefault().buildBundle().bundle!!, "private-journal") }

    @Test
    fun unfinishedNameAndStepsCanBeRepairedInOneReviewedUndoableChange() {
        val editor = editor()
        val valid = editor.draft!!.buildBundle().document!!
        val unfinished = editor.draft!!.copy(name = "", steps = emptyList())
        editor.update(unfinished)
        assertTrue(editor.draft!!.buildBundle().document == null)
        val bridge = AssistantDraftBridge(editor)
        val capture = bridge.capture()
        val candidate = valid.copy(name = "Wait a minute", steps = listOf(com.veyarc.model.Wait(java.util.UUID.randomUUID().toString(), com.veyarc.model.DurationMillis(60_000))))
        assertTrue(bridge.apply(capture.localBindingId!!, capture.snapshot!!, WorkflowJson.encode(candidate)))
        assertEquals(candidate.name, editor.draft!!.name)
        assertEquals(candidate.steps, editor.draft!!.steps)
        assertEquals(unfinished.lastSavedRevision, editor.draft!!.lastSavedRevision)
        assertEquals(unfinished.allowedPackagesText, editor.draft!!.allowedPackagesText)
        editor.undo()
        assertEquals(unfinished, editor.draft)
    }

    @Test
    fun branchingNeverCopiesApplyAuthorityAndRegenerationRequiresFreshReview() {
        val editor = editor()
        val before = editor.draft
        val bridge = AssistantDraftBridge(editor)
        val state = AssistantPreviewState(compileCandidate = ::assistantCompilationIssues, applyCandidate = bridge::apply, contextSource = bridge::capture)
        state.open(editor.assistantContext())
        state.scenario = PreviewScenario.VALIDATED_PROPOSAL
        state.compose("Rename the workflow")
        state.send()
        state.advance(state.requestId!!)
        val original = state.conversation!!
        val turn = original.turns.single()
        assertTrue(state.canApply(turn))
        state.branch(turn.id)
        val copied = state.conversation!!.turns.single()
        assertFalse(state.canApply(copied))
        state.applyProposal(copied.id)
        assertEquals(before, editor.draft)
        state.select(original.id)
        state.regenerate(turn.id)
        val refreshed = state.conversation!!.turns.single()
        assertEquals(turn.baseSnapshot, refreshed.baseSnapshot)
        assertFalse(state.canApply(refreshed))
        state.advance(state.requestId!!)
        assertEquals(before, editor.draft)
        assertTrue(state.canApply(state.conversation!!.turns.single()))
        state.applyProposal(state.conversation!!.turns.single().id)
        assertEquals("Sample workflow name", editor.draft!!.name)
        assertEquals(original, state.conversations.first { it.id == original.id })
    }

    @Test
    fun applyingNewStepsCannotChangeAnAlreadyCompiledPlan() {
        val editor = editor()
        val bridge = AssistantDraftBridge(editor)
        val base = editor.draft!!.buildBundle().document!!
        val plan = com.veyarc.engine.WorkflowCompiler.compileOrThrow(base)
        val expectedPlan = com.veyarc.engine.WorkflowCompiler.compileOrThrow(base)
        val capture = bridge.capture()
        val candidate = base.copy(name = "Edited draft", steps = listOf(com.veyarc.model.Wait(java.util.UUID.randomUUID().toString(), com.veyarc.model.DurationMillis(250))))
        assertTrue(bridge.apply(capture.localBindingId!!, capture.snapshot!!, WorkflowJson.encode(candidate)))
        assertEquals(candidate.steps, editor.draft!!.steps)
        assertEquals(expectedPlan, plan)
        editor.undo()
        assertEquals(expectedPlan, plan)
        assertEquals(base.steps, editor.draft!!.steps)
    }

    @Test
    fun creationStartsEmptyClarifiesMissingDetailsAndKeepsIdentityWhenConfigured() {
        val editor = WorkflowEditorSession()
        editor.createForAssistant(WorkflowDraft.newDefault().viewport)
        val id = editor.draft!!.workflowId
        assertTrue(editor.draft!!.steps.isEmpty())
        assertEquals("", editor.draft!!.allowedPackagesText)
        assertFalse(editor.canUndo)
        val bridge = AssistantDraftBridge(editor)
        val chat = AssistantPreviewState(compileCandidate = ::assistantCompilationIssues, applyCandidate = bridge::apply, contextSource = bridge::capture)
        chat.open(editor.assistantContext())
        val chatId = chat.selectedId
        chat.scenario = PreviewScenario.VALIDATED_PROPOSAL
        chat.compose("Create a workflow")
        chat.send()
        chat.advance(chat.requestId!!)
        assertEquals(com.veyarc.feature.assistant.AssistantOutcome.CLARIFICATION, chat.conversation!!.turns.single().outcome)
        assertTrue(editor.draft!!.buildBundle().document == null)
        editor.update(editor.draft!!.copy(allowedPackagesText = "com.example.target"))
        chat.compose("Tap a button without geometry")
        chat.send()
        chat.advance(chat.requestId!!)
        assertEquals(com.veyarc.feature.assistant.AssistantOutcome.CLARIFICATION, chat.conversation!!.turns.last().outcome)
        editor.update(editor.draft!!.copy(allowedPackagesText = "com.example.target", steps = listOf(com.veyarc.model.Wait(java.util.UUID.randomUUID().toString(), com.veyarc.model.DurationMillis(500)))))
        chat.open(editor.assistantContext())
        chat.compose("Propose the configured workflow")
        chat.send()
        chat.advance(chat.requestId!!)
        chat.applyProposal(chat.conversation!!.turns.last().id)
        assertEquals(id, editor.draft!!.workflowId)
        assertEquals(chatId, chat.selectedId)
        assertEquals(null, editor.draft!!.lastSavedRevision)
        assertEquals(1, editor.draft!!.nextRevision)
        assertEquals("Sample workflow name", editor.draft!!.name)
    }

    @Test
    fun appliesOnlyApprovedFieldsInOneUndoWithoutSavingAndCannotApplyTwiceAfterUndo() {
        val editor = editor()
        val bridge = AssistantDraftBridge(editor)
        val state = AssistantPreviewState(compileCandidate = ::assistantCompilationIssues, applyCandidate = bridge::apply, contextSource = bridge::capture)
        state.open(AssistantPreviewContext(editor.draft!!.workflowId))
        state.scenario = PreviewScenario.VALIDATED_PROPOSAL
        state.compose("Change my workflow")
        state.send()
        state.advance(state.requestId!!)
        val turn = state.conversation!!.turns.single()
        val before = editor.draft!!
        assertTrue(state.canApply(turn))
        assertFalse(editor.canUndo)
        state.applyProposal(turn.id)
        assertEquals("Sample workflow name", editor.draft!!.name)
        assertEquals(before.copy(name = "Sample workflow name"), editor.draft)
        assertTrue(editor.isDirty)
        assertEquals(ProposalApplyState.APPLIED, state.conversation!!.turns.single().applyState)
        state.applyProposal(turn.id)
        editor.undo()
        assertEquals(before, editor.draft)
        assertFalse(editor.canUndo)
        assertFalse(editor.isDirty)
        state.applyProposal(turn.id)
        assertEquals(before, editor.draft)
        editor.redo()
        assertEquals("Sample workflow name", editor.draft!!.name)
        state.compose("No change")
        state.send()
        state.advance(state.requestId!!)
        val unchanged = state.conversation!!.turns.last()
        assertFalse(state.canApply(unchanged))
        state.applyProposal(unchanged.id)
        assertEquals(ProposalApplyState.NONE, state.conversation!!.turns.last().applyState)
    }

    @Test
    fun stalePrivateAssetBindingsReopenedDraftAndInvalidCandidateFailClosed() {
        val editor = editor()
        val bridge = AssistantDraftBridge(editor)
        val captured = bridge.capture()
        val base = editor.draft!!.buildBundle().document!!
        val candidate = WorkflowJson.encode(base.copy(name = "Proposed"))
        assertFalse(bridge.apply(captured.localBindingId!!, captured.snapshot!!, WorkflowJson.encode(base)))
        assertTrue(editor.beginSave())
        assertFalse(editor.beginSave())
        assertFalse(bridge.apply(captured.localBindingId!!, captured.snapshot!!, candidate))
        editor.finishSave()
        assertFalse(AssistantDraftBridge(editor).apply(captured.localBindingId!!, captured.snapshot!!, candidate))
        editor.update(editor.draft!!.copy(recordingJournalFileName = "replaced-journal"))
        assertEquals(captured.snapshot, bridge.capture().snapshot)
        assertFalse(bridge.apply(captured.localBindingId!!, captured.snapshot!!, candidate))
        val current = bridge.capture()
        assertFalse(bridge.apply(current.localBindingId!!, current.snapshot!!, WorkflowJson.encode(base.copy(revision = 999))))
        assertFalse(bridge.apply(current.localBindingId!!, current.snapshot!!, "{}"))
        editor.close()
        assertFalse(bridge.apply(current.localBindingId!!, current.snapshot!!, candidate))
    }

    @Test
    fun unloadedAssetsAndLocalMetadataSurviveApplyAndUndo() {
        val editor = editor()
        editor.update(editor.draft!!.copy(assetDescriptors = listOf(WorkflowAssetDescriptor(AssetId("existing_image"), AssetKind.IMAGE_TEMPLATE, "Template", "image/png", 20, 30))))
        val before = editor.draft!!
        val bridge = AssistantDraftBridge(editor)
        val captured = bridge.capture()
        val candidate = before.buildBundle().document!!.copy(name = "New name")
        assertTrue(bridge.apply(captured.localBindingId!!, captured.snapshot!!, WorkflowJson.encode(candidate)))
        assertEquals(before.assetDescriptors, editor.draft!!.assetDescriptors)
        assertTrue(editor.draft!!.assets.isEmpty())
        assertEquals(before.recordingJournalFileName, editor.draft!!.recordingJournalFileName)
        assertEquals(before.lastSavedRevision, editor.draft!!.lastSavedRevision)
        editor.undo()
        assertEquals(before, editor.draft)
    }
}
