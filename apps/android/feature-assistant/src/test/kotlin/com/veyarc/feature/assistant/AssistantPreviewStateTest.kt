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
    fun everyFixtureTerminatesAndLongChatsAreAllowed() {
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
        state.scenario = PreviewScenario.EXPLANATION
        repeat(45) {
            state.compose("Sample $it " + "x".repeat(5000))
            state.send()
            finish(state)
        }
        assertTrue(state.conversation!!.turns.size > 40)
        state.compose("One more")
        assertTrue(state.canSend)
        repeat(25) {
            state.newChat()
            state.compose("Chat $it")
            state.send()
            finish(state)
        }
        assertEquals(26, state.conversations.size)
    }

    @Test
    fun newChatReusesAnEmptyChatAndKeepsItsUnsentMessage() {
        val state = AssistantPreviewState().apply { open(AssistantPreviewContext()) }
        val id = state.selectedId
        state.compose("Unsent message")
        repeat(5) { state.newChat() }
        assertEquals(id, state.selectedId)
        assertEquals("Unsent message", state.conversation!!.composer)
        assertEquals(1, state.conversations.size)
        state.send()
        state.newChat()
        assertNotEquals(id, state.selectedId)
        assertEquals(2, state.conversations.size)
    }

    private fun finish(state: AssistantPreviewState) {
        repeat(100) { state.requestId?.let(state::advance) }
        assertNull("Fixture must terminate", state.requestId)
    }

    @Test
    fun branchAndRegenerateKeepOriginalMessagesAndNeverSendOnPinOrBranch() {
        val state = AssistantPreviewState().apply { open(AssistantPreviewContext("workflow-a")) }
        repeat(3) {
            state.compose("Question $it")
            state.send()
            finish(state)
        }
        state.compose("Unsent follow-up")
        val original = state.conversation!!
        state.togglePin()
        val pinned = state.conversation!!
        assertTrue(pinned.pinned)
        assertEquals(pinned, AssistantPreviewState.decode(AssistantPreviewState.encode(pinned)))
        state.branch(original.turns[1].id)
        val branch = state.conversation!!
        assertNotEquals(original.id, branch.id)
        assertEquals(original.turns.take(2).map { it.reply }, branch.turns.map { it.reply })
        assertEquals("", branch.composer)
        assertTrue(branch.turns.all { it.localBindingId == null })
        assertFalse(branch.pinned)
        assertEquals(pinned, state.conversations.first { it.id == original.id })
        assertNull(state.requestId)
        assertEquals(pinned, state.history.first())
        state.select(original.id)
        state.regenerate(original.turns.first().id)
        assertNotEquals(original.id, state.selectedId)
        assertEquals(1, state.conversation!!.turns.size)
        assertEquals("Question 0", state.conversation!!.turns.single().prompt)
        assertNotEquals(original.turns.first().requestId, state.requestId)
        finish(state)
        assertEquals(pinned, state.conversations.first { it.id == original.id })
        repeat(25) {
            state.newChat()
            state.compose("Chat $it")
            state.send()
            finish(state)
        }
        state.select(original.id)
        assertTrue(state.canBranch(original.turns.first()))
        assertTrue(state.canRegenerate(original.turns.first()))
        state.regenerate(original.turns.first().id)
        assertNotEquals(original.id, state.selectedId)
        finish(state)
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
