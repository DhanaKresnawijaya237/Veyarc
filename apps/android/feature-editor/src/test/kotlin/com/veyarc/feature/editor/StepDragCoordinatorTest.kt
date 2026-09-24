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
    fun pointerDeltaUpdatesTheDropPosition() {
        val draft = WorkflowDraft.newDefault().addStep(StepListPath(), WorkflowStepKind.WAIT, "com.veyarc.app")
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
