package com.veyarc.app

import android.app.UiAutomation
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.veyarc.app.ui.theme.VeyarcTheme
import com.veyarc.feature.assistant.AssistantPreviewContext
import com.veyarc.feature.assistant.AssistantPreviewHost
import com.veyarc.feature.assistant.AssistantPreviewScreen
import com.veyarc.feature.assistant.AssistantPreviewState
import com.veyarc.feature.assistant.AssistantThinking
import com.veyarc.feature.assistant.DeepSeekEffort
import com.veyarc.feature.assistant.DeepSeekModel
import com.veyarc.feature.assistant.DeepSeekRequest
import com.veyarc.feature.assistant.PreviewScenario
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AssistantPreviewNavigationTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun settingsKeepsAiConnectionCollapsedUntilOpened() {
        compose.onNodeWithContentDescription("Settings", useUnmergedTree = true).performClick()
        compose.onNodeWithText("AI connection").assertIsDisplayed()
        compose.onNodeWithText("AI chat help").assertDoesNotExist()
        compose.onNodeWithText("AI connection").performClick()
        compose.onNodeWithText("AI chat help").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Home", useUnmergedTree = true).performClick()
        compose.onNodeWithContentDescription("Settings", useUnmergedTree = true).performClick()
        compose.onNodeWithText("AI chat help").assertDoesNotExist()
    }

    @Test
    fun editorEntryRecreationAndBackKeepTheUnsentMessage() {
        val owner = androidx.lifecycle.ViewModelProvider(compose.activity)[MainEditorSession::class.java]
        compose.onNodeWithContentDescription("Editor", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Create with AI").performClick()
        compose.waitUntil(10_000) {
            runCatching { compose.onNodeWithTag("assistant-composer").assertExists() }.isSuccess ||
                runCatching { compose.onNodeWithText("No chat selected").assertExists() }.isSuccess
        }
        if (runCatching { compose.onNodeWithTag("assistant-composer").assertExists() }.isFailure) compose.onNodeWithText("New chat").performClick()
        compose.waitUntil(10_000) { runCatching { compose.onNodeWithTag("assistant-composer").assertIsEnabled() }.isSuccess }
        compose.onNodeWithTag("assistant-composer").performTextReplacement("Keep this unsent draft")
        Espresso.closeSoftKeyboard()
        compose.activityRule.scenario.recreate()
        org.junit.Assert.assertSame(owner, androidx.lifecycle.ViewModelProvider(compose.activity)[MainEditorSession::class.java])
        compose.onNodeWithTag("assistant-preview").assertIsDisplayed()
        compose.onNodeWithText("Keep this unsent draft").assertExists()
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        compose.onNodeWithText("Keep this unsent draft").assertExists()
        assertEquals(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
            compose.activity.window.attributes.softInputMode and android.view.WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST,
        )
        val headerTop = compose.onNodeWithTag("assistant-header").fetchSemanticsNode().boundsInWindow.top
        repeat(3) {
            compose.onNodeWithTag("assistant-composer").performTouchInput { click() }
            compose.waitUntil(10_000) {
                androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
            }
            compose.waitForIdle()
            assertEquals(headerTop, compose.onNodeWithTag("assistant-header").fetchSemanticsNode().boundsInWindow.top, 2f)
            val ime = androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)!!.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom
            val bottom = compose.onNodeWithTag("assistant-composer-panel").fetchSemanticsNode().boundsInWindow.bottom
            val gap = compose.activity.window.decorView.height - ime - bottom
            org.junit.Assert.assertTrue("Production composer must sit above the keyboard: $gap", gap in -2f..80f)
            Espresso.pressBack()
            compose.waitUntil(10_000) {
                androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == false
            }
        }

        compose.runOnIdle { org.junit.Assert.assertNull(owner.assistant.requestId) }
        compose.onNodeWithContentDescription("Back to editor").performClick()
        compose.onNodeWithContentDescription("Ask AI").assertExists()
        compose.onNodeWithContentDescription("Ask AI").performClick()
        compose.waitUntil(10_000) { runCatching { compose.onNodeWithText("Enable AI in Settings").assertExists() }.isSuccess }
        compose.onNodeWithText("Enable AI in Settings").performClick()
        compose.onNodeWithText("AI connection").assertIsDisplayed()
        compose.onNodeWithText("AI chat help").performScrollTo().performClick()
        compose.onNodeWithText("Get started").assertIsDisplayed()
        compose.onNodeWithText("Apply updates your draft. Undo is in the editor.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Close AI chat help").performClick()
        compose.onNodeWithContentDescription("Home", useUnmergedTree = true).performClick()
        compose.onNodeWithContentDescription("Settings", useUnmergedTree = true).performClick()
        compose.onNodeWithText("AI chat help").assertDoesNotExist()
    }

    @Test
    fun connectionPanelsFitTheirContentAndModelChoiceIsSaved() {
        if (InstrumentationRegistry.getArguments().getString("layoutLabel")?.contains("landscape") == true) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_90)
            compose.waitUntil(10_000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
        }
        val owner = androidx.lifecycle.ViewModelProvider(compose.activity)[MainEditorSession::class.java]
        val originalModel = owner.assistant.selectedModel
        compose.waitUntil(10_000) { owner.assistant.canChange }
        compose.onNodeWithContentDescription("Settings", useUnmergedTree = true).performClick()
        compose.onNodeWithText("AI connection").performClick()
        compose.onNodeWithTag("deepseek-logo").assertExists()
        capture("connection-settings")
        compose.onNodeWithText("Privacy and costs").performScrollTo().performClick()
        compose.onNodeWithText("Done").assertDoesNotExist()
        compose.onNodeWithText("Provider pricing").performScrollTo().assertIsDisplayed()
        capture("connection-privacy")
        compose.onNodeWithContentDescription("Close Privacy and costs").performClick()
        compose.onNodeWithText("AI chat help").performScrollTo().performClick()
        compose.onNode(hasScrollToIndexAction() and hasAnyAncestor(hasTestTag("assistant-panel-scroll")))
            .performScrollToNode(hasText("See Privacy and costs for shared data and charges."))
        val last = compose.onNodeWithText("See Privacy and costs for shared data and charges.").fetchSemanticsNode().boundsInRoot
        val panel = compose.onNodeWithTag("assistant-panel").fetchSemanticsNode().boundsInRoot
        val density = compose.activity.resources.displayMetrics.density
        org.junit.Assert.assertTrue("Help must not reserve a blank footer: ${panel.bottom - last.bottom}", panel.bottom - last.bottom in 0f..24f * density)
        capture("connection-help")
        compose.onNodeWithContentDescription("Close AI chat help").performClick()
        compose.onNodeWithText("Model").performScrollTo().performClick()
        capture("connection-models")
        compose.onNodeWithText("DeepSeek V4 Pro").assertIsEnabled().performClick()
        compose.waitUntil(10_000) { owner.assistant.selectedModel == DeepSeekModel.PRO }
        compose.waitUntil(10_000) {
            kotlinx.coroutines.runBlocking { AppPreferences.observe(compose.activity).first().assistantModel == DeepSeekModel.PRO }
        }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("AI connection").performClick()
        compose.onNodeWithText("V4 Pro").performScrollTo().assertIsDisplayed()
        compose.waitUntil(10_000) { owner.assistant.canChange }
        compose.runOnIdle { owner.assistant.setModel(originalModel) }
        compose.waitUntil(10_000) {
            kotlinx.coroutines.runBlocking { AppPreferences.observe(compose.activity).first().assistantModel == originalModel }
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        captureAssistantPreview(name)
    }
}

