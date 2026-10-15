package com.veyarc.app

import android.app.Activity
import android.content.ContextWrapper
import android.view.Window
import android.view.WindowManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.veyarc.app.ui.theme.VeyarcTheme
import com.veyarc.data.storage.AssistantKeyStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AssistantConnectionSettingsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun enableControlPrecedesProvidersWithoutOldHelpLinks() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent { VeyarcTheme { Column(Modifier.verticalScroll(rememberScrollState())) { AssistantConnectionSettings(AssistantKeyStore(context)) } } }
        val control = compose.onNodeWithTag("assistant-enable").fetchSemanticsNode().boundsInRoot
        val test = compose.onNodeWithContentDescription("Test all connections").fetchSemanticsNode().boundsInRoot
        assertTrue(test.right <= control.left)
        val provider = compose.onNodeWithContentDescription("Expand DeepSeek").fetchSemanticsNode().boundsInRoot
        assertTrue(control.bottom < provider.top)
        compose.onNodeWithText("Privacy and costs").assertDoesNotExist()
        compose.onNodeWithText("AI chat help").assertDoesNotExist()
    }

    @Test fun newDeepSeekPresetKeepsCurrentVisionModelAndNativeThinkingControls() {
        var saved: com.veyarc.data.storage.AssistantConnection? = null
        compose.setContent { VeyarcTheme { ConnectionEditor(null, false, onManageModels = {}, onDismiss = {}, onSave = { saved = it }) } }
        compose.onNodeWithTag("connection-provider").performClick()
        compose.onNodeWithText("Search providers").performTextInput("DeepSeek")
        closeKeyboard()
        chooseProvider("deepseek")
        compose.onNodeWithTag("connection-model").assertTextEquals("Model ID", "deepseek-flash")
        compose.onNodeWithText("Advanced").performScrollTo().performClick()
        compose.onNodeWithText("DeepSeek Chat Completions").performScrollTo().assertExists()
        compose.onNodeWithTag("connection-save").performScrollTo().performClick()
        compose.runOnIdle {
            val profile = requireNotNull(saved)
            assertTrue(profile.defaultModel.vision)
            assertTrue(profile.id != com.veyarc.data.storage.AssistantConnection.DEEPSEEK_ID)
            org.junit.Assert.assertEquals(com.veyarc.data.storage.AssistantProtocol.DEEPSEEK, profile.protocolFor(profile.defaultModel))
            assertTrue("HIGH" in profile.defaultModel.efforts)
        }
    }

    @Test fun editProviderTitleRemainsOpaqueInDarkMode() {
        compose.setContent { VeyarcTheme(darkTheme = true) { ConnectionEditor(com.veyarc.data.storage.AssistantConnection.deepSeek(), false, onManageModels = {}, onDismiss = {}, onSave = {}) } }
        compose.onNodeWithTag("connection-provider").assert(SemanticsMatcher.keyNotDefined(androidx.compose.ui.semantics.SemanticsActions.OnClick))
        compose.onNodeWithTag("connection-provider").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Disabled))
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNode(hasText("DeepSeek") and !hasSetTextAction()).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val color = layouts.single().layoutInput.style.color
        org.junit.Assert.assertEquals(1f, color.alpha, 0f)
        assertTrue(color.red > 0.8f && color.green > 0.8f && color.blue > 0.8f)
        capture("edit-provider-title")
    }

    @Test fun catalogModelsCanBeAddedTogetherWithoutChangingDefaultAndSaveFailureKeepsChoices() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val profile = com.veyarc.data.storage.AssistantConnection.openAI()
        val extras = com.veyarc.data.storage.AssistantProviderCatalog.providers.first { it.id == "openai" }.models.filter { it.id != profile.defaultModelId }.take(2)
        val owner = kotlinx.coroutines.SupervisorJob()
        var failSave = true
        var pendingSave: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        var persisted: com.veyarc.data.storage.AssistantConnections? = null
        val assistant = com.veyarc.feature.assistant.AssistantPreviewState(scope = kotlinx.coroutines.CoroutineScope(owner + kotlinx.coroutines.Dispatchers.Main), providerCall = { error("No requests expected") }, onConnectionsChanged = {
            pendingSave?.await()
            if (failSave) error("Storage unavailable")
            persisted = it
        })
        try {
            compose.runOnUiThread {
                assistant.restoreConnections(com.veyarc.data.storage.AssistantConnections(listOf(profile, com.veyarc.data.storage.AssistantConnection.deepSeek()), profile.id))
                assistant.open(com.veyarc.feature.assistant.AssistantPreviewContext())
            }
            compose.setContent { VeyarcTheme(darkTheme = true) { Column(Modifier.verticalScroll(rememberScrollState())) { AssistantConnectionSettings(AssistantKeyStore(context), assistant) } } }
            compose.onNodeWithContentDescription("Expand OpenAI").assertExists()
            compose.onNodeWithContentDescription("Expand DeepSeek").assertExists()
            compose.onNodeWithTag("manage-models-${profile.id}").assertDoesNotExist()
            capture("providers-collapsed")
            expandProvider("OpenAI")
            val modelsBounds = compose.onNodeWithTag("manage-models-${profile.id}").fetchSemanticsNode().boundsInRoot
            val defaultBounds = compose.onNode(hasText("Default model") and hasAnyAncestor(hasTestTag("connection-group-${profile.id}"))).fetchSemanticsNode().boundsInRoot
            assertTrue(modelsBounds.top < defaultBounds.top)
            val firstProvider = compose.onNodeWithTag("connection-group-${profile.id}").fetchSemanticsNode().boundsInRoot
            val nextProvider = compose.onNodeWithTag("connection-group-deepseek").fetchSemanticsNode().boundsInRoot
            assertTrue(firstProvider.bottom < nextProvider.top)
            capture("provider-groups")
            openModels("OpenAI")
            compose.onNodeWithText("Add models").performClick()
            val customBounds = compose.onNodeWithText("Custom model").fetchSemanticsNode().boundsInRoot
            val listBounds = compose.onNodeWithTag("connection-model-list").fetchSemanticsNode().boundsInRoot
            org.junit.Assert.assertEquals(listBounds.center.x, customBounds.center.x, 1f)
            extras.forEach { model ->
                compose.waitUntil(10_000) { runCatching { compose.onNodeWithTag("connection-model-list").performScrollToNode(hasTestTag("managed-model-${model.id}")) }.isSuccess }
                compose.onNodeWithTag("managed-model-${model.id}").performClick().assertIsOn()
            }
            capture("add-models")
            val addLayouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            compose.onNodeWithText("Add 2 models").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(addLayouts) }
            compose.runOnIdle { pendingSave = kotlinx.coroutines.CompletableDeferred() }
            compose.onNodeWithTag("add-selected-models").performClick()
            compose.onNodeWithTag("add-selected-models").assertIsNotEnabled()
            compose.onNodeWithText("Add 2 models").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(addLayouts) }
            org.junit.Assert.assertEquals(addLayouts.first().layoutInput.style.color, addLayouts.last().layoutInput.style.color)
            compose.runOnIdle {
                pendingSave!!.complete(Unit)
                pendingSave = null
            }
            compose.waitUntil(10_000) { assistant.connectionError != null }
            compose.onNodeWithTag("add-selected-models").assertTextEquals("Add 2 models")
            compose.runOnIdle {
                org.junit.Assert.assertEquals(profile, assistant.defaultConnection)
                failSave = false
            }
            compose.onNodeWithTag("add-selected-models").performClick()
            compose.waitUntil(10_000) { assistant.defaultConnection.models.size == 3 }
            compose.onNodeWithTag("managed-model-${profile.defaultModelId}").assertIsDisplayed().assertIsSelected()
            compose.onNodeWithContentDescription("Use ${extras.first().name} as default").assertDoesNotExist()
            compose.onNodeWithContentDescription("Remove model ${extras.last().name}").performScrollTo()
            val iconBeforeSave = compose.onNodeWithContentDescription("Remove model ${extras.last().name}").captureToImage().toPixelMap()
            compose.runOnIdle { pendingSave = kotlinx.coroutines.CompletableDeferred() }
            compose.onNodeWithTag("managed-model-${extras.first().id}").performScrollTo().assertIsNotSelected().performClick()
            compose.onNodeWithContentDescription("Remove model ${extras.last().name}").performScrollTo().assertIsNotEnabled()
            val iconDuringSave = compose.onNodeWithContentDescription("Remove model ${extras.last().name}").captureToImage().toPixelMap()
            for (y in 0 until iconBeforeSave.height) for (x in 0 until iconBeforeSave.width) org.junit.Assert.assertEquals(iconBeforeSave[x, y], iconDuringSave[x, y])
            compose.runOnIdle {
                pendingSave!!.complete(Unit)
                pendingSave = null
            }
            compose.waitUntil(10_000) { assistant.defaultConnection.defaultModelId == extras.first().id }
            compose.onNodeWithTag("managed-model-${profile.defaultModelId}").performScrollTo().assertIsNotSelected()
            compose.onNodeWithContentDescription("Remove model ${extras.first().name}").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithContentDescription("Remove model ${extras.last().name}").performScrollTo().performClick()
            compose.waitUntil(10_000) { assistant.defaultConnection.models.size == 2 }
            compose.runOnIdle {
                org.junit.Assert.assertEquals(assistant.connections, persisted)
                org.junit.Assert.assertEquals(profile.defaultModelId, assistant.conversation!!.modelId)
                org.junit.Assert.assertEquals(profile.id, assistant.connections.defaultConnectionId)
            }
            capture("saved-models")
        } finally {
            compose.runOnUiThread {
                pendingSave?.complete(Unit)
                assistant.dispose()
            }
            owner.cancel()
        }
    }

    @Test fun claudeCatalogSetsNativeProtocolAndSeparatesAdvancedCategories() {
        var saved: com.veyarc.data.storage.AssistantConnection? = null
        compose.setContent { VeyarcTheme { ConnectionEditor(null, false, onManageModels = {}, onDismiss = {}, onSave = { saved = it }) } }
        compose.onNodeWithTag("connection-provider").performClick()
        compose.onNodeWithText("Search providers").performTextInput("Anthropic")
        closeKeyboard()
        chooseProvider("anthropic")
        compose.onNodeWithTag("draft-find-models").assertDoesNotExist()
        compose.onNodeWithText("Advanced").performScrollTo().performClick()
        compose.onNodeWithText("Protocol").assertDoesNotExist()
        compose.onNodeWithText("Model capabilities").performScrollTo().assertExists()
        compose.onNodeWithText("Text and images").performScrollTo().assertExists()
        compose.onNodeWithTag("capability-Images").assertDoesNotExist()
        compose.onNodeWithTag("default-effort-MINIMAL").assertDoesNotExist()
        compose.onNodeWithTag("default-effort-XHIGH").assertDoesNotExist()
        compose.onNodeWithText("Thinking and effort").assertDoesNotExist()
        compose.onNodeWithText("Request compatibility").assertDoesNotExist()
        capture("listed-model-advanced")
        compose.onNodeWithTag("connection-save").performScrollTo().performClick()
        compose.runOnIdle {
            val profile = requireNotNull(saved)
            org.junit.Assert.assertEquals("anthropic", profile.providerId)
            org.junit.Assert.assertEquals(com.veyarc.data.storage.AssistantProtocol.ANTHROPIC_MESSAGES, profile.protocolFor(profile.defaultModel))
            assertTrue(profile.defaultModel.vision)
        }
    }

    @Test fun listedModelsOnlyOfferTheirEffortsAndCustomModelsRetainFullControls() {
        var saved: com.veyarc.data.storage.AssistantConnection? = null
        compose.setContent { VeyarcTheme { ConnectionEditor(null, false, onManageModels = {}, onDismiss = {}, onSave = { saved = it }) } }
        compose.onNodeWithTag("connection-provider").performClick()
        capture("provider-picker")
        compose.onNodeWithText("Search providers").performTextInput("Google")
        closeKeyboard()
        compose.onNodeWithTag("provider-logo-google", useUnmergedTree = true).assertExists()
        chooseProvider("google")
        compose.onNodeWithTag("default-effort-HIGH").performScrollTo().performClick()
        compose.onNodeWithTag("default-effort-OFF").assertDoesNotExist()
        compose.onNodeWithTag("default-effort-MAX").assertDoesNotExist()
        compose.onNodeWithText("Advanced").performScrollTo().performClick()
        compose.onNodeWithText("Request compatibility").assertDoesNotExist()
        compose.onNodeWithText("Maximum output tokens (optional)").performScrollTo().performTextReplacement("999999")
        closeKeyboard()
        compose.onNodeWithTag("connection-save").assertIsNotEnabled()
        compose.onNodeWithText("Maximum output tokens (optional)").performScrollTo().performTextReplacement("4096")
        closeKeyboard()
        compose.onNodeWithTag("connection-save").performScrollTo().performClick()
        compose.runOnIdle {
            org.junit.Assert.assertEquals(listOf("MINIMAL", "LOW", "MEDIUM", "HIGH"), saved!!.defaultModel.efforts)
            org.junit.Assert.assertEquals("HIGH", saved!!.defaultEffort)
            org.junit.Assert.assertEquals(4096, saved!!.defaultModel.maxOutputTokens)
        }
        compose.onNodeWithTag("connection-model").performScrollTo().performTextReplacement("my-custom-model")
        closeKeyboard()
        compose.onNodeWithTag("capability-Max").performScrollTo().performClick()
        compose.onNodeWithTag("capability-Images").performScrollTo().performClick()
        compose.onNodeWithText("Request compatibility").performScrollTo().assertExists()
        compose.onNodeWithText("Protocol").performScrollTo().assertExists()
        capture("custom-model-advanced")
        compose.onNodeWithTag("connection-save").performScrollTo().performClick()
        compose.runOnIdle {
            org.junit.Assert.assertEquals(listOf("OFF", "MAX"), saved!!.defaultModel.efforts)
            assertTrue(saved!!.defaultModel.vision)
        }
    }

    @Test fun openAIPresetUsesNativeFormatWithoutEnablingOrSwitchingChat() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val owner = kotlinx.coroutines.SupervisorJob()
        val assistant = com.veyarc.feature.assistant.AssistantPreviewState(scope = kotlinx.coroutines.CoroutineScope(owner + kotlinx.coroutines.Dispatchers.Main), providerCall = { error("Setup must never send") })
        try {
            compose.runOnUiThread { assistant.open(com.veyarc.feature.assistant.AssistantPreviewContext()) }
            compose.setContent { VeyarcTheme { Column(Modifier.verticalScroll(rememberScrollState())) { AssistantConnectionSettings(AssistantKeyStore(context), assistant) } } }
            compose.onNodeWithTag("assistant-add-connection").performScrollTo().performClick()
            compose.onNodeWithTag("connection-provider").performClick()
            chooseProvider("openai")
            compose.onNodeWithTag("connection-model").assertTextEquals("Model ID", "gpt-5.4")
            compose.onNodeWithText("Model").assertExists()
            compose.onNodeWithTag("connection-save").performScrollTo().performClick()
            awaitText("OpenAI API key")
            compose.onNodeWithText("Cancel").performClick()
            compose.runOnIdle {
                val profile = assistant.connections.connections.single { it.protocol == com.veyarc.data.storage.AssistantProtocol.OPENAI_RESPONSES }
                assertTrue(profile.defaultModel.vision && profile.defaultModel.reasoningSummary)
                assertTrue("XHIGH" in profile.defaultModel.efforts)
                assertFalse(assistant.providerEnabled)
                org.junit.Assert.assertEquals(com.veyarc.data.storage.AssistantConnection.DEEPSEEK_ID, assistant.conversation!!.connectionId)
            }
        } finally {
            compose.runOnUiThread { assistant.dispose() }
            owner.cancel()
        }
    }

    @Test
    fun discoveryAndManualModelsKeepChatChoicesAndExposeConfiguredEfforts() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val profile = com.veyarc.data.storage.AssistantConnection(UUID.randomUUID().toString(), "Server", "https://example.org/v1", models = listOf(com.veyarc.data.storage.AssistantModelConfig("search")), defaultModelId = "search")
        val store = AssistantKeyStore(context, profile.id)
        val owner = kotlinx.coroutines.SupervisorJob()
        var calls = 0
        val assistant = com.veyarc.feature.assistant.AssistantPreviewState(scope = kotlinx.coroutines.CoroutineScope(owner + kotlinx.coroutines.Dispatchers.Main), providerCall = { error("No chat expected") }, modelDiscovery = {
            calls++
            listOf(com.veyarc.data.storage.AssistantModelConfig("model-b"))
        })
        try {
            store.save("fixture-model-discovery")
            compose.runOnUiThread {
                assistant.restoreConnections(com.veyarc.data.storage.AssistantConnections(listOf(profile), profile.id))
                assistant.open(com.veyarc.feature.assistant.AssistantPreviewContext())
            }
            compose.setContent { VeyarcTheme { Column(Modifier.verticalScroll(rememberScrollState())) { AssistantConnectionSettings(AssistantKeyStore(context), assistant) } } }
            expandProvider("Server")
            awaitText("Key saved")
            openModels("Server")
            compose.onNodeWithText("Add models").performClick()
            val customBounds = compose.onNodeWithText("Custom model").fetchSemanticsNode().boundsInRoot
            val listBounds = compose.onNodeWithTag("connection-model-list").fetchSemanticsNode().boundsInRoot
            org.junit.Assert.assertEquals(listBounds.center.x, customBounds.center.x, 1f)
            compose.onNodeWithText("Find models").performClick()
            awaitText("model-b")
            compose.onNodeWithTag("managed-model-model-b").performClick()
            compose.onNodeWithTag("add-selected-models").performClick()
            compose.runOnIdle {
                org.junit.Assert.assertEquals(1, calls)
                org.junit.Assert.assertEquals("search", assistant.conversation!!.modelId)
                org.junit.Assert.assertEquals("search", assistant.defaultConnection.defaultModelId)
                org.junit.Assert.assertEquals(2, assistant.defaultConnection.models.size)
                assertFalse(assistant.defaultConnection.defaultModel.vision)
                assertFalse(assistant.providerEnabled)
            }
            compose.onNodeWithTag("managed-model-model-b").performClick()
            compose.onNodeWithTag("managed-model-search").assertIsNotSelected()
            compose.onNodeWithTag("managed-model-model-b").assertIsSelected()
            compose.onNodeWithContentDescription("Remove model model-b").assertIsNotEnabled()
            compose.onNodeWithText("Add models").performClick()
            compose.onNodeWithText("Custom model").performClick()
            compose.onNodeWithTag("connection-model").performTextInput("manual-model")
            closeKeyboard()
            compose.onNodeWithText("Advanced").performScrollTo().performClick()
            compose.onNodeWithTag("capability-Medium").performScrollTo().performClick()
            compose.onNodeWithTag("connection-save").performScrollTo().performClick()
            compose.runOnIdle { org.junit.Assert.assertEquals("model-b", assistant.defaultConnection.defaultModelId) }
            compose.onNodeWithTag("managed-model-manual-model").performClick()
            compose.onNodeWithContentDescription("Close Models").performClick()
            awaitText("Default effort")
            compose.onNodeWithContentDescription("Edit Server").performScrollTo().performClick()
            compose.onNodeWithText("Medium").performScrollTo().performClick()
            compose.onNodeWithTag("connection-save").performScrollTo().performClick()
            compose.runOnIdle {
                org.junit.Assert.assertEquals("manual-model", assistant.defaultConnection.defaultModelId)
                org.junit.Assert.assertEquals("MEDIUM", assistant.defaultConnection.defaultEffort)
                org.junit.Assert.assertEquals(listOf("OFF", "MEDIUM"), assistant.defaultConnection.defaultModel.efforts)
                org.junit.Assert.assertEquals("search", assistant.conversation!!.modelId)
            }
            compose.onNodeWithContentDescription("Edit Server").performScrollTo().performClick()
            compose.onNodeWithTag("connection-model").performTextReplacement("unseen-model")
            closeKeyboard()
            compose.onNodeWithText("Advanced").performScrollTo().performClick()
            compose.onNodeWithTag("capability-Medium").performScrollTo().assertIsOff()
            compose.onNodeWithTag("capability-Images").performScrollTo().assertIsOff()
            compose.onNodeWithContentDescription("Close Edit connection").performClick()
        } finally {
            compose.runOnUiThread { assistant.dispose() }
            owner.cancel()
            store.remove()
        }
    }

    @Test
    fun customConnectionSetupUsesSeparateKeyAndKeepsExistingChat() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "key-profiles-${UUID.randomUUID()}")
        val legacyStore = AssistantKeyStore(object : ContextWrapper(context) {
            override fun getNoBackupFilesDir() = directory
        })
        val owner = kotlinx.coroutines.SupervisorJob()
        val assistant = com.veyarc.feature.assistant.AssistantPreviewState(scope = kotlinx.coroutines.CoroutineScope(owner + kotlinx.coroutines.Dispatchers.Main), providerCall = { error("Setup must never send") })
        var customId: String? = null
        try {
            compose.runOnUiThread { assistant.open(com.veyarc.feature.assistant.AssistantPreviewContext()) }
            compose.setContent { VeyarcTheme { Column(Modifier.verticalScroll(rememberScrollState())) { AssistantConnectionSettings(legacyStore, assistant) } } }
            awaitText("Add connection")
            compose.onNodeWithTag("assistant-add-connection").performScrollTo().performClick()
            compose.onNodeWithTag("connection-name").performTextInput("My server")
            compose.onNodeWithTag("connection-address").performTextInput("http://example.org/v1")
            compose.onNodeWithTag("connection-model").performTextInput("my-model")
            compose.onNodeWithTag("connection-save").assertIsNotEnabled()
            compose.onNodeWithTag("connection-address").performTextReplacement("https://example.org/v1")
            closeKeyboard()
            compose.onNodeWithTag("connection-save").performScrollTo().performClick()
            awaitText("My server API key")
            compose.onNodeWithTag("assistant-key-entry").performTextInput("fixture-custom-profile-key")
            closeKeyboard()
            compose.onNodeWithText("Save key").performClick()
            compose.onNodeWithContentDescription("Expand My server").assertExists()
            expandProvider("My server")
            awaitText("Key saved")
            compose.runOnIdle {
                customId = assistant.connections.connections.first { it.name == "My server" }.id
                assertFalse(assistant.providerEnabled)
                assertTrue(assistant.conversation!!.connectionId == com.veyarc.data.storage.AssistantConnection.DEEPSEEK_ID)
            }
            assertTrue(AssistantKeyStore(context, customId!!).hasKey())
            assertFalse(legacyStore.hasKey())
            compose.onNodeWithContentDescription("Use My server for new chats").performScrollTo().performClick()
            compose.runOnIdle {
                assertTrue(assistant.connections.defaultConnectionId == customId)
                assertTrue(assistant.conversation!!.connectionId != customId)
            }
            compose.onNodeWithContentDescription("Remove My server connection").performScrollTo().performClick()
            compose.onNodeWithText("Remove connection").performClick()
            compose.waitUntil(10_000) { !AssistantKeyStore(context, customId!!).hasKey() }
            compose.runOnIdle { assertTrue(assistant.connections.connections.size == 1) }
        } finally {
            compose.runOnUiThread { assistant.dispose() }
            owner.cancel()
            customId?.let { AssistantKeyStore(context, it).remove() }
            directory.deleteRecursively()
        }
    }

    @Test
    fun providerCanCollapseAndDefaultsDoNotChangeAnExistingChat() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "key-defaults-${UUID.randomUUID()}")
        val store = AssistantKeyStore(object : ContextWrapper(context) {
            override fun getNoBackupFilesDir() = directory
        })
        val owner = kotlinx.coroutines.SupervisorJob()
        val assistant = com.veyarc.feature.assistant.AssistantPreviewState(scope = kotlinx.coroutines.CoroutineScope(owner + kotlinx.coroutines.Dispatchers.Main), providerCall = { error("Defaults never send a request") })
        try {
            compose.runOnUiThread { assistant.open(com.veyarc.feature.assistant.AssistantPreviewContext()) }
            compose.setContent { VeyarcTheme { Column(Modifier.verticalScroll(rememberScrollState())) { AssistantConnectionSettings(store, assistant) } } }
            compose.onNodeWithText("Default model").assertDoesNotExist()
            compose.onNodeWithText("Default effort").assertDoesNotExist()
            expandProvider("DeepSeek")
            awaitText("Default model")
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            compose.onNodeWithText("Default model").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
            compose.onNodeWithText("Default effort").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
            org.junit.Assert.assertEquals(layouts[0].layoutInput.style.fontSize, layouts[1].layoutInput.style.fontSize)
            compose.onNodeWithText("Default model").assert(SemanticsMatcher.keyNotDefined(androidx.compose.ui.semantics.SemanticsActions.OnClick))
            openModels("DeepSeek")
            compose.onNodeWithTag("managed-model-deepseek-v4-pro").performClick()
            compose.onNodeWithContentDescription("Close Models").performClick()
            compose.onNodeWithContentDescription("Edit DeepSeek").performScrollTo().performClick()
            compose.onNodeWithText("High").performScrollTo().performClick()
            compose.onNodeWithTag("connection-save").performScrollTo().performClick()
            compose.runOnIdle {
                org.junit.Assert.assertEquals(com.veyarc.feature.assistant.DeepSeekModel.PRO, assistant.defaultModel)
                org.junit.Assert.assertEquals(com.veyarc.feature.assistant.DeepSeekEffort.HIGH, assistant.defaultEffort)
                org.junit.Assert.assertEquals(com.veyarc.feature.assistant.DeepSeekModel.FLASH, assistant.selectedModel)
                org.junit.Assert.assertEquals(com.veyarc.feature.assistant.DeepSeekEffort.OFF, assistant.conversation!!.effort)
            }
            compose.onNodeWithContentDescription("Collapse DeepSeek").performClick()
            compose.onNodeWithText("Default model").assertDoesNotExist()
            compose.onNodeWithText("Default effort").assertDoesNotExist()
            compose.onNodeWithText("Privacy and costs").assertDoesNotExist()
            compose.onNodeWithContentDescription("Expand DeepSeek").performClick()
            compose.onNodeWithText("Default effort").assertExists()
            compose.onNodeWithContentDescription("Test all connections").assertIsNotEnabled()
        } finally {
            compose.runOnUiThread { assistant.dispose() }
            owner.cancel()
            directory.deleteRecursively()
        }
    }

    @Test
    fun localKeySetupNeverEnablesAiAndDiscardsUnsavedEntry() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "key-ui-${UUID.randomUUID()}")
        val store = AssistantKeyStore(object : ContextWrapper(context) {
            override fun getNoBackupFilesDir() = directory
        })
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
        val restoration = StateRestorationTester(compose)
        var window: Window? = null
        try {
            compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
            restoration.setContent {
                var activityContext = LocalContext.current
                while (activityContext is ContextWrapper && activityContext !is Activity) activityContext = activityContext.baseContext
                window = (activityContext as? Activity)?.window
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    VeyarcTheme { Column(Modifier.verticalScroll(rememberScrollState())) { AssistantConnectionSettings(store) } }
                }
            }
            expandProvider("DeepSeek")
            compose.waitUntil(10_000) { compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Add API key")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("assistant-enable").assertIsNotEnabled()
            compose.onNodeWithContentDescription("Test all connections").assertIsNotEnabled()
            compose.onNodeWithContentDescription("Add API key").performScrollTo().performClick()
            compose.runOnIdle { assertTrue(window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0) }
            compose.onNodeWithTag("assistant-key-entry").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
            compose.onNodeWithTag("assistant-key-entry").performTextInput("fixture-unsaved-key")
            closeKeyboard()
            compose.onNodeWithText("Cancel").performClick()
            compose.runOnIdle { assertTrue(window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE == 0) }
            compose.onNodeWithContentDescription("Add API key").performScrollTo().performClick()
            compose.onNodeWithTag("assistant-key-entry").assertTextEquals("API key", "")
            compose.onNodeWithTag("assistant-key-entry").performTextInput("fixture-saved-key")
            closeKeyboard()
            compose.onNodeWithText("Save key").performClick()
            awaitText("Key saved")
            assertTrue(store.hasKey())
            compose.onNodeWithContentDescription("Replace API key").performScrollTo().performClick()
            compose.onNodeWithTag("assistant-key-entry").assertTextEquals("API key", "")
            compose.onNodeWithTag("assistant-key-entry").performTextInput("fixture-discard-on-background")
            compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.CREATED }
            compose.waitForIdle()
            compose.onNodeWithTag("assistant-key-entry").assertDoesNotExist()
            compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
            compose.onNodeWithContentDescription("Replace API key").performScrollTo().performClick()
            compose.onNodeWithTag("assistant-key-entry").performTextInput("fixture-discard-on-restore")
            restoration.emulateSavedInstanceStateRestore()
            compose.onNodeWithTag("assistant-key-entry").assertDoesNotExist()
            awaitText("Key saved")
            compose.onNodeWithContentDescription("Replace API key").performScrollTo().performClick()
            compose.onNodeWithTag("assistant-key-entry").assertTextEquals("API key", "")
            compose.onNodeWithTag("assistant-key-entry").performTextInput("fixture-replacement-key")
            closeKeyboard()
            compose.onNodeWithText("Save key").performClick()
            awaitText("Key saved")
            compose.onNodeWithContentDescription("Remove API key").performScrollTo().performClick()
            compose.onNodeWithText("Remove key", substring = false).performClick()
            awaitText("No API key")
            assertFalse(store.hasKey())
            compose.onNodeWithTag("assistant-enable").assertIsNotEnabled()
        } finally {
            store.remove()
            directory.deleteRecursively()
        }
    }

    @Test
    fun optInSendsNothingAndReplacingTheKeyCancelsAnExplicitConnectionTest() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "key-opt-in-${UUID.randomUUID()}")
        val store = AssistantKeyStore(object : ContextWrapper(context) {
            override fun getNoBackupFilesDir() = directory
        })
        val owner = kotlinx.coroutines.SupervisorJob()
        var calls = 0
        var cancelled = false
        val assistant = com.veyarc.feature.assistant.AssistantPreviewState(
            scope = kotlinx.coroutines.CoroutineScope(owner + kotlinx.coroutines.Dispatchers.Main),
            providerCall = {
                calls++
                try {
                    kotlinx.coroutines.awaitCancellation()
                } finally {
                    cancelled = true
                }
            },
        )
        try {
            store.save("fixture-opt-in-key")
            compose.setContent {
                VeyarcTheme { Column(Modifier.verticalScroll(rememberScrollState())) { AssistantConnectionSettings(store, assistant) } }
            }
            expandProvider("DeepSeek")
            awaitText("Key saved")
            compose.onNodeWithText("Provider privacy policy").assertDoesNotExist()
            capture("settings")
            compose.onNodeWithText("Privacy and costs").assertDoesNotExist()
            compose.onNodeWithTag("assistant-enable").performScrollTo().performClick()
            capture("consent")
            compose.onNodeWithText("Details").assertDoesNotExist()
            compose.runOnIdle { org.junit.Assert.assertEquals(0, calls) }
            compose.onNodeWithText("Enable for this session").performClick()
            compose.onNodeWithTag("assistant-enable").assertIsOn().performClick().assertIsOff()
            compose.runOnIdle {
                assertFalse(assistant.providerEnabled)
                org.junit.Assert.assertEquals(0, calls)
            }
            compose.onNodeWithTag("assistant-enable").performClick()
            compose.onNodeWithText("Enable for this session").performClick()
            compose.onNodeWithTag("assistant-enable").assertIsOn()
            capture("enabled")
            compose.runOnIdle {
                assertTrue(assistant.providerEnabled)
                org.junit.Assert.assertEquals(0, calls)
            }
            compose.onNodeWithContentDescription("Test all connections").performScrollTo().performClick()
            compose.onNodeWithText("Cancel").performClick()
            compose.runOnIdle { org.junit.Assert.assertEquals(0, calls) }
            compose.onNodeWithContentDescription("Test all connections").performScrollTo().performClick()
            compose.onNodeWithText("Send test request").performClick()
            compose.waitUntil(10_000) { calls == 1 }
            compose.onNodeWithContentDescription("Replace API key").performScrollTo().performClick()
            compose.onNodeWithTag("assistant-key-entry").performTextInput("fixture-new-opt-in-key")
            closeKeyboard()
            compose.onNodeWithText("Save key").performClick()
            compose.waitUntil(10_000) { cancelled }
            compose.runOnIdle {
                assertFalse(assistant.providerEnabled)
                org.junit.Assert.assertEquals(1, calls)
            }
            awaitText("Key saved")
            compose.onNodeWithContentDescription("Test all connections").assert(androidx.compose.ui.test.isEnabled())
            org.junit.Assert.assertEquals("fixture-new-opt-in-key", store.readForRequest())
        } finally {
            compose.runOnIdle { assistant.dispose() }
            owner.cancel()
            store.remove()
            directory.deleteRecursively()
        }
    }

    private fun chooseProvider(id: String) {
        compose.waitUntil(10_000) { runCatching { compose.onNodeWithTag("provider-choice-$id").assertExists() }.isSuccess }
        compose.onNodeWithTag("provider-choice-$id").performScrollTo().performClick()
    }

    private fun openModels(provider: String) {
        expandProvider(provider)
        compose.onNodeWithContentDescription("Manage $provider models").performScrollTo().performClick()
    }

    private fun expandProvider(provider: String) {
        if (compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Expand $provider")).fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithContentDescription("Expand $provider").performScrollTo().performClick()
            compose.waitForIdle()
        }
    }

    private fun closeKeyboard() {
        compose.onNode(isFocused() and hasSetTextAction()).performImeAction()
        compose.waitForIdle()
    }

    private fun awaitText(value: String) {
        compose.waitUntil(10_000) { runCatching { compose.onNode(hasText(value) and !hasSetTextAction()).assertExists() }.isSuccess }
        compose.waitForIdle()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        android.os.SystemClock.sleep(300)
        val directory = File(instrumentation.targetContext.cacheDir, "assistant-preview-qa").apply { mkdirs() }
        File(directory, "portrait-$name.png").outputStream().use {
            instrumentation.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
