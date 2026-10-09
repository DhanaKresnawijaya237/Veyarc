package com.veyarc.app

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.FrameMetrics
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.veyarc.app.ui.theme.VeyarcTheme
import com.veyarc.data.storage.AndroidKeystoreKeySource
import com.veyarc.data.storage.ChatRepository
import com.veyarc.data.storage.DecodedTemplate
import com.veyarc.data.storage.EncryptedTemplateAssetCodec
import com.veyarc.data.storage.StoredChat
import com.veyarc.data.storage.StoredRunStatus
import com.veyarc.data.storage.TEMPLATE_ASSET_KEY_ALIAS
import com.veyarc.data.storage.WorkflowAssetDescriptor
import com.veyarc.data.storage.WorkflowRepository
import com.veyarc.feature.assistant.AssistantAttachmentImporter
import com.veyarc.feature.assistant.AssistantOutcome
import com.veyarc.feature.assistant.AssistantPreviewContext
import com.veyarc.feature.assistant.AssistantPreviewScreen
import com.veyarc.feature.assistant.AssistantPreviewState
import com.veyarc.feature.assistant.PreviewConversation
import com.veyarc.feature.assistant.PreviewReplyStatus
import com.veyarc.feature.assistant.PreviewScenario
import com.veyarc.feature.assistant.PreviewTurn
import com.veyarc.feature.editor.WorkflowDraft
import com.veyarc.feature.editor.WorkflowEditorScreen
import com.veyarc.feature.editor.WorkflowEditorSession
import com.veyarc.model.AssetId
import com.veyarc.model.AssetKind
import com.veyarc.model.DurationMillis
import com.veyarc.model.Wait
import com.veyarc.model.WorkflowIds
import com.veyarc.model.bundle.BundleAsset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Repeatable local emulator comparisons; timings are observations, not device-independent gates. */
@RunWith(AndroidJUnit4::class)
class PerformanceScenarioTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun navigationWithManyWorkflowsAndRuns() {
        val repository = WorkflowRepository(context)
        val drafts = List(45) { index -> largeDraft(15).copy(name = "Performance workflow $index") }
        val pages = MainPageState()
        val editor = WorkflowEditorSession()
        var selected by mutableStateOf(VeyarcDestination.WORKFLOWS)
        try {
            drafts.forEach { draft ->
                repository.saveRevision(draft.buildBundle().bundle!!)
                repeat(5) { repository.finishRun(repository.beginRun(draft.workflowId, 1), StoredRunStatus.COMPLETED) }
            }
            compose.setContent {
                VeyarcTheme(darkTheme = true) {
                    VeyarcAppShell(selected, { selected = it }) { destination ->
                        when (destination) {
                            VeyarcDestination.HOME -> HomeDashboardScreen(repository, 0, {}, {}, pages.home)
                            VeyarcDestination.WORKFLOWS -> WorkflowManagerScreen(repository, 0, {}, {}, pageState = pages.workflows)
                            VeyarcDestination.EDITOR -> WorkflowEditorScreen(repository, editor, {}, {})
                            VeyarcDestination.RUNS -> RunManagerScreen(repository, 0, {}, pageState = pages.runs)
                            VeyarcDestination.SETTINGS -> SettingsScreen(repository = repository, refreshGeneration = 0, themePreference = ThemePreference.DARK, onThemePreferenceChange = {}, storageState = pages.settings)
                        }
                    }
                }
            }
            compose.waitUntil(30_000) { pages.workflows.loaded.value }
            val frames = captureFrames()
            val samples = VeyarcDestination.entries.associateWith { mutableListOf<Long>() }
            repeat(6) { round ->
                VeyarcDestination.entries.forEach { destination ->
                    val elapsed = timed {
                        compose.runOnIdle { selected = destination }
                        compose.waitForIdle()
                        compose.waitUntil(30_000) {
                            when (destination) {
                                VeyarcDestination.HOME -> pages.home.dashboard.value != null
                                VeyarcDestination.RUNS -> pages.runs.loaded.value
                                VeyarcDestination.SETTINGS -> pages.settings.workflows.value.size >= drafts.size
                                else -> true
                            }
                        }
                    }
                    if (round > 0) samples.getValue(destination) += elapsed
                }
            }
            samples.forEach { (destination, values) -> report("tab_${destination.name.lowercase()}", values) }
            reportFrames("navigation_frames", frames)
            assertTrue(pages.runs.runs.value.size >= 225)
        } finally {
            drafts.forEach { repository.deleteWorkflow(it.workflowId) }
            repository.close()
        }
    }

    @Test
    fun largeEditorWithManyTemplates() {
        val repository = WorkflowRepository(context)
        val draft = performanceEditorDraft(1_500, 200)
        val session = WorkflowEditorSession()
        var generation by mutableIntStateOf(0)
        try {
            repository.saveRevision(draft.buildBundle().bundle!!)
            compose.setContent {
                VeyarcTheme(darkTheme = true) {
                    key(generation) {
                        WorkflowEditorScreen(repository, session, {}, {}, initialStoredWorkflowId = draft.workflowId, initialStoredRevision = 1)
                    }
                }
            }
            compose.waitUntil(60_000) { session.draft?.workflowId == draft.workflowId }
            compose.waitForIdle()
            val frames = captureFrames()
            val openSamples = List(5) {
                timed {
                    compose.runOnIdle {
                        session.close()
                        generation++
                    }
                    compose.waitUntil(60_000) { session.draft?.workflowId == draft.workflowId }
                    compose.waitForIdle()
                }
            }
            report("editor_open_1500_blocks_200_templates", openSamples)
            val composed = compose.onAllNodes(hasText("Perf template", substring = true), useUnmergedTree = true).fetchSemanticsNodes().size
            Log.i(TAG, "PERF composed_templates=$composed")
            report(
                "editor_title_1500_blocks_200_templates",
                List(10) { index ->
                    timed {
                        compose.onNodeWithTag("workflow-title").performTextReplacement("Performance rename $index")
                        compose.waitForIdle()
                    }
                },
            )
            report(
                "editor_scroll_1500_blocks",
                List(5) { index ->
                    timed {
                        compose.onNodeWithTag("workflow-block-list").performScrollToKey(draft.steps[if (index % 2 == 0) 1499 else 0].id)
                        compose.waitForIdle()
                    }
                },
            )
            reportFrames("large_editor_frames", frames)
            assertEquals(1_500, session.draft!!.steps.size)
            assertEquals(200, session.draft!!.assetDescriptors.size)
            compose.runOnIdle {
                session.undo()
                assertEquals("Performance rename 8", session.draft!!.name)
            }
        } finally {
            repository.deleteWorkflow(draft.workflowId)
            repository.close()
        }
    }

    @Test
    fun reopeningChatWithLongHistory() {
        val repository = ChatRepository(context)
        val rows = List(100) { index ->
            val chat = PreviewConversation(
                id = UUID.randomUUID().toString(),
                workflowId = null,
                title = "Performance chat $index",
                createdAtMs = 1,
                updatedAtMs = index + 1L,
                turns = List(30) { turn ->
                    PreviewTurn(
                        UUID.randomUUID().toString(),
                        "Explain $turn",
                        PreviewScenario.EXPLANATION,
                        reply = "## Workflow\n\n${"A short explanation of the workflow. ".repeat(30)}",
                        status = PreviewReplyStatus.COMPLETE,
                        outcome = AssistantOutcome.EXPLANATION,
                    )
                },
            )
            StoredChat(chat.id, null, chat.createdAtMs, chat.updatedAtMs, chat.turns.last().requestId, "COMPLETE", Json.encodeToString(PreviewConversation.serializer(), chat).toByteArray())
        }
        val owner = SupervisorJob()
        lateinit var state: AssistantPreviewState
        var initialized = false
        try {
            rows.forEach { repository.save(it, create = true) }
            compose.runOnIdle {
                state = AssistantPreviewState(repository, CoroutineScope(owner + Dispatchers.Main))
                initialized = true
            }
            compose.waitUntil(30_000) { state.canChange }
            compose.runOnIdle { state.open(AssistantPreviewContext()) }
            compose.setContent { VeyarcTheme(darkTheme = true) { if (state.isOpen) AssistantPreviewScreen(state) } }
            compose.waitUntil(30_000) { state.canChange }
            compose.waitForIdle()
            val frames = captureFrames()
            report(
                "chat_reopen_100_chats_3000_turns",
                List(5) {
                    compose.runOnIdle { state.close() }
                    compose.waitUntil(30_000) { state.pendingWrites == 0 }
                    timed {
                        compose.runOnIdle { state.open(AssistantPreviewContext()) }
                        compose.waitUntil(30_000) { state.canChange }
                        compose.waitForIdle()
                    }
                },
            )
            assertEquals(30, state.conversation!!.turns.size)
            reportFrames("chat_reopen_frames", frames)
        } finally {
            if (initialized) compose.runOnIdle { state.dispose() }
            owner.cancel()
            runBlocking { owner.join() }
            repository.delete(rows.map { it.id }.toSet())
            repository.close()
        }
    }

    @Test
    fun validationChatPersistenceAndImagePreviews() {
        listOf(300, 1_500, 5_000).forEach { count ->
            val draft = largeDraft(count)
            val session = WorkflowEditorSession()
            session.open(draft.buildBundle().bundle!!)
            session.validationFor(session.draft!!)
            repeat(5) { session.validationFor(session.draft!!.copy(name = "Warm $it")) }
            report("validation_rename_$count", List(40) { index -> timed { assertTrue(session.validationFor(session.draft!!.copy(name = "Rename $index")).issues.isEmpty()) } })
        }
        val chats = ChatRepository(context)
        val rows = List(300) { StoredChat(UUID.randomUUID().toString(), null, 1, 1, null, "EMPTY", ByteArray(8 * 1024) { 7 }) }
        try {
            rows.forEach { chats.save(it, create = true) }
            repeat(5) { chats.save(rows.last(), create = false) }
            report("chat_save_with_300_chats", List(25) { timed { chats.save(rows.last(), create = false) } })
            report("chat_load_300_chats", List(5) { timed { assertTrue(chats.load().size >= 300) } })
        } finally {
            chats.delete(rows.map { it.id }.toSet())
            chats.close()
        }
        val importer = AssistantAttachmentImporter(context)
        val bitmap = Bitmap.createBitmap(1_200, 800, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(1_200 * 800) { index -> 0xFF000000.toInt() or ((index * 31) and 0xFFFFFF) }
        bitmap.setPixels(pixels, 0, 1_200, 0, 0, 1_200, 800)
        val images = mutableListOf<com.veyarc.feature.assistant.AssistantAttachment>()
        try {
            repeat(12) { images += importer.saveImage("Performance $it.png", bitmap) }
            images.forEach { assertTrue(importer.preview(it).width <= 320) }
            report("image_preview_warm_12_images", List(60) { index -> timed { assertTrue(importer.preview(images[index % images.size]).width <= 320) } })
        } finally {
            bitmap.recycle()
            // Only these synthetic blobs are removed; no shared attachment-store prune.
            images.forEach { java.io.File(context.filesDir, "assistant-attachments/attachment-${it.id}.wfenc").delete() }
        }
    }

    private fun largeDraft(count: Int) = WorkflowDraft.newDefault().copy(name = "Performance editor", steps = List(count) { Wait(WorkflowIds.newUuid(), DurationMillis(10)) })

    private fun timed(block: () -> Unit): Long {
        val start = SystemClock.elapsedRealtimeNanos()
        block()
        return SystemClock.elapsedRealtimeNanos() - start
    }

    private fun report(name: String, samples: List<Long>) {
        val ordered = samples.sorted()
        Log.i(TAG, "PERF $name n=${samples.size} median_ms=${ordered[ordered.size / 2] / 1e6} p95_ms=${ordered[kotlin.math.ceil(ordered.size * .95).toInt() - 1] / 1e6} samples_ns=${samples.joinToString(",")}")
    }

    private fun captureFrames(): MutableList<Long> {
        val frames = java.util.Collections.synchronizedList(mutableListOf<Long>())
        compose.runOnIdle {
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).first().window
                .addOnFrameMetricsAvailableListener({ _, metrics, _ -> frames += metrics.getMetric(FrameMetrics.TOTAL_DURATION) }, Handler(Looper.getMainLooper()))
        }
        return frames
    }

    private fun reportFrames(name: String, frames: MutableList<Long>) {
        val samples = synchronized(frames) { frames.toList() }
        assertTrue("Frame metrics should be available", samples.isNotEmpty())
        report(name, samples)
        Log.i(TAG, "PERF $name over_16ms=${samples.count { it > 16_666_667 }} over_32ms=${samples.count { it > 32_000_000 }}")
    }

    private companion object {
        const val TAG = "VeyarcPerformance"
    }
}

internal fun performanceEditorDraft(blockCount: Int, templateCount: Int): WorkflowDraft {
    val codec = EncryptedTemplateAssetCodec(AndroidKeystoreKeySource(TEMPLATE_ASSET_KEY_ALIAS))
    val decoded = DecodedTemplate(2, 2, ByteArray(16) { it.toByte() })
    val assets = try {
        List(templateCount) { index ->
            val id = AssetId("perf-image-$index")
            BundleAsset(id, AssetKind.IMAGE_TEMPLATE, "image/png", codec.encrypt(id, decoded), true, 2, 2)
        }
    } finally {
        decoded.clear()
    }
    return WorkflowDraft.newDefault().copy(
        name = "Performance editor",
        steps = List(blockCount) { Wait(WorkflowIds.newUuid(), DurationMillis(10)) },
        assets = assets,
        assetDescriptors = assets.map { WorkflowAssetDescriptor(it.assetId, it.kind, null, it.mimeType, 2, 2, 1, it.byteLength.toLong()) },
        assetNames = assets.associate { it.assetId.value to "Perf template ${it.assetId.value}" },
    )
}
