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
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.espresso.Espresso
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
            compose.onNodeWithText("Test connection").assertIsNotEnabled()
            compose.onNodeWithText("Add API key").performScrollTo().performClick()
            compose.runOnIdle { assertTrue(window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0) }
            compose.onNodeWithTag("assistant-key-entry").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
            compose.onNodeWithTag("assistant-key-entry").performTextInput("fixture-unsaved-key")
            Espresso.closeSoftKeyboard()
            compose.onNodeWithText("Cancel").performClick()
            compose.runOnIdle { assertTrue(window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE == 0) }
            compose.onNodeWithText("Add API key").performScrollTo().performClick()
            compose.onNodeWithTag("assistant-key-entry").assertTextEquals("API key", "")
            compose.onNodeWithTag("assistant-key-entry").performTextInput("fixture-saved-key")
            Espresso.closeSoftKeyboard()
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
            Espresso.closeSoftKeyboard()
            compose.onNodeWithText("Save key").performClick()
            awaitText("Key saved")
            compose.onNodeWithText("Remove API key").performScrollTo().performClick()
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
            compose.onNodeWithText("Done").performClick()
            compose.onNodeWithText("Enable AI").performScrollTo().performClick()
            capture("consent")
            compose.onNodeWithText("Details").performScrollTo().performClick()
            compose.onNodeWithText("Provider API terms").performScrollTo().assertExists()
            compose.onNodeWithText("Done").performClick()
            compose.runOnIdle { org.junit.Assert.assertEquals(0, calls) }
            compose.onNodeWithText("Enable for this session").performClick()
            compose.runOnIdle {
                assertTrue(assistant.providerEnabled)
                org.junit.Assert.assertEquals(0, calls)
            }
            compose.onNodeWithText("Test connection").performScrollTo().performClick()
            compose.onNodeWithText("Cancel").performClick()
            compose.runOnIdle { org.junit.Assert.assertEquals(0, calls) }
            compose.onNodeWithText("Test connection").performScrollTo().performClick()
            compose.onNodeWithText("Send test request").performClick()
            compose.waitUntil(10_000) { calls == 1 }
            compose.onNodeWithText("Replace API key").performScrollTo().performClick()
            compose.onNodeWithTag("assistant-key-entry").performTextInput("fixture-new-opt-in-key")
            Espresso.closeSoftKeyboard()
            compose.onNodeWithText("Save key").performClick()
            compose.waitUntil(10_000) { cancelled }
            compose.runOnIdle {
                assertFalse(assistant.providerEnabled)
                org.junit.Assert.assertEquals(1, calls)
            }
            awaitText("Key saved")
            compose.onNodeWithText("Test connection").assertIsNotEnabled()
            org.junit.Assert.assertEquals("fixture-new-opt-in-key", store.readForRequest())
        } finally {
            compose.runOnIdle { assistant.dispose() }
            owner.cancel()
            store.remove()
            directory.deleteRecursively()
        }
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
