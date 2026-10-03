package com.veyarc.app

import android.graphics.Bitmap
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.veyarc.app.ui.theme.VeyarcTheme
import com.veyarc.feature.assistant.AssistantPreviewContext
import com.veyarc.feature.assistant.AssistantPreviewHost
import com.veyarc.feature.assistant.AssistantPreviewScreen
import com.veyarc.feature.assistant.AssistantPreviewState
import com.veyarc.feature.assistant.PreviewScenario
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AssistantPreviewNavigationTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

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
        compose.onNodeWithText("Close").performClick()
    }
}

@RunWith(AndroidJUnit4::class)
class AssistantPreviewUiTest {
    @get:Rule
    val compose = createComposeRule()

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
        compose.onNodeWithText("More models").assertIsNotEnabled()
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
        assertEquals(2, state.history.size)
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

    private fun capture(name: String) {
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        // Compose idle can precede the compositor frame, especially when a dialog closes.
        android.os.SystemClock.sleep(300)
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "assistant-preview-qa").apply { mkdirs() }
        val layout = InstrumentationRegistry.getArguments().getString("layoutLabel") ?: "portrait"
        File(directory, "$layout-$name.png").outputStream().use { output ->
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, output)
        }
    }
}
