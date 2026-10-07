package com.veyarc.app

import android.content.ClipboardManager
import android.content.Context
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.assertTouchWidthIsEqualTo
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.veyarc.app.ui.theme.VeyarcTheme
import com.veyarc.feature.assistant.AssistantOutcome
import com.veyarc.feature.assistant.AssistantPreviewContext
import com.veyarc.feature.assistant.AssistantPreviewHost
import com.veyarc.feature.assistant.AssistantPreviewState
import com.veyarc.feature.assistant.AssistantResponse
import com.veyarc.feature.assistant.PreviewReplyStatus
import com.veyarc.model.WorkflowJson
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** No key, socket, or paid request. The retained owner exercises the production chat composable. */
class AssistantAcceptanceOwner : ViewModel() {
    var calls = 0
    var cancellations = 0
    var response = CompletableDeferred<ByteArray>()
    val state = AssistantPreviewState(scope = viewModelScope, providerCall = {
        calls++
        try {
            response.await()
        } finally {
            if (!response.isCompleted) cancellations++
        }
    }).apply {
        open(AssistantPreviewContext())
        enableProvider()
    }

    fun complete(text: String) {
        val chat = state.conversation!!
        response.complete(WorkflowJson.encodeCanonical(AssistantResponse.serializer(), AssistantResponse(1, state.requestId!!, chat.id, "draft:${chat.draftId}", AssistantOutcome.EXPLANATION, text)).toByteArray())
    }

    override fun onCleared() {
        state.dispose()
    }
}

@RunWith(AndroidJUnit4::class)
class AssistantAccessibilityAcceptanceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun show(): AssistantAcceptanceOwner {
        lateinit var owner: AssistantAcceptanceOwner
        compose.activityRule.scenario.onActivity { activity ->
            owner = ViewModelProvider(activity)[AssistantAcceptanceOwner::class.java]
            activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
            activity.setContent {
                VeyarcTheme {
                    AssistantPreviewHost(true, owner.state, AssistantPreviewContext()) { androidx.compose.material3.Text("Editor fixture") }
                }
            }
        }
        compose.waitForIdle()
        return owner
    }

    @Test
    fun pendingProviderSurvivesRecreationAndBackCancelsWithoutResend() {
        val owner = show()
        compose.runOnIdle {
            owner.state.compose("Explain this draft")
            owner.state.send()
        }
        compose.waitUntil(10_000) { owner.calls == 1 }
        val token = owner.state.requestId
        compose.activityRule.scenario.recreate()
        assertSame(owner, show())
        compose.onNodeWithContentDescription("Stop response").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(token, owner.state.requestId)
            assertEquals(1, owner.calls)
            assertEquals(0, owner.cancellations)
        }
        compose.onNodeWithContentDescription("Back to editor").performClick()
        compose.waitUntil(10_000) { owner.cancellations == 1 }
        compose.runOnIdle {
            assertFalse(owner.state.isOpen)
            assertEquals(PreviewReplyStatus.INTERRUPTED, owner.state.conversation!!.turns.single().status)
            assertEquals(1, owner.calls)
            assertEquals(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING, compose.activity.window.attributes.softInputMode)
        }
    }

    @Test
    fun providerControlsHaveTouchTargetsAndStatusAnnouncements() {
        val owner = show()
        listOf("Back to editor", "New chat", "Chat options", "Send message").forEach {
            compose.onNodeWithContentDescription(it).assertIsDisplayed().assertTouchWidthIsEqualTo(48.dp).assertTouchHeightIsEqualTo(48.dp)
        }
        compose.runOnIdle {
            owner.state.compose("Explain")
            owner.state.send()
        }
        compose.onNodeWithText("Responding…").performScrollTo().assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        compose.onNodeWithContentDescription("Stop response").assertIsEnabled().performClick()
        compose.onNodeWithText("Stopped").performScrollTo().assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        compose.runOnIdle { owner.response = CompletableDeferred() }
        compose.onNodeWithText("Retry request").performScrollTo().performClick()
        compose.waitUntil(10_000) { owner.calls == 2 }
        compose.runOnIdle { owner.complete("A complete explanation.") }
        compose.onNodeWithTag("assistant-reply-${owner.state.conversation!!.turns.last().id}").performScrollTo().assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        compose.onNodeWithText("Copy").assertDoesNotExist()
        compose.onNodeWithText("View captured context").assertDoesNotExist()
        compose.onNodeWithText("A complete explanation.").performTouchInput { longClick() }
        compose.onNodeWithText("Copy").performClick()
        compose.runOnIdle {
            val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals("A complete explanation.", clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
    }

    @Test
    fun completingLongReplyDoesNotPullReaderFromOlderMessages() {
        val owner = show()
        compose.runOnIdle {
            owner.state.compose("First question")
            owner.state.send()
        }
        compose.waitUntil(10_000) { owner.calls == 1 }
        compose.runOnIdle { owner.complete((1..80).joinToString("\n") { "Line $it describes a workflow detail." }) }
        compose.onNodeWithTag("assistant-reply-${owner.state.conversation!!.turns.single().id}").performScrollTo()
        compose.runOnIdle {
            owner.response = CompletableDeferred()
            owner.state.compose("Second question")
            owner.state.send()
        }
        compose.waitUntil(10_000) { owner.calls == 2 }
        compose.onNodeWithTag("assistant-messages").performScrollToIndex(1)
        compose.onNodeWithTag("assistant-messages").performTouchInput { swipeDown() }
        compose.onNode(hasText("First question") and hasAnyAncestor(hasTestTag("assistant-messages"))).assertIsDisplayed()
        compose.runOnIdle { owner.complete("The later answer is complete.") }
        compose.onNode(hasText("First question") and hasAnyAncestor(hasTestTag("assistant-messages"))).assertIsDisplayed()
        val jump = compose.onNodeWithContentDescription("Jump to latest").assertIsDisplayed()
        val buttonBounds = jump.fetchSemanticsNode().boundsInRoot
        val messageBounds = compose.onNodeWithTag("assistant-messages").fetchSemanticsNode().boundsInRoot
        assertTrue(buttonBounds.center.x > messageBounds.center.x)
        jump.performClick()
        compose.onNodeWithText("The later answer is complete.").assertIsDisplayed()
        compose.runOnIdle { assertTrue(owner.state.conversation!!.turns.all { it.status == PreviewReplyStatus.COMPLETE }) }
    }
}
