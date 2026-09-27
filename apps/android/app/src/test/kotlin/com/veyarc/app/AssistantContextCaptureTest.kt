package com.veyarc.app

import com.veyarc.data.storage.WorkflowAssetDescriptor
import com.veyarc.feature.assistant.AssistantPreviewContext
import com.veyarc.feature.assistant.AssistantPreviewState
import com.veyarc.feature.editor.WorkflowDraft
import com.veyarc.model.AssetId
import com.veyarc.model.AssetKind
import com.veyarc.model.DurationMillis
import com.veyarc.model.Repeat
import com.veyarc.model.StringValue
import com.veyarc.model.VariableDeclaration
import com.veyarc.model.VariableType
import com.veyarc.model.Wait
import com.veyarc.model.bundle.BundleAsset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantContextCaptureTest {
    @Test
    fun snapshotContainsUnsavedFieldsAndAssetReferencesButNoImageOrJournalData() {
        val id = AssetId("image_a")
        val asset = BundleAsset(id, AssetKind.IMAGE_TEMPLATE, "image/png", "SECRET_IMAGE_BYTES".toByteArray(), true)
        val draft = WorkflowDraft.newDefault().copy(
            name = "  Unsaved name  ",
            allowedPackagesText = "unfinished package text",
            assets = listOf(asset),
            assetDescriptors = listOf(WorkflowAssetDescriptor(id, AssetKind.IMAGE_TEMPLATE, "Image label", "image/png", 20, 30)),
            recordingJournalFileName = "SECRET_RECORDING_JOURNAL",
        )
        val snapshot = draft.captureAssistantContext().snapshot!!
        snapshot.validate()
        assertTrue(snapshot.json.contains("  Unsaved name  "))
        assertTrue(snapshot.json.contains("unfinished package text"))
        assertTrue(snapshot.json.contains("Image label"))
        assertFalse(snapshot.json.contains("SECRET"))
        assertFalse(snapshot.json.contains("recordingJournal"))
        assertFalse(snapshot.json.contains("bytes"))
        assertEquals(snapshot, draft.copy(assets = emptyList()).captureAssistantContext().snapshot)
        assertEquals(snapshot, draft.copy(collapsedStepIds = emptySet()).captureAssistantContext().snapshot)
        assertNotEquals(snapshot.snapshotId, draft.copy(steps = listOf(Wait(java.util.UUID.randomUUID().toString(), DurationMillis(2000)))).captureAssistantContext().snapshot!!.snapshotId)
        assertNull((null as WorkflowDraft?).captureAssistantContext().snapshot)
    }

    @Test
    fun sendCapturesLatestDraftAndRetryKeepsTheOriginalWithoutEditingTheWorkflow() {
        var draft = WorkflowDraft.newDefault()
        val original = draft
        val state = AssistantPreviewState()
        state.open(AssistantPreviewContext(draft.workflowId)) { draft.captureAssistantContext() }
        val preview = state.previewContext().snapshot!!
        draft = draft.copy(name = "Changed after preview")
        state.compose("Explain my edits")
        state.send()
        val captured = state.conversation!!.turns.single().baseSnapshot!!
        assertNotEquals(preview.snapshotId, captured.snapshotId)
        state.stop()
        draft = draft.copy(name = "Changed after send")
        state.retry()
        assertEquals(captured, state.conversation!!.turns.single().baseSnapshot)
        assertEquals("Changed after send", draft.name)
        assertEquals(original.steps, draft.steps)
        state.stop()
        state.compose("Keep this text")
        draft = WorkflowDraft.newDefault()
        state.send()
        assertEquals("Keep this text", state.conversation!!.composer)
        assertEquals(1, state.conversation!!.turns.size)
        assertTrue(state.inputError!!.contains("another workflow"))
    }

    @Test
    fun excessiveBytesBlocksAndDepthAreRejectedWithoutLosingThePrompt() {
        val base = WorkflowDraft.newDefault()
        val oversized = base.copy(variables = (1..20).associate { "v$it" to VariableDeclaration(VariableType.STRING, StringValue("界".repeat(2000))) })
        assertTrue(oversized.captureAssistantContext().error!!.contains("32 KiB"))
        assertTrue(base.copy(steps = (1..201).map { Wait(java.util.UUID.randomUUID().toString(), DurationMillis(1)) }).captureAssistantContext().error!!.contains("200 blocks"))
        var nested: com.veyarc.model.WorkflowStep = Wait(java.util.UUID.randomUUID().toString(), DurationMillis(1))
        repeat(13) { nested = Repeat(java.util.UUID.randomUUID().toString(), 1, listOf(nested)) }
        assertTrue(base.copy(steps = listOf(nested)).captureAssistantContext().error!!.contains("nesting"))
        val state = AssistantPreviewState()
        state.open(AssistantPreviewContext(base.workflowId)) { oversized.captureAssistantContext() }
        state.compose("Keep my prompt")
        state.send()
        assertEquals("Keep my prompt", state.conversation!!.composer)
        assertTrue(state.conversation!!.turns.isEmpty())
        assertNull(state.requestId)
    }
}
