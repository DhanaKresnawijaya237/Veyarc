@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.veyarc.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import java.util.UUID

object WorkflowIds {
    fun newUuid(): String = UUID.randomUUID().toString()
}

internal object ContractChecks {
    private val variableNamePattern = Regex("[a-z][a-z0-9_]{0,63}")
    private val packageNamePattern = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")

    fun requireStableId(value: String, fieldName: String) {
        require(runCatching { UUID.fromString(value) }.isSuccess) {
            "$fieldName must be a UUID"
        }
    }

    fun requireVariableName(value: String) {
        require(variableNamePattern.matches(value)) {
            "Variable names must match [a-z][a-z0-9_]{0,63}"
        }
    }

    fun requirePackageName(value: String) {
        require(value.length <= 255 && packageNamePattern.matches(value)) {
            "Invalid Android package name"
        }
    }

    fun requireAssetId(value: String) {
        require(value.isNotBlank() && value.length <= 128 && value.all { it.isLetterOrDigit() || it in "._-" }) {
            "Asset IDs must be 1 to 128 ASCII-safe characters"
        }
    }

    fun requireFinite(value: Double, fieldName: String) {
        require(value.isFinite()) { "$fieldName must be finite" }
    }
}

@JvmInline
@Serializable
value class DurationMillis(val value: Long) {
    init {
        require(value >= 0) { "Duration must be nonnegative" }
    }
}

@JvmInline
@Serializable
value class PackageName(val value: String) {
    init {
        ContractChecks.requirePackageName(value)
    }
}

@JvmInline
@Serializable
value class VariableName(val value: String) {
    init {
        ContractChecks.requireVariableName(value)
    }
}

@JvmInline
@Serializable
value class AssetId(val value: String) {
    init {
        ContractChecks.requireAssetId(value)
    }
}

@Serializable
data class NormalizedCoordinate(
    val x: Double,
    val y: Double,
) {
    init {
        ContractChecks.requireFinite(x, "x")
        ContractChecks.requireFinite(y, "y")
        require(x in 0.0..1.0) { "x must be between 0 and 1" }
        require(y in 0.0..1.0) { "y must be between 0 and 1" }
    }
}

@Serializable
data class NormalizedRect(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
) {
    init {
        ContractChecks.requireFinite(x, "x")
        ContractChecks.requireFinite(y, "y")
        ContractChecks.requireFinite(width, "width")
        ContractChecks.requireFinite(height, "height")
        require(x in 0.0..1.0 && y in 0.0..1.0) { "Rectangle origin must be between 0 and 1" }
        require(width > 0.0 && height > 0.0) { "Rectangle dimensions must be positive" }
        require(x + width <= 1.0 && y + height <= 1.0) { "Rectangle must fit inside the display" }
    }

    fun contains(point: NormalizedCoordinate): Boolean = point.x >= x && point.x <= x + width && point.y >= y && point.y <= y + height
}

@Serializable
sealed interface RegionAnchor {
    @Serializable
    @SerialName("center")
    data object Center : RegionAnchor

    @Serializable
    @SerialName("offset")
    data class Offset(
        val x: Double,
        val y: Double,
    ) : RegionAnchor {
        init {
            ContractChecks.requireFinite(x, "anchor.x")
            ContractChecks.requireFinite(y, "anchor.y")
            require(x in 0.0..1.0 && y in 0.0..1.0) { "Anchor offset must be between 0 and 1" }
        }
    }
}

@Serializable
enum class DisplayOrientation {
    @SerialName("portrait")
    PORTRAIT,

    @SerialName("landscape")
    LANDSCAPE,
}

@Serializable
enum class CoordinateSpace {
    @SerialName("fullDisplayNormalized")
    FULL_DISPLAY_NORMALIZED,
}

@Serializable
enum class OrientationMode {
    @SerialName("currentScreenRelative")
    CURRENT_SCREEN_RELATIVE,
}

@Serializable
enum class WindowMode {
    @SerialName("fullDisplayOnly")
    FULL_DISPLAY_ONLY,
}

@Serializable
data class ViewportSpec(
    val coordinateSpace: CoordinateSpace = CoordinateSpace.FULL_DISPLAY_NORMALIZED,
    val orientationMode: OrientationMode = OrientationMode.CURRENT_SCREEN_RELATIVE,
    val windowMode: WindowMode = WindowMode.FULL_DISPLAY_ONLY,
    val recordedWidthPx: Int,
    val recordedHeightPx: Int,
    val recordedDensityDpi: Int,
    val recordedOrientation: DisplayOrientation,
) {
    init {
        require(recordedWidthPx > 0) { "Recorded width must be positive" }
        require(recordedHeightPx > 0) { "Recorded height must be positive" }
        require(recordedDensityDpi > 0) { "Recorded density must be positive" }
    }
}

