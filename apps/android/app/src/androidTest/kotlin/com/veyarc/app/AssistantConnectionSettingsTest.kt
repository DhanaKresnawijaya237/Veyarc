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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
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

    @Test fun openAIPresetUsesNativeFormatWithoutEnablingOrSwitchingChat() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val owner = kotlinx.coroutines.SupervisorJob()
        val assistant = com.veyarc.feature.assistant.AssistantPreviewState(scope = kotlinx.coroutines.CoroutineScope(owner + kotlinx.coroutines.Dispatchers.Main), providerCall = { error("Setup must never send") })
        try {
            compose.runOnUiThread { assistant.open(com.veyarc.feature.assistant.AssistantPreviewContext()) }
            compose.setContent { VeyarcTheme { Column(Modifier.verticalScroll(rememberScrollState())) { AssistantConnectionSettings(AssistantKeyStore(context), assistant) } } }
            compose.onNodeWithTag("assistant-add-connection").performScrollTo().performClick()
            compose.onNodeWithText("OpenAI").performClick()
            compose.onNodeWithTag("connection-model").assertTextEquals("Model ID", "gpt-5.4")
            compose.onNodeWithText("OpenAI Responses API").assertExists()
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
        val profile = com.veyarc.data.storage.AssistantConnection(UUID.randomUUID().toString(), "Server", "https://example.org/v1", models = listOf(com.veyarc.data.storage.AssistantModelConfig("model-a")), defaultModelId = "model-a")
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
            awaitText("Key saved")
            compose.onNodeWithText("Default model").performClick()
            compose.onNodeWithText("Find models").performClick()
            awaitText("Server model-b")
            compose.onNodeWithText("Server model-b").performClick()
            compose.runOnIdle {
                org.junit.Assert.assertEquals(1, calls)
                org.junit.Assert.assertEquals("model-a", assistant.conversation!!.modelId)
                org.junit.Assert.assertEquals("model-b", assistant.defaultConnection.defaultModelId)
                assertFalse(assistant.defaultConnection.defaultModel.vision)
                assertFalse(assistant.providerEnabled)
            }
            compose.onNodeWithText("Default model").performClick()
            compose.onNodeWithText("Add model").performClick()
            compose.onNodeWithTag("connection-model").performTextInput("manual-model")
            closeKeyboard()
            compose.onNodeWithText("Advanced").performScrollTo().performClick()
            compose.onNodeWithTag("capability-Medium").performScrollTo().performClick()
            compose.onNodeWithTag("connection-save").performScrollTo().performClick()
            awaitText("Default effort")
            compose.onNodeWithText("Default effort").performClick()
            compose.onNodeWithText("Medium").performClick()
            compose.runOnIdle {
                org.junit.Assert.assertEquals("manual-model", assistant.defaultConnection.defaultModelId)
                org.junit.Assert.assertEquals("MEDIUM", assistant.defaultConnection.defaultEffort)
                org.junit.Assert.assertEquals(listOf("OFF", "MEDIUM"), assistant.defaultConnection.defaultModel.efforts)
                org.junit.Assert.assertEquals("model-a", assistant.conversation!!.modelId)
            }
            compose.onNodeWithContentDescription("Edit Server").performScrollTo().performClick()
            compose.onNodeWithTag("connection-model").performTextReplacement("unseen-model")
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
            awaitText("Key saved")
            compose.runOnIdle {
                customId = assistant.connections.connections.first { it.name == "My server" }.id
                assertFalse(assistant.providerEnabled)
                assertTrue(assistant.conversation!!.connectionId == com.veyarc.data.storage.AssistantConnection.DEEPSEEK_ID)
            }
            assertTrue(AssistantKeyStore(context, customId!!).hasKey())
            assertFalse(legacyStore.hasKey())
            compose.onNodeWithText("Use for new chats").performScrollTo().performClick()
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
            awaitText("Default model")
            compose.onNodeWithText("Default model").performClick()
            compose.onNodeWithText("DeepSeek V4 Pro").performClick()
            compose.onNodeWithText("Default effort").performClick()
            compose.onNodeWithText("High").performClick()
            compose.runOnIdle {
                org.junit.Assert.assertEquals(com.veyarc.feature.assistant.DeepSeekModel.PRO, assistant.defaultModel)
                org.junit.Assert.assertEquals(com.veyarc.feature.assistant.DeepSeekEffort.HIGH, assistant.defaultEffort)
                org.junit.Assert.assertEquals(com.veyarc.feature.assistant.DeepSeekModel.FLASH, assistant.selectedModel)
                org.junit.Assert.assertEquals(com.veyarc.feature.assistant.DeepSeekEffort.OFF, assistant.conversation!!.effort)
            }
            compose.onNodeWithContentDescription("Collapse DeepSeek").performClick()
            compose.onNodeWithText("Default model").assertDoesNotExist()
            compose.onNodeWithText("Default effort").assertDoesNotExist()
            compose.onNodeWithText("Privacy and costs").assertExists()
            compose.onNodeWithContentDescription("Expand DeepSeek").performClick()
            compose.onNodeWithText("Default effort").assertExists()
            compose.onNodeWithContentDescription("Test connection").assertIsNotEnabled()
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
            awaitText("Add API key")
            compose.onNodeWithText("Enable AI").assertIsNotEnabled()
            compose.onNodeWithContentDescription("Test connection").assertIsNotEnabled()
            compose.onNodeWithText("Add API key").performScrollTo().performClick()
            compose.runOnIdle { assertTrue(window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0) }
            compose.onNodeWithTag("assistant-key-entry").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
            compose.onNodeWithTag("assistant-key-entry").performTextInput("fixture-unsaved-key")
            closeKeyboard()
            compose.onNodeWithText("Cancel").performClick()
            compose.runOnIdle { assertTrue(window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE == 0) }
            compose.onNodeWithText("Add API key").performScrollTo().performClick()
            compose.onNodeWithTag("assistant-key-entry").assertTextEquals("API key", "")
            compose.onNodeWithTag("assistant-key-entry").performTextInput("fixture-saved-key")
            closeKeyboard()
            compose.onNodeWithText("Save key").performClick()
            awaitText("Key saved")
            assertTrue(store.hasKey())
            compose.onNodeWithText("Replace API key").performScrollTo().performClick()
            compose.onNodeWithTag("assistant-key-entry").assertTextEquals("API key", "")
            compose.onNodeWithTag("assistant-key-entry").performTextInput("fixture-discard-on-background")
            compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.CREATED }
            compose.waitForIdle()
            compose.onNodeWithTag("assistant-key-entry").assertDoesNotExist()
            compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
            compose.onNodeWithText("Replace API key").performScrollTo().performClick()
            compose.onNodeWithTag("assistant-key-entry").performTextInput("fixture-discard-on-restore")
            restoration.emulateSavedInstanceStateRestore()
            compose.onNodeWithTag("assistant-key-entry").assertDoesNotExist()
            awaitText("Key saved")
            compose.onNodeWithText("Replace API key").performScrollTo().performClick()
            compose.onNodeWithTag("assistant-key-entry").assertTextEquals("API key", "")
            compose.onNodeWithTag("assistant-key-entry").performTextInput("fixture-replacement-key")
            closeKeyboard()
            compose.onNodeWithText("Save key").performClick()
            awaitText("Key saved")
            compose.onNodeWithContentDescription("Remove API key").performScrollTo().performClick()
            compose.onNodeWithText("Remove key", substring = false).performClick()
            awaitText("No API key")
            assertFalse(store.hasKey())
            compose.onNodeWithText("Enable AI").assertIsNotEnabled()
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
            awaitText("Key saved")
            compose.onNodeWithText("Provider privacy policy").assertDoesNotExist()
            capture("settings")
            compose.onNodeWithText("Privacy and costs").performScrollTo().performClick()
            compose.onNodeWithText("Provider privacy policy").performScrollTo().assertExists()
            compose.onNodeWithContentDescription("Close Privacy and costs").performClick()
            compose.onNodeWithText("Enable AI").performScrollTo().performClick()
            capture("consent")
            compose.onNodeWithText("Details").assertDoesNotExist()
            compose.runOnIdle { org.junit.Assert.assertEquals(0, calls) }
            compose.onNodeWithText("Enable for this session").performClick()
            compose.runOnIdle {
                assertTrue(assistant.providerEnabled)
                org.junit.Assert.assertEquals(0, calls)
            }
            compose.onNodeWithContentDescription("Test connection").performScrollTo().performClick()
            compose.onNodeWithText("Cancel").performClick()
            compose.runOnIdle { org.junit.Assert.assertEquals(0, calls) }
            compose.onNodeWithContentDescription("Test connection").performScrollTo().performClick()
            compose.onNodeWithText("Send test request").performClick()
            compose.waitUntil(10_000) { calls == 1 }
            compose.onNodeWithText("Replace API key").performScrollTo().performClick()
            compose.onNodeWithTag("assistant-key-entry").performTextInput("fixture-new-opt-in-key")
            closeKeyboard()
            compose.onNodeWithText("Save key").performClick()
            compose.waitUntil(10_000) { cancelled }
            compose.runOnIdle {
                assertFalse(assistant.providerEnabled)
                org.junit.Assert.assertEquals(1, calls)
            }
            awaitText("Key saved")
            compose.onNodeWithContentDescription("Test connection").assertIsNotEnabled()
            org.junit.Assert.assertEquals("fixture-new-opt-in-key", store.readForRequest())
        } finally {
            compose.runOnIdle { assistant.dispose() }
            owner.cancel()
            store.remove()
            directory.deleteRecursively()
        }
    }

    private fun closeKeyboard() {
        compose.onNode(isFocused() and hasSetTextAction()).performImeAction()
        compose.waitForIdle()
    }

    private fun awaitText(value: String) {
        compose.waitUntil(10_000) { runCatching { compose.onNodeWithText(value).assertExists() }.isSuccess }
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
