package com.veyarc.app

import androidx.compose.ui.geometry.Offset
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
            fun drag(from: Offset, to: Offset, cancelDrag: Boolean = false) {
                compose.mainClock.autoAdvance = false
                try {
                    row.performTouchInput { down(from) }
                    compose.mainClock.advanceTimeBy(700)
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
            drag(center(packages[0]), center(packages[1]), cancelDrag = true)
            assertEquals(listOf(packages[1], packages[2], packages[0]), session.draft!!.buildBundle().bundle!!.document.allowedPackages.map { it.value })
            val bounds = row.fetchSemanticsNode().boundsInRoot
            drag(center(packages[0]), Offset(bounds.width - 30f, bounds.height / 2))
            assertEquals(listOf(packages[1], packages[2]), session.draft!!.buildBundle().bundle!!.document.allowedPackages.map { it.value })
            compose.onNodeWithTag("allowed-app-delete").assertDoesNotExist()
        } finally {
            repository.close()
        }
    }
}
