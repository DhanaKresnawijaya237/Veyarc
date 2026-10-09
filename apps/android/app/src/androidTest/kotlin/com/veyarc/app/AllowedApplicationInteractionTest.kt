package com.veyarc.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import com.veyarc.app.ui.theme.VeyarcTheme
import com.veyarc.data.storage.WorkflowRepository
import com.veyarc.feature.editor.WorkflowDraft
import com.veyarc.feature.editor.WorkflowEditorScreen
import com.veyarc.feature.editor.WorkflowEditorSession
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AllowedApplicationInteractionTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun tappingTheIconOpensTheAppAndKeepsTheDraft() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val repository = WorkflowRepository(instrumentation.targetContext)
        val session = WorkflowEditorSession().apply { open(WorkflowDraft.newDefault().copy(allowedPackagesText = "com.android.settings").buildBundle().bundle!!) }
        val before = session.draft
        fun focusedWindow(): String = instrumentation.uiAutomation.executeShellCommand("dumpsys window").use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText().lineSequence().firstOrNull { line -> "mCurrentFocus=" in line }.orEmpty() }
        }
        try {
            compose.setContent { VeyarcTheme(darkTheme = true) { WorkflowEditorScreen(repository, session, {}, {}) } }
            compose.onNodeWithTag("allowed-app-com.android.settings").performTouchInput { click(Offset(center.x, height * 0.3f)) }
            compose.waitUntil(10_000) { "com.android.settings" in focusedWindow() }
            instrumentation.uiAutomation.executeShellCommand("input keyevent 4").close()
            compose.waitUntil(10_000) { "com.veyarc.app" in focusedWindow() }
            assertEquals(before, session.draft)
        } finally {
            repository.close()
        }
    }

    @Test
    fun holdReordersCancelRestoresAndDropOnDeleteRemovesOnlyThatApp() {
        val repository = WorkflowRepository(InstrumentationRegistry.getInstrumentation().targetContext)
        val packages = listOf("com.android.settings", "com.android.chrome", "com.veyarc.app")
        val session = WorkflowEditorSession().apply { open(WorkflowDraft.newDefault().copy(allowedPackagesText = packages.joinToString(", ")).buildBundle().bundle!!) }
        try {
            compose.setContent { VeyarcTheme(darkTheme = true) { WorkflowEditorScreen(repository, session, {}, {}) } }
            val row = compose.onNodeWithTag("allowed-app-row")
            fun center(packageName: String) = compose.onNodeWithTag("allowed-app-$packageName").fetchSemanticsNode().boundsInRoot.center - row.fetchSemanticsNode().boundsInRoot.topLeft
            fun drag(from: Offset, to: Offset, cancelDrag: Boolean = false, stationaryPackage: String? = null) {
                val stationaryBefore = stationaryPackage?.let(::center)
                compose.mainClock.autoAdvance = false
                try {
                    row.performTouchInput { down(from) }
                    compose.mainClock.advanceTimeBy(550)
                    if (stationaryBefore != null) {
                        val stationaryAfter = center(requireNotNull(stationaryPackage))
                        assertEquals("Other apps must not jump when a new hold starts", stationaryBefore.x, stationaryAfter.x, 2f)
                        assertEquals(stationaryBefore.y, stationaryAfter.y, 2f)
                    }
                    compose.mainClock.advanceTimeBy(150)
                    row.performTouchInput { moveTo(to) }
                    compose.mainClock.advanceTimeBy(200)
                    row.performTouchInput { if (cancelDrag) cancel() else up() }
                    compose.mainClock.advanceTimeBy(200)
                } finally {
                    compose.mainClock.autoAdvance = true
                }
                compose.waitForIdle()
            }
            drag(center(packages[0]), center(packages[2]))
            assertEquals(listOf(packages[1], packages[2], packages[0]), session.draft!!.buildBundle().bundle!!.document.allowedPackages.map { it.value })
            drag(center(packages[0]), center(packages[1]), cancelDrag = true, stationaryPackage = packages[1])
            assertEquals(listOf(packages[1], packages[2], packages[0]), session.draft!!.buildBundle().bundle!!.document.allowedPackages.map { it.value })
            val bounds = row.fetchSemanticsNode().boundsInRoot
            drag(center(packages[0]), Offset(bounds.width - 30f, bounds.height / 2))
            assertEquals(listOf(packages[1], packages[2]), session.draft!!.buildBundle().bundle!!.document.allowedPackages.map { it.value })
            compose.onNodeWithTag("allowed-app-delete").assertDoesNotExist()
        } finally {
            repository.close()
        }
    }

    @Test
    fun dragFollowsEveryMoveAndScrollsBesideDeleteAfterSourceLeavesViewport() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val repository = WorkflowRepository(instrumentation.targetContext)
        val packages = listOf("com.android.settings", "com.android.chrome", "com.veyarc.app") + List(9) { "com.example.allowedapp$it" }
        val session = WorkflowEditorSession().apply { open(WorkflowDraft.newDefault().copy(allowedPackagesText = packages.joinToString(", ")).buildBundle().bundle!!) }
        try {
            compose.setContent { VeyarcTheme(darkTheme = true) { WorkflowEditorScreen(repository, session, {}, {}) } }
            val row = compose.onNodeWithTag("allowed-app-row")
            val bounds = row.fetchSemanticsNode().boundsInRoot
            val original = compose.onNodeWithTag("allowed-app-${packages.first()}").fetchSemanticsNode().boundsInRoot.center
            val from = original - bounds.topLeft
            compose.mainClock.autoAdvance = false
            try {
                row.performTouchInput { down(from) }
                compose.mainClock.advanceTimeBy(700)
                val held = compose.onNodeWithTag("allowed-app-dragging").fetchSemanticsNode().boundsInRoot.center
                assertEquals(original.x, held.x, 2f)
                assertEquals(original.y - 4 * instrumentation.targetContext.resources.displayMetrics.density, held.y, 2f)
                // Several small moves expose transform feedback that a single large move misses.
                repeat(12) { index ->
                    val position = from + Offset((bounds.width - 90f - from.x) * (index + 1) / 12, -12f * (index + 1) / 12)
                    row.performTouchInput { moveTo(position) }
                    compose.mainClock.advanceTimeBy(32)
                    val actual = compose.onNodeWithTag("allowed-app-dragging").fetchSemanticsNode().boundsInRoot.center
                    val expected = held + position - from
                    assertEquals("horizontal finger tracking at move $index", expected.x, actual.x, 2f)
                    assertEquals("vertical finger tracking at move $index", expected.y, actual.y, 2f)
                }
                // Beside the ghost circle is a scroll lane, not a delete target.
                val deleteBounds = compose.onNodeWithTag("allowed-app-delete").fetchSemanticsNode().boundsInRoot
                row.performTouchInput { moveTo(Offset(deleteBounds.left - bounds.left - 8f, deleteBounds.center.y - bounds.top)) }
                compose.mainClock.advanceTimeBy(32)
                val heldBesideDelete = compose.onNodeWithTag("allowed-app-dragging").fetchSemanticsNode().boundsInRoot.center
                compose.mainClock.advanceTimeBy(4_000)
                val afterScrolling = compose.onNodeWithTag("allowed-app-dragging").fetchSemanticsNode().boundsInRoot.center
                assertEquals(heldBesideDelete.x, afterScrolling.x, 2f)
                assertEquals(heldBesideDelete.y, afterScrolling.y, 2f)
                compose.onNodeWithTag("allowed-app-${packages.last()}").assertIsDisplayed()
                compose.onNodeWithTag("allowed-app-${packages.first()}").assertDoesNotExist()
                compose.onNodeWithTag("allowed-app-dragging").assertIsDisplayed()
                row.performTouchInput { up() }
                compose.mainClock.advanceTimeBy(200)
                assertEquals(packages.drop(1) + packages.first(), session.draft!!.buildBundle().bundle!!.document.allowedPackages.map { it.value })
                compose.onNodeWithTag("allowed-app-delete").assertDoesNotExist()

                val beforeCancel = session.draft
                val backFrom = compose.onNodeWithTag("allowed-app-${packages.first()}").fetchSemanticsNode().boundsInRoot.center - bounds.topLeft
                row.performTouchInput { down(backFrom) }
                compose.mainClock.advanceTimeBy(700)
                row.performTouchInput { moveTo(Offset(10f, 5f)) }
                compose.mainClock.advanceTimeBy(3_000)
                compose.onNodeWithTag("allowed-app-${packages[1]}").assertIsDisplayed()
                compose.onNodeWithTag("allowed-app-${packages.first()}").assertDoesNotExist()
                row.performTouchInput { cancel() }
                compose.mainClock.advanceTimeBy(200)
                assertEquals(beforeCancel, session.draft)
                compose.onNodeWithTag("allowed-app-dragging").assertDoesNotExist()
                compose.onNodeWithTag("allowed-app-delete").assertDoesNotExist()
            } finally {
                compose.mainClock.autoAdvance = true
            }
        } finally {
            repository.close()
        }
    }
}
