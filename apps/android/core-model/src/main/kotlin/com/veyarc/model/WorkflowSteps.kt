@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.veyarc.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@Serializable
data class TimedPoint(
    val point: NormalizedCoordinate,
    val offsetMs: DurationMillis,
)

@Serializable
@JsonClassDiscriminator("type")
sealed interface WorkflowStep {
    val id: String
}

@Serializable
@SerialName("tapPoint")
data class TapPoint(
    override val id: String,
    val point: NormalizedCoordinate,
    val backgroundAssetId: AssetId? = null,
) : WorkflowStep {
    init {
        ContractChecks.requireStableId(id, "Step id")
    }
}

@Serializable
@SerialName("tapRegion")
data class TapRegion(
    override val id: String,
    val region: NormalizedRect,
    val anchor: RegionAnchor = RegionAnchor.Center,
    val backgroundAssetId: AssetId? = null,
) : WorkflowStep {
    init {
        ContractChecks.requireStableId(id, "Step id")
    }
}

fun TapRegion.resolvePoint(): NormalizedCoordinate = when (val selectedAnchor = anchor) {
    RegionAnchor.Center -> NormalizedCoordinate(region.x + region.width / 2.0, region.y + region.height / 2.0)

    is RegionAnchor.Offset ->
        NormalizedCoordinate(
            region.x + region.width * selectedAnchor.x,
            region.y + region.height * selectedAnchor.y,
        )
}

@Serializable
@SerialName("longPress")
data class LongPress(
    override val id: String,
    val point: NormalizedCoordinate,
    val durationMs: DurationMillis,
    val backgroundAssetId: AssetId? = null,
) : WorkflowStep {
    init {
        ContractChecks.requireStableId(id, "Step id")
        require(durationMs.value > 0) { "Long press duration must be positive" }
    }
}

@Serializable
@SerialName("swipePath")
data class SwipePath(
    override val id: String,
    val durationMs: DurationMillis,
    val points: List<TimedPoint>,
    val backgroundAssetId: AssetId? = null,
) : WorkflowStep {
    init {
        ContractChecks.requireStableId(id, "Step id")
        require(durationMs.value > 0) { "Swipe duration must be positive" }
        require(points.size >= 2) { "Swipe path must contain at least two points" }
        require(points.first().offsetMs.value == 0L) { "Swipe path must start at offset 0" }
        require(points.last().offsetMs.value == durationMs.value) {
            "Swipe path must end at its declared duration"
        }
        require(points.zipWithNext().all { (first, second) -> first.offsetMs.value < second.offsetMs.value }) {
            "Swipe path offsets must be strictly increasing"
        }
    }
}

@Serializable
@SerialName("wait")
data class Wait(
    override val id: String,
    val durationMs: DurationMillis,
    val durationVariable: VariableName? = null,
) : WorkflowStep {
    init {
        ContractChecks.requireStableId(id, "Step id")
    }
}

@Serializable
@SerialName("stageBoundary")
data class StageBoundary(
    override val id: String,
) : WorkflowStep {
    init {
        ContractChecks.requireStableId(id, "Step id")
    }
}

@Serializable
@SerialName("jump")
data class Jump(
    override val id: String,
    val targetStepId: String,
    val maxVisits: Int,
) : WorkflowStep {
    init {
        ContractChecks.requireStableId(id, "Step id")
        ContractChecks.requireStableId(targetStepId, "Jump target id")
        require(maxVisits > 0) { "Jump visit count must be positive" }
    }
}

@Serializable
@SerialName("waitUntil")
data class WaitUntil(
    override val id: String,
    val condition: Condition,
    val timeoutMs: DurationMillis,
    val pollIntervalMs: DurationMillis,
) : WorkflowStep {
    init {
        ContractChecks.requireStableId(id, "Step id")
        require(pollIntervalMs.value > 0) { "Polling interval must be positive" }
    }
}

@Serializable
@SerialName("if")
data class IfElse(
    override val id: String,
    val condition: Condition,
    val thenSteps: List<WorkflowStep>,
    val elseSteps: List<WorkflowStep>,
) : WorkflowStep {
    init {
        ContractChecks.requireStableId(id, "Step id")
    }
}

@Serializable
@SerialName("repeat")
data class Repeat(
    override val id: String,
    val count: Int,
    val bodySteps: List<WorkflowStep>,
    val countVariable: VariableName? = null,
) : WorkflowStep {
    init {
        ContractChecks.requireStableId(id, "Step id")
        require(count >= 0) { "Repeat count must be nonnegative" }
    }
}

@Serializable
@SerialName("while")
data class WhileStep(
    override val id: String,
    val condition: Condition,
    val maxIterations: Int,
    val bodySteps: List<WorkflowStep>,
) : WorkflowStep {
    init {
        ContractChecks.requireStableId(id, "Step id")
        require(maxIterations > 0) { "While maxIterations must be positive" }
    }
}

