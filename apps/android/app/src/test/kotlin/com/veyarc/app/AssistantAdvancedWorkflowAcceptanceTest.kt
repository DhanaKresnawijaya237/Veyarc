package com.veyarc.app

import com.veyarc.data.storage.WorkflowAssetDescriptor
import com.veyarc.feature.assistant.AssistantCandidateValidator
import com.veyarc.feature.assistant.AssistantDraftContext
import com.veyarc.feature.assistant.AssistantProposalReview
import com.veyarc.feature.assistant.AssistantReviewStatus
import com.veyarc.feature.editor.WorkflowDraft
import com.veyarc.feature.editor.WorkflowEditorSession
import com.veyarc.model.AndCondition
import com.veyarc.model.AssetId
import com.veyarc.model.AssetKind
import com.veyarc.model.ComparisonOperator
import com.veyarc.model.DurationMillis
import com.veyarc.model.IfElse
import com.veyarc.model.ImageAbsentCondition
import com.veyarc.model.ImageMatchMode
import com.veyarc.model.ImagePresentCondition
import com.veyarc.model.Jump
import com.veyarc.model.LiteralOperand
import com.veyarc.model.NormalizedRect
import com.veyarc.model.NotCondition
import com.veyarc.model.NumberValue
import com.veyarc.model.OrCondition
import com.veyarc.model.Repeat
import com.veyarc.model.SetVariable
import com.veyarc.model.StageBoundary
import com.veyarc.model.StepOutcome
import com.veyarc.model.StepResultCondition
import com.veyarc.model.StringValue
import com.veyarc.model.VariableComparisonCondition
import com.veyarc.model.VariableDeclaration
import com.veyarc.model.VariableName
import com.veyarc.model.VariableOperand
import com.veyarc.model.VariableType
import com.veyarc.model.Wait
import com.veyarc.model.WaitUntil
import com.veyarc.model.WhileStep
import com.veyarc.model.WorkflowDocument
import com.veyarc.model.WorkflowJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Local acceptance only: no provider, repository, image loading, or execution driver. */
class AssistantAdvancedWorkflowAcceptanceTest {
    private fun id(number: Int) = "00000000-0000-4000-8000-${number.toString().padStart(12, '0')}"
    private val crop = AssetId("crop")
    private val screen = AssetId("screen")
    private val region = NormalizedRect(0.0, 0.0, 1.0, 1.0)
    private val assignment = SetVariable(id(5), VariableName("count"), NumberValue(2.0))
    private val wait = Wait(id(6), DurationMillis(100))
    private val loop = WhileStep(id(4), NotCondition(ImageAbsentCondition(screen, region, 0.9)), 2, listOf(assignment, wait))
    private val branch = IfElse(
        id(3),
        AndCondition(listOf(ImagePresentCondition(crop, region, 0.9), VariableComparisonCondition(VariableOperand(VariableName("count")), ComparisonOperator.LESS_THAN, LiteralOperand(NumberValue(3.0))))),
        listOf(loop),
        listOf(Wait(id(7), DurationMillis(200))),
    )
    private val repeat = Repeat(id(2), 2, listOf(branch))
    private val steps = listOf(
        StageBoundary(id(1)),
        repeat,
        StageBoundary(id(8)),
        WaitUntil(id(9), OrCondition(listOf(StepResultCondition(repeat.id, StepOutcome.COMPLETED), ImagePresentCondition(screen, region, 0.9, ImageMatchMode.SCREEN_STATE))), DurationMillis(1000), DurationMillis(100)),
        Jump(id(10), id(8), 2),
        StageBoundary(id(11)),
        Wait(id(12), DurationMillis(300)),
    )
    private val source = WorkflowDraft.newDefault().copy(
        workflowId = id(100),
        name = "Advanced acceptance",
        lastSavedRevision = 3,
        latestKnownRevision = 5,
        steps = steps,
        variables = mapOf("count" to VariableDeclaration(VariableType.NUMBER, NumberValue(0.0))),
        assetDescriptors = listOf(
            WorkflowAssetDescriptor(crop, AssetKind.IMAGE_TEMPLATE, "Crop", "image/png", 20, 30),
            WorkflowAssetDescriptor(screen, AssetKind.IMAGE_TEMPLATE, "Screen", "image/png", 1080, 2400),
        ),
        recordingJournalFileName = "private-recording-journal",
    )
    private val document = requireNotNull(source.buildBundle().document)

