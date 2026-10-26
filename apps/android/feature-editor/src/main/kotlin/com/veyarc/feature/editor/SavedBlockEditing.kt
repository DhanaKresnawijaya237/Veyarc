package com.veyarc.feature.editor

import com.veyarc.model.IfElse
import com.veyarc.model.Repeat
import com.veyarc.model.ReusableSection
import com.veyarc.model.StageBoundary
import com.veyarc.model.WhileStep
import com.veyarc.model.WorkflowIds
import com.veyarc.model.WorkflowStep
import com.veyarc.model.resolveRange
import com.veyarc.model.reusableSections

internal data class PendingSavedBlocks(
    val name: String,
    val startId: String,
    val endId: String,
    val linked: Boolean = true,
    val selectedIds: Set<String>? = null,
)

internal data class SavedBlockSource(val title: String, val path: StepListPath, val steps: List<WorkflowStep>) {
    fun label(id: String): String {
        var number = 0
        var stage = 0
        return steps.firstNotNullOfOrNull { step ->
            val text = if (step is StageBoundary) "Stage ${++stage}" else "${++number}. ${step.displayName()}"
            text.takeIf { step.id == id }
        } ?: "Choose a block"
    }
}

internal fun WorkflowDraft.savedBlockSources(): List<SavedBlockSource> = buildList {
    fun visit(items: List<WorkflowStep>, path: StepListPath, title: String) {
        val visible = items.filterNot { it is ReusableSection }
        if (visible.any { it !is StageBoundary }) add(SavedBlockSource(title, path, visible))
        var number = 0
        for (step in items) {
            if (step is StageBoundary) continue
            if (step is ReusableSection) {
                visit(step.bodySteps, path.child(step.id, NestedBranch.SECTION_BODY), "Saved · ${step.name}")
                continue
            }
            number++
            val prefix = if (path.segments.isEmpty()) "$number." else "$title › $number."
            when (step) {
                is IfElse -> {
                    visit(step.thenSteps, path.child(step.id, NestedBranch.IF_THEN), "$prefix If true")
                    visit(step.elseSteps, path.child(step.id, NestedBranch.IF_ELSE), "$prefix If false")
                }

                is Repeat -> visit(step.bodySteps, path.child(step.id, NestedBranch.REPEAT_BODY), "$prefix Repeat")

                is WhileStep -> visit(step.bodySteps, path.child(step.id, NestedBranch.WHILE_BODY), "$prefix While")

                else -> Unit
            }
        }
    }
    visit(steps, StepListPath(), "Workflow")
}

internal fun WorkflowDraft.pendingSavedBlocks(path: StepListPath? = null, selected: Set<String> = emptySet()): PendingSavedBlocks? {
    val sources = savedBlockSources()
    val source = sources.firstOrNull { it.path == path } ?: sources.firstOrNull() ?: return null
    val chosen = if (selected.isEmpty()) listOf(source.steps.first { it !is StageBoundary }) else source.steps.filter { it.id in selected }
    if (chosen.none { it !is StageBoundary }) return null
    val chosenIds = chosen.mapTo(mutableSetOf()) { it.id }
    val indexes = source.steps.indices.filter { source.steps[it].id in chosenIds }
    val adjacent = indexes.size == indexes.last() - indexes.first() + 1
    val names = steps.reusableSections().values.mapTo(mutableSetOf()) { it.name.trim().lowercase() }
    val name = generateSequence(1) { it + 1 }.map { "Sequence $it" }.first { it.lowercase() !in names }
    return PendingSavedBlocks(name, chosen.first().id, chosen.last().id, adjacent, chosen.mapTo(mutableSetOf()) { it.id }.takeUnless { adjacent })
}

internal fun WorkflowDraft.saveBlocks(pending: PendingSavedBlocks): Pair<WorkflowDraft, String> {
    val name = pending.name.trim()
    require(name.isNotBlank() && name.length <= 80 && steps.reusableSections().values.none { it.name.trim().equals(name, true) }) { "Choose a different saved name" }
    val range = requireNotNull(steps.resolveRange(pending.startId, pending.endId)) { "Choose valid start and end blocks" }
    val chosen = range.filter { pending.selectedIds == null || it.id in pending.selectedIds }
    require(chosen.any { it !is StageBoundary }) { "Choose at least one action to save" }
    val id = WorkflowIds.newUuid()
    if (pending.linked) {
        require(pending.selectedIds == null) { "Select adjacent blocks for a linked range" }
        return copy(steps = steps + ReusableSection(id, name, emptyList(), pending.startId, pending.endId)) to id
    }
    val snapshots = remapClipboardSteps(chosen.withSectionDependencies(steps), emptyMap())
    val names = steps.reusableSections().values.mapTo(mutableSetOf()) { it.name.trim().lowercase() }.apply { add(name.lowercase()) }
    val dependencies = snapshots.filterIsInstance<ReusableSection>().map { definition ->
        val base = definition.name.trim().take(68)
        val label = generateSequence(1) { it + 1 }.map { "$base copy $it" }.first { it.lowercase() !in names }
        names += label.lowercase()
        definition.copy(name = label)
    }
    return copy(steps = steps + dependencies + ReusableSection(id, name, snapshots.filterNot { it is ReusableSection })) to id
}

internal fun WorkflowDraft.updateSequenceParameters(id: String, inputs: Set<String>, outputs: Set<String>): WorkflowDraft {
    require((inputs + outputs).all { it in variables }) { "Choose existing workflow variables" }
    val index = steps.indexOfFirst { it.id == id && it is ReusableSection }
    require(index >= 0) { "Choose existing saved blocks" }
    var updated = updateStep(StepListPath(), index) { (it as ReusableSection).copy(inputs = inputs, outputs = outputs) }
    for (caller in updated.sequenceCallers(id)) {
        updated = updated.updateStep(caller.path, caller.index) {
            (it as com.veyarc.model.RunSection).copy(inputs = it.inputs.filterKeys { name -> name in inputs }, outputs = it.outputs.filterKeys { name -> name in outputs })
        }
    }
    return updated
}
