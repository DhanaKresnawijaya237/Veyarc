package com.veyarc.feature.editor

import androidx.compose.runtime.staticCompositionLocalOf
import com.veyarc.model.AndCondition
import com.veyarc.model.Condition
import com.veyarc.model.IfElse
import com.veyarc.model.Jump
import com.veyarc.model.LaunchApp
import com.veyarc.model.OrCondition
import com.veyarc.model.PackageName
import com.veyarc.model.Repeat
import com.veyarc.model.ReusableSection
import com.veyarc.model.RunSection
import com.veyarc.model.StageBoundary
import com.veyarc.model.WhileStep
import com.veyarc.model.WorkflowStep
import com.veyarc.model.bundle.WorkflowBundle
import com.veyarc.model.reusableSections

data class RecordingInsertion(val path: StepListPath = StepListPath(), val index: Int = 0, val afterStepId: String? = null) {
    fun isValid(draft: WorkflowDraft): Boolean = resolve(draft) != null

    fun anchored(draft: WorkflowDraft): RecordingInsertion = copy(afterStepId = draft.stepsAt(path).getOrNull(index - 1)?.id)

    fun outside(draft: WorkflowDraft): RecordingInsertion? {
        val parent = path.segments.lastOrNull() ?: return null
        val outerPath = StepListPath(path.segments.dropLast(1))
        val index = draft.stepsAt(outerPath).indexOfFirst { it.id == parent.parentId }
        return if (index < 0) null else RecordingInsertion(outerPath, index + 1).anchored(draft)
    }

    fun resolve(draft: WorkflowDraft): RecordingInsertion? {
        var steps = draft.steps
        for (segment in path.segments) {
            val parent = steps.firstOrNull { it.id == segment.parentId } ?: return null
            steps = when (segment.branch) {
                NestedBranch.IF_THEN -> (parent as? IfElse)?.thenSteps
                NestedBranch.IF_ELSE -> (parent as? IfElse)?.elseSteps
                NestedBranch.WHILE_BODY -> (parent as? WhileStep)?.bodySteps
                NestedBranch.SECTION_BODY -> (parent as? ReusableSection)?.bodySteps
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
                NestedBranch.SECTION_BODY -> (parent as? ReusableSection)?.name ?: "Section"
                NestedBranch.REPEAT_BODY -> "Repeat · actions"
            }
            steps = when (parent) {
                is IfElse -> if (segment.branch == NestedBranch.IF_ELSE) parent.elseSteps else parent.thenSteps
                is WhileStep -> parent.bodySteps
                is ReusableSection -> parent.bodySteps
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

/** Infer the foreground app at this position without running any workflow actions. */
fun RecordingInsertion.appCandidates(draft: WorkflowDraft): Set<PackageName> {
    val target = requireNotNull(resolve(draft)) { "Choose a recording section first." }
    val sections = draft.steps.reusableSections()
    fun launches(steps: List<WorkflowStep>): Set<PackageName> = steps.flatMapTo(linkedSetOf()) { step ->
        when (step) {
            is RunSection -> launches(sections[step.sectionId]?.bodySteps.orEmpty())
            is LaunchApp -> setOf(step.packageName)
            is IfElse -> launches(step.thenSteps) + launches(step.elseSteps)
            is Repeat -> launches(step.bodySteps)
            is WhileStep -> launches(step.bodySteps)
            else -> emptySet()
        }
    }
    val all = launches(target.path.sectionId?.let { sections[it]?.bodySteps } ?: draft.steps)
    if (all.size <= 1) return all
    fun after(steps: List<WorkflowStep>, initial: Set<PackageName?>): Set<PackageName?> = steps.fold(initial) { apps, step ->
        when (step) {
            is RunSection -> after(sections[step.sectionId]?.bodySteps.orEmpty(), apps)
            is LaunchApp -> setOf(step.packageName)
            is IfElse -> after(step.thenSteps, apps) + after(step.elseSteps, apps)
            is Repeat -> if (step.count == 0) apps else after(step.bodySteps, apps)
            is WhileStep -> apps + after(step.bodySteps, apps)
            is Jump -> all + null
            else -> apps
        }
    }
    var apps: Set<PackageName?> = setOf(null)
    var steps = draft.steps
    for ((depth, segment) in target.path.segments.withIndex()) {
        val position = steps.indexOfFirst { it.id == segment.parentId }
        apps = after(steps.take(position), apps)
        steps = draft.stepsAt(StepListPath(target.path.segments.take(depth + 1)))
    }
    apps = after(steps.take(target.index), apps)
    if (apps == setOf<PackageName?>(null) && target.path.segments.isEmpty() && steps.take(target.index).all { it is StageBoundary }) {
        (steps.drop(target.index).firstOrNull { it !is StageBoundary } as? LaunchApp)?.let { return setOf(it.packageName) }
    }
    return if (null in apps) all else apps.filterNotNull().toSet()
}

enum class RecordingConditionEdit { REPLACE, ALL, ANY }

data class RecordingConditionTarget(val path: StepListPath, val stepId: String, val condition: Condition) {
    fun applyTo(draft: WorkflowDraft, image: Condition, edit: RecordingConditionEdit): WorkflowDraft {
        val index = draft.stepsAt(path).indexOfFirst { it.id == stepId }
        require(index >= 0) { "This section was removed. Add from screen again." }
        require(draft.stepsAt(path)[index] is IfElse || draft.stepsAt(path)[index] is WhileStep) { "This section changed. Add from screen again." }
        return draft.updateCondition(path, index) { current ->
            require(current == condition) { "This condition changed. Add from screen again." }
            when (edit) {
                RecordingConditionEdit.REPLACE -> image
                RecordingConditionEdit.ALL -> AndCondition(((current as? AndCondition)?.conditions ?: listOf(current)) + image)
                RecordingConditionEdit.ANY -> OrCondition(((current as? OrCondition)?.conditions ?: listOf(current)) + image)
            }
        }
    }
}

fun RecordingInsertion.conditionTarget(draft: WorkflowDraft): RecordingConditionTarget? {
    if (!isValid(draft)) return null
    val parent = path.segments.lastOrNull() ?: return null
    val outer = StepListPath(path.segments.dropLast(1))
    val step = draft.stepsAt(outer).firstOrNull { it.id == parent.parentId }
    val condition = when (step) {
        is IfElse -> step.condition
        is WhileStep -> step.condition
        else -> return null
    }
    return RecordingConditionTarget(outer, step.id, condition)
}

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
        is ReusableSection -> countRecordingSteps(step.bodySteps)
        is Repeat -> countRecordingSteps(step.bodySteps)
        is WhileStep -> countRecordingSteps(step.bodySteps)
        else -> 0
    }
}

internal fun WorkflowStep.emptyRecordingBody(): WorkflowStep = when (this) {
    is IfElse -> copy(thenSteps = emptyList(), elseSteps = emptyList())
    is ReusableSection -> copy(bodySteps = emptyList())
    is Repeat -> copy(bodySteps = emptyList())
    is WhileStep -> copy(bodySteps = emptyList())
    else -> this
}
