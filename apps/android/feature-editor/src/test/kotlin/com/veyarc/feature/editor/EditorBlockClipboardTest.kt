package com.veyarc.feature.editor

import com.veyarc.model.AssetId
import com.veyarc.model.DisplayOrientation
import com.veyarc.model.DurationMillis
import com.veyarc.model.ImagePresentCondition
import com.veyarc.model.Jump
import com.veyarc.model.NormalizedRect
import com.veyarc.model.Repeat
import com.veyarc.model.StageBoundary
import com.veyarc.model.StepOutcome
import com.veyarc.model.StepResultCondition
import com.veyarc.model.WaitUntil
import com.veyarc.model.WorkflowIds
import com.veyarc.model.WorkflowJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class EditorBlockClipboardTest {
    @Test
    fun remapsNestedIdsReferencesAndImagesWithoutChangingTheSource() {
        val boundary = StageBoundary(WorkflowIds.newUuid())
        val check = WaitUntil(
            WorkflowIds.newUuid(),
            ImagePresentCondition(AssetId("source-image"), NormalizedRect(0.0, 0.0, 1.0, 1.0), 0.9),
            DurationMillis(1_000),
            DurationMillis(100),
        )
        val result = WaitUntil(
            WorkflowIds.newUuid(),
            StepResultCondition(check.id, StepOutcome.COMPLETED),
            DurationMillis(1_000),
            DurationMillis(100),
        )
        val repeat = Repeat(WorkflowIds.newUuid(), 2, listOf(check, result))
        val jump = Jump(WorkflowIds.newUuid(), boundary.id, 2)
        val source = listOf(boundary, repeat, jump)
        val serialized = WorkflowJson.encodeCanonical(
            BlockClipboardEntry.serializer(),
            BlockClipboardEntry(orientation = DisplayOrientation.PORTRAIT, steps = source, images = emptyList()),
        )
        val restored = WorkflowJson.decodeStrict(BlockClipboardEntry.serializer(), serialized)

        val copied = remapClipboardSteps(restored.steps, mapOf("source-image" to AssetId("destination-image")))
        val copiedBoundary = copied[0] as StageBoundary
        val copiedChildren = (copied[1] as Repeat).bodySteps
        assertNotEquals(boundary.id, copiedBoundary.id)
        assertEquals(copiedBoundary.id, (copied[2] as Jump).targetStepId)
        assertEquals("destination-image", ((copiedChildren[0] as WaitUntil).condition as ImagePresentCondition).assetId!!.value)
        assertEquals(copiedChildren[0].id, ((copiedChildren[1] as WaitUntil).condition as StepResultCondition).stepId)
        assertEquals("source-image", check.condition.let { it as ImagePresentCondition }.assetId!!.value)
    }

    @Test
    fun insertingABatchIsOneUndoableEditorChange() {
        val session = WorkflowEditorSession()
        session.create(WorkflowDraft.newDefault().viewport)
        val original = session.draft!!
        val step = StageBoundary(WorkflowIds.newUuid())

        session.update(original.insertSteps(StepListPath(), 0, listOf(step)))
        assertEquals(step.id, session.draft!!.steps.first().id)
        session.undo()
        assertEquals(original.steps, session.draft!!.steps)
    }
}