@RunWith(AndroidJUnit4::class)
class AssistantPreviewUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Before
    fun useRequestedLayout() {
        if (InstrumentationRegistry.getArguments().getString("layoutLabel")?.contains("landscape") == true) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_90)
            compose.waitUntil(10_000) {
                InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            }
        }
    }

    @Test
    fun slowTypingAutosavesWithoutBlinkingTheComposerControls() {
        val repository = com.veyarc.data.storage.ChatRepository(InstrumentationRegistry.getInstrumentation().targetContext)
        val owner = kotlinx.coroutines.SupervisorJob()
        val scope = kotlinx.coroutines.CoroutineScope(owner + kotlinx.coroutines.Dispatchers.Main)
        lateinit var state: AssistantPreviewState
        var initialized = false
        var observer: kotlinx.coroutines.Job? = null
        val saves = mutableListOf<Triple<Int, Boolean, Boolean>>()
        try {
            compose.runOnIdle {
                state = AssistantPreviewState(repository, scope, providerCall = { error("Typing must not send a request") })
                initialized = true
            }
            compose.waitUntil(10_000) { state.canChange }
            compose.runOnIdle {
                state.open(AssistantPreviewContext(java.util.UUID.randomUUID().toString()))
                state.enableProvider()
            }
            compose.waitUntil(10_000) { state.canChange }
            compose.setContent { VeyarcTheme(darkTheme = true) { AssistantPreviewScreen(state) } }
            compose.onNodeWithTag("assistant-composer").performTextInput("Slow")
            compose.runOnIdle {
                observer = scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                    androidx.compose.runtime.snapshotFlow { Triple(state.pendingWrites, state.canChange, state.canSend) }.collect { saves += it }
                }
            }
            repeat(3) { index ->
                val text = "Slow $index"
                compose.onNodeWithTag("assistant-composer").performTextReplacement(text)
                compose.waitUntil(10_000) {
                    state.pendingWrites == 0 && repository.load().single { it.id == state.selectedId }.payload.toString(Charsets.UTF_8).contains("\"composer\":\"$text\"")
                }
                compose.onNodeWithContentDescription("Send message").assertIsEnabled()
                compose.onNodeWithContentDescription("New chat").assertIsEnabled()
                compose.onNodeWithContentDescription("Add attachment").assertIsDisplayed()
            }
            compose.runOnIdle {
                observer?.cancel()
                org.junit.Assert.assertTrue("Observe real background writes", saves.any { it.first > 0 })
                org.junit.Assert.assertTrue("Autosaving must leave the controls available: $saves", saves.all { it.second && it.third })
            }
        } finally {
            compose.runOnIdle {
                observer?.cancel()
                if (initialized) state.dispose()
            }
            owner.cancel()
            if (initialized) state.selectedId?.let(repository::delete)
            repository.close()
        }
    }

    @Test
    @androidx.test.filters.SdkSuppress(minSdkVersion = 30)
    fun systemBarAppearanceMatchesTheThemeDuringSheetDrags() {
        val state = AssistantPreviewState().apply { open(AssistantPreviewContext()) }
        var dark by mutableStateOf(true)
        compose.setContent { VeyarcTheme(darkTheme = dark) { AssistantPreviewScreen(state) } }
        fun checkBars(expectSheet: Boolean) {
            compose.runOnIdle {
                val roots = android.view.inspector.WindowInspector.getGlobalWindowViews().filter { it.isShown }
                org.junit.Assert.assertTrue("Check the activity and sheet windows", roots.size >= if (expectSheet) 2 else 1)
                roots.forEach { root ->
                    val appearance = requireNotNull(root.windowInsetsController).systemBarsAppearance
                    assertEquals("Status icons must match the app theme", !dark, appearance and android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS != 0)
                    assertEquals("Navigation icons must match the app theme", !dark, appearance and android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS != 0)
                }
            }
        }
        listOf(true, false).forEach { theme ->
            compose.runOnIdle { dark = theme }
            checkBars(false)
            compose.onNodeWithContentDescription("Add attachment").performClick()
            checkBars(true)
            val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
            compose.onNodeWithTag("assistant-panel-header").performTouchInput {
                down(center)
                moveBy(Offset(0f, 28f * density), delayMillis = 300)
            }
            checkBars(true)
            compose.onNodeWithTag("assistant-panel-header").performTouchInput {
                advanceEventTime(500)
                up()
            }
            checkBars(true)
            compose.onNodeWithTag("assistant-panel-header").performTouchInput { swipeDown(durationMillis = 80) }
            compose.onNodeWithTag("assistant-panel").assertDoesNotExist()
            checkBars(false)
        }
    }

    @Test
    fun clearHistoryOffersWorkflowAndAllChatScopes() {
        val state = AssistantPreviewState().apply {
            open(AssistantPreviewContext("other-workflow"))
            compose("Keep this draft")
            open(AssistantPreviewContext("current-workflow"))
            compose("Clear this draft")
        }
        compose.setContent { VeyarcTheme(darkTheme = true) { AssistantPreviewScreen(state) } }
        fun openClear() {
            if (runCatching { compose.onNodeWithContentDescription("Chat options").assertExists() }.isSuccess) {
                compose.onNodeWithContentDescription("Chat options").performClick()
                compose.onNodeWithText("More", substring = false).performClick()
            }
            compose.onNodeWithText("Chat storage", substring = false).performClick()
            compose.onNodeWithText("Clear chat history", substring = false).performClick()
        }
        openClear()
        compose.onNodeWithText("This workflow", substring = false).assertIsSelected()
        compose.onNodeWithText("All chats", substring = false).assertIsNotSelected()
        compose.onNodeWithText("Clear history", substring = false).performClick()
        compose.runOnIdle {
            assertEquals("Keep this draft", state.conversations.single().composer)
            assertEquals("other-workflow", state.conversations.single().workflowId)
        }
        openClear()
        compose.onNodeWithText("All chats", substring = false).performClick()
        compose.onNodeWithText("All chats", substring = false).assertIsSelected()
        compose.onNodeWithText("Clear history", substring = false).performClick()
        compose.runOnIdle { org.junit.Assert.assertTrue(state.conversations.isEmpty()) }
    }

    @Test
    fun effortSelectionUpdatesTheComposerAndLocksDuringARequest() {
        fun effortOption(label: String) = compose.onNode(hasText(label) and hasAnyAncestor(hasTestTag("assistant-effort-options")))
        fun modelOption(label: String) = compose.onNode(hasText(label) and hasAnyAncestor(hasTestTag("assistant-panel")))
        val owner = kotlinx.coroutines.SupervisorJob()
        var calls = 0
        val state = AssistantPreviewState(scope = kotlinx.coroutines.CoroutineScope(owner + kotlinx.coroutines.Dispatchers.Main), providerCall = {
            calls++
            kotlinx.coroutines.awaitCancellation()
        }).apply {
            open(AssistantPreviewContext())
            enableProvider()
        }
        try {
            compose.setContent { VeyarcTheme(darkTheme = true) { AssistantPreviewScreen(state) } }
            compose.onNodeWithText("Off").assertIsDisplayed()
            compose.onNodeWithText("DeepSeek V4.1 Flash").assertIsDisplayed()
            compose.onNodeWithTag("assistant-model-picker").performClick()
            modelOption("DeepSeek V4 Pro").assertIsEnabled().performClick()
            compose.onNodeWithTag("assistant-model-picker").performClick()
            compose.onNodeWithContentDescription("Open Effort").assertExists()
            compose.onNodeWithText("Effort").performClick()
            DeepSeekEffort.entries.forEach { compose.onNodeWithContentDescription("Open ${it.label}").assertDoesNotExist() }
            effortOption("Off").assertIsSelected()
            compose.onNodeWithText("Medium").assertDoesNotExist()
            effortOption("High").assertIsEnabled().performClick()
            compose.onNodeWithText("High").assertIsDisplayed()
            compose.onNodeWithTag("assistant-composer").performTextReplacement("Explain this workflow")
            Espresso.closeSoftKeyboard()
            compose.onNodeWithContentDescription("Send message").performClick()
            compose.waitUntil(10_000) { calls == 1 }
            compose.onNodeWithTag("assistant-model-picker").performClick()
            modelOption("DeepSeek V4.1 Flash").assertIsNotEnabled()
            modelOption("DeepSeek V4 Pro").assertIsSelected().assertIsNotEnabled()
            compose.onNodeWithText("Effort").performClick()
            effortOption("High").assertIsSelected().assertIsNotEnabled()
            effortOption("Low").assertIsNotEnabled()
            compose.onNodeWithContentDescription("Close Effort").performClick()
            compose.onNodeWithContentDescription("Stop response").performClick()
            compose.onNodeWithTag("assistant-model-picker").performClick()
            compose.onNodeWithText("Effort").performClick()
            effortOption("Max").assertIsEnabled().performClick()
            compose.runOnIdle {
                assertEquals(com.veyarc.feature.assistant.DeepSeekEffort.MAX, state.conversation!!.effort)
                assertEquals(com.veyarc.feature.assistant.DeepSeekEffort.HIGH, state.conversation!!.turns.single().effort)
                assertEquals(DeepSeekModel.PRO, state.conversation!!.turns.single().model)
                assertEquals(1, calls)
            }
            compose.onNodeWithTag("assistant-model-picker").performClick()
            modelOption("DeepSeek V4.1 Flash").performClick()
            compose.onNodeWithText("DeepSeek V4.1 Flash").assertIsDisplayed()
            compose.runOnIdle { assertEquals(DeepSeekModel.FLASH, state.selectedModel) }
            capture("effort-selected")
        } finally {
            compose.runOnIdle { state.dispose() }
            owner.cancel()
        }
    }

    @Test
    fun currentWindowKeepsTypingAndNavigationReachable() {
        val state = AssistantPreviewState().apply { open(AssistantPreviewContext()) }
        var keyboardVisible by mutableStateOf(false)
        var imeBottom = 0
        compose.setContent {
            val bottom = WindowInsets.ime.getBottom(LocalDensity.current)
            val visible = bottom > 0
            SideEffect { keyboardVisible = visible }
            SideEffect { imeBottom = bottom }
            VeyarcTheme(darkTheme = false) {
                AssistantPreviewHost(true, state, AssistantPreviewContext()) {
                    androidx.compose.material3.Text("Editor pane")
                }
            }
        }
        if (InstrumentationRegistry.getArguments().getString("expectWide") == "true") {
            compose.onNodeWithTag("assistant-split").assertExists()
            compose.onNodeWithText("Editor pane").assertIsDisplayed()
        }
        capture("empty-light")
        val headerTop = runCatching { compose.onNodeWithTag("assistant-header").fetchSemanticsNode().boundsInRoot.top }.getOrNull()
        compose.onNodeWithTag("assistant-composer").performClick().performTextInput("Create a workflow")
        compose.waitUntil(10_000) { keyboardVisible }
        compose.waitForIdle()
        capture("keyboard-light")
        compose.onNodeWithContentDescription("Send message").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back to editor").assertIsDisplayed()
        if (runCatching { compose.onNodeWithTag("assistant-header").assertExists() }.isSuccess) {
            assertEquals(headerTop!!, compose.onNodeWithTag("assistant-header").fetchSemanticsNode().boundsInRoot.top, 2f)
        }
        val rootBottom = compose.onNodeWithTag("assistant-preview").fetchSemanticsNode().boundsInRoot.bottom
        val composerBottom = compose.onNodeWithTag("assistant-composer-panel").fetchSemanticsNode().boundsInRoot.bottom
        org.junit.Assert.assertTrue("Composer must stay above the keyboard without a large gap", rootBottom - imeBottom - composerBottom in -2f..80f)
        Espresso.pressBack()
        compose.waitUntil(10_000) { !keyboardVisible }
        compose.waitForIdle()
        compose.onNodeWithTag("assistant-preview").assertIsDisplayed()
        repeat(3) {
            compose.onNodeWithTag("assistant-composer").performTouchInput { click() }
            compose.waitUntil(10_000) { keyboardVisible }
            compose.onNodeWithContentDescription("Send message").assertIsDisplayed()
            Espresso.pressBack()
            compose.waitUntil(10_000) { !keyboardVisible }
            compose.waitForIdle()
            if (headerTop != null) {
                compose.waitUntil(10_000) { runCatching { compose.onNodeWithTag("assistant-header").assertExists() }.isSuccess }
                assertEquals(headerTop, compose.onNodeWithTag("assistant-header").fetchSemanticsNode().boundsInRoot.top, 2f)
            }
        }
    }

    @Test
    fun branchingKeepsLatestReplyVisibleOnTheFirstLayout() {
        val state = AssistantPreviewState().apply {
            open(AssistantPreviewContext())
            repeat(8) { index ->
                compose("Explain workflow $index")
                send()
                repeat(100) { requestId?.let(::advance) }
            }
        }
        compose.setContent { VeyarcTheme(darkTheme = true) { AssistantPreviewScreen(state) } }
        compose.onNodeWithTag("assistant-reply-actions-${state.conversation!!.turns.last().id}").assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        compose.onNode(
            androidx.compose.ui.test.hasContentDescription("Branch to new chat") and
                hasAnyAncestor(hasTestTag("assistant-reply-actions-${state.conversation!!.turns.last().id}")),
        ).performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.onNodeWithTag("assistant-reply-actions-${state.conversation!!.turns.last().id}").assertIsDisplayed()
        compose.mainClock.autoAdvance = true
    }

    @Test
    fun replyBarBranchesAndFutureControlsStayHonest() {
        val state = AssistantPreviewState().apply {
            open(AssistantPreviewContext())
            compose("Explain a workflow")
            send()
            repeat(100) { requestId?.let(::advance) }
        }
        val original = state.conversation!!
        compose.setContent { VeyarcTheme(darkTheme = true) { AssistantPreviewScreen(state) } }
        compose.onNodeWithContentDescription("Copy reply").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Branch to new chat").performScrollTo().performClick()
        compose.runOnIdle {
            org.junit.Assert.assertNotEquals(original.id, state.selectedId)
            assertEquals(original, state.conversations.first { it.id == original.id })
            org.junit.Assert.assertNull(state.requestId)
        }

        compose.onNodeWithContentDescription("Chat options").performClick()
        compose.onNodeWithText("Pin").assertIsDisplayed()
        compose.onNodeWithText("Workflow context").assertDoesNotExist()
        capture("chat-menu")
        compose.onNodeWithText("Pin").performClick()
        compose.runOnIdle { org.junit.Assert.assertTrue(state.conversation!!.pinned) }
        val branchId = state.selectedId!!
        compose.onNodeWithContentDescription("Chat options").performClick()
        compose.onNodeWithText("Chat history").performClick()
        compose.onNodeWithTag("assistant-history-$branchId").assertIsSelected()
        compose.onNodeWithTag("assistant-history-${original.id}").assertIsNotSelected()
        compose.onNodeWithText("Done").assertDoesNotExist()
        capture("history-dark")
        compose.onNodeWithContentDescription("Close Chats").performClick()
        compose.onNodeWithContentDescription("Chat options").performClick()
        compose.onNodeWithText("More").performClick()
        compose.onNodeWithText("Workflow context").assertIsDisplayed()
        compose.onNodeWithText("Chat storage").performClick()
        compose.onNodeWithText("Done").assertDoesNotExist()
        capture("storage-dark")
        compose.onNodeWithContentDescription("Close Chat storage").performClick()
        compose.onNodeWithContentDescription("Chat options").performClick()
        compose.onNodeWithText("More").performClick()
        Espresso.pressBack()
        compose.onNodeWithContentDescription("Add attachment").performClick()
        compose.onNodeWithText("Attachments coming soon").assertIsDisplayed()
        compose.onNodeWithText("Photos").assertIsNotEnabled()
        capture("attachments")
        Espresso.pressBack()
        compose.onNodeWithText("Sample").performClick()
        compose.onNodeWithText("Select model").assertIsDisplayed()
        compose.onNode(hasText("Sample chat") and hasAnyAncestor(hasTestTag("assistant-panel"))).assertIsSelected()
        compose.onNodeWithText("DeepSeek V4 Pro").assertDoesNotExist()
        capture("models")
        compose.onNodeWithText("Effort").performClick()
        compose.onNodeWithText("High").assertIsNotEnabled()
        capture("effort")
        compose.onNodeWithContentDescription("Back to models").performClick()
        compose.onNodeWithText("Select model").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close Select model").performClick()
        compose.onNodeWithTag("assistant-composer").assertIsDisplayed()
    }

    @Test
    fun samplesCanStopRetryAndReviewWithoutApplying() {
        val state = AssistantPreviewState().apply { open(AssistantPreviewContext("example", "Morning routine", 3)) }
        compose.mainClock.autoAdvance = false
        compose.setContent { VeyarcTheme(darkTheme = false) { AssistantPreviewScreen(state) } }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithContentDescription("Send message").assertIsNotEnabled()
        compose.onNodeWithText("Explain this workflow").performClick()
        compose.onNodeWithContentDescription("Send message").performClick()
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithContentDescription("Stop response").performClick()
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithText("Stopped").assertExists()
        compose.onNodeWithText("Retry sample").performClick()
        compose.mainClock.advanceTimeBy(5000)
        assertEquals(1, state.conversation!!.turns.size)
        compose.runOnIdle {
            state.scenario = PreviewScenario.PROPOSAL
            state.compose("Change a wait")
            state.send()
        }
        compose.mainClock.advanceTimeBy(5000)
        compose.onNodeWithTag("assistant-review-toggle").performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithText("Apply to draft").assertIsNotEnabled()
        capture("proposal-light")
        compose.onNodeWithTag("assistant-review-toggle").performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(100)
        capture("conversation-light")
    }

    @Test
    fun largeTextKeepsComposerAndHistoryReachable() {
        val state = AssistantPreviewState().apply {
            open(AssistantPreviewContext("example", "A workflow with a deliberately long name", 3))
        }
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                VeyarcTheme(darkTheme = true) { AssistantPreviewScreen(state) }
            }
        }
        compose.onNodeWithTag("assistant-composer").assertIsDisplayed().performTextInput("Explain")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithContentDescription("Send message").assertIsDisplayed()
        capture("empty-dark-large-text")
        compose.onNodeWithContentDescription("Chat options").performClick()
        compose.onNodeWithText("Chat history").performClick()
        compose.onNodeWithText("Chats").assertIsDisplayed()
        compose.onNodeWithText("Done").assertDoesNotExist()
        capture("history-large-text")
        compose.onNodeWithContentDescription("Close Chats").performClick()
        val originalId = state.selectedId!!
        compose.onNodeWithContentDescription("New chat").performClick()
        assertEquals(1, state.history.size)
        assertEquals(originalId, state.selectedId)
        compose.onNodeWithContentDescription("Chat options").performClick()
        compose.onNodeWithText("Chat history").performClick()
        compose.onNodeWithTag("assistant-history-$originalId").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(originalId, state.selectedId)
            assertEquals("Explain", state.conversation!!.composer)
        }
    }

    @Test
    fun workflowContextShowsCurrentAndCapturedUnsavedText() {
        var draft = com.veyarc.feature.editor.WorkflowDraft.newDefault().copy(name = "Unsaved context name")
        val state = AssistantPreviewState().apply {
            open(AssistantPreviewContext(draft.workflowId, draft.name)) { draft.captureAssistantContext() }
        }
        compose.setContent { VeyarcTheme { AssistantPreviewScreen(state) } }
        compose.onNodeWithContentDescription("Chat options").performClick()
        compose.onNodeWithText("More").performClick()
        compose.onNodeWithText("Workflow context").performClick()
        compose.onNodeWithText("Show shared data").performScrollTo().performClick()
        compose.onNodeWithTag("assistant-context-json").assertTextContains("Unsaved context name", substring = true)
        capture("workflow-context")
        compose.onNodeWithContentDescription("Close Workflow context").performClick()
        compose.runOnIdle {
            state.compose("Explain")
            state.send()
            state.stop()
            draft = draft.copy(name = "Later edit")
        }
        compose.onNodeWithText("View captured context").assertDoesNotExist()
        compose.onNodeWithContentDescription("Your message: Explain").performScrollTo().performTouchInput { longClick() }
        capture("message-actions")
        compose.onNodeWithText("View captured context").performClick()
        compose.onNodeWithText("Captured workflow context").assertExists()
        compose.onNodeWithText("Show shared data").performScrollTo().performClick()
        compose.onNodeWithTag("assistant-context-json").assertTextContains("Unsaved context name", substring = true)
        compose.onNodeWithContentDescription("Close Captured workflow context").performClick()
        compose.onNodeWithContentDescription("Chat options").performClick()
        compose.onNodeWithText("More").performClick()
        compose.onNodeWithText("Workflow context").performClick()
        compose.onNodeWithText("Show shared data").performScrollTo().performClick()
        compose.onNodeWithTag("assistant-context-json").assertTextContains("Later edit", substring = true)
        assertEquals("Later edit", draft.name)
    }

    @Test
    fun clarificationAndRejectedResponsesHaveNoApplicableProposal() {
        val state = AssistantPreviewState().apply {
            open(AssistantPreviewContext())
            scenario = PreviewScenario.CLARIFICATION
            compose("Help me create a workflow")
            send()
            repeat(100) { requestId?.let(::advance) }
        }
        compose.setContent { VeyarcTheme { AssistantPreviewScreen(state) } }
        compose.onNodeWithText(state.conversation!!.turns.single().reply).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("assistant-review-toggle").assertDoesNotExist()
        compose.runOnIdle {
            state.scenario = PreviewScenario.INVALID_OUTPUT
            state.compose("Keep this rejected prompt")
            state.send()
            state.advance(state.requestId!!)
        }
        compose.onNodeWithText("Response rejected:", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Retry sample").assertExists()
        compose.onNodeWithText("Apply to draft").assertDoesNotExist()
        capture("response-rejected")
    }

    @Test
    fun candidateValidationShowsDiagnosticsAndNeverEnablesApply() {
        val draft = com.veyarc.feature.editor.WorkflowDraft.newDefault()
        val state = AssistantPreviewState(compileCandidate = ::assistantCompilationIssues) { draft.captureAssistantContext() }
        state.open(AssistantPreviewContext(draft.workflowId))
        state.scenario = PreviewScenario.VALIDATED_PROPOSAL
        state.compose("Validate this sample")
        state.send()
        state.advance(state.requestId!!)
        compose.setContent { VeyarcTheme { AssistantPreviewScreen(state) } }
        compose.onNodeWithTag("assistant-review-toggle").performScrollTo().performClick()
        compose.onNodeWithText("Ready for review").assertIsDisplayed()
        compose.onNodeWithText("Device capabilities have not been checked.", substring = true).assertExists()
        compose.onNodeWithText("Apply to draft").assertIsNotEnabled()
        capture("candidate-valid")
        compose.onNodeWithTag("assistant-review-toggle").performScrollTo().performClick()
        compose.runOnIdle {
            state.newChat()
            state.scenario = PreviewScenario.INVALID_CANDIDATE
            state.compose("Reject locked fields")
            state.send()
            state.advance(state.requestId!!)
        }
        compose.onNodeWithTag("assistant-review-toggle").performScrollTo().performClick()
        compose.onNodeWithText("Invalid proposal").assertIsDisplayed()
        compose.onNodeWithText("Technical details").performScrollTo().performClick()
        compose.onNodeWithText("Candidate changed locked fields:", substring = true).assertExists()
        compose.onNodeWithText("Apply to draft").assertIsNotEnabled()
        capture("candidate-invalid")
        assertEquals("New workflow", draft.name)
    }

    @Test
    fun reviewShowsLocalValuesAndDetectsEditsWhileOpen() {
        var draft by mutableStateOf(com.veyarc.feature.editor.WorkflowDraft.newDefault())
        val state = AssistantPreviewState(compileCandidate = ::assistantCompilationIssues) { draft.captureAssistantContext() }
        state.open(AssistantPreviewContext(draft.workflowId))
        state.scenario = PreviewScenario.VALIDATED_PROPOSAL
        state.compose("Review this sample")
        state.send()
        state.advance(state.requestId!!)
        compose.setContent { VeyarcTheme { AssistantPreviewScreen(state) } }
        compose.onNodeWithTag("assistant-review-toggle").performScrollTo().performClick()
        compose.onNodeWithText("After: Sample workflow name").performScrollTo().assertIsDisplayed()
        capture("review-values")
        compose.runOnIdle { draft = draft.copy(name = "Edited after Send") }
        compose.onNodeWithText("Stale proposal").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Apply to draft").performScrollTo().assertIsNotEnabled()
        capture("review-stale")
        compose.onNodeWithText("Dismiss proposal").performScrollTo().performClick()
        compose.onNodeWithText("Dismissed proposal").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("assistant-review-toggle").performScrollTo().performClick()
        compose.onNodeWithTag("assistant-review-toggle").performScrollTo().performClick()
        compose.onNodeWithText("Dismissed proposal").assertIsDisplayed()
        assertEquals("Edited after Send", draft.name)
    }

    @Test
    fun largeProposalPreviewOpensEveryChangeWithoutApplying() {
        val args = InstrumentationRegistry.getArguments()
        val singleChange = args.getString("singleLongChange") == "true"
        val largeText = args.getString("previewLargeText") == "true"
        val owner = kotlinx.coroutines.SupervisorJob()
        val editor = com.veyarc.feature.editor.WorkflowEditorSession().apply { open(com.veyarc.feature.editor.WorkflowDraft.newDefault().buildBundle().bundle!!) }
        val before = editor.draft!!
        val bridge = AssistantDraftBridge(editor)
        val note = "Keep this full note. ".repeat(100) + "End of full note"
        val candidate = before.buildBundle().bundle!!.document.copy(
            variables = mapOf("note" to com.veyarc.model.VariableDeclaration(com.veyarc.model.VariableType.STRING, com.veyarc.model.StringValue(note))),
            steps = if (singleChange) before.steps else List(20) { com.veyarc.model.Wait(java.util.UUID.randomUUID().toString(), com.veyarc.model.DurationMillis(1000L + it)) },
        )
        lateinit var state: AssistantPreviewState
        try {
            compose.runOnIdle {
                state = AssistantPreviewState(
                    scope = kotlinx.coroutines.CoroutineScope(owner + kotlinx.coroutines.Dispatchers.Main),
                    compileCandidate = ::assistantCompilationIssues,
                    applyCandidate = bridge::apply,
                    providerCall = {
                        val chat = state.conversation!!
                        val turn = chat.turns.last()
                        val response = com.veyarc.feature.assistant.AssistantResponse(
                            1,
                            turn.requestId,
                            chat.id,
                            turn.baseSnapshot!!.snapshotId,
                            com.veyarc.feature.assistant.AssistantOutcome.PROPOSAL,
                            "Here are the changes.",
                            com.veyarc.model.WorkflowJson.format.parseToJsonElement(com.veyarc.model.WorkflowJson.encode(candidate)) as kotlinx.serialization.json.JsonObject,
                        )
                        com.veyarc.model.WorkflowJson.encodeCanonical(com.veyarc.feature.assistant.AssistantResponse.serializer(), response).toByteArray()
                    },
                    contextSource = bridge::capture,
                ).apply {
                    open(AssistantPreviewContext(before.workflowId))
                    enableProvider()
                    compose("Update this workflow")
                    send()
                }
            }
            compose.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, if (largeText) 2f else density.fontScale)) {
                    VeyarcTheme(darkTheme = true) { AssistantPreviewScreen(state) }
                }
            }
            compose.waitUntil(10_000) { state.conversation!!.turns.last().outcome != null }
            compose.runOnIdle { org.junit.Assert.assertTrue(state.conversation!!.turns.last().proposal!!.isValid) }
            compose.onNodeWithTag("assistant-review-toggle").performScrollTo().performClick()
            val preview = compose.onNodeWithTag("assistant-changes-preview").performScrollTo().fetchSemanticsNode().boundsInRoot
            val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
            org.junit.Assert.assertTrue("Inline preview must stay compact", preview.height <= 220f * density + 2f)
            if (!singleChange) compose.onNodeWithText("1019 ms", substring = true).assertDoesNotExist()
            capture("changes-preview")
            repeat(2) { compose.onNodeWithTag("assistant-messages").performTouchInput { swipeUp() } }
            compose.onNodeWithText("View all changes").assertIsDisplayed().performClick()
            capture("all-changes-open")
            compose.waitUntil(10_000) { runCatching { compose.onNodeWithText("All changes").assertIsDisplayed() }.isSuccess }
            compose.onNodeWithText("All changes").assertIsDisplayed()
            val fullNote = compose.onNode(hasText("End of full note", substring = true) and hasAnyAncestor(hasTestTag("assistant-all-changes")))
            fullNote.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
                val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                action(layouts)
                org.junit.Assert.assertFalse("Full review must not truncate values", layouts.single().hasVisualOverflow)
                org.junit.Assert.assertTrue(layouts.single().lineCount > 2)
            }
            if (!singleChange) compose.onNodeWithText("1019 ms", substring = true).performScrollTo().assertIsDisplayed()
            capture("all-changes")
            compose.runOnIdle { assertEquals(before, editor.draft) }
            compose.onNodeWithContentDescription("Close All changes").performClick()
            repeat(2) { compose.onNodeWithTag("assistant-messages").performTouchInput { swipeUp() } }
            compose.onNodeWithText("Apply all changes").assertIsDisplayed().assertIsEnabled().performClick()
            compose.runOnIdle {
                assertEquals(candidate.steps, editor.draft!!.steps)
                assertEquals(candidate.variables, editor.draft!!.variables)
                editor.undo()
                assertEquals(before, editor.draft)
            }
            compose.onNodeWithText("Apply all changes").assertIsNotEnabled()
        } finally {
            owner.cancel()
        }
    }

    @Test
    fun explicitApplyChangesDraftOnceAndEditorUndoRestoresIt() {
        val editor = com.veyarc.feature.editor.WorkflowEditorSession().apply { open(com.veyarc.feature.editor.WorkflowDraft.newDefault().buildBundle().bundle!!) }
        val before = editor.draft
        val bridge = AssistantDraftBridge(editor)
        val state = AssistantPreviewState(compileCandidate = ::assistantCompilationIssues, applyCandidate = bridge::apply, contextSource = bridge::capture)
        state.open(AssistantPreviewContext(editor.draft!!.workflowId))
        state.scenario = PreviewScenario.VALIDATED_PROPOSAL
        state.compose("Rename this draft")
        state.send()
        state.advance(state.requestId!!)
        assertEquals(before, editor.draft)
        compose.setContent { VeyarcTheme { AssistantPreviewScreen(state) } }
        compose.onNodeWithTag("assistant-review-toggle").performScrollTo().performClick()
        compose.onNodeWithText("Apply to draft").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText("Applied to draft").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Apply to draft").performScrollTo().assertIsNotEnabled()
        assertEquals("Sample workflow name", editor.draft!!.name)
        capture("applied-draft")
        compose.runOnIdle { editor.undo() }
        assertEquals(before, editor.draft)
        compose.onNodeWithText("Apply to draft").assertIsNotEnabled()
    }

    @Test
    fun thinkingStreamsSeparatelyAndStaysCompactWhenExpanded() {
        val owner = kotlinx.coroutines.SupervisorJob()
        val result = kotlinx.coroutines.CompletableDeferred<ByteArray>()
        var request: DeepSeekRequest? = null
        lateinit var state: AssistantPreviewState
        val largeText = InstrumentationRegistry.getArguments().getString("thinkingLargeText") == "true"
        val thought = "I’ll check the order of the steps before suggesting a change."
        try {
            compose.runOnIdle {
                state = AssistantPreviewState(
                    scope = kotlinx.coroutines.CoroutineScope(owner + kotlinx.coroutines.Dispatchers.Main),
                    providerCall = {
                        val chat = state.conversation!!
                        val current = chat.turns.last()
                        if (current.prompt.startsWith("Earlier question")) {
                            com.veyarc.model.WorkflowJson.encodeCanonical(
                                com.veyarc.feature.assistant.AssistantResponse.serializer(),
                                com.veyarc.feature.assistant.AssistantResponse(1, current.requestId, chat.id, "draft:${chat.draftId}", com.veyarc.feature.assistant.AssistantOutcome.EXPLANATION, "Earlier reply. ".repeat(30)),
                            ).toByteArray()
                        } else {
                            request = it
                            result.await()
                        }
                    },
                ).apply {
                    open(AssistantPreviewContext())
                    enableProvider()
                }
            }
            // Leave the parent chat scrollable, so a leaked child fling would move it.
            repeat(8) { index ->
                compose.runOnIdle {
                    state.compose("Earlier question $index")
                    state.send()
                }
                compose.waitUntil(10_000) { state.conversation!!.turns.last().outcome != null }
            }
            compose.runOnIdle {
                state.apply {
                    setEffort(DeepSeekEffort.HIGH)
                    compose("Explain this workflow")
                    send()
                }
            }
            compose.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, if (largeText) 2f else density.fontScale)) {
                    VeyarcTheme(darkTheme = true) { AssistantPreviewScreen(state) }
                }
            }
            compose.waitUntil(10_000) { request != null }
            val turn = state.conversation!!.turns.last()
            val toggle = "assistant-thinking-toggle-${turn.id}"
            val panel = "assistant-thinking-text-${turn.id}"
            compose.onNodeWithText("Thinking…").performScrollTo().assertIsDisplayed()
            compose.runOnIdle { request!!.reportThinking(AssistantThinking(thought)) }
            compose.onNodeWithTag(panel).assertDoesNotExist()
            compose.onNodeWithTag("assistant-reply-${turn.id}").assertDoesNotExist()
            compose.onNodeWithText("Apply to draft").assertDoesNotExist()
            compose.onNodeWithTag(toggle).performScrollTo().performClick()
            compose.onNodeWithText(thought).assertIsDisplayed()
            capture("thinking-live")
            val fullThought = (1..1200).joinToString("\n") { "Checking step order $it." } + "\nLatest thought"
            compose.runOnIdle { request!!.reportThinking(AssistantThinking(fullThought)) }
            val bounds = compose.onNodeWithTag(panel).performScrollTo().fetchSemanticsNode().boundsInRoot
            val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
            org.junit.Assert.assertTrue("Expanded thinking must stay compact", bounds.height <= 180f * density + 2f)
            compose.onNodeWithText("Latest thought").assertIsDisplayed()
            compose.runOnIdle { request!!.reportThinking(AssistantThinking(fullThought + "\nNewest changing line")) }
            compose.onNodeWithText("Newest changing line").assertIsDisplayed()
            val outerTop = compose.onNodeWithTag(toggle).fetchSemanticsNode().boundsInRoot.top
            repeat(3) { compose.onNodeWithTag(panel).performTouchInput { swipeUp(durationMillis = 100) } }
            assertEquals(outerTop, compose.onNodeWithTag(toggle).fetchSemanticsNode().boundsInRoot.top, 2f)
            compose.onNodeWithTag(panel).performScrollToIndex(0)
            repeat(3) { compose.onNodeWithTag(panel).performTouchInput { swipeDown(durationMillis = 100) } }
            assertEquals(outerTop, compose.onNodeWithTag(toggle).fetchSemanticsNode().boundsInRoot.top, 2f)
            compose.runOnIdle { request!!.reportThinking(AssistantThinking(fullThought + "\nChanged while reading earlier thoughts")) }
            compose.onNodeWithText("Checking step order 1.").assertIsDisplayed()
            compose.onNodeWithContentDescription("Jump to latest thoughts").performClick()
            compose.onNodeWithText("Changed while reading earlier thoughts").assertIsDisplayed()
            compose.onNodeWithContentDescription("Stop response").assertIsDisplayed().performClick()
            compose.onNodeWithText("Partial thoughts").assertExists()
            compose.onNodeWithText("This older entry was shortened.").assertDoesNotExist()
            compose.runOnIdle { assertEquals(fullThought + "\nChanged while reading earlier thoughts", state.conversation!!.turns.last().thinking!!.text) }
            capture("thinking-stopped")
            compose.onNodeWithTag(toggle).performScrollTo().performClick()
            compose.onNodeWithTag(panel).assertDoesNotExist()
            compose.onNodeWithTag("assistant-composer").assertIsDisplayed()
        } finally {
            compose.runOnIdle { state.dispose() }
            owner.cancel()
        }
    }

    @Test
    fun completedThinkingRemainsCollapsibleBesideTheFormattedAnswer() {
        val owner = kotlinx.coroutines.SupervisorJob()
        lateinit var state: AssistantPreviewState
        val thought = "The wait step needs to stay before the next action."
        try {
            compose.runOnIdle {
                state = AssistantPreviewState(
                    scope = kotlinx.coroutines.CoroutineScope(owner + kotlinx.coroutines.Dispatchers.Main),
                    providerCall = { request ->
                        request.reportThinking(AssistantThinking(thought, finished = true))
                        val chat = state.conversation!!
                        val turn = chat.turns.single()
                        com.veyarc.model.WorkflowJson.encodeCanonical(
                            com.veyarc.feature.assistant.AssistantResponse.serializer(),
                            com.veyarc.feature.assistant.AssistantResponse(
                                1,
                                turn.requestId,
                                chat.id,
                                "draft:${chat.draftId}",
                                com.veyarc.feature.assistant.AssistantOutcome.EXPLANATION,
                                "## Step order\n\nKeep the **Wait** before the next action.",
                            ),
                        ).toByteArray()
                    },
                ).apply {
                    open(AssistantPreviewContext())
                    enableProvider()
                    setEffort(DeepSeekEffort.LOW)
                    compose("Explain step order")
                    send()
                }
            }
            compose.setContent { VeyarcTheme { AssistantPreviewScreen(state) } }
            compose.waitUntil(10_000) { state.conversation!!.turns.single().outcome != null }
            val turn = state.conversation!!.turns.single()
            val panel = "assistant-thinking-text-${turn.id}"
            compose.onNodeWithText("Thoughts").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag(panel).assertDoesNotExist()
            compose.onNodeWithText("Step order").assertExists()
            compose.onNodeWithTag("assistant-thinking-toggle-${turn.id}").performClick()
            compose.onNodeWithText(thought).assertIsDisplayed()
            capture("thinking-complete-light")
            compose.onNodeWithTag("assistant-thinking-toggle-${turn.id}").performClick()
            compose.onNodeWithTag(panel).assertDoesNotExist()
            compose.onNodeWithContentDescription("Copy reply").performScrollTo().assertIsDisplayed()
            compose.onNodeWithContentDescription("Branch to new chat").performClick()
            compose.onNodeWithText("Thoughts").performScrollTo().performClick()
            compose.onNodeWithText(thought).assertIsDisplayed()
        } finally {
            compose.runOnIdle { state.dispose() }
            owner.cancel()
        }
    }

    @Test
    fun newChatButtonReusesEmptyChatAndReplyActionsStayNearComposer() {
        val state = AssistantPreviewState().apply { open(AssistantPreviewContext()) }
        val emptyId = state.selectedId
        compose.setContent { VeyarcTheme { AssistantPreviewScreen(state) } }
        compose.onNodeWithTag("assistant-composer").performTextReplacement("Keep this draft")
        Espresso.closeSoftKeyboard()
        repeat(3) { compose.onNodeWithContentDescription("New chat").performClick() }
        compose.runOnIdle {
            assertEquals(emptyId, state.selectedId)
            assertEquals("Keep this draft", state.conversation!!.composer)
            assertEquals(1, state.conversations.size)
            state.send()
            repeat(100) { state.requestId?.let(state::advance) }
        }
        val turn = state.conversation!!.turns.single()
        val actions = compose.onNodeWithTag("assistant-reply-actions-${turn.id}").fetchSemanticsNode().boundsInRoot
        val composer = compose.onNodeWithTag("assistant-composer-panel").fetchSemanticsNode().boundsInRoot
        val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        capture("compact-reply-actions")
        org.junit.Assert.assertTrue("Reply actions must sit next to the composer: ${composer.top - actions.bottom} px, actions=$actions composer=$composer", composer.top - actions.bottom in 0f..16f * density)
        val icon = compose.onNodeWithContentDescription("Copy reply", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(18f * density, icon.width, 2f)
    }

    @Test
    fun historyFilterSearchLongPressAndAnimatedSelectionUseTheChosenChats() {
        val state = AssistantPreviewState().apply {
            open(AssistantPreviewContext())
            listOf("Morning", "Lunch", "Evening").forEach { title ->
                compose(title)
                send()
                repeat(100) { requestId?.let(::advance) }
                rename(title)
                if (title != "Evening") newChat()
            }
        }
        val morning = state.history.single { it.title == "Morning" }.id
        val lunch = state.history.single { it.title == "Lunch" }.id
        val evening = state.selectedId
        compose.setContent { VeyarcTheme(darkTheme = true) { AssistantPreviewScreen(state) } }
        compose.onNodeWithContentDescription("Chat options").performClick()
        compose.onNodeWithText("Chat history").performClick()
        compose.onNodeWithTag("assistant-history-$morning").performTouchInput { longClick() }
        compose.onNodeWithText("Rename", substring = false).performClick()
        compose.onNodeWithText("Chat title").performTextReplacement("Early morning")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithText("Rename", substring = false).performClick()
        compose.onNodeWithTag("assistant-history-$morning").performTouchInput { longClick() }
        capture("history-long-press")
        compose.onNodeWithText("Pin", substring = false).performClick()
        val historyBounds = compose.onNodeWithTag("assistant-panel").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithContentDescription("Filter chats").performClick()
        capture("history-filter")
        compose.onNodeWithText("Pinned", substring = false).performClick()
        assertEquals("Pinned must keep the All page size", historyBounds, compose.onNodeWithTag("assistant-panel").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithTag("assistant-history-$morning").assertExists()
        compose.onNodeWithTag("assistant-history-$lunch").assertDoesNotExist()
        compose.onNodeWithContentDescription("Filter chats").performClick()
        compose.onNodeWithText("All", substring = false).performClick()
        assertEquals("Returning to All must keep the page size", historyBounds, compose.onNodeWithTag("assistant-panel").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithTag("assistant-history-search").performTextReplacement("lunch")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("assistant-history-$lunch").assertExists()
        compose.onNodeWithTag("assistant-history-$morning").assertDoesNotExist()
        compose.onNodeWithTag("assistant-history-search").performTextReplacement("")
        Espresso.closeSoftKeyboard()
        val titleBefore = compose.onNodeWithText("Early morning", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.left
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("Select chats").performClick()
        compose.mainClock.advanceTimeBy(100)
        val titleDuring = compose.onNodeWithText("Early morning", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.left
        compose.mainClock.advanceTimeBy(600)
        val titleAfter = compose.onNodeWithText("Early morning", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.left
        compose.mainClock.autoAdvance = true
        org.junit.Assert.assertTrue("Selection controls must slide in rather than jump: $titleBefore, $titleDuring, $titleAfter", titleDuring > titleBefore && titleDuring < titleAfter)
        compose.onNodeWithTag("assistant-history-check-$morning", useUnmergedTree = true).assertExists()
        Espresso.pressBack()
        compose.onNodeWithText("Chats", substring = false).assertIsDisplayed()
        compose.onNodeWithContentDescription("Select chats").assertIsDisplayed().performClick()
        compose.onNodeWithTag("assistant-history-$morning").performClick()
        compose.onNodeWithTag("assistant-history-$lunch").performClick()
        compose.onNodeWithText("2 selected").assertIsDisplayed()
        capture("history-selection")
        compose.onNodeWithContentDescription("Delete selected chats").performClick()
        compose.onNodeWithText("Cancel", substring = false).performClick()
        compose.runOnIdle { assertEquals(3, state.history.size) }
        compose.onNodeWithContentDescription("Delete selected chats").performClick()
        compose.onNodeWithText("Delete", substring = false).performClick()
        compose.runOnIdle {
            assertEquals(evening, state.selectedId)
            assertEquals(listOf(evening), state.history.map { it.id })
        }
    }

    @Test
    fun fastPanelPullsDismissFromTheHeaderAndScrollableBody() {
        val state = AssistantPreviewState().apply {
            open(AssistantPreviewContext())
            repeat(12) { index ->
                compose("Chat $index")
                send()
                repeat(100) { requestId?.let(::advance) }
                newChat()
            }
        }
        compose.setContent { VeyarcTheme(darkTheme = true) { AssistantPreviewScreen(state) } }
        repeat(6) { attempt ->
            compose.onNodeWithContentDescription("Chat options").performClick()
            compose.waitForIdle()
            if (attempt >= 4) compose.mainClock.autoAdvance = false
            compose.onNodeWithText("Chat history").performClick()
            if (attempt >= 4) compose.mainClock.advanceTimeBy(120)
            val target = if (attempt % 2 == 0) "assistant-panel-header" else "assistant-history-list"
            compose.onNodeWithTag(target).performTouchInput {
                swipe(start = Offset(center.x, 24f), end = Offset(center.x, 330f), durationMillis = if (attempt < 2) 80 else 160)
            }
            compose.mainClock.autoAdvance = true
            compose.onNodeWithTag("assistant-panel").assertDoesNotExist()
        }
        compose.onNodeWithContentDescription("Add attachment").performClick()
        compose.onNodeWithTag("assistant-panel-header").performTouchInput { swipeDown(durationMillis = 80) }
        compose.onNodeWithTag("assistant-panel").assertDoesNotExist()
    }

    @Test
    fun panelBoundariesStayStableAndEveryHeaderStillDrags() {
        val state = AssistantPreviewState().apply {
            open(AssistantPreviewContext())
            repeat(80) { index ->
                compose("Saved chat $index")
                send()
                repeat(100) { requestId?.let(::advance) }
                newChat()
            }
        }
        val largeText = InstrumentationRegistry.getArguments().getString("panelLargeText") == "true"
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (largeText) 2f else density.fontScale)) {
                VeyarcTheme(darkTheme = true) { AssistantPreviewScreen(state) }
            }
        }
        fun checkPanel(title: String, scrollTag: String = "assistant-panel-scroll") {
            compose.onNodeWithText(title).assertIsDisplayed()
            compose.waitForIdle()
            val before = compose.onNodeWithTag("assistant-panel").fetchSemanticsNode().boundsInRoot
            val header = compose.onNodeWithTag("assistant-panel-header").fetchSemanticsNode().boundsInRoot
            compose.mainClock.autoAdvance = false
            try {
                repeat(2) {
                    compose.onNodeWithTag(scrollTag).performTouchInput { swipeUp(durationMillis = 100) }
                    repeat(45) {
                        compose.mainClock.advanceTimeByFrame()
                        val now = compose.onNodeWithTag("assistant-panel").fetchSemanticsNode().boundsInRoot
                        assertEquals("$title must not bounce or resize", before.top, now.top, 2f)
                        assertEquals("$title header must stay fixed", header.top, compose.onNodeWithTag("assistant-panel-header").fetchSemanticsNode().boundsInRoot.top, 2f)
                    }
                }
            } finally {
                compose.mainClock.autoAdvance = true
            }
            val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
            compose.onNodeWithTag("assistant-panel-header").performTouchInput {
                down(center)
                moveBy(Offset(0f, 28f * density), delayMillis = 300)
            }
            org.junit.Assert.assertTrue("$title header must pull the panel", compose.onNodeWithTag("assistant-panel").fetchSemanticsNode().boundsInRoot.top > before.top + 4f)
            compose.onNodeWithTag("assistant-panel-header").performTouchInput {
                advanceEventTime(500)
                up()
            }
            compose.waitForIdle()
            assertEquals("$title must return to its stable position", before.top, compose.onNodeWithTag("assistant-panel").fetchSemanticsNode().boundsInRoot.top, 2f)
            capture("stable-${title.lowercase().replace(' ', '-')}")
        }
        compose.onNodeWithContentDescription("Chat options").performClick()
        compose.onNodeWithText("Chat history").performClick()
        checkPanel("Chats", "assistant-history-list")
        compose.onNodeWithTag("assistant-history-list").performScrollToIndex(80)
        checkPanel("Chats", "assistant-history-list")
        compose.onNodeWithContentDescription("Close Chats").performClick()
        compose.onNodeWithContentDescription("Chat options").performClick()
        compose.onNodeWithText("More").performClick()
        compose.onNodeWithText("Workflow context").performClick()
        checkPanel("Workflow context")
        compose.onNodeWithContentDescription("Close Workflow context").performClick()
        compose.onNodeWithContentDescription("Chat options").performClick()
        compose.onNodeWithText("More").performClick()
        compose.onNodeWithText("Chat storage").performClick()
        checkPanel("Chat storage")
        compose.onNodeWithContentDescription("Close Chat storage").performClick()
        compose.onNodeWithContentDescription("Add attachment").performClick()
        checkPanel("Add to chat")
        compose.onNodeWithContentDescription("Close Add to chat").performClick()
        compose.onNodeWithText("Sample").performClick()
        checkPanel("Select model")
        compose.onNodeWithText("Effort").performScrollTo().performClick()
        checkPanel("Effort")
        compose.onNodeWithContentDescription("Close Effort").performClick()
    }

    @Test
    fun historyScrollReachesTopWithoutPullingThenFreshPullAndHeaderDragDismiss() {
        val state = AssistantPreviewState().apply {
            open(AssistantPreviewContext())
            repeat(20) { index ->
                compose("Saved chat $index")
                send()
                repeat(100) { requestId?.let(::advance) }
                newChat()
            }
        }
        compose.setContent { VeyarcTheme(darkTheme = true) { AssistantPreviewScreen(state) } }
        fun openHistory() {
            compose.onNodeWithContentDescription("Chat options").performClick()
            compose.onNodeWithText("Chat history").performClick()
            compose.waitForIdle()
        }
        openHistory()
        compose.onNodeWithTag("assistant-history-list").performScrollToIndex(3)
        val top = compose.onNodeWithTag("assistant-panel").fetchSemanticsNode().boundsInRoot.top
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("assistant-history-list").performTouchInput { swipeDown(durationMillis = 400) }
        repeat(45) {
            compose.mainClock.advanceTimeByFrame()
            assertEquals("Returning content to the top must not drag the sheet", top, compose.onNodeWithTag("assistant-panel").fetchSemanticsNode().boundsInRoot.top, 2f)
        }
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("assistant-history-list").performScrollToIndex(0)
        compose.onNodeWithTag("assistant-history-list").performTouchInput { swipeDown(durationMillis = 400) }
        compose.onNodeWithTag("assistant-panel").assertDoesNotExist()
        openHistory()
        compose.onNodeWithTag("assistant-history-list").performScrollToIndex(10)
        val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        compose.onNodeWithTag("assistant-panel-header").performTouchInput {
            swipe(start = center, end = center + Offset(0f, 240f * density), durationMillis = 400)
        }
        compose.onNodeWithTag("assistant-panel").assertDoesNotExist()
        compose.onNodeWithTag("assistant-composer").assertIsDisplayed()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        captureAssistantPreview(name)
    }
}

private fun captureAssistantPreview(name: String) {
    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    // Compose idle can precede the compositor frame, especially when a dialog closes.
    android.os.SystemClock.sleep(300)
    val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "assistant-preview-qa").apply { mkdirs() }
    val layout = InstrumentationRegistry.getArguments().getString("layoutLabel") ?: "portrait"
    File(directory, "$layout-$name.png").outputStream().use { output ->
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, output)
    }
}
