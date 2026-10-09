package com.veyarc.feature.editor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.veyarc.model.NormalizedCoordinate
import com.veyarc.model.Repeat
import com.veyarc.model.TapPoint
import com.veyarc.model.WorkflowIds
import org.junit.Assert.assertEquals
import org.junit.Test

class StepDragCoordinatorTest {
    @Test
    fun lazyDropKeepsTheIndicesOfOffscreenBlocksAndDropsStaleBounds() {
        val initial = WorkflowDraft.newDefault()
        val draft = initial.copy(steps = List(100) { TapPoint(WorkflowIds.newUuid(), NormalizedCoordinate(0.2, 0.3)) })
        val root = StepListPath()
        val coordinator = StepDragCoordinator()
        coordinator.updatePipeline(root, Rect(0f, 0f, 400f, 800f), draft)
        coordinator.updateStep(root, draft.steps[0].id, StepBounds(-1000f, 80), draft)
        coordinator.updateStep(root, draft.steps[50].id, StepBounds(200f, 80), draft)
        coordinator.updateStep(root, draft.steps[51].id, StepBounds(300f, 80), draft)
        coordinator.updateStep(root, draft.steps[52].id, StepBounds(400f, 80), draft)
        coordinator.start(root, draft.steps[0].id, Offset(100f, 260f), draft)
        assertEquals(50, coordinator.target?.index)
        coordinator.drag(Offset(0f, 300f), draft)
        assertEquals(52, coordinator.target?.index)
        coordinator.removeStep(root, draft.steps[50].id)
        coordinator.removeStep(root, draft.steps[51].id)
        coordinator.drag(Offset(0f, -400f), draft)
        assertEquals(51, coordinator.target?.index)
    }

    @Test
    fun pointerDeltaUpdatesTheDropPosition() {
        val initial = WorkflowDraft.newDefault()
        val draft = initial.copy(steps = initial.steps.take(1)).addStep(StepListPath(), WorkflowStepKind.WAIT, "com.veyarc.app")
        val root = StepListPath()
        val first = draft.steps.first()
        val second = draft.steps.last()
        val coordinator = StepDragCoordinator()
        coordinator.updatePipeline(root, Rect(0f, 0f, 400f, 800f), draft)
        coordinator.updateStep(root, first.id, StepBounds(100f, 80), draft)
        coordinator.updateStep(root, second.id, StepBounds(220f, 80), draft)

        coordinator.start(root, first.id, Offset(100f, 120f), draft)
        coordinator.drag(Offset(0f, 180f), draft)

        assertEquals(1, coordinator.target?.index)
    }

    @Test
    fun heldBlockDoesNotFollowItsMovingLayout() {
        val draft = WorkflowDraft.newDefault()
        val root = StepListPath()
        val step = draft.steps.first()
        val coordinator = StepDragCoordinator()
        coordinator.updatePipeline(root, Rect(0f, 0f, 400f, 800f), draft)
        coordinator.updateStep(root, step.id, StepBounds(100f, 80), draft)

        coordinator.start(root, step.id, Offset(100f, 120f), draft)
        coordinator.drag(Offset(0f, 40f), draft)
        coordinator.updateStep(root, step.id, StepBounds(130f, 80), draft)

        assertEquals(10f, coordinator.dragOffsetY)
    }

    @Test
    fun nestedBlockCanTargetTheRootPipeline() {
        val tap = TapPoint(WorkflowIds.newUuid(), NormalizedCoordinate(0.2, 0.3))
        val repeat = Repeat(WorkflowIds.newUuid(), 2, listOf(tap))
        val draft = WorkflowDraft.newDefault().copy(steps = listOf(repeat))
        val root = StepListPath()
        val body = root.child(repeat.id, NestedBranch.REPEAT_BODY)
        val coordinator = StepDragCoordinator()
        coordinator.updatePipeline(root, Rect(0f, 0f, 400f, 800f), draft)
        coordinator.updatePipeline(body, Rect(40f, 100f, 360f, 400f), draft)
        coordinator.updateStep(body, tap.id, StepBounds(140f, 80), draft)

        coordinator.start(body, tap.id, Offset(100f, 160f), draft)
        coordinator.drag(Offset(-90f, 340f), draft)

        assertEquals(root, coordinator.target?.path)
    }
}
