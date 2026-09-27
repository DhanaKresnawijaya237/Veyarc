package com.veyarc.feature.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantPreviewStateTest {
    @Test
    fun stopRetryAndLateTicksNeverDuplicateThePrompt() {
        val state = AssistantPreviewState()
        state.open(AssistantPreviewContext("workflow-a"))
        state.compose("   ")
        state.send()
        assertTrue(state.conversation!!.turns.isEmpty())
        state.compose("Explain the wait")
        state.send()
        val firstToken = state.requestId!!
        state.send()
        state.advance(firstToken)
        state.stop()
        val partial = state.conversation!!.turns.single()
        assertEquals(PreviewReplyStatus.STOPPED, partial.status)
        state.advance(firstToken)
        assertEquals(partial, state.conversation!!.turns.single())
        state.retry()
        assertNotEquals(firstToken, state.requestId)
        state.advance(firstToken)
        assertEquals("", state.conversation!!.turns.single().reply)
        finish(state)
        assertEquals(PreviewReplyStatus.COMPLETE, state.conversation!!.turns.single().status)
        assertEquals("Explain the wait", state.conversation!!.turns.single().prompt)
        assertNull(state.requestId)
    }

    @Test
    fun historyIsScopedAndClosingKeepsTheComposerWithoutResuming() {
        val state = AssistantPreviewState()
        val firstContext = AssistantPreviewContext("workflow-a", "Morning", 2)
        state.open(firstContext)
        state.compose("First prompt")
        state.send()
        state.compose("Unsent follow-up")
        state.close()
        state.open(firstContext)
        assertEquals("Unsent follow-up", state.conversation!!.composer)
        assertEquals(PreviewReplyStatus.INTERRUPTED, state.conversation!!.turns.single().status)
        assertNull(state.requestId)
        val firstChatId = state.selectedId!!
        state.open(AssistantPreviewContext("workflow-b"))
        assertEquals(1, state.history.size)
        state.select(firstChatId)
        assertNotEquals(firstChatId, state.selectedId)
        state.open(firstContext)
        assertEquals(firstChatId, state.selectedId)
        state.rename("My chat")
        assertEquals("My chat", state.conversation!!.title)
        state.deleteCurrent()
        assertNull(state.conversation)
        assertTrue(state.history.isEmpty())
    }

    @Test
    fun everyFixtureTerminatesAndPreviewBoundsAreEnforced() {
        val state = AssistantPreviewState()
        state.open(AssistantPreviewContext())
        PreviewScenario.entries.forEach { scenario ->
            state.scenario = scenario
            state.compose("Sample")
            state.send()
            finish(state)
            val turn = state.conversation!!.turns.last()
            assertTrue(turn.reply.isNotBlank() || turn.responseError != null)
            assertEquals(if (scenario in listOf(PreviewScenario.ERROR, PreviewScenario.INVALID_OUTPUT)) PreviewReplyStatus.ERROR else PreviewReplyStatus.COMPLETE, turn.status)
        }
        state.compose("x".repeat(AssistantPreviewState.MAX_PROMPT_LENGTH + 1))
        assertEquals("", state.conversation!!.composer)
        assertTrue(state.inputError != null)
        repeat(AssistantPreviewState.MAX_TURNS) {
            state.compose("Sample")
            state.send()
            finish(state)
        }
        assertEquals(AssistantPreviewState.MAX_TURNS, state.conversation!!.turns.size)
        assertFalse(state.canSend)
        repeat(AssistantPreviewState.MAX_CHATS) { state.newChat() }
        assertEquals(AssistantPreviewState.MAX_CHATS, state.conversations.size)
    }

    private fun finish(state: AssistantPreviewState) {
        repeat(100) { state.requestId?.let(state::advance) }
        assertNull("Fixture must terminate", state.requestId)
    }

    @Test
    fun encryptedPayloadContractPreservesIdsAndRejectsMetadataMismatch() {
        val state = AssistantPreviewState()
        state.open(AssistantPreviewContext("workflow-a", "My workflow", 2))
        state.compose("Keep this prompt")
        state.send()
        state.advance(state.requestId!!)
        state.stop()
        state.compose("Unsent text")
        val chat = state.conversation!!
        val stored = AssistantPreviewState.encode(chat)
        assertEquals(chat, AssistantPreviewState.decode(stored))
        assertTrue(runCatching { AssistantPreviewState.decode(stored.copy(id = java.util.UUID.randomUUID().toString())) }.isFailure)
        assertTrue(runCatching { AssistantPreviewState.decode(stored.copy(status = "COMPLETE")) }.isFailure)
    }
}
