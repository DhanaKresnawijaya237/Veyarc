package com.veyarc.app

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.veyarc.app.ui.theme.VeyarcTheme
import com.veyarc.data.storage.StoredRunStatus
import com.veyarc.data.storage.WorkflowRepository
import com.veyarc.feature.editor.WorkflowDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManagerInteractionTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun workflowHoldOpensActionsWithoutRunningAndSelectionSlidesIn() {
        val repository = WorkflowRepository(InstrumentationRegistry.getInstrumentation().targetContext)
        val draft = WorkflowDraft.newDefault().copy(name = "Workflow hold test ${System.nanoTime()}")
        var plays = 0
        var edits = 0
        repository.saveRevision(draft.buildBundle().bundle!!)
        try {
            compose.setContent { VeyarcTheme(darkTheme = true) { WorkflowManagerScreen(repository, 0, { edits++ }, { plays++ }) } }
            compose.waitUntil(10_000) { compose.onAllNodes(hasText(draft.name)).fetchSemanticsNodes().isNotEmpty() }
            val card = compose.onNodeWithTag("workflow-card-${draft.workflowId}")
            card.performTouchInput { longClick() }
            compose.onNodeWithText("Run", substring = false).assertIsEnabled()
            compose.onNodeWithText("Revision history").assertExists()
            compose.onNodeWithText("Delete", substring = false).assertExists()
            compose.onNodeWithText("1 selected").assertDoesNotExist()
            compose.onNodeWithContentDescription("More workflow actions").assertDoesNotExist()
            compose.onNodeWithContentDescription("Play ${draft.name}").assertDoesNotExist()
            compose.runOnIdle {
                assertEquals(0, plays)
                assertEquals(0, edits)
            }
            compose.onNodeWithText("Run", substring = false).performClick()
            compose.runOnIdle { assertEquals(1, plays) }
            card.performTouchInput { longClick() }
            compose.onNodeWithText("Delete", substring = false).performClick()
            compose.onNodeWithText("Cancel", substring = false).performClick()
            assertTrue(repository.listWorkflowSummaries().any { it.workflowId == draft.workflowId })
            checkSelectionAnimation(draft.name)
            compose.onNode(hasText(draft.name) and hasClickAction()).performClick()
            compose.onNodeWithText("1 selected").assertExists()
            compose.runOnIdle {
                assertEquals(1, plays)
                assertEquals(0, edits)
            }
        } finally {
            repository.deleteWorkflow(draft.workflowId)
            repository.close()
        }
    }

    @Test
    fun runHoldOffersDeleteInBothViewsAndProtectsActiveRuns() {
        val repository = WorkflowRepository(InstrumentationRegistry.getInstrumentation().targetContext)
        val draft = WorkflowDraft.newDefault().copy(name = "Run hold test ${System.nanoTime()}")
        repository.saveRevision(draft.buildBundle().bundle!!)
        val now = System.currentTimeMillis()
        val finished = repository.beginRun(draft.workflowId, 1, startedAtMs = now)
        repository.finishRun(finished, StoredRunStatus.COMPLETED, finishedAtMs = now + 1)
        repository.renameRun(finished, "Finished hold test")
        val active = repository.beginRun(draft.workflowId, 1, startedAtMs = now + 10)
        try {
            compose.setContent { VeyarcTheme(darkTheme = true) { RunManagerScreen(repository, 0, {}) } }
            compose.waitUntil(10_000) { compose.onAllNodes(hasText("Finished hold test")).fetchSemanticsNodes().isNotEmpty() }
            listOf("All runs", "By workflow").forEach { view ->
                compose.onNodeWithText(view, substring = false).performClick()
                compose.onNodeWithTag("run-card-$finished").performTouchInput { longClick() }
                compose.onNodeWithText("Delete", substring = false).assertIsEnabled().performClick()
                compose.onNodeWithText("Cancel", substring = false).performClick()
                assertTrue(repository.listRunSummaries().any { it.runId == finished })
                compose.onNodeWithTag("run-card-$active").performTouchInput { longClick() }
                compose.onNodeWithText("Delete", substring = false).assertIsNotEnabled()
                Espresso.pressBack()
                compose.onNodeWithText("1 selected").assertDoesNotExist()
            }
            checkSelectionAnimation("Finished hold test")
            compose.onNodeWithTag("run-card-$finished").performTouchInput { click() }
            compose.onNodeWithText("1 selected").assertExists()
        } finally {
            repository.finishRun(active, StoredRunStatus.STOPPED)
            repository.deleteWorkflow(draft.workflowId)
            repository.close()
        }
    }

    private fun checkSelectionAnimation(title: String) {
        val before = compose.onNodeWithText(title, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.left
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithContentDescription("Select items").performClick()
            compose.mainClock.advanceTimeBy(100)
            val during = compose.onNodeWithText(title, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.left
            compose.mainClock.advanceTimeBy(600)
            val after = compose.onNodeWithText(title, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.left
            assertTrue("Checkboxes must slide in: $before, $during, $after", during > before && during < after)
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }
}
