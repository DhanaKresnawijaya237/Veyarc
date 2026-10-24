package com.veyarc.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("section")
data class ReusableSection(override val id: String, val name: String, val bodySteps: List<WorkflowStep>) : WorkflowStep {
    init {
        ContractChecks.requireStableId(id, "Section id")
        require(name.isNotBlank() && name.length <= 80) { "Section name must be 1 to 80 characters" }
    }
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
