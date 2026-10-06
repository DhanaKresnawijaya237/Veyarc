package com.veyarc.app

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.veyarc.app.ui.theme.VeyarcTheme
import com.veyarc.data.storage.AssistantConnection
import com.veyarc.data.storage.AssistantConnections
import com.veyarc.data.storage.AssistantModelConfig
import com.veyarc.feature.assistant.AssistantAttachmentImporter
import com.veyarc.feature.assistant.AssistantPreviewContext
import com.veyarc.feature.assistant.AssistantPreviewScreen
import com.veyarc.feature.assistant.AssistantPreviewState
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class AssistantProviderPickerUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun nativeOpenAIShowsExtraHighEffortAndAccurateSummaryLabel() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val profile = AssistantConnection.openAI().copy(defaultEffort = "HIGH")
        val owner = kotlinx.coroutines.SupervisorJob()
        val pending = kotlinx.coroutines.CompletableDeferred<ByteArray>()
        val state = AssistantPreviewState(scope = kotlinx.coroutines.CoroutineScope(owner + kotlinx.coroutines.Dispatchers.Main), providerCall = { request ->
            request.reportThinking(com.veyarc.feature.assistant.AssistantThinking("Checking the requested change.", summary = true, finished = true))
            request.reportReply("First paragraph.\n\nSecond paragraph.")
            pending.await()
        }, attachments = AssistantAttachmentImporter(context))
        try {
            compose.runOnUiThread {
                state.restoreConnections(AssistantConnections(listOf(profile), profile.id))
                state.open(AssistantPreviewContext())
            }
            compose.setContent { VeyarcTheme { AssistantPreviewScreen(state) } }
            compose.onNodeWithText("OpenAI GPT-5.4").assertExists()
            compose.onNodeWithContentDescription("Add attachment").performClick()
            compose.onNodeWithText("Camera").assertIsEnabled()
            compose.onNodeWithText("Photos").assertIsEnabled()
            compose.onNodeWithText("Files").assertIsEnabled()
            compose.onNodeWithContentDescription("Close Add to chat").performClick()
            compose.onNodeWithTag("assistant-model-picker").performClick()
            val effortBounds = compose.onNodeWithTag("chat-model-effort").fetchSemanticsNode().boundsInRoot
            val modelBounds = compose.onNodeWithText("GPT-5.4").fetchSemanticsNode().boundsInRoot
            org.junit.Assert.assertTrue(effortBounds.top > modelBounds.bottom)
            compose.onNodeWithText("Effort").performClick()
            compose.onNodeWithText("Extra high").assertExists()
            compose.onNodeWithText("Max").assertDoesNotExist()
            compose.onNodeWithText("Extra high").performClick()
            compose.runOnUiThread {
                state.enableProvider()
                state.compose("Explain")
                state.send()
            }
            compose.waitUntil(10_000) { runCatching { compose.onNodeWithText("Reasoning summary").assertExists() }.isSuccess }
            compose.onNodeWithText("Reasoning summary").performClick()
            compose.onNodeWithText("Checking the requested change.").assertExists()
            compose.onNodeWithText("Thoughts").assertDoesNotExist()
            compose.runOnUiThread { state.stop() }
            compose.runOnIdle {
                org.junit.Assert.assertEquals(true, state.conversation!!.turns.single().thinking!!.summary)
            }
        } finally {
            compose.runOnUiThread { state.dispose() }
            owner.cancel()
        }
    }

    @Test fun composerUsesFullNamesAndOnlyOffersModelFeatures() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val profile = AssistantConnection(UUID.randomUUID().toString(), "Custom", "https://example.org/v1", models = listOf(AssistantModelConfig("text-model", "Text model")), defaultModelId = "text-model")
        val owner = kotlinx.coroutines.SupervisorJob()
        val state = AssistantPreviewState(scope = kotlinx.coroutines.CoroutineScope(owner + kotlinx.coroutines.Dispatchers.Main), providerCall = { error("No chat expected") }, attachments = AssistantAttachmentImporter(context))
        try {
            compose.runOnUiThread {
                state.restoreConnections(AssistantConnections(listOf(profile, AssistantConnection.deepSeek()), profile.id))
                state.open(AssistantPreviewContext())
            }
            compose.setContent { VeyarcTheme { AssistantPreviewScreen(state) } }
            compose.onNodeWithText("Custom Text model").assertExists()
            compose.onNodeWithContentDescription("Add attachment").performClick()
            compose.onNodeWithText("Camera").assertIsNotEnabled()
            compose.onNodeWithText("Photos").assertIsNotEnabled()
            compose.onNodeWithText("Files").assertIsEnabled()
            compose.onNodeWithContentDescription("Close Add to chat").performClick()
            compose.onNodeWithTag("assistant-model-picker").performClick()
            compose.onNodeWithText("Effort").assertDoesNotExist()
            compose.onNodeWithTag("chat-model-provider-${profile.id}").assertExists()
            compose.onNodeWithTag("chat-model-provider-deepseek").performScrollTo().assertExists()
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { screenshot ->
                java.io.File(context.cacheDir, "provider-grouped-models.png").outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            }
            compose.onNodeWithText("V4.1 Flash").performScrollTo().performClick()
            compose.onNodeWithText("DeepSeek V4.1 Flash").assertExists()
            compose.onNodeWithTag("assistant-model-picker").performClick()
            compose.onNodeWithText("Effort").assertExists()
        } finally {
            compose.runOnUiThread { state.dispose() }
            owner.cancel()
        }
    }
}
