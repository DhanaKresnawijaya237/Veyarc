package com.veyarc.model

/** Returns whether a workflow needs a temporary MediaProjection frame during execution. */
fun WorkflowDocument.usesVisualConditions(entrySectionId: String? = null): Boolean {
    val sections = steps.reusableSections()
    val visited = mutableSetOf<String>()
    fun uses(items: List<WorkflowStep>): Boolean = items.any { step ->
        when (step) {
            is ReusableSection -> false
            is RunSection -> visited.add(step.sectionId) && sections[step.sectionId]?.resolveBody(steps)?.let(::uses) == true
            is CaptureImage, is FindImage -> true
            is WaitUntil -> step.condition.usesVisualCondition()
            is IfElse -> step.condition.usesVisualCondition() || uses(step.thenSteps) || uses(step.elseSteps)
            is WhileStep -> step.condition.usesVisualCondition() || uses(step.bodySteps)
            is Repeat -> uses(step.bodySteps)
            else -> false
        }
    }
    return uses(if (entrySectionId == null) steps else listOf(RunSection(entrySectionId, entrySectionId)))
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
