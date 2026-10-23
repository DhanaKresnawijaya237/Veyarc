package com.veyarc.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("captureImage")
data class CaptureImage(
    override val id: String,
    val name: VariableName,
    val region: NormalizedRect,
    val backgroundAssetId: AssetId? = null,
) : WorkflowStep {
    init {
        ContractChecks.requireStableId(id, "Step id")
    }
}

@Serializable
@SerialName("capturedImage")
data class CapturedImageCondition(
    val name: VariableName,
    val region: NormalizedRect,
    val matchThreshold: Double,
    val present: Boolean = true,
    val backgroundAssetId: AssetId? = null,
) : Condition {
    init {
        require(matchThreshold.isFinite() && matchThreshold in 0.0..1.0) { "Match threshold must be between 0 and 1" }
    }
}

fun List<WorkflowStep>.capturedImages(): Map<String, List<CaptureImage>> = buildMap<String, MutableList<CaptureImage>> {
    fun visit(steps: List<WorkflowStep>) {
        steps.forEach { step ->
            when (step) {
                is CaptureImage -> getOrPut(step.name.value) { mutableListOf() }.add(step)

                is IfElse -> {
                    visit(step.thenSteps)
                    visit(step.elseSteps)
                }

                is Repeat -> visit(step.bodySteps)

                is WhileStep -> visit(step.bodySteps)

                else -> Unit
            }
        }
    }
    visit(this@capturedImages)
}

fun Condition.capturedImageName(): VariableName? = when (this) {
    is ImagePresentCondition -> capturedImage
    is ImageAbsentCondition -> capturedImage
    is CapturedImageCondition -> name
    else -> null
}

fun Condition.capturedBackgroundName(): VariableName? = when (this) {
    is ImagePresentCondition -> capturedBackground
    is ImageAbsentCondition -> capturedBackground
    is PixelColorCondition -> capturedBackground
    else -> null
}

/** Keep older revisions readable while editing all image sources in the same controls. */
fun Condition.integratedImageCondition(): Condition = when (this) {
    is CapturedImageCondition -> if (present) ImagePresentCondition(region = region, matchThreshold = matchThreshold, capturedImage = name, backgroundAssetId = backgroundAssetId) else ImageAbsentCondition(region = region, matchThreshold = matchThreshold, capturedImage = name, backgroundAssetId = backgroundAssetId)
    is AndCondition -> copy(conditions = conditions.map(Condition::integratedImageCondition))
    is OrCondition -> copy(conditions = conditions.map(Condition::integratedImageCondition))
    is NotCondition -> copy(condition = condition.integratedImageCondition())
    else -> this
}
