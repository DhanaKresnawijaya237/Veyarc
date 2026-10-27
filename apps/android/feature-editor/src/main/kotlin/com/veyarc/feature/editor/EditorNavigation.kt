package com.veyarc.feature.editor

import com.veyarc.model.IfElse
import com.veyarc.model.Repeat
import com.veyarc.model.ReusableSection
import com.veyarc.model.RunSection
import com.veyarc.model.StageBoundary
import com.veyarc.model.WhileStep
import com.veyarc.model.WorkflowStep
import com.veyarc.model.resolveBody
import com.veyarc.model.reusableSections

internal data class EditorBlockLocation(val path: StepListPath, val index: Int, val step: WorkflowStep, val label: String, val context: String, val modelPath: String) {
    val title: String get() = if (context == "Workflow") label else "$context / $label"
}

data class DraftIssueDisplay(val title: String, val message: String)

internal data class EditorNavigationRequest(val workflowId: String, val revision: Int, val stepId: String?, val issue: DraftIssue?)

internal fun WorkflowDraft.blockLocations(): List<EditorBlockLocation> = buildList {
    val definitions = steps.reusableSections()
    for (source in savedBlockSources()) {
        val indexes = stepsAt(source.path).mapIndexed { index, step -> step.id to index }.toMap()
        var number = 0
        var stage = 0
        for (step in source.steps) {
            val label = if (step is StageBoundary) "Stage ${++stage}" else "${++number}. ${if (step is RunSection) definitions[step.sectionId]?.name ?: "Choose saved blocks" else step.displayName()}"
            val index = indexes.getValue(step.id)
            add(EditorBlockLocation(source.path, index, step, label, source.title, modelPath(source.path, index)))
        }
    }
    steps.forEachIndexed { index, step -> if (step is ReusableSection) add(EditorBlockLocation(StepListPath(), index, step, step.name, "Saved blocks", "/steps/$index")) }
}

internal fun WorkflowDraft.sequenceCallers(id: String): List<EditorBlockLocation> = blockLocations().filter { (it.step as? RunSection)?.sectionId == id }

internal fun WorkflowDraft.issueLocation(issue: DraftIssue, locations: List<EditorBlockLocation> = blockLocations()): EditorBlockLocation? {
    val direct = locations.filter { issue.path == it.modelPath || issue.path.startsWith("${it.modelPath}/") }.maxByOrNull { it.modelPath.length } ?: return null
    val linked = direct.step as? ReusableSection
    if (linked?.isLinked != true || !issue.path.startsWith("${direct.modelPath}/bodySteps/")) return direct
    val tokens = issue.path.removePrefix("${direct.modelPath}/bodySteps/").split('/')
    var target = linked.resolveBody(steps)?.getOrNull(tokens.first().toIntOrNull() ?: return direct) ?: return direct
    var at = 1
    while (at + 1 < tokens.size) {
        val children = when (val step = target) {
            is IfElse -> when (tokens[at]) {
                "thenSteps" -> step.thenSteps
                "elseSteps" -> step.elseSteps
                else -> null
            }

            is Repeat -> step.bodySteps.takeIf { tokens[at] == "bodySteps" }

            is WhileStep -> step.bodySteps.takeIf { tokens[at] == "bodySteps" }

            else -> null
        } ?: break
        target = children.getOrNull(tokens[at + 1].toIntOrNull() ?: break) ?: break
        at += 2
    }
    return locations.firstOrNull { it.step.id == target.id } ?: direct
}

fun WorkflowDraft.issueDisplay(issue: DraftIssue): DraftIssueDisplay {
    val locations = blockLocations()
    val location = issueLocation(issue, locations)
    val linkedContext = steps.filterIsInstance<ReusableSection>().firstOrNull { it.isLinked && issue.path.startsWith("/steps/${steps.indexOf(it)}/bodySteps/") }
    val title = linkedContext?.let { "${it.name} / ${location?.label ?: "Saved blocks"}" } ?: location?.title ?: when {
        issue.path.startsWith("/allowedPackages") -> "Allowed apps"
        issue.path.startsWith("/assets") -> "Image templates"
        issue.path.startsWith("/variables") -> "Variables"
        issue.path == "/name" -> "Workflow name"
        issue.path.startsWith("/viewport") -> "Workflow orientation"
        else -> "Workflow settings"
    }
    var message = when {
        "Choose existing start and end blocks" in issue.message -> "Reselect the first and last blocks. Keep them in order and on the same level."
        "circular reference" in issue.message -> "This saved sequence eventually runs itself. Choose a different sequence."
        "Choose a different section name" in issue.message -> "Give this saved sequence a different name."
        "Choose a reusable section" in issue.message -> "Choose saved blocks to run. The previous sequence is missing."
        "Keep reusable sections" in issue.message -> "Keep this sequence in Saved blocks, and use a run block to call it."
        "Go to must target" in issue.message -> "Choose another destination in this ${if (location?.path?.sectionId != null) "saved sequence" else "workflow"} or an enclosing group."
        "Referenced step does not exist" in issue.message -> "Choose a block to check. The previous block was deleted."
        "Referenced step must appear earlier" in issue.message -> "Choose a block that runs before this check."
        "And requires at least two" in issue.message -> "Add at least two conditions to All."
        "Or requires at least two" in issue.message -> "Add at least two conditions to Any."
        "At least one valid Android package" in issue.message -> "Add at least one app to Allowed apps."
        "initial value" in issue.message -> "Choose a starting value that matches the variable type."
        "occurs more than once" in issue.message -> "A block was duplicated incorrectly. Recreate the duplicated block."
        "Asset " in issue.message && "not declared" in issue.message -> "Choose an image for this block. Its previous image was deleted."
        "nesting depth" in issue.message.lowercase() -> "This workflow has too many nested groups. Move a group outward."
        else -> issue.message
    }
    val names = locations.associate { it.step.id to it.label } + steps.reusableSections().mapValues { it.value.name } + assetNames
    val ids = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    message = ids.replace(message) { names[it.value] ?: "deleted item" }
    message = message.replace("maxLoopIterations", "the workflow repeat limit").replace("maxPathPoints", "the supported path size").replace("traversal order", "execution order")
    return DraftIssueDisplay(title, message)
}

internal fun WorkflowDraft.makeCallIndependent(caller: BlockSelection): Pair<WorkflowDraft, String> {
    val index = stepsAt(caller.path).indexOfFirst { it.id == caller.stepId }
    val call = stepsAt(caller.path).getOrNull(index) as? RunSection ?: error("This run block was removed")
    val original = requireNotNull(steps.reusableSections()[call.sectionId]) { "Choose saved blocks first" }
    val snapshots = remapClipboardSteps(listOf(original).withSectionDependencies(steps), emptyMap()).filterIsInstance<ReusableSection>()
    val names = steps.reusableSections().values.mapTo(mutableSetOf()) { it.name.trim().lowercase() }
    val copies = snapshots.map { section ->
        val base = section.name.trim().take(68)
        val name = generateSequence(1) { it + 1 }.map { "$base copy $it" }.first { it.lowercase() !in names }
        names += name.lowercase()
        section.copy(name = name)
    }
    val id = copies.first().id
    return copy(steps = steps + copies).updateStep(caller.path, index) { call.copy(sectionId = id) } to id
}
