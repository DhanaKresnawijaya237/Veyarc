package com.veyarc.feature.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class AssistantResponseTest {
    private val request = UUID.randomUUID().toString()
    private val conversation = UUID.randomUUID().toString()
    private val base = "a".repeat(64)
    private fun response(outcome: AssistantOutcome = AssistantOutcome.EXPLANATION) = AssistantResponse(1, request, conversation, base, outcome, "Sample text with a quoted \"key\": value")
    private fun decode(bytes: ByteArray) = AssistantResponseCodec.decode(bytes, request, conversation, base)

    @Test
    fun acceptsBothTextOutcomesAndRejectsWrongBindings() {
        listOf(AssistantOutcome.EXPLANATION, AssistantOutcome.CLARIFICATION).forEach { assertEquals(response(it), decode(AssistantResponseCodec.encode(response(it)))) }
        listOf(response().copy(contractVersion = 2), response().copy(requestId = UUID.randomUUID().toString()), response().copy(conversationId = UUID.randomUUID().toString()), response().copy(baseSnapshotId = "different")).forEach {
            assertTrue(runCatching { decode(AssistantResponseCodec.encode(it)) }.isFailure)
        }
    }

    @Test
    fun rejectsMalformedUnknownDuplicateNestedNullAndNonStringFields() {
        val valid = AssistantResponseCodec.encode(response()).toString(Charsets.UTF_8)
        listOf(
            valid.dropLast(1), valid + " garbage", "[$valid]", "```json\n$valid\n```",
            valid.replace("\"explanation\"", "\"proposal\""),
            valid.replace("\"explanation\"", "null"),
            valid.replace("\"explanation\"", "{}"),
            valid.replace("\"explanation\"", "17"),
            valid.replace("\"contractVersion\":1", "\"contractVersion\":\"1\""),
            valid.replace("\"contractVersion\":1", "\"contractVersion\":1,\"contractVersion\":1"),
            valid.replace("\"contractVersion\":1", "\"contractVersion\":1,\"contractVer\\u0073ion\":1"),
            valid.replace("\"contractVersion\":1", "\"contractVersion\":1,\"candidate\":\"untrusted\""),
            valid.replace("\"contractVersion\":1,", ""),
            "{\"text\":" + "[".repeat(1000) + "0" + "]".repeat(1000) + "}",
        ).forEach { assertTrue("Must reject $it", runCatching { decode(it.toByteArray()) }.isFailure) }
    }

    @Test
    fun proposalEnvelopeRequiresCandidateAndDuplicateChecksAreScopedToEachObject() {
        val candidate = kotlinx.serialization.json.Json.parseToJsonElement("{\"steps\":[{\"id\":\"a\"},{\"id\":\"b\"}]}") as kotlinx.serialization.json.JsonObject
        val proposal = response().copy(outcome = AssistantOutcome.PROPOSAL, candidate = candidate)
        assertEquals(proposal, decode(AssistantResponseCodec.encode(proposal)))
        assertTrue(runCatching { decode(AssistantResponseCodec.encode(proposal.copy(candidate = null))) }.isFailure)
        assertTrue(runCatching { decode(AssistantResponseCodec.encode(proposal.copy(outcome = AssistantOutcome.EXPLANATION))) }.isFailure)
        val duplicate = AssistantResponseCodec.encode(proposal).toString(Charsets.UTF_8).replace("\"id\":\"a\"", "\"id\":\"a\",\"id\":\"b\"")
        assertTrue(runCatching { decode(duplicate.toByteArray()) }.isFailure)
    }

    @Test
    fun enforcesByteTextAndUtf8Limits() {
        assertTrue(runCatching { decode(ByteArray(AssistantResponseCodec.MAX_BYTES + 1)) }.isFailure)
        assertTrue(runCatching { decode(byteArrayOf(0xc3.toByte(), 0x28)) }.isFailure)
        assertTrue(runCatching { decode(AssistantResponseCodec.encode(response().copy(text = " "))) }.isFailure)
        assertTrue(runCatching { decode(AssistantResponseCodec.encode(response().copy(text = "a".repeat(16001)))) }.isFailure)
        val maximumText = response().copy(text = "界".repeat(16000))
        assertEquals(maximumText, decode(AssistantResponseCodec.encode(maximumText)))
    }

    @Test
    fun rejectionKeepsPromptAndPartialTextAndLateOrDuplicateResultsAreIgnored() {
        val state = AssistantPreviewState().apply {
            open(AssistantPreviewContext())
            compose("Keep me")
            send()
        }
        val first = state.requestId!!
        state.advance(first)
        val partial = state.conversation!!.turns.single().reply
        state.receive(first, "{}".toByteArray())
        val failed = state.conversation!!.turns.single()
        assertEquals("Keep me", failed.prompt)
        assertEquals(partial, failed.reply)
        assertEquals(PreviewReplyStatus.ERROR, failed.status)
        assertTrue(failed.responseError != null)
        assertNull(state.requestId)
        state.retry()
        val chat = state.conversation!!
        val turn = chat.turns.single()
        val accepted = AssistantResponse(1, turn.requestId, chat.id, turn.responseBaseId(chat), AssistantOutcome.CLARIFICATION, "Which app?")
        state.receive(first, AssistantResponseCodec.encode(accepted))
        assertEquals(PreviewReplyStatus.RESPONDING, state.conversation!!.turns.single().status)
        state.receive(turn.requestId, AssistantResponseCodec.encode(accepted))
        val complete = state.conversation!!
        assertEquals(AssistantOutcome.CLARIFICATION, complete.turns.single().outcome)
        assertEquals(complete, AssistantPreviewState.decode(AssistantPreviewState.encode(complete)))
        state.receive(turn.requestId, "{}".toByteArray())
        assertEquals(complete, state.conversation)
        state.compose("Second prompt")
        state.send()
        val stoppedToken = state.requestId!!
        state.stop()
        val stopped = state.conversation
        state.receive(stoppedToken, "{}".toByteArray())
        assertEquals(stopped, state.conversation)
    }
}