@Serializable
@SerialName("launchApp")
data class LaunchApp(
    override val id: String,
    val packageName: PackageName,
    val foregroundTimeoutMs: DurationMillis,
) : WorkflowStep {
    init {
        ContractChecks.requireStableId(id, "Step id")
        require(foregroundTimeoutMs.value > 0) { "Foreground timeout must be positive" }
    }
}

@Serializable
@SerialName("stop")
data class Stop(
    override val id: String,
    val reason: StopReason,
    val message: String? = null,
) : WorkflowStep {
    init {
        ContractChecks.requireStableId(id, "Step id")
        require(message == null || message.length <= 512) { "Stop message must be at most 512 characters" }
    }
}

@Serializable
@SerialName("setVariable")
data class SetVariable(
    override val id: String,
    val name: VariableName,
    val value: ScalarValue,
    val source: Operand? = null,
    val calculation: VariableCalculation? = null,
) : WorkflowStep {
    init {
        ContractChecks.requireStableId(id, "Step id")
    }
}

@Serializable
@JsonClassDiscriminator("type")
sealed interface Condition

@Serializable
@SerialName("pixelColor")
data class PixelColorCondition(
    val point: NormalizedCoordinate,
    val expectedArgb: Long,
    val tolerance: Int,
    val region: NormalizedRect? = null,
    val backgroundAssetId: AssetId? = null,
) : Condition {
    init {
        require(expectedArgb in 0L..0xFFFF_FFFFL) { "ARGB color must fit an unsigned 32-bit value" }
        require(tolerance in 0..255) { "Color tolerance must be between 0 and 255" }
    }
}

@Serializable
@SerialName("imagePresent")
data class ImagePresentCondition(
    val assetId: AssetId,
    val region: NormalizedRect,
    val matchThreshold: Double,
    val matchMode: ImageMatchMode = ImageMatchMode.APPEARS_WITHIN_REGION,
    val backgroundAssetId: AssetId? = null,
) : Condition {
    init {
        ContractChecks.requireFinite(matchThreshold, "matchThreshold")
        require(matchThreshold in 0.0..1.0) { "Match threshold must be between 0 and 1" }
    }
}

@Serializable
enum class ImageMatchMode {
    @SerialName("appearsWithinRegion")
    APPEARS_WITHIN_REGION,

    @SerialName("screenState")
    SCREEN_STATE,
}

@Serializable
@SerialName("imageAbsent")
data class ImageAbsentCondition(
    val assetId: AssetId,
    val region: NormalizedRect,
    val matchThreshold: Double,
    val backgroundAssetId: AssetId? = null,
) : Condition {
    init {
        ContractChecks.requireFinite(matchThreshold, "matchThreshold")
        require(matchThreshold in 0.0..1.0) { "Match threshold must be between 0 and 1" }
    }
}

@Serializable
@SerialName("foregroundPackage")
data class ForegroundPackageCondition(val packageName: PackageName) : Condition

@Serializable
@SerialName("orientation")
data class OrientationCondition(val orientation: DisplayOrientation) : Condition

@Serializable
@SerialName("variableComparison")
data class VariableComparisonCondition(
    val left: Operand,
    val operator: ComparisonOperator,
    val right: Operand,
) : Condition

@Serializable
@SerialName("stepResult")
data class StepResultCondition(
    val stepId: String,
    val outcome: StepOutcome,
) : Condition {
    init {
        ContractChecks.requireStableId(stepId, "Referenced step id")
    }
}

@Serializable
@SerialName("and")
data class AndCondition(val conditions: List<Condition>) : Condition

@Serializable
@SerialName("or")
data class OrCondition(val conditions: List<Condition>) : Condition

@Serializable
@SerialName("not")
data class NotCondition(val condition: Condition) : Condition

/** Reference images belong to the editor; they never change touch coordinates or image matching. */
fun WorkflowStep.backgroundAssetId(): AssetId? = when (this) {
    is CaptureImage -> backgroundAssetId
    is TapPoint -> backgroundAssetId
    is TapRegion -> backgroundAssetId
    is LongPress -> backgroundAssetId
    is SwipePath -> backgroundAssetId
    else -> null
}

fun WorkflowStep.withBackgroundAsset(id: AssetId?): WorkflowStep = when (this) {
    is CaptureImage -> copy(backgroundAssetId = id)
    is TapPoint -> copy(backgroundAssetId = id)
    is TapRegion -> copy(backgroundAssetId = id)
    is LongPress -> copy(backgroundAssetId = id)
    is SwipePath -> copy(backgroundAssetId = id)
    else -> this
}

fun Condition.backgroundAssetId(): AssetId? = when (this) {
    is CapturedImageCondition -> backgroundAssetId
    is PixelColorCondition -> backgroundAssetId
    is ImagePresentCondition -> backgroundAssetId
    is ImageAbsentCondition -> backgroundAssetId
    else -> null
}
