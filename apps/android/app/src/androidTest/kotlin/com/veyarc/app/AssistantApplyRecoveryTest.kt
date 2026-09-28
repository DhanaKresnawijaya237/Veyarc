package com.veyarc.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.veyarc.data.storage.ChatRepository
import com.veyarc.feature.assistant.AssistantPreviewContext
import com.veyarc.feature.assistant.AssistantPreviewState
import com.veyarc.feature.assistant.PreviewScenario
import com.veyarc.feature.assistant.ProposalApplyState
import com.veyarc.feature.editor.WorkflowDraft
import com.veyarc.feature.editor.WorkflowEditorSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AssistantApplyRecoveryTest {
    @Test
    fun durableConsumptionSurvivesReloadAndUndoAndStorageFailureCannotMutate() = runBlocking {
        exercise { state, editor, repository ->
            val before = editor.draft
            val turn = state.conversation!!.turns.single()
            withContext(Dispatchers.Main) {
                state.applyProposal(turn.id)
                state.applyProposal(turn.id)
            }
            await { state.canChange }
            assertEquals(ProposalApplyState.APPLIED, state.conversation!!.turns.single().applyState)
            withContext(Dispatchers.Main) {
                editor.undo()
                state.reload()
            }
            await { state.canChange }
            withContext(Dispatchers.Main) { state.applyProposal(turn.id) }
            assertEquals(before, editor.draft)
            assertFalse(editor.canUndo)
            assertEquals(ProposalApplyState.APPLIED, state.conversation!!.turns.single().applyState)
            // Simulate process loss after the durable reservation but before outcome persistence.
            val stored = repository.load().single { it.id == state.selectedId }
            repository.save(stored.copy(payload = stored.payload.toString(Charsets.UTF_8).replace("\"applyState\":\"APPLIED\"", "\"applyState\":\"RESERVED\"").toByteArray()), false)
            withContext(Dispatchers.Main) { state.reload() }
            await { state.canChange }
            assertEquals(ProposalApplyState.RESERVED, state.conversation!!.turns.single().applyState)
            withContext(Dispatchers.Main) { state.applyProposal(turn.id) }
            assertEquals(before, editor.draft)
            withContext(Dispatchers.Main) {
                state.newChat()
            }
            await { state.canChange }
            withContext(Dispatchers.Main) {
                state.compose("Another change")
                state.send()
            }
            await { state.requestId != null }
            withContext(Dispatchers.Main) { state.advance(state.requestId!!) }
            await { state.canChange }
            repository.close()
            withContext(Dispatchers.Main) { state.applyProposal(state.conversation!!.turns.single().id) }
            await { state.storageError != null && state.pendingWrites == 0 }
            assertEquals(before, editor.draft)
            assertFalse(editor.canUndo)
        }
    }

    @Test
    fun leavingChatBeforeReservationCompletesBlocksApply() = runBlocking {
        exercise { state, editor, _ ->
            val before = editor.draft
            withContext(Dispatchers.Main) {
                state.applyProposal(state.conversation!!.turns.single().id)
                state.close()
            }
            await { state.canChange }
            assertEquals(before, editor.draft)
            assertEquals(ProposalApplyState.REJECTED, state.conversation!!.turns.single().applyState)
        }
    }

    private suspend fun exercise(test: suspend (AssistantPreviewState, WorkflowEditorSession, ChatRepository) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = ChatRepository(context)
        val cleanup = ChatRepository(context)
        val owner = SupervisorJob()
        val editor = WorkflowEditorSession().apply { open(WorkflowDraft.newDefault().buildBundle().bundle!!) }
        val bridge = AssistantDraftBridge(editor)
        val state = withContext(Dispatchers.Main) { AssistantPreviewState(repository, CoroutineScope(owner + Dispatchers.Main), ::assistantCompilationIssues, bridge::apply, contextSource = bridge::capture) }
        try {
            await { state.canChange }
            withContext(Dispatchers.Main) { state.open(AssistantPreviewContext(editor.draft!!.workflowId)) }
            await { state.canChange }
            withContext(Dispatchers.Main) {
                state.scenario = PreviewScenario.VALIDATED_PROPOSAL
                state.compose("Rename it")
                state.send()
            }
            await { state.requestId != null }
            withContext(Dispatchers.Main) { state.advance(state.requestId!!) }
            await { state.canChange }
            test(state, editor, repository)
        } finally {
            owner.cancel()
            cleanup.load().filter { it.workflowId == editor.draft!!.workflowId }.forEach { cleanup.delete(it.id) }
            repository.close()
            cleanup.close()
        }
    }

    private suspend fun await(predicate: () -> Boolean) = withTimeout(10000) {
        while (!withContext(Dispatchers.Main) { predicate() }) delay(20)
    }
}