    private fun editor(draft: WorkflowDraft) = WorkflowEditorSession().apply {
        createForAssistant(draft.viewport)
        update(draft)
    }

    private fun withBranch(value: IfElse): WorkflowDocument = document.copy(steps = steps.map { if (it == repeat) repeat.copy(bodySteps = listOf(value)) else it })

    @Test
    fun nestedEditsHaveExactLocalDiffAndOneUndoPreservesUnloadedAssetsAndRevision() {
        val editor = editor(source)
        val bridge = AssistantDraftBridge(editor)
        val captured = bridge.capture()
        val snapshot = requireNotNull(captured.snapshot)
        val context = WorkflowJson.decodeStrict(AssistantDraftContext.serializer(), snapshot.json)
        assertEquals(source.steps, context.steps)
        assertEquals(source.variables, context.variables)
        assertEquals(document.assets, context.assets)
        assertFalse(snapshot.json.contains("private-recording-journal"))
        val changedBranch = branch.copy(
            condition = AndCondition(listOf(ImagePresentCondition(crop, region, 0.95), (branch.condition as AndCondition).conditions[1])),
            thenSteps = listOf(loop.copy(maxIterations = 3, bodySteps = listOf(assignment.copy(value = NumberValue(3.0))))),
            elseSteps = branch.elseSteps + wait.copy(durationMs = DurationMillis(250)),
        )
        val candidate = document.copy(
            variables = mapOf("count" to VariableDeclaration(VariableType.NUMBER, NumberValue(1.0))),
            steps = steps.map {
                when (it) {
                    repeat -> repeat.copy(count = 3, bodySteps = listOf(changedBranch))
                    is Jump -> it.copy(maxVisits = 3)
                    else -> it
                }
            },
        )
        val json = WorkflowJson.encode(candidate)
        val proposal = AssistantCandidateValidator.validate(snapshot, json, ::assistantCompilationIssues)
        assertTrue(proposal.issues.toString(), proposal.isValid)
        assertEquals(candidate, WorkflowJson.decode(proposal.candidateJson!!))
        assertEquals(AssistantReviewStatus.READY, AssistantProposalReview.status(snapshot, bridge.capture(), proposal, false))
        val diff = AssistantProposalReview.changes(snapshot, proposal)
        fun changed(suffix: String, before: String, after: String) {
            assertTrue("Missing $suffix in $diff", diff.any { it.label.endsWith(suffix) && it.before == before && it.after == after })
        }
        changed("Variables / count / initialValue / value", "0.0", "1.0")
        changed("/ count", "2", "3")
        val conditionDiff = diff.single { it.label == "Block ${branch.id} at Steps / 2 / bodySteps / 1 / condition / conditions" }
        assertTrue(conditionDiff.before!!.contains("\"matchThreshold\":0.9"))
        assertEquals(conditionDiff.before!!.replace("\"matchThreshold\":0.9", "\"matchThreshold\":0.95"), conditionDiff.after)
        changed("/ maxIterations", "2", "3")
        changed("/ value / value", "2.0", "3.0")
        changed("/ maxVisits", "2", "3")
        changed("/ durationMs", "100", "250")
        assertTrue(diff.any { it.label == "Moved block ${wait.id}" && it.before == "Steps / 2 / bodySteps / 1 / thenSteps / 1 / bodySteps / 2" && it.after == "Steps / 2 / bodySteps / 1 / elseSteps / 2" })
        assertEquals(source, editor.draft)
        assertTrue(bridge.apply(captured.localBindingId!!, snapshot, json))
        assertEquals(source.copy(steps = candidate.steps, variables = candidate.variables), editor.draft)
        assertTrue(editor.draft!!.assets.isEmpty())
        assertEquals(3, editor.draft!!.lastSavedRevision)
        assertEquals(6, editor.draft!!.nextRevision)
        assertFalse(bridge.apply(captured.localBindingId!!, snapshot, json))
        editor.undo()
        assertEquals(source, editor.draft)
        editor.redo()
        assertEquals(candidate, editor.draft!!.buildBundle().document)
    }

