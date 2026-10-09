package com.veyarc.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.veyarc.data.storage.ChatRepository
import com.veyarc.feature.assistant.AssistantPreviewContext
import com.veyarc.feature.assistant.AssistantPreviewState
import com.veyarc.feature.assistant.PreviewReplyStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AssistantHistoryRecoveryTest {
    @Test
    fun storageFailureKeepsThePromptAndPreventsDispatch() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = ChatRepository(context)
        val cleanup = ChatRepository(context)
        val owner = SupervisorJob()
        var id: String? = null
        try {
            val state = withContext(Dispatchers.Main) { AssistantPreviewState(repository, CoroutineScope(owner + Dispatchers.Main), providerCall = { error("Storage failure must prevent provider dispatch") }) }
            await { state.canChange }
            withContext(Dispatchers.Main) { state.open(AssistantPreviewContext(UUID.randomUUID().toString(), "Storage failure test")) }
            await { state.canChange }
            id = state.selectedId!!
            repository.close()
            withContext(Dispatchers.Main) {
                state.enableProvider()
                state.compose("Do not lose this prompt")
                state.send()
            }
            await { state.storageError != null && state.pendingWrites == 0 }
            assertNull(state.requestId)
            assertEquals("Do not lose this prompt", state.conversation!!.turns.single().prompt)
            assertEquals(PreviewReplyStatus.INTERRUPTED, state.conversation!!.turns.single().status)
            assertEquals("EMPTY", cleanup.load().single { it.id == id }.status)
        } finally {
            owner.cancel()
            id?.let { cleanup.delete(it) }
            repository.close()
            cleanup.close()
        }
    }

    @Test
    fun replacingTheOwnerRestoresDraftAndInterruptsWithoutResending() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val firstRepository = ChatRepository(context)
        val secondRepository = ChatRepository(context)
        val firstJob = SupervisorJob()
        val secondJob = SupervisorJob()
        val workflow = AssistantPreviewContext(UUID.randomUUID().toString(), "Recovery test")
        var id: String? = null
        try {
            val first = withContext(Dispatchers.Main) { AssistantPreviewState(firstRepository, CoroutineScope(firstJob + Dispatchers.Main)) }
            await { first.canChange }
            val draft = com.veyarc.feature.editor.WorkflowDraft.newDefault().copy(workflowId = workflow.workflowId!!, name = "Captured before owner loss")
            withContext(Dispatchers.Main) { first.open(workflow) { draft.captureAssistantContext() } }
            await { first.canChange }
            id = first.selectedId!!
            withContext(Dispatchers.Main) {
                first.compose("Saved before the sample starts")
                first.send()
            }
            await { first.requestId != null }
            assertEquals("RESPONDING", firstRepository.load().single { it.id == id }.status)
            withContext(Dispatchers.Main) { first.compose("Keep my unsent follow-up") }
            withTimeout(10_000) {
                while (!firstRepository.load().single { it.id == id }.payload.toString(Charsets.UTF_8).contains("Keep my unsent follow-up")) delay(50)
            }
            // Model-owner loss without a graceful Stop, as on process death.
            firstJob.cancel()
            val restored = withContext(Dispatchers.Main) { AssistantPreviewState(secondRepository, CoroutineScope(secondJob + Dispatchers.Main), compileCandidate = ::assistantCompilationIssues) }
            await { restored.canChange }
            withContext(Dispatchers.Main) { restored.open(workflow) { draft.captureAssistantContext() } }
            await { restored.canChange }
            assertNull(restored.requestId)
            assertEquals(id, restored.selectedId)
            assertEquals("Keep my unsent follow-up", restored.conversation!!.composer)
            assertEquals(PreviewReplyStatus.INTERRUPTED, restored.conversation!!.turns.single().status)
            assertEquals(1, restored.conversation!!.turns.single().attempt)
            assertEquals(draft.captureAssistantContext().snapshot, restored.conversation!!.turns.single().baseSnapshot)
            assertEquals("INTERRUPTED", secondRepository.load().single { it.id == id }.status)
            withContext(Dispatchers.Main) { restored.rename("Restored chat") }
            await { restored.canChange }
            assertTrue(secondRepository.load().single { it.id == id }.payload.toString(Charsets.UTF_8).contains("Restored chat"))
            withContext(Dispatchers.Main) {
                restored.scenario = com.veyarc.feature.assistant.PreviewScenario.CLARIFICATION
                restored.compose("Ask for missing details")
                restored.send()
            }
            await { restored.requestId != null }
            withContext(Dispatchers.Main) { repeat(100) { restored.requestId?.let(restored::advance) } }
            await { restored.canChange }
            withContext(Dispatchers.Main) { restored.reload() }
            await { restored.canChange }
            assertEquals(com.veyarc.feature.assistant.AssistantOutcome.CLARIFICATION, restored.conversation!!.turns.last().outcome)
            withContext(Dispatchers.Main) {
                restored.scenario = com.veyarc.feature.assistant.PreviewScenario.VALIDATED_PROPOSAL
                restored.compose("Validate a sample candidate")
                restored.send()
            }
            await { restored.requestId != null }
            withContext(Dispatchers.Main) { restored.advance(restored.requestId!!) }
            await { restored.canChange }
            val candidate = restored.conversation!!.turns.last().proposal!!.candidateJson
            assertTrue(candidate != null)
            withContext(Dispatchers.Main) { restored.reload() }
            await { restored.canChange }
            assertEquals(candidate, restored.conversation!!.turns.last().proposal!!.candidateJson)
            withContext(Dispatchers.Main) { restored.dismissProposal(restored.conversation!!.turns.last().id) }
            await { restored.canChange }
            withContext(Dispatchers.Main) { restored.reload() }
            await { restored.canChange }
            assertTrue(restored.conversation!!.turns.last().proposalDismissed)
            withContext(Dispatchers.Main) { restored.close() }
            await { restored.canChange }
            // Deletion by another repository path (including workflow deletion) invalidates cached history.
            secondRepository.delete(id)
            withContext(Dispatchers.Main) { restored.open(workflow) { draft.captureAssistantContext() } }
            await { restored.canChange }
            assertTrue(restored.conversations.none { it.id == id })
            id = restored.selectedId!!
            withContext(Dispatchers.Main) { restored.deleteCurrent() }
            await { restored.canChange }
            assertTrue(secondRepository.load().none { it.id == id })
        } finally {
            firstJob.cancel()
            secondJob.cancel()
            id?.let { firstRepository.delete(it) }
            firstRepository.close()
            secondRepository.close()
        }
    }

    private suspend fun await(predicate: () -> Boolean) = withTimeout(10_000) {
        while (!withContext(Dispatchers.Main) { predicate() }) delay(20)
    }
}
