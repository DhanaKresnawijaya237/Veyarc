package com.veyarc.feature.editor

import com.veyarc.model.Repeat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowEditorStateTest {
    @Test
    fun newDraftBuildsAValidFirstRevision() {
        val draft = WorkflowDraft.newDefault()

        val result = draft.buildBundle()

        assertTrue(result.issues.isEmpty())
        assertNotNull(result.bundle)
        assertEquals(1, result.bundle!!.document.revision)
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
}
