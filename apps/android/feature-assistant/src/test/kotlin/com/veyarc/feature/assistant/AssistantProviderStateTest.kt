package com.veyarc.feature.assistant

import com.veyarc.model.WorkflowJson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class AssistantProviderStateTest {
    @Test
    fun optInDispatchAndRetryKeepOnePromptAndRejectLateResults() = runBlocking {
        val calls = mutableListOf<Pair<DeepSeekRequest, CompletableDeferred<ByteArray>>>()
        val state = AssistantPreviewState(scope = this, providerCall = { request ->
            val result = CompletableDeferred<ByteArray>()
            calls += request to result
            // Deliberately uncooperative fixture: attempt identity must still reject late results.
            withContext(NonCancellable) { result.await() }
        })
        state.open(AssistantPreviewContext())
        state.compose("Explain the workflow")
        state.send()
        state.testConnection()
        assertFalse(state.canSend)
        assertTrue(calls.isEmpty())
        state.enableProvider()
        state.setEffort(DeepSeekEffort.HIGH)
        yield()
        assertTrue(calls.isEmpty())
        state.send()
        state.send()
        yield()
        assertEquals(1, calls.size)
        assertEquals(DeepSeekEffort.HIGH, calls[0].first.effort)
        state.setEffort(DeepSeekEffort.LOW)
        assertEquals(DeepSeekEffort.HIGH, state.conversation!!.effort)
        val original = state.conversation!!.turns.single()
        state.advance(original.requestId)
        assertEquals("", state.conversation!!.turns.single().reply)
        state.stop()
        assertEquals(PreviewReplyStatus.STOPPED, state.conversation!!.turns.single().status)
        state.setEffort(DeepSeekEffort.MAX)
        state.retry()
        yield()
        assertEquals(2, calls.size)
        assertEquals(DeepSeekEffort.MAX, calls[1].first.effort)
        assertEquals(DeepSeekEffort.MAX, state.conversation!!.turns.single().effort)
        assertEquals(original.baseSnapshot, state.conversation!!.turns.single().baseSnapshot)
        assertNotEquals(original.requestId, state.requestId)
        calls[0].second.complete(reply(calls[0].first, "Late reply"))
        yield()
        assertEquals("", state.conversation!!.turns.single().reply)
        calls[1].second.complete(reply(calls[1].first, "Accepted reply"))
        yield()
        assertEquals("Accepted reply", state.conversation!!.turns.single().reply)
        assertEquals(1, state.conversation!!.turns.size)
        assertTrue(state.conversation!!.turns.single().provider)
        assertEquals(state.conversation, AssistantPreviewState.decode(AssistantPreviewState.encode(state.conversation!!)))
        val selectedId = state.selectedId!!
        state.branch(state.conversation!!.turns.single().id)
        assertEquals(DeepSeekEffort.MAX, state.conversation!!.effort)
        state.newChat()
        assertEquals(DeepSeekEffort.OFF, state.conversation!!.effort)
        state.select(selectedId)
        assertEquals(DeepSeekEffort.MAX, state.conversation!!.effort)
        state.disableProvider()
        assertFalse(state.canSend)
        state.dispose()
    }

    @Test
    fun historyContainsOnlySameChatCompletedProviderProseAndNeverSamplesOrCandidates() {
        val state = AssistantPreviewState()
        val chatId = UUID.randomUUID().toString()
        fun turn(prompt: String, provider: Boolean = true, status: PreviewReplyStatus = PreviewReplyStatus.COMPLETE) = PreviewTurn(
            UUID.randomUUID().toString(),
            prompt,
            PreviewScenario.EXPLANATION,
            reply = "$prompt reply",
            status = status,
            provider = provider,
            outcome = if (status == PreviewReplyStatus.COMPLETE) AssistantOutcome.EXPLANATION else null,
        )
        val chat = PreviewConversation(
            chatId,
            null,
            turns = listOf(
                turn("local-secret-sample", provider = false),
                turn("stopped-secret", status = PreviewReplyStatus.STOPPED),
                turn("failed-secret", status = PreviewReplyStatus.ERROR),
                turn("eligible"),
                turn("invalid-candidate-secret").copy(outcome = AssistantOutcome.PROPOSAL, proposal = AssistantProposalCheck(issues = listOf("invalid"))),
                turn("current", status = PreviewReplyStatus.RESPONDING),
            ),
        )
        val body = state.providerRequest(chat).body.toString(Charsets.UTF_8)
        val messages = WorkflowJson.format.parseToJsonElement(body).jsonObject.getValue("messages").jsonArray
        assertEquals(4, messages.size)
        assertEquals("eligible", messages[1].jsonObject.getValue("content").jsonPrimitive.content)
        assertEquals("eligible reply", messages[2].jsonObject.getValue("content").jsonPrimitive.content)
        assertFalse(body.contains("secret"))
        val other = chat.copy(id = UUID.randomUUID().toString(), turns = listOf(chat.turns.last()))
        assertEquals(2, WorkflowJson.format.parseToJsonElement(state.providerRequest(other).body.toString(Charsets.UTF_8)).jsonObject.getValue("messages").jsonArray.size)
        val oversized = chat.copy(turns = List(12) { turn("x".repeat(4000)).copy(reply = "y".repeat(16000)) } + chat.turns.last())
        assertEquals(DeepSeekFailure.REQUEST_TOO_LARGE, (runCatching { state.providerRequest(oversized) }.exceptionOrNull() as DeepSeekException).reason)
        val stored = AssistantPreviewState.encode(chat.copy(turns = listOf(chat.turns.first())))
        val legacy = stored.copy(payload = stored.payload.toString(Charsets.UTF_8).replace(",\"provider\":false", "").toByteArray())
        assertFalse(AssistantPreviewState.decode(legacy).turns.single().provider)
        val legacyEffort = stored.copy(payload = stored.payload.toString(Charsets.UTF_8).replace(",\"effort\":\"OFF\"", "").toByteArray())
        assertEquals(DeepSeekEffort.OFF, AssistantPreviewState.decode(legacyEffort).effort)
        assertEquals(DeepSeekEffort.OFF, AssistantPreviewState.decode(legacyEffort).turns.single().effort)
    }

    @Test
    fun failuresAreSanitizedAndDisableCloseClearAndOwnerLossCancelWithoutResending() = runBlocking {
        val owner = CoroutineScope(coroutineContext + SupervisorJob())
        val results = mutableListOf<CompletableDeferred<ByteArray>>()
        var calls = 0
        val state = AssistantPreviewState(scope = owner, providerCall = {
            calls++
            if (calls == 1) error("raw-secret-provider-detail")
            CompletableDeferred<ByteArray>().also { results += it }.await()
        })
        try {
            state.open(AssistantPreviewContext())
            state.enableProvider()
            state.compose("Keep my prompt")
            state.send()
            yield()
            assertEquals(PreviewReplyStatus.ERROR, state.conversation!!.turns.last().status)
            assertFalse(state.conversation!!.turns.last().responseError!!.contains("secret"))
            state.retry()
            yield()
            state.disableProvider()
            yield()
            assertEquals(PreviewReplyStatus.INTERRUPTED, state.conversation!!.turns.last().status)
            state.enableProvider()
            assertEquals(2, calls)
            state.retry()
            yield()
            state.close()
            yield()
            state.open(AssistantPreviewContext())
            assertEquals(3, calls)
            state.retry()
            yield()
            state.clearHistory()
            yield()
            assertTrue(state.conversations.isEmpty())
            state.newChat()
            state.compose("Owner loss")
            state.send()
            yield()
            owner.cancel()
            yield()
            assertNull(state.requestId)
            assertEquals(PreviewReplyStatus.INTERRUPTED, state.conversation!!.turns.single().status)
            assertEquals(5, calls)
        } finally {
            state.dispose()
            owner.cancel()
        }
    }

    @Test
    fun connectionTestHasNoChatOrSchemaAndOldCancellationCannotFinishNewTest() = runBlocking {
        val calls = mutableListOf<Pair<DeepSeekRequest, CompletableDeferred<ByteArray>>>()
        val state = AssistantPreviewState(scope = this, providerCall = { request ->
            val result = CompletableDeferred<ByteArray>()
            calls += request to result
            withContext(NonCancellable) { result.await() }
        })
        state.enableProvider()
        state.testConnection()
        yield()
        val request = calls[0].first
        val body = WorkflowJson.format.parseToJsonElement(request.body.toString(Charsets.UTF_8)).jsonObject
        assertEquals("128", body.getValue("max_tokens").jsonPrimitive.content)
        assertEquals(2, body.getValue("messages").jsonArray.size)
        assertTrue(request.body.size < 1024)
        assertFalse(request.streaming)
        assertFalse(request.body.toString(Charsets.UTF_8).contains("workflow.schema"))
        assertTrue(state.conversations.isEmpty())
        state.stop()
        state.testConnection()
        yield()
        calls[0].second.complete(reply(request, "Old test"))
        yield()
        assertTrue(state.testingConnection)
        assertNull(state.connectionTestStatus)
        calls[1].second.complete(reply(calls[1].first, "Connected"))
        yield()
        assertFalse(state.testingConnection)
        assertTrue(state.connectionTestStatus!!.startsWith("Connection succeeded"))
        state.dispose()
    }

    private fun reply(request: DeepSeekRequest, text: String) = AssistantResponseCodec.encode(
        AssistantResponse(1, request.requestId, request.conversationId, request.baseSnapshotId, AssistantOutcome.EXPLANATION, text),
    )
}
