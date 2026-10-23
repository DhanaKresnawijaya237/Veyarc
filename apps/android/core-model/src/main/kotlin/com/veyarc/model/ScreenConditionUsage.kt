package com.veyarc.model

/** Returns whether a workflow needs a temporary MediaProjection frame during execution. */
fun WorkflowDocument.usesVisualConditions(): Boolean = steps.any(::stepUsesVisualCondition)

private fun stepUsesVisualCondition(step: WorkflowStep): Boolean = when (step) {
    is CaptureImage, is FindImage -> true

    is WaitUntil -> step.condition.usesVisualCondition()

    is IfElse -> step.condition.usesVisualCondition() ||
        step.thenSteps.any(::stepUsesVisualCondition) ||
        step.elseSteps.any(::stepUsesVisualCondition)

    is WhileStep -> step.condition.usesVisualCondition() || step.bodySteps.any(::stepUsesVisualCondition)

    is Repeat -> step.bodySteps.any(::stepUsesVisualCondition)

    else -> false
}

private fun Condition.usesVisualCondition(): Boolean = when (this) {
    is CapturedImageCondition,
    is PixelColorCondition,
    is ImagePresentCondition,
    is ImageAbsentCondition,
    -> true

    is AndCondition -> conditions.any { it.usesVisualCondition() }

    is OrCondition -> conditions.any { it.usesVisualCondition() }

    is NotCondition -> condition.usesVisualCondition()

    else -> false
}
