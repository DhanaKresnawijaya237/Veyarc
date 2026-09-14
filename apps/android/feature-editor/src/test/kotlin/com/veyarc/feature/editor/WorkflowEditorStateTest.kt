package com.veyarc.feature.editor

import com.veyarc.model.DisplayOrientation
import com.veyarc.model.DurationMillis
import com.veyarc.model.NormalizedCoordinate
import com.veyarc.model.PixelColorCondition
import com.veyarc.model.Repeat
import com.veyarc.model.SwipePath
import com.veyarc.model.TapPoint
import com.veyarc.model.WaitUntil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowEditorStateTest {
    @Test
    fun historicalRevisionAlwaysBuildsTheNextKnownRevision() {
        val current = WorkflowDraft.newDefault().buildBundle(revision = 4).bundle!!
        val session = WorkflowEditorSession()

        session.open(current, latestKnownRevision = 9)

        assertEquals(10, session.draft!!.nextRevision)
        assertEquals(10, session.draft!!.buildBundle().bundle!!.document.revision)
        assertTrue(session.requiresRevisionSave)
    }

    @Test
    fun editorSessionTracksDirtyStateAndUndoWithoutTreatingCollapseAsContent() {
        val original = WorkflowDraft.newDefault()
        val bundle = original.buildBundle().bundle ?: error("Expected a valid draft")
        val session = WorkflowEditorSession()
        session.open(bundle)

        assertFalse(session.isDirty)
        session.update(session.draft!!.toggleCollapsed(session.draft!!.steps.first().id))
        assertFalse(session.isDirty)

        session.update(session.draft!!.copy(name = "Changed"))
        assertTrue(session.isDirty)
        assertTrue(session.canUndo)
        session.undo()
        assertFalse(session.isDirty)
        session.redo()
        assertTrue(session.isDirty)

        session.markSaved(session.draft!!)
        assertFalse(session.isDirty)
        assertFalse(session.canUndo)
    }

    @Test
    fun editorSessionKeepsSavedOrientationForRevisionWarnings() {
        val original = WorkflowDraft.newDefault()
        val session = WorkflowEditorSession()
        session.open(original.buildBundle().bundle!!)

        assertEquals(DisplayOrientation.PORTRAIT, session.savedOrientation)
        session.update(
            session.draft!!.copy(
                viewport = session.draft!!.viewport.copy(recordedOrientation = DisplayOrientation.LANDSCAPE),
            ),
        )

        assertEquals(DisplayOrientation.PORTRAIT, session.savedOrientation)
    }

    @Test
    fun newDraftBuildsAValidFirstRevision() {
        val draft = WorkflowDraft.newDefault()

        val result = draft.buildBundle()

        assertTrue(result.issues.isEmpty())
        assertNotNull(result.bundle)
        assertEquals(1, result.bundle!!.document.revision)
        assertEquals(draft.steps.map { it.id }.toSet(), draft.collapsedStepIds)
    }

    @Test
    fun newlyAddedBlocksStartCollapsed() {
        val draft = WorkflowDraft.newDefault().addStep(StepListPath(), WorkflowStepKind.WAIT, "com.veyarc.app")

        assertTrue(draft.steps.last().id in draft.collapsedStepIds)
    }

    @Test
    fun blockCanBeInsertedBetweenExistingBlocks() {
        val draft = WorkflowDraft.newDefault()
        val inserted = draft.insertStep(StepListPath(), 1, WorkflowStepKind.WAIT, "com.veyarc.app")

        assertTrue(inserted.steps[1] is com.veyarc.model.Wait)
        assertTrue(inserted.steps[1].id in inserted.collapsedStepIds)
    }

    @Test
    fun blockCanMoveAcrossTheWholeListInOneUpdate() {
        val draft = WorkflowDraft.newDefault()
            .addStep(StepListPath(), WorkflowStepKind.WAIT, "com.veyarc.app")
        val lastId = draft.steps.last().id

        val moved = draft.moveStepTo(StepListPath(), draft.steps.lastIndex, 0)

        assertEquals(lastId, moved.steps.first().id)
    }

    @Test
    fun nestedBlocksCanBeAddedMovedAndRemoved() {
        var draft = WorkflowDraft.newDefault()
        val root = StepListPath()

        draft = draft.addStep(root, WorkflowStepKind.REPEAT, "com.veyarc.app")
        val repeat = draft.steps.last() as Repeat
        val body = root.child(repeat.id, NestedBranch.REPEAT_BODY)
        val originalBody = draft.stepsAt(body)

        draft = draft.addStep(body, WorkflowStepKind.WAIT, "com.veyarc.app")
        assertEquals(originalBody.size + 1, draft.stepsAt(body).size)

        draft = draft.moveStep(body, draft.stepsAt(body).lastIndex, -1)
        assertEquals(originalBody.last().id, draft.stepsAt(body)[1].id)

        draft = draft.removeStep(body, 0)
        assertEquals(originalBody.size, draft.stepsAt(body).size)
    }

    @Test
    fun invalidPackageIsShownAsDraftFeedbackAndCannotBeSaved() {
        val draft = WorkflowDraft.newDefault().copy(allowedPackagesText = "not a package")

        val result = draft.buildBundle()

        assertNull(result.bundle)
        assertTrue(result.issues.any { it.path == "/allowedPackages" })
    }

    @Test
    fun geometryEditsRemainInTheValidatedWorkflowBundle() {
        var draft = WorkflowDraft.newDefault()
        val root = StepListPath()

        draft = draft.updateStep(root, 0) {
            (it as TapPoint).copy(point = NormalizedCoordinate(0.25, 0.75))
        }
        draft = draft.addStep(root, WorkflowStepKind.SWIPE_PATH, "com.veyarc.app")
        val swipeIndex = draft.steps.lastIndex
        draft = draft.updateStep(root, swipeIndex) { step ->
            val swipe = step as SwipePath
            swipe.copy(
                points = swipe.points.map { timed ->
                    timed.copy(point = NormalizedCoordinate(timed.point.x, 0.6))
                },
            )
        }

        val result = draft.buildBundle()

        assertTrue(result.issues.isEmpty())
        val bundle = result.bundle ?: error("Expected geometry draft to build")
        assertEquals(NormalizedCoordinate(0.25, 0.75), (bundle.document.steps[0] as TapPoint).point)
        assertEquals(0.6, (bundle.document.steps[swipeIndex] as SwipePath).points.first().point.y, 0.000001)
    }

    @Test
    fun conditionEditsRemainInTheValidatedWorkflowBundle() {
        var draft = WorkflowDraft.newDefault()
        val root = StepListPath()
        draft = draft.addStep(root, WorkflowStepKind.WAIT_UNTIL, "com.veyarc.app")
        val conditionIndex = draft.steps.lastIndex
        val condition = PixelColorCondition(
            point = NormalizedCoordinate(0.2, 0.8),
            expectedArgb = 0xFF112233L,
            tolerance = 12,
        )

        draft = draft.updateCondition(root, conditionIndex) { condition }
        draft = draft.updateStep(root, conditionIndex) {
            (it as WaitUntil).copy(timeoutMs = DurationMillis(2_000))
        }

        val result = draft.buildBundle()

        assertTrue(result.issues.isEmpty())
        val saved = result.bundle!!.document.steps[conditionIndex] as WaitUntil
        assertEquals(condition, saved.condition)
        assertEquals(2_000L, saved.timeoutMs.value)
    }

    @Test
    fun assetCanBeAddedAndRemovedFromDraft() {
        val asset = com.veyarc.model.bundle.BundleAsset(
            assetId = com.veyarc.model.AssetId("template-test"),
            kind = com.veyarc.model.AssetKind.IMAGE_TEMPLATE,
            mimeType = "image/x-veyarc-rgba",
            bytes = byteArrayOf(1, 2, 3),
            encrypted = true,
        )
        var draft = WorkflowDraft.newDefault().addAsset(asset)
        assertEquals(listOf(asset.assetId), draft.assets.map { it.assetId })
        assertEquals("Template 1", draft.assetNames[asset.assetId.value])
        draft = draft.removeAsset(asset.assetId)
        assertTrue(draft.assets.isEmpty())
    }
}
