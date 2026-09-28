package com.veyarc.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.veyarc.data.storage.ChatRepository
import com.veyarc.feature.assistant.AssistantOutcome
import com.veyarc.feature.assistant.AssistantPreviewContext
import com.veyarc.feature.assistant.AssistantPreviewState
import com.veyarc.feature.assistant.AssistantResponse
import com.veyarc.feature.assistant.PreviewReplyStatus
import com.veyarc.feature.assistant.ProposalApplyState
import com.veyarc.feature.editor.WorkflowDraft
import com.veyarc.model.WorkflowDocument
import com.veyarc.model.WorkflowJson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AssistantProviderRecoveryTest {
    @Test
    fun dispatchWaitsForStorageAndProviderProposalStillRequiresReview() = runBlocking {
        val repository = ChatRepository(InstrumentationRegistry.getInstrumentation().targetContext)
        val owner = SupervisorJob()
        val draft = WorkflowDraft.newDefault()
        val capture = draft.captureAssistantContext()
        val result = CompletableDeferred<ByteArray>()
        var calls = 0
        var id: String? = null
        lateinit var state: AssistantPreviewState
        try {
            state = withContext(Dispatchers.Main) {
                AssistantPreviewState(
                    repository,
                    CoroutineScope(owner + Dispatchers.Main),
                    compileCandidate = ::assistantCompilationIssues,
                    contextSource = { capture },
                    providerCall = {
                        val chat = state.conversation!!
                        val saved = withContext(Dispatchers.IO) { repository.load().single { it.id == chat.id } }
                        assertEquals("RESPONDING", saved.status)
                        assertEquals(state.requestId, saved.requestId)
                        assertTrue(saved.payload.toString(Charsets.UTF_8).contains("\"provider\":true"))
                        calls++
                        result.await()
                    },
                )
            }
            await { state.canChange }
            withContext(Dispatchers.Main) { state.open(AssistantPreviewContext(draft.workflowId)) }
            await { state.canChange }
            id = state.selectedId!!
            withContext(Dispatchers.Main) {
                state.compose("Suggest a new name")
                state.send()
                assertTrue(state.conversation!!.turns.isEmpty())
                state.enableProvider()
                state.send()
            }
            await { calls == 1 }
            val original = draft.buildBundle().document!!
            withContext(Dispatchers.Main) {
                val chat = state.conversation!!
                val response = AssistantResponse(
                    1,
                    state.requestId!!,
                    chat.id,
                    capture.snapshot!!.snapshotId,
                    AssistantOutcome.PROPOSAL,
                    "Review this name change.",
                    WorkflowJson.format.encodeToJsonElement(WorkflowDocument.serializer(), original.copy(name = "Proposed name")).jsonObject,
                )
                result.complete(WorkflowJson.encodeCanonical(AssistantResponse.serializer(), response).toByteArray())
            }
            await { state.canChange && state.requestId == null }
            assertTrue(state.conversation!!.turns.single().proposal!!.isValid)
            assertEquals(original.name, draft.name)
            assertEquals(ProposalApplyState.NONE, state.conversation!!.turns.single().applyState)
            withContext(Dispatchers.Main) { state.reload() }
            await { state.canChange }
            assertTrue(state.conversation!!.turns.single().provider)
            assertEquals(1, calls)
        } finally {
            withContext(Dispatchers.Main) { state.dispose() }
            owner.cancelAndJoin()
            id?.let { repository.delete(it) }
            repository.close()
        }
    }

    @Test
    fun ownerLossRestoresInterruptedProviderTurnWithoutConsentOrResend() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = ChatRepository(context)
        val nextRepository = ChatRepository(context)
        val firstOwner = SupervisorJob()
        val nextOwner = SupervisorJob()
        val workflow = AssistantPreviewContext(java.util.UUID.randomUUID().toString())
        var id: String? = null
        var calls = 0
        var cancelled = false
        try {
            val first = withContext(Dispatchers.Main) {
                AssistantPreviewState(repository, CoroutineScope(firstOwner + Dispatchers.Main), providerCall = {
                    calls++
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled = true
                    }
                })
            }
            await { first.canChange }
            withContext(Dispatchers.Main) { first.open(workflow) }
            await { first.canChange }
            id = first.selectedId!!
            withContext(Dispatchers.Main) {
                first.enableProvider()
                first.compose("Keep this pending prompt")
                first.send()
            }
            await { calls == 1 }
            firstOwner.cancelAndJoin()
            assertTrue(cancelled)
            val restored = withContext(Dispatchers.Main) {
                AssistantPreviewState(nextRepository, CoroutineScope(nextOwner + Dispatchers.Main), providerCall = {
                    calls++
                    error("Must not resend")
                })
            }
            await { restored.canChange }
            withContext(Dispatchers.Main) { restored.open(workflow) }
            await { restored.canChange }
            assertFalse(restored.providerEnabled)
            assertFalse(restored.canRetry)
            assertEquals(id, restored.selectedId)
            assertEquals(PreviewReplyStatus.INTERRUPTED, restored.conversation!!.turns.single().status)
            assertEquals("Keep this pending prompt", restored.conversation!!.turns.single().prompt)
            assertTrue(restored.conversation!!.turns.single().provider)
            assertEquals(1, calls)
            withContext(Dispatchers.Main) { restored.dispose() }
        } finally {
            firstOwner.cancelAndJoin()
            nextOwner.cancelAndJoin()
            id?.let { nextRepository.delete(it) }
            repository.close()
            nextRepository.close()
        }
    }

    private suspend fun await(predicate: () -> Boolean) = withTimeout(10_000) {
        while (!withContext(Dispatchers.Main) { predicate() }) delay(20)
    }
}
