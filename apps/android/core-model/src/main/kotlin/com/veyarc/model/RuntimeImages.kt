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
    is ImagePresentCondition -> copy(capturedBackground = null)
    is ImageAbsentCondition -> copy(capturedBackground = null)
    is PixelColorCondition -> copy(capturedBackground = null)
    is AndCondition -> copy(conditions = conditions.map(Condition::integratedImageCondition))
    is OrCondition -> copy(conditions = conditions.map(Condition::integratedImageCondition))
    is NotCondition -> copy(condition = condition.integratedImageCondition())
    else -> this
}

fun CaptureImage.isFullScreenCapture(): Boolean = region.x == 0.0 && region.y == 0.0 && region.width == 1.0 && region.height == 1.0

fun Condition.capturedTargetIssue(captures: Map<String, List<CaptureImage>>, width: Int, height: Int): String? {
    if (this is AndCondition) return conditions.firstNotNullOfOrNull { it.capturedTargetIssue(captures, width, height) }
    if (this is OrCondition) return conditions.firstNotNullOfOrNull { it.capturedTargetIssue(captures, width, height) }
    if (this is NotCondition) return condition.capturedTargetIssue(captures, width, height)
    val name = capturedImageName() ?: return null
    val definitions = captures[name.value].orEmpty()
    if (definitions.isEmpty()) return "Add a Capture image block for ${name.value}."
    val screenState = this is ImagePresentCondition && matchMode == ImageMatchMode.SCREEN_STATE
    if (definitions.any { it.isFullScreenCapture() != screenState }) return if (screenState) "Screen state checks need a full-screen capture." else "Full-screen captures can only be used for screen state checks."
    if (screenState) return null
    val search = when (this) {
        is ImagePresentCondition -> region
        is ImageAbsentCondition -> region
        is CapturedImageCondition -> region
        else -> return null
    }
    fun span(start: Double, size: Double, pixels: Int) = kotlin.math.ceil((start + size) * pixels).toInt().coerceAtMost(pixels) - kotlin.math.floor(start * pixels).toInt()
    val sw = span(search.x, search.width, width)
    val sh = span(search.y, search.height, height)
    return definitions.firstNotNullOfOrNull {
        val cw = span(it.region.x, it.region.width, width)
        val ch = span(it.region.y, it.region.height, height)
        if (cw > sw || ch > sh) "Capture is $cw x $ch px. Enlarge the $sw x $sh px search area." else null
    }
}
