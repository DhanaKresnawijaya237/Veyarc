package com.veyarc.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("section")
data class ReusableSection(
    override val id: String,
    val name: String,
    val bodySteps: List<WorkflowStep>,
    val startStepId: String? = null,
    val endStepId: String? = null,
) : WorkflowStep {
    init {
        ContractChecks.requireStableId(id, "Section id")
        require(name.isNotBlank() && name.length <= 80) { "Section name must be 1 to 80 characters" }
        require((startStepId == null) == (endStepId == null)) { "Choose both ends of the linked range" }
        startStepId?.let { ContractChecks.requireStableId(it, "Start block id") }
        endStepId?.let { ContractChecks.requireStableId(it, "End block id") }
        require(startStepId == null || bodySteps.isEmpty()) { "A linked range cannot also contain a saved copy" }
    }

    val isLinked: Boolean get() = startStepId != null
}

@Serializable
@SerialName("runSection")
data class RunSection(override val id: String, val sectionId: String) : WorkflowStep {
    init {
        ContractChecks.requireStableId(id, "Step id")
        ContractChecks.requireStableId(sectionId, "Section id")
    }
}

fun WorkflowStep.childSteps(): List<WorkflowStep> = when (this) {
    is IfElse -> thenSteps + elseSteps
    is Repeat -> bodySteps
    is WhileStep -> bodySteps
    is ReusableSection -> bodySteps
    else -> emptyList()
}

fun List<WorkflowStep>.walkSteps(): Sequence<WorkflowStep> = asSequence().flatMap { sequenceOf(it) + it.childSteps().walkSteps() }

fun List<WorkflowStep>.reusableSections(): Map<String, ReusableSection> = filterIsInstance<ReusableSection>().associateBy { it.id }

fun List<WorkflowStep>.stepLists(): Sequence<List<WorkflowStep>> = sequence {
    yield(this@stepLists)
    for (step in this@stepLists) {
        when (step) {
            is IfElse -> {
                yieldAll(step.thenSteps.stepLists())
                yieldAll(step.elseSteps.stepLists())
            }

            is Repeat -> yieldAll(step.bodySteps.stepLists())

            is WhileStep -> yieldAll(step.bodySteps.stepLists())

            is ReusableSection -> yieldAll(step.bodySteps.stepLists())

            else -> Unit
        }
    }
}

/** Endpoints are identities; inserting earlier blocks never retargets a range. */
fun ReusableSection.resolveBody(steps: List<WorkflowStep>): List<WorkflowStep>? {
    if (!isLinked) return bodySteps
    return steps.resolveRange(requireNotNull(startStepId), requireNotNull(endStepId))
}

fun List<WorkflowStep>.resolveRange(startStepId: String, endStepId: String): List<WorkflowStep>? {
    val source = stepLists().firstOrNull { list -> list.any { it.id == startStepId && it !is ReusableSection } } ?: return null
    val start = source.indexOfFirst { it.id == startStepId }
    val end = source.indexOfFirst { it.id == endStepId && it !is ReusableSection }
    return if (end < start) null else source.subList(start, end + 1).filterNot { it is ReusableSection }
}

fun List<WorkflowStep>.cyclicSections(): Set<String> {
    val definitions = reusableSections()
    val visiting = linkedSetOf<String>()
    val complete = mutableSetOf<String>()
    val cycles = mutableSetOf<String>()
    for (root in definitions.keys) {
        val pending = java.util.ArrayDeque<Pair<String, Boolean>>()
        pending.addLast(root to false)
        while (pending.isNotEmpty()) {
            val (id, finished) = pending.removeLast()
            if (id in complete) continue
            if (finished) {
                visiting.remove(id)
                complete += id
                continue
            }
            if (!visiting.add(id)) {
                cycles += visiting.dropWhile { it != id }
                continue
            }
            pending.addLast(id to true)
            definitions[id]?.resolveBody(this)?.walkSteps()?.filterIsInstance<RunSection>()?.forEach { pending.addLast(it.sectionId to false) }
        }
    }
    return cycles
}

/** A section's temporary images belong to its body, never to its caller. */
fun List<WorkflowStep>.capturedImagesByStep(): Map<String, Map<String, List<CaptureImage>>> = buildMap {
    fun visit(steps: List<WorkflowStep>, captures: Map<String, List<CaptureImage>>) {
        steps.forEach { step ->
            put(step.id, captures)
            if (step is ReusableSection) visit(step.bodySteps, step.bodySteps.capturedImages()) else visit(step.childSteps(), captures)
        }
    }
    visit(this@capturedImagesByStep, this@capturedImagesByStep.capturedImages())
}

fun Condition.withCaptureNames(names: Map<VariableName, VariableName>): Condition {
    if (names.isEmpty()) return this
    fun name(value: VariableName?) = names[value] ?: value
    return when (this) {
        is ImagePresentCondition -> copy(capturedImage = name(capturedImage), capturedBackground = name(capturedBackground))
        is ImageAbsentCondition -> copy(capturedImage = name(capturedImage), capturedBackground = name(capturedBackground))
        is CapturedImageCondition -> copy(name = names[this.name] ?: this.name)
        is PixelColorCondition -> copy(capturedBackground = name(capturedBackground))
        is AndCondition -> copy(conditions = conditions.map { it.withCaptureNames(names) })
        is OrCondition -> copy(conditions = conditions.map { it.withCaptureNames(names) })
        is NotCondition -> copy(condition = condition.withCaptureNames(names))
        else -> this
    }
}