@Serializable
data class RunLimits(
    val maxTotalSteps: Int,
    val maxRuntimeMs: Long,
    val maxLoopIterations: Int,
    val maxNestingDepth: Int,
    val maxPathPoints: Int,
    val maxStepDurationMs: Long,
) {
    init {
        require(maxTotalSteps > 0) { "maxTotalSteps must be positive" }
        require(maxRuntimeMs > 0) { "maxRuntimeMs must be positive" }
        require(maxLoopIterations > 0) { "maxLoopIterations must be positive" }
        require(maxNestingDepth > 0) { "maxNestingDepth must be positive" }
        require(maxPathPoints >= 2) { "maxPathPoints must be at least 2" }
        require(maxStepDurationMs > 0) { "maxStepDurationMs must be positive" }
    }
}

@Serializable
enum class AssetKind {
    @SerialName("imageTemplate")
    IMAGE_TEMPLATE,
}

@Serializable
data class AssetReference(
    val assetId: AssetId,
    val kind: AssetKind,
    val displayName: String? = null,
    val widthPx: Int? = null,
    val heightPx: Int? = null,
) {
    init {
        require((widthPx == null) == (heightPx == null)) { "Image dimensions must be supplied together" }
        require(widthPx == null || (widthPx > 0 && heightPx!! > 0)) { "Image dimensions must be positive" }
    }
}

@Serializable
enum class VariableType {
    @SerialName("boolean")
    BOOLEAN,

    @SerialName("number")
    NUMBER,

    @SerialName("string")
    STRING,
}

@Serializable
@JsonClassDiscriminator("type")
sealed interface ScalarValue

@Serializable
@SerialName("boolean")
data class BooleanValue(val value: Boolean) : ScalarValue

@Serializable
@SerialName("number")
data class NumberValue(val value: Double) : ScalarValue {
    init {
        ContractChecks.requireFinite(value, "value")
    }
}

@Serializable
@SerialName("string")
data class StringValue(val value: String) : ScalarValue {
    init {
        require(value.length <= 4096) { "String values must be at most 4096 characters" }
    }
}

fun ScalarValue.variableType(): VariableType = when (this) {
    is BooleanValue -> VariableType.BOOLEAN
    is NumberValue -> VariableType.NUMBER
    is StringValue -> VariableType.STRING
}

@Serializable
data class VariableDeclaration(
    val type: VariableType,
    val initialValue: ScalarValue,
    val choices: List<String> = emptyList(),
) {
    init {
        require(type == initialValue.variableType()) { "Variable type must match its initial value" }
        require(choices.isEmpty() || type == VariableType.STRING) { "Only text variables can have review choices" }
        require(choices.all { it.isNotBlank() && it.length <= 4096 }) { "Choices must contain 1 to 4096 characters of text" }
        require(choices.distinct().size == choices.size) { "Choices must be unique" }
        require(choices.isEmpty() || (initialValue as StringValue).value in choices) { "Choose a listed option as the starting value" }
    }
}

@Serializable
@JsonClassDiscriminator("type")
sealed interface Operand

@Serializable
@SerialName("literal")
data class LiteralOperand(val value: ScalarValue) : Operand

@Serializable
@SerialName("variable")
data class VariableOperand(val name: VariableName) : Operand

@Serializable
enum class ArithmeticOperator {
    @SerialName("add")
    ADD,

    @SerialName("subtract")
    SUBTRACT,

    @SerialName("multiply")
    MULTIPLY,

    @SerialName("divide")
    DIVIDE,

    @SerialName("remainder")
    REMAINDER,
}

@Serializable
data class VariableCalculation(val operator: ArithmeticOperator, val operand: Operand)

@Serializable
enum class ComparisonOperator {
    @SerialName("equals")
    EQUALS,

    @SerialName("notEquals")
    NOT_EQUALS,

    @SerialName("lessThan")
    LESS_THAN,

    @SerialName("lessThanOrEqual")
    LESS_THAN_OR_EQUAL,

    @SerialName("greaterThan")
    GREATER_THAN,

    @SerialName("greaterThanOrEqual")
    GREATER_THAN_OR_EQUAL,
}

@Serializable
enum class StepOutcome {
    @SerialName("completed")
    COMPLETED,

    @SerialName("failed")
    FAILED,

    @SerialName("cancelled")
    CANCELLED,

    @SerialName("timedOut")
    TIMED_OUT,

    @SerialName("stopped")
    STOPPED,

    @SerialName("notExecuted")
    NOT_EXECUTED,
}

@Serializable
enum class StopReason {
    @SerialName("userRequested")
    USER_REQUESTED,

    @SerialName("safety")
    SAFETY,

    @SerialName("workflow")
    WORKFLOW,

    @SerialName("condition")
    CONDITION,
}