    @Test
    fun configuredUnsavedDraftAcceptsAdvancedCreationButStaleCandidateCannotApply() {
        val configured = source.copy(lastSavedRevision = null, latestKnownRevision = null, steps = listOf(Wait(id(99), DurationMillis(125))), recordingJournalFileName = null)
        val editor = editor(configured)
        val bridge = AssistantDraftBridge(editor)
        val captured = bridge.capture()
        val candidate = document.copy(revision = 1)
        val json = WorkflowJson.encode(candidate)
        val proposal = AssistantCandidateValidator.validate(captured.snapshot, json, ::assistantCompilationIssues)
        assertTrue(proposal.issues.toString(), proposal.isValid)
        val diff = AssistantProposalReview.changes(captured.snapshot!!, proposal)
        assertTrue(diff.any { it.label.startsWith("Added block ${branch.id}") })
        assertTrue(diff.any { it.label.startsWith("Removed block ${id(99)}") })
        editor.update(configured.copy(name = "User edited while reviewing"))
        assertEquals(AssistantReviewStatus.STALE, AssistantProposalReview.status(captured.snapshot, bridge.capture(), proposal, false))
        assertFalse(bridge.apply(captured.localBindingId!!, captured.snapshot!!, json))
        editor.undo()
        val fresh = bridge.capture()
        assertTrue(bridge.apply(fresh.localBindingId!!, fresh.snapshot!!, json))
        assertEquals(candidate, editor.draft!!.buildBundle().document)
        assertNull(editor.draft!!.lastSavedRevision)
        assertEquals(configured.workflowId, editor.draft!!.workflowId)
        editor.undo()
        assertEquals(configured, editor.draft)
    }

    @Test
    fun invalidNestedReferencesTypesAndBoundsNeverReachApply() {
        val editor = editor(source)
        val bridge = AssistantDraftBridge(editor)
        val captured = bridge.capture()
        val cases = listOf(
            "MISSING_VARIABLE" to document.copy(variables = emptyMap()),
            "VARIABLE_TYPE_MISMATCH" to withBranch(branch.copy(thenSteps = listOf(loop.copy(bodySteps = listOf(assignment.copy(value = StringValue("wrong")), wait))))),
            "UNKNOWN_STEP_REFERENCE" to document.copy(steps = steps.map { if (it is Jump) it.copy(targetStepId = wait.id) else it }),
            "UNKNOWN_STEP_REFERENCE" to document.copy(steps = steps.filterNot { it.id == id(8) }),
            "DUPLICATE_STEP_ID" to document.copy(steps = steps + wait),
            "MISSING_ASSET" to withBranch(branch.copy(condition = ImagePresentCondition(AssetId("missing"), region, 0.9))),
            "LIMIT_EXCEEDED" to withBranch(branch.copy(thenSteps = listOf(loop.copy(maxIterations = source.runLimits.maxLoopIterations + 1)))),
            "INVALID_LOGICAL_ARITY" to withBranch(branch.copy(condition = AndCondition(listOf(ImagePresentCondition(crop, region, 0.9))))),
            "FORWARD_STEP_REFERENCE" to withBranch(branch.copy(condition = StepResultCondition(id(12), StepOutcome.COMPLETED))),
        )
        cases.forEach { (expected, candidate) ->
            val json = WorkflowJson.encode(candidate)
            val result = AssistantCandidateValidator.validate(captured.snapshot, json, ::assistantCompilationIssues)
            assertFalse(expected, result.isValid)
            assertNull(result.candidateJson)
            assertTrue("Expected $expected, got ${result.issues}", result.issues.any { it.contains(expected) })
            assertEquals(AssistantReviewStatus.INVALID, AssistantProposalReview.status(captured.snapshot, bridge.capture(), result, false))
            assertFalse(bridge.apply(captured.localBindingId!!, captured.snapshot!!, json))
            assertEquals(source, editor.draft)
        }
    }

    @Test
    fun malformedAndOversizedAdvancedCandidatesFailBeforeCompilationWithoutTruncation() {
        val snapshot = source.captureAssistantContext().snapshot!!
        val json = WorkflowJson.encode(document)
        val oversizedBlocks = WorkflowJson.encode(document.copy(steps = steps + List(201) { Wait(id(1000 + it), DurationMillis(1)) }))
        listOf(json.dropLast(1), json.replace("\"maxIterations\":2", "\"maxIterations\":2,\"maxIterations\":3"), json.replace("\"maxVisits\":2", "\"maxVisits\":\"2\""), oversizedBlocks, json + " ".repeat(AssistantCandidateValidator.MAX_CANDIDATE_BYTES + 1)).forEach { bad ->
            var compiled = false
            val result = AssistantCandidateValidator.validate(snapshot, bad) {
                compiled = true
                emptyList()
            }
            assertFalse(result.isValid)
            assertNull(result.candidateJson)
            assertFalse(compiled)
        }
        assertEquals(document, source.buildBundle().document)
    }
}
