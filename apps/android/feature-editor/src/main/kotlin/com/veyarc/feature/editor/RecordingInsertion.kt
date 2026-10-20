package com.veyarc.feature.editor

import androidx.compose.runtime.staticCompositionLocalOf
import com.veyarc.model.IfElse
import com.veyarc.model.LaunchApp
import com.veyarc.model.Repeat
import com.veyarc.model.StageBoundary
import com.veyarc.model.WhileStep
import com.veyarc.model.WorkflowStep
import com.veyarc.model.bundle.WorkflowBundle

data class RecordingInsertion(val path: StepListPath = StepListPath(), val index: Int = 0, val afterStepId: String? = null) {
    fun isValid(draft: WorkflowDraft): Boolean = resolve(draft) != null

    fun anchored(draft: WorkflowDraft): RecordingInsertion = copy(afterStepId = draft.stepsAt(path).getOrNull(index - 1)?.id)

    fun resolve(draft: WorkflowDraft): RecordingInsertion? {
        var steps = draft.steps
        for (segment in path.segments) {
            val parent = steps.firstOrNull { it.id == segment.parentId } ?: return null
            steps = when (segment.branch) {
                NestedBranch.IF_THEN -> (parent as? IfElse)?.thenSteps
                NestedBranch.IF_ELSE -> (parent as? IfElse)?.elseSteps
                NestedBranch.WHILE_BODY -> (parent as? WhileStep)?.bodySteps
                NestedBranch.REPEAT_BODY -> (parent as? Repeat)?.bodySteps
            } ?: return null
        }
        val resolved = if (afterStepId == null) {
            index
        } else {
            val preceding = steps.indexOfFirst { it.id == afterStepId }
            if (preceding < 0) return null
            preceding + 1
        }
        return copy(index = resolved).takeIf { resolved in 0..steps.size }
    }

    fun label(draft: WorkflowDraft): String {
        val target = resolve(draft) ?: return "Choose a recording section"
        var steps = draft.steps
        val labels = path.segments.map { segment ->
            val position = steps.indexOfFirst { it.id == segment.parentId }
            val blockNumber = steps.take(position + 1).count { it !is StageBoundary }
            val parent = steps.getOrNull(position)
            val section = when (segment.branch) {
                NestedBranch.IF_THEN -> "If · then"
                NestedBranch.IF_ELSE -> "If · otherwise"
                NestedBranch.WHILE_BODY -> "While · actions"
                NestedBranch.REPEAT_BODY -> "Repeat · actions"
            }
            steps = when (parent) {
                is IfElse -> if (segment.branch == NestedBranch.IF_ELSE) parent.elseSteps else parent.thenSteps
                is WhileStep -> parent.bodySteps
                is Repeat -> parent.bodySteps
                else -> emptyList()
            }
            "$blockNumber. $section"
        }
        val blockNumber = steps.take(target.index).count { it !is StageBoundary }
        return (labels + if (target.index == 0) "Start here" else "After block $blockNumber").joinToString(" / ")
    }
}

data class RecordingEditorControls(
    val onRecord: (StepListPath, Int) -> Unit,
    val onClose: (() -> Unit)? = null,
    val preparing: Boolean = false,
)

internal val LocalRecordingEditorControls = staticCompositionLocalOf<RecordingEditorControls?> { null }

/** Merge one paused segment without changing existing block IDs, assets, or branch contents. */
fun WorkflowDraft.insertRecording(segment: WorkflowBundle, destination: RecordingInsertion, previousPackage: String?): Pair<WorkflowDraft, RecordingInsertion> {
    val target = requireNotNull(destination.resolve(this)) { "The recording section changed. Choose Record here again." }
    require(viewport == segment.document.viewport) { "Record in the workflow's original orientation and screen size." }
    val incoming = segment.document
    val recordedSteps = incoming.steps.let {
        if (it.firstOrNull() is LaunchApp && (it.first() as LaunchApp).packageName.value == previousPackage) it.drop(1) else it
    }
    val packages = (allowedPackagesText.split(',', ' ', '\n').filter(String::isNotBlank) + incoming.allowedPackages.map { it.value }).distinct()
    val updated = insertSteps(target.path, target.index, recordedSteps).copy(
        allowedPackagesText = packages.joinToString(", "),
        runLimits = runLimits.copy(
            maxTotalSteps = maxOf(runLimits.maxTotalSteps, incoming.runLimits.maxTotalSteps, countRecordingSteps(steps) + recordedSteps.size + 16),
            maxRuntimeMs = maxOf(runLimits.maxRuntimeMs, incoming.runLimits.maxRuntimeMs),
            maxPathPoints = maxOf(runLimits.maxPathPoints, incoming.runLimits.maxPathPoints),
            maxStepDurationMs = maxOf(runLimits.maxStepDurationMs, incoming.runLimits.maxStepDurationMs),
        ),
    )
    return updated to target.copy(index = target.index + recordedSteps.size).anchored(updated)
}

private fun countRecordingSteps(steps: List<WorkflowStep>): Int = steps.sumOf { step ->
    1 + when (step) {
        is IfElse -> countRecordingSteps(step.thenSteps) + countRecordingSteps(step.elseSteps)
        is Repeat -> countRecordingSteps(step.bodySteps)
        is WhileStep -> countRecordingSteps(step.bodySteps)
        else -> 0
    }
}

internal fun WorkflowStep.emptyRecordingBody(): WorkflowStep = when (this) {
    is IfElse -> copy(thenSteps = emptyList(), elseSteps = emptyList())
    is Repeat -> copy(bodySteps = emptyList())
    is WhileStep -> copy(bodySteps = emptyList())
    else -> this
}
