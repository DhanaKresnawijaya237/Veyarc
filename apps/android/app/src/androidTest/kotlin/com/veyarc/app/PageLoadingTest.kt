package com.veyarc.app

import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.veyarc.app.ui.theme.VeyarcTheme
import com.veyarc.data.storage.StoredRunStatus
import com.veyarc.data.storage.WorkflowRepository
import com.veyarc.feature.editor.WorkflowDraft
import com.veyarc.feature.editor.WorkflowEditorScreen
import com.veyarc.feature.editor.WorkflowEditorSession
import com.veyarc.model.DurationMillis
import com.veyarc.model.Wait
import com.veyarc.model.WorkflowIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PageLoadingTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun tabChangesKeepLoadedDataAndRefreshLaterChanges() {
        val repository = WorkflowRepository(InstrumentationRegistry.getInstrumentation().targetContext)
        val draft = WorkflowDraft.newDefault().copy(name = "Tab loading test ${System.nanoTime()}")
        repository.saveRevision(draft.buildBundle().bundle!!)
        val runId = repository.beginRun(draft.workflowId, 1)
        repository.finishRun(runId, StoredRunStatus.COMPLETED)
        val pages = MainPageState()
        val editor = WorkflowEditorSession()
        var selected by mutableStateOf(VeyarcDestination.WORKFLOWS)
        compose.setContent {
            VeyarcTheme(darkTheme = true) {
                VeyarcAppShell(selected, { selected = it }) { destination ->
                    when (destination) {
                        VeyarcDestination.HOME -> HomeDashboardScreen(repository, 0, {}, {}, pages.home)

                        VeyarcDestination.WORKFLOWS -> WorkflowManagerScreen(repository, 0, {}, {}, pageState = pages.workflows)

                        VeyarcDestination.EDITOR -> WorkflowEditorScreen(repository, editor, {}, {})

                        VeyarcDestination.RUNS -> RunManagerScreen(repository, 0, {}, pageState = pages.runs)

                        VeyarcDestination.SETTINGS -> SettingsScreen(
                            repository = repository,
                            refreshGeneration = 0,
                            themePreference = ThemePreference.DARK,
                            onThemePreferenceChange = {},
                            storageState = pages.settings,
                        )
                    }
                }
            }
        }
        try {
            compose.waitUntil(10_000) { pages.workflows.loaded.value }
            VeyarcDestination.entries.forEach { destination ->
                compose.runOnIdle { selected = destination }
                compose.waitForIdle()
                when (destination) {
                    VeyarcDestination.HOME -> compose.waitUntil(10_000) { pages.home.dashboard.value != null }
                    VeyarcDestination.RUNS -> compose.waitUntil(10_000) { pages.runs.loaded.value }
                    VeyarcDestination.SETTINGS -> compose.waitUntil(10_000) { pages.settings.workflows.value.any { it.workflowId == draft.workflowId } }
                    else -> Unit
                }
            }
            assertTrue(pages.workflows.workflows.value.any { it.summary.workflowId == draft.workflowId })
            assertTrue(pages.runs.runs.value.any { it.record.runId == runId })
            assertTrue(pages.home.workflows.value.any { it.workflowId == draft.workflowId })
            val renamed = "Updated ${draft.name}"
            repository.saveRevision(draft.copy(name = renamed).buildBundle(revision = 2).bundle!!)
            repository.renameRun(runId, "Updated run")
            compose.runOnIdle { selected = VeyarcDestination.WORKFLOWS }
            compose.waitUntil(10_000) { pages.workflows.workflows.value.any { it.summary.name == renamed } }
            compose.onNodeWithText(renamed).assertExists()
            compose.runOnIdle { selected = VeyarcDestination.RUNS }
            compose.waitUntil(10_000) { pages.runs.runs.value.any { it.record.runId == runId && it.record.name == "Updated run" && it.workflowName == renamed } }
            compose.onNodeWithText("Updated run").assertExists()
        } finally {
            repository.deleteWorkflow(draft.workflowId)
            repository.close()
        }
    }

    @Test
    fun largeWorkflowOpensWithoutComposingEveryBlock() {
        val repository = WorkflowRepository(InstrumentationRegistry.getInstrumentation().targetContext)
        val draft = WorkflowDraft.newDefault().copy(
            name = "Large editor test",
            steps = List(300) { Wait(WorkflowIds.newUuid(), DurationMillis(10)) },
        )
        repository.saveRevision(draft.buildBundle().bundle!!)
        val session = WorkflowEditorSession()
        try {
            val start = SystemClock.uptimeMillis()
            compose.setContent {
                VeyarcTheme(darkTheme = true) {
                    WorkflowEditorScreen(
                        repository = repository,
                        session = session,
                        initialStoredWorkflowId = draft.workflowId,
                        initialStoredRevision = 1,
                        onRunWorkflow = { error("This test must not run a workflow") },
                        onOpenWorkflowManager = {},
                    )
                }
            }
            compose.waitUntil(30_000) { session.draft?.workflowId == draft.workflowId }
            compose.waitForIdle()
            val composed = compose.onAllNodes(hasText("Wait", substring = true), useUnmergedTree = true).fetchSemanticsNodes().size
            Log.i("VeyarcPerformance", "300 blocks: open=${SystemClock.uptimeMillis() - start}ms, composed=$composed")
            assertEquals(300, session.draft!!.steps.size)
            assertTrue("Only nearby blocks should be composed, got $composed", composed < 30)
            // Drag well beyond the original viewport: the source must stay pinned until release.
            val source = draft.steps[20]
            val list = compose.onNodeWithTag("workflow-block-list")
            list.performScrollToKey(source.id)
            val listBounds = list.fetchSemanticsNode().boundsInRoot
            val title = compose.onNodeWithText("21. Wait", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val startPointer = title.center - listBounds.topLeft
            compose.mainClock.autoAdvance = false
            try {
                list.performTouchInput { down(startPointer) }
                compose.mainClock.advanceTimeBy(700)
                list.performTouchInput { moveTo(Offset(startPointer.x, listBounds.height - 20f)) }
                compose.mainClock.advanceTimeBy(2_000)
                list.performTouchInput { up() }
                compose.mainClock.advanceTimeBy(300)
            } finally {
                compose.mainClock.autoAdvance = true
            }
            compose.waitForIdle()
            assertTrue("Drag should cross offscreen blocks", session.draft!!.steps.indexOfFirst { it.id == source.id } > 25)
            assertEquals(300, session.draft!!.steps.map { it.id }.distinct().size)
            compose.onNodeWithTag("workflow-block-list").performScrollToKey(draft.steps.last().id)
            compose.onNodeWithText("300. Wait").assertExists()
        } finally {
            repository.deleteWorkflow(draft.workflowId)
            repository.close()
        }
    }

    @Test
    fun backgroundOpeningKeepsUnsavedChangesUntilConfirmed() {
        val repository = WorkflowRepository(InstrumentationRegistry.getInstrumentation().targetContext)
        val target = WorkflowDraft.newDefault()
        repository.saveRevision(target.buildBundle().bundle!!)
        val session = WorkflowEditorSession()
        session.create(WorkflowDraft.newDefault().viewport)
        session.update(session.draft!!.copy(name = "Unsaved work"))
        val original = session.draft
        try {
            compose.setContent {
                VeyarcTheme(darkTheme = true) {
                    WorkflowEditorScreen(repository, session, {}, {}, initialStoredWorkflowId = target.workflowId, initialStoredRevision = 1)
                }
            }
            compose.waitUntil(10_000) { compose.onAllNodes(hasText("Replace unsaved changes?")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Keep editing").performClick()
            compose.runOnIdle {
                assertEquals(original, session.draft)
                assertTrue(session.isDirty)
            }
        } finally {
            repository.deleteWorkflow(target.workflowId)
            repository.close()
        }
    }

    @Test
    fun savingBeforeOpeningAnOlderRevisionRefreshesRevisionHistory() {
        val repository = WorkflowRepository(InstrumentationRegistry.getInstrumentation().targetContext)
        val target = WorkflowDraft.newDefault()
        repository.saveRevision(target.buildBundle(revision = 1).bundle!!)
        val current = target.buildBundle(revision = 2).bundle!!
        repository.saveRevision(current)
        val session = WorkflowEditorSession()
        session.open(current)
        session.update(session.draft!!.copy(name = "Saved before replacement"))
        try {
            compose.setContent {
                VeyarcTheme(darkTheme = true) {
                    WorkflowEditorScreen(repository, session, {}, {}, initialStoredWorkflowId = target.workflowId, initialStoredRevision = 1)
                }
            }
            compose.waitUntil(10_000) { compose.onAllNodes(hasText("Replace unsaved changes?")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Save", substring = false).performClick()
            compose.waitUntil(10_000) { session.draft?.lastSavedRevision == 1 && session.revisionSummaries.any { it.revision == 3 } }
            compose.runOnIdle {
                assertEquals(3, session.draft!!.latestKnownRevision)
                assertEquals(4, session.draft!!.nextRevision)
                assertTrue(!session.isDirty)
            }
            assertEquals("Saved before replacement", repository.loadRevisionDocument(target.workflowId, 3).document.name)
        } finally {
            repository.deleteWorkflow(target.workflowId)
            repository.close()
        }
    }
}
