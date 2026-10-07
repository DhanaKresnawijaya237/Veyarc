package com.veyarc.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.veyarc.app.ui.theme.VeyarcTheme
import com.veyarc.data.storage.StoredRunEvent
import com.veyarc.data.storage.StoredRunRecord
import com.veyarc.data.storage.StoredRunStatus
import com.veyarc.feature.editor.StoredRunDetailScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RunDetailsUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun failedRunShowsReadableSummaryAndKeepsOriginalMessages() {
        val step = UUID.randomUUID().toString()
        val record = record(StoredRunStatus.FAILED).copy(interruptionReason = "Tap $step could not complete", eventHistory = listOf(StoredRunEvent(99_000, "Step $step started"), StoredRunEvent(99_200, "Tap failed")))
        var opened = false
        var back = false
        compose.setContent { VeyarcTheme(darkTheme = true) { Surface(color = MaterialTheme.colorScheme.background) { StoredRunDetailScreen(record, { back = true }, { opened = true }, workflowName = "Login workflow") } } }
        compose.onNodeWithText("Login workflow").assertIsDisplayed()
        compose.onNodeWithText("Failed").assertIsDisplayed()
        capture("failed-run")
        compose.onNodeWithText("Steps finished").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Tap could not complete").performScrollTo().assertIsDisplayed()
        assertEquals(0, compose.onAllNodes(hasText(record.runId, substring = true)).fetchSemanticsNodes().size)
        compose.onNodeWithText("Open revision 3").performScrollTo().performClick()
        assertEquals(true, opened)
        compose.onNodeWithContentDescription("Close run details").performScrollTo().performClick()
        assertEquals(true, back)
        assertEquals("Step $step started", record.eventHistory.first().message)
    }

    @Test fun longActivityRemainsLazyAndTheFinalEventIsReachable() {
        val record = record(StoredRunStatus.COMPLETED).copy(eventHistory = List(500) { StoredRunEvent(10_000 + it * 100L, "Tap completed $it") })
        compose.setContent { VeyarcTheme(darkTheme = true) { Surface(color = MaterialTheme.colorScheme.background) { StoredRunDetailScreen(record, {}, workflowName = "Long workflow") } } }
        val count = compose.onAllNodes(hasText("Tap completed", substring = true)).fetchSemanticsNodes().size
        org.junit.Assert.assertTrue("Only nearby events should be composed, got $count", count < 20)
        capture("completed-run")
        compose.onNodeWithTag("run-details").performScrollToIndex(502)
        compose.onNodeWithText("Tap completed 499").assertIsDisplayed()
    }

    private fun record(status: StoredRunStatus) = StoredRunRecord(UUID.randomUUID().toString(), UUID.randomUUID().toString(), 3, status, 1_790_000_000_000L, 1_790_000_005_000L, null, 2, 5, emptyList(), true, null)

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.cacheDir, "run-details-qa").apply { mkdirs() }
        val layout = InstrumentationRegistry.getArguments().getString("layoutLabel") ?: "portrait"
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        File(directory, "$layout-$name.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
