package com.veyarc.feature.assistant

import com.veyarc.model.WorkflowJson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    fun defaultsApplyOnlyToNewChatsAndComposerChoicesStayInTheirChat() = runBlocking {
        var modelSaved: DeepSeekModel? = null
        var effortSaved: DeepSeekEffort? = null
        val state = AssistantPreviewState(scope = this, providerCall = { error("No request expected") }, onModelChanged = { modelSaved = it }, onDefaultEffortChanged = { effortSaved = it })
        state.restoreModelPreference(DeepSeekModel.PRO)
        state.restoreEffortPreference(DeepSeekEffort.HIGH)
        state.open(AssistantPreviewContext())
        assertEquals(DeepSeekModel.PRO, state.selectedModel)
        assertEquals(DeepSeekEffort.HIGH, state.conversation!!.effort)
        val first = state.selectedId!!
        state.chooseDefaultModel(DeepSeekModel.FLASH)
        state.chooseDefaultEffort(DeepSeekEffort.LOW)
        assertEquals(DeepSeekModel.FLASH, modelSaved)
        assertEquals(DeepSeekEffort.LOW, effortSaved)
        assertEquals(DeepSeekModel.PRO, state.selectedModel)
        assertEquals(DeepSeekEffort.HIGH, state.conversation!!.effort)
        state.enableProvider()
        state.compose("Explain")
        state.send()
        state.stop()
        state.newChat()
        assertEquals(DeepSeekModel.FLASH, state.selectedModel)
        assertEquals(DeepSeekEffort.LOW, state.conversation!!.effort)
        state.setModel(DeepSeekModel.PRO)
        state.setEffort(DeepSeekEffort.MAX)
        assertEquals(DeepSeekModel.FLASH, state.defaultModel)
        assertEquals(DeepSeekEffort.LOW, state.defaultEffort)
        state.select(first)
        assertEquals(DeepSeekModel.PRO, state.selectedModel)
        assertEquals(DeepSeekEffort.HIGH, state.conversation!!.effort)
        state.dispose()
    }

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
        state.setModel(DeepSeekModel.PRO)
        yield()
        assertTrue(calls.isEmpty())
        state.send()
        state.send()
        yield()
        assertEquals(1, calls.size)
        assertEquals(DeepSeekEffort.HIGH, calls[0].first.effort)
        assertEquals(DeepSeekModel.PRO, calls[0].first.model)
        withContext(Dispatchers.IO) { calls[0].first.reportThinking(AssistantThinking("Live first thought")) }
        yield()
        assertEquals("Live first thought", state.conversation!!.turns.single().thinking!!.text)
        assertNull(state.conversation!!.turns.single().outcome)
        assertNull(state.conversation!!.turns.single().proposal)
        withContext(Dispatchers.IO) { calls[0].first.reportReply("Live partial answer") }
        yield()
        assertEquals("Live partial answer", state.conversation!!.turns.single().reply)
        assertEquals(PreviewReplyStatus.RESPONDING, state.conversation!!.turns.single().status)
        assertNull(state.conversation!!.turns.single().proposal)
        state.setEffort(DeepSeekEffort.LOW)
        state.setModel(DeepSeekModel.FLASH)
        assertEquals(DeepSeekModel.PRO, state.selectedModel)
        assertEquals(DeepSeekEffort.HIGH, state.conversation!!.effort)
        val original = state.conversation!!.turns.single()
        state.advance(original.requestId)
        assertEquals("Live partial answer", state.conversation!!.turns.single().reply)
        state.stop()
        assertEquals(PreviewReplyStatus.STOPPED, state.conversation!!.turns.single().status)
        assertEquals("Live first thought", state.conversation!!.turns.single().thinking!!.text)
        state.setEffort(DeepSeekEffort.MAX)
        state.setModel(DeepSeekModel.FLASH)
        state.retry()
        yield()
        assertEquals(2, calls.size)
        assertNull(state.conversation!!.turns.single().thinking)
        calls[0].first.reportThinking(AssistantThinking("Stale secret thought", finished = true))
        calls[0].first.reportReply("Stale partial answer")
        yield()
        assertNull(state.conversation!!.turns.single().thinking)
        assertEquals("", state.conversation!!.turns.single().reply)
        assertEquals(DeepSeekEffort.MAX, calls[1].first.effort)
        assertEquals(DeepSeekModel.FLASH, calls[1].first.model)
        assertEquals(DeepSeekEffort.MAX, state.conversation!!.turns.single().effort)
        assertEquals(original.baseSnapshot, state.conversation!!.turns.single().baseSnapshot)
        assertNotEquals(original.requestId, state.requestId)
        calls[0].second.complete(reply(calls[0].first, "Late reply"))
        yield()
        assertEquals("", state.conversation!!.turns.single().reply)
        calls[1].first.reportThinking(AssistantThinking("Current thought", finished = true))
        calls[1].second.complete(reply(calls[1].first, "Accepted reply"))
        yield()
        assertEquals("Accepted reply", state.conversation!!.turns.single().reply)
        assertEquals(AssistantThinking("Current thought", finished = true), state.conversation!!.turns.single().thinking)
        calls[1].first.reportThinking(AssistantThinking("Late thought after completion"))
        calls[1].first.reportReply("Late answer after completion")
        yield()
        assertEquals("Current thought", state.conversation!!.turns.single().thinking!!.text)
        assertEquals("Accepted reply", state.conversation!!.turns.single().reply)
        assertEquals(1, state.conversation!!.turns.size)
        assertTrue(state.conversation!!.turns.single().provider)
        assertEquals(state.conversation, AssistantPreviewState.decode(AssistantPreviewState.encode(state.conversation!!)))
        val selectedId = state.selectedId!!
        state.branch(state.conversation!!.turns.single().id)
        assertEquals(DeepSeekEffort.MAX, state.conversation!!.effort)
        assertEquals(DeepSeekModel.FLASH, state.selectedModel)
        state.setModel(DeepSeekModel.PRO)
        state.newChat()
        assertEquals(DeepSeekEffort.OFF, state.conversation!!.effort)
        assertEquals(DeepSeekModel.FLASH, state.selectedModel)
        state.select(selectedId)
        assertEquals(DeepSeekEffort.MAX, state.conversation!!.effort)
        assertEquals(DeepSeekModel.FLASH, state.selectedModel)
        state.setModel(DeepSeekModel.PRO)
        state.regenerate(state.conversation!!.turns.single().id)
        yield()
        assertEquals(DeepSeekModel.PRO, calls[2].first.model)
        calls[2].second.complete(reply(calls[2].first, "Regenerated"))
        yield()
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
                turn("eligible").copy(effort = DeepSeekEffort.HIGH, thinking = AssistantThinking("secret thinking never sent", finished = true)),
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
        val longChat = chat.copy(turns = List(45) { turn("Earlier $it").copy(reply = "y".repeat(60000)) } + chat.turns.last())
        val window = WorkflowJson.format.parseToJsonElement(state.providerRequest(longChat).body.toString(Charsets.UTF_8)).jsonObject.getValue("messages").jsonArray
        assertTrue(window.size < 92)
        assertEquals("Earlier 44", window[window.size - 3].jsonObject.getValue("content").jsonPrimitive.content)
        assertEquals(46, longChat.turns.size)
        val stored = AssistantPreviewState.encode(chat.copy(turns = listOf(chat.turns.first())))
        val legacy = stored.copy(payload = stored.payload.toString(Charsets.UTF_8).replace(",\"provider\":false", "").toByteArray())
        assertFalse(AssistantPreviewState.decode(legacy).turns.single().provider)
        val legacyEffort = stored.copy(payload = stored.payload.toString(Charsets.UTF_8).replace(",\"effort\":\"OFF\"", "").toByteArray())
        assertEquals(DeepSeekEffort.OFF, AssistantPreviewState.decode(legacyEffort).effort)
        assertEquals(DeepSeekEffort.OFF, AssistantPreviewState.decode(legacyEffort).turns.single().effort)
        val legacyThinking = stored.copy(payload = stored.payload.toString(Charsets.UTF_8).replace(",\"thinking\":null", "").toByteArray())
        assertNull(AssistantPreviewState.decode(legacyThinking).turns.single().thinking)
    }

    @Test
    fun savingLongThinkingRetainsEveryThoughtAndAnswerWithoutTruncating() {
        val chat = PreviewConversation(
            UUID.randomUUID().toString(),
            null,
            turns = List(10) {
                PreviewTurn(
                    UUID.randomUUID().toString(), "Prompt $it", PreviewScenario.EXPLANATION,
                    reply = "Answer $it " + "a".repeat(15000), status = PreviewReplyStatus.COMPLETE,
                    provider = true, effort = DeepSeekEffort.HIGH, outcome = AssistantOutcome.EXPLANATION,
                    thinking = AssistantThinking("Thought $it " + "b".repeat(30000), finished = true),
                )
            },
        )
        val stored = AssistantPreviewState.encode(chat)
        assertTrue(stored.payload.size <= com.veyarc.data.storage.ChatRepository.MAX_PAYLOAD_BYTES)
        val restored = AssistantPreviewState.decode(stored)
        assertEquals(chat.turns.map { it.reply }, restored.turns.map { it.reply })
        assertEquals(chat.turns.map { it.prompt }, restored.turns.map { it.prompt })
        assertTrue(stored.payload.size > 256 * 1024)
        assertEquals(chat, restored)
        assertTrue(restored.turns.none { it.thinking!!.truncated })
        assertTrue(runCatching { AssistantThinking("x".repeat(AssistantThinking.MAX_LENGTH + 1)) }.isFailure)
        assertTrue(runCatching { AssistantPreviewState.encode(chat.copy(turns = chat.turns.map { it.copy(effort = DeepSeekEffort.OFF) })) }.isFailure)
        val oversizedCore = chat.copy(turns = List(20) { chat.turns[it % 10].copy(id = UUID.randomUUID().toString(), thinking = null) })
        assertEquals(oversizedCore, AssistantPreviewState.decode(AssistantPreviewState.encode(oversizedCore)))
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
        state.setModel(DeepSeekModel.PRO)
        state.testConnection()
        yield()
        val request = calls[0].first
        val body = WorkflowJson.format.parseToJsonElement(request.body.toString(Charsets.UTF_8)).jsonObject
        assertEquals("128", body.getValue("max_tokens").jsonPrimitive.content)
        assertEquals(DeepSeekModel.PRO.apiId, body.getValue("model").jsonPrimitive.content)
        state.setModel(DeepSeekModel.FLASH)
        assertEquals(DeepSeekModel.PRO, state.selectedModel)
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
        assertEquals("Connected.", state.connectionTestStatus)
        state.dispose()
    }

    private fun reply(request: DeepSeekRequest, text: String) = AssistantResponseCodec.encode(
        AssistantResponse(1, request.requestId, request.conversationId, request.baseSnapshotId, AssistantOutcome.EXPLANATION, text),
    )
}
