package com.veyarc.app

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
            compose.onNodeWithText("DeepSeek V4.1 Flash").performClick()
            compose.onNodeWithText("DeepSeek V4.1 Flash").assertExists()
            compose.onNodeWithTag("assistant-model-picker").performClick()
            compose.onNodeWithText("Effort").assertExists()
        } finally {
            compose.runOnUiThread { state.dispose() }
            owner.cancel()
        }
    }
}
