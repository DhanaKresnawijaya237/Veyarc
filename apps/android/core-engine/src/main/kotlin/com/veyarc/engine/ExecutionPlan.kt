package com.veyarc.engine

import com.veyarc.model.AndCondition
import com.veyarc.model.AssetId
import com.veyarc.model.AssetReference
import com.veyarc.model.Condition
import com.veyarc.model.DurationMillis
import com.veyarc.model.ImageAbsentCondition
import com.veyarc.model.ImageMatchMode
import com.veyarc.model.ImagePresentCondition
import com.veyarc.model.NormalizedCoordinate
import com.veyarc.model.NormalizedRect
import com.veyarc.model.NotCondition
import com.veyarc.model.OrCondition
import com.veyarc.model.PackageName
import com.veyarc.model.RegionAnchor
import com.veyarc.model.RunLimits
import com.veyarc.model.ScalarValue
import com.veyarc.model.StopReason
import com.veyarc.model.TimedPoint
import com.veyarc.model.VariableDeclaration
import com.veyarc.model.ViewportSpec
import java.util.Collections

/** A platform-neutral feature that a runner or driver must support. */
enum class EngineCapability {
    TAP_POINT,
    TAP_REGION,
    LONG_PRESS,
    SWIPE_PATH,
    WAIT,
    STAGE_BOUNDARY,
    JUMP,
    WAIT_UNTIL,
    IF_ELSE,
    REPEAT,
    WHILE,
    LAUNCH_APP,
    STOP,
    SET_VARIABLE,
    CAPTURE_IMAGE,
    PIXEL_COLOR_CONDITION,
    IMAGE_TEMPLATE_CONDITION,
    FOREGROUND_PACKAGE_CONDITION,
    ORIENTATION_CONDITION,
    VARIABLE_COMPARISON_CONDITION,
    STEP_RESULT_CONDITION,
    LOGICAL_CONDITION,
}

/** The capabilities available to the compiler for a particular target. */
class EngineCapabilities(supported: Set<EngineCapability>) {
    val supported: Set<EngineCapability> = Collections.unmodifiableSet(supported.toSet())

    companion object {
        fun all(): EngineCapabilities = of(EngineCapability.values().toSet())

        fun none(): EngineCapabilities = of(emptySet())

        fun of(capabilities: Set<EngineCapability>): EngineCapabilities = EngineCapabilities(Collections.unmodifiableSet(capabilities.toSet()))
    }

    fun supports(capability: EngineCapability): Boolean = capability in supported

    override fun equals(other: Any?): Boolean = other is EngineCapabilities && supported == other.supported

    override fun hashCode(): Int = supported.hashCode()

    override fun toString(): String = "EngineCapabilities(supported=$supported)"
}

/** The static worst-case cost of executing a compiled workflow. */
data class ControlFlowAnalysis(
    val worstCaseStepCount: Long,
    val worstCaseRuntimeMs: Long,
    val maxNestingDepth: Int,
    val maxLoopBound: Int,
    val arithmeticOverflow: Boolean,
    val hasDynamicStepCount: Boolean = false,
) {
    val estimatedStepCount: Long? get() = worstCaseStepCount.takeUnless { hasDynamicStepCount || arithmeticOverflow }
    init {
        require(worstCaseStepCount >= 0) { "worstCaseStepCount must be nonnegative" }
        require(worstCaseRuntimeMs >= 0) { "worstCaseRuntimeMs must be nonnegative" }
        require(maxNestingDepth > 0) { "maxNestingDepth must be positive" }
        require(maxLoopBound >= 0) { "maxLoopBound must be nonnegative" }
    }
}

/** An immutable, executable representation of a validated workflow. */
data class ExecutionPlan(
    val workflowId: String,
    val workflowRevision: Int,
    val workflowName: String,
    val schemaVersion: Int,
    val allowedPackages: Set<PackageName>,
    val viewport: ViewportSpec,
    val runLimits: RunLimits,
    val variables: Map<String, VariableDeclaration>,
    val assets: List<AssetReference>,
    val steps: List<ExecutionStep>,
    val analysis: ControlFlowAnalysis,
)

enum class ImageAssetUse {
    FULL_SCREEN,
    CROPPED_TARGET,
}

/** Returns only the image assets that execution can reach, including nested conditions. */
fun ExecutionPlan.referencedImageAssets(): Map<AssetId, Set<ImageAssetUse>> {
    val referenced = linkedMapOf<AssetId, MutableSet<ImageAssetUse>>()

    fun collect(condition: Condition) {
        when (condition) {
            is ImagePresentCondition ->
                referenced
                    .getOrPut(condition.assetId) { linkedSetOf() }
                    .add(
                        if (condition.matchMode == ImageMatchMode.SCREEN_STATE) {
                            ImageAssetUse.FULL_SCREEN
                        } else {
                            ImageAssetUse.CROPPED_TARGET
                        },
                    )

            is ImageAbsentCondition ->
                referenced
                    .getOrPut(condition.assetId) { linkedSetOf() }
                    .add(ImageAssetUse.CROPPED_TARGET)

            is AndCondition -> condition.conditions.forEach(::collect)

            is OrCondition -> condition.conditions.forEach(::collect)

            is NotCondition -> collect(condition.condition)

            else -> Unit
        }
    }

    fun collect(steps: List<ExecutionStep>) {
        steps.forEach { step ->
            when (step) {
                is ExecutionStep.WaitUntil -> collect(step.condition)

                is ExecutionStep.IfElse -> {
                    collect(step.condition)
                    collect(step.thenSteps)
                    collect(step.elseSteps)
                }

                is ExecutionStep.Repeat -> collect(step.bodySteps)

                is ExecutionStep.While -> {
                    collect(step.condition)
                    collect(step.bodySteps)
                }

                else -> Unit
            }
        }
    }

    collect(steps)
    return immutableMap(referenced.mapValues { (_, uses) -> immutableSet(uses) })
}

/** Typed steps consumed by the deterministic runner. */
sealed interface ExecutionStep {
    val id: String

    data class CaptureImage(override val id: String, val name: com.veyarc.model.VariableName, val region: NormalizedRect) : ExecutionStep

    data class TapPoint(
        override val id: String,
        val point: NormalizedCoordinate,
    ) : ExecutionStep

    data class TapRegion(
        override val id: String,
        val region: NormalizedRect,
        val anchor: RegionAnchor,
        val resolvedPoint: NormalizedCoordinate,
    ) : ExecutionStep

    data class LongPress(
        override val id: String,
        val point: NormalizedCoordinate,
        val durationMs: DurationMillis,
    ) : ExecutionStep

    data class SwipePath(
        override val id: String,
        val durationMs: DurationMillis,
        val points: List<TimedPoint>,
    ) : ExecutionStep

    data class Wait(
        override val id: String,
        val durationMs: DurationMillis,
        val durationVariable: com.veyarc.model.VariableName? = null,
    ) : ExecutionStep

    data class StageBoundary(
        override val id: String,
    ) : ExecutionStep

    data class Jump(
        override val id: String,
        val targetStepId: String,
        val maxVisits: Int,
    ) : ExecutionStep

    data class WaitUntil(
        override val id: String,
        val condition: Condition,
        val timeoutMs: DurationMillis,
        val pollIntervalMs: DurationMillis,
    ) : ExecutionStep

    data class IfElse(
        override val id: String,
        val condition: Condition,
        val thenSteps: List<ExecutionStep>,
        val elseSteps: List<ExecutionStep>,
    ) : ExecutionStep

    data class Repeat(
        override val id: String,
        val count: Int,
        val bodySteps: List<ExecutionStep>,
        val countVariable: com.veyarc.model.VariableName? = null,
    ) : ExecutionStep

    data class While(
        override val id: String,
        val condition: Condition,
        val maxIterations: Int,
        val bodySteps: List<ExecutionStep>,
    ) : ExecutionStep

    data class LaunchApp(
        override val id: String,
        val packageName: PackageName,
        val foregroundTimeoutMs: DurationMillis,
    ) : ExecutionStep

    data class Stop(
        override val id: String,
        val reason: StopReason,
        val message: String?,
    ) : ExecutionStep

    data class SetVariable(
        override val id: String,
        val name: com.veyarc.model.VariableName,
        val value: ScalarValue,
        val source: com.veyarc.model.Operand? = null,
        val calculation: com.veyarc.model.VariableCalculation? = null,
    ) : ExecutionStep
}

/** Why compilation failed, without exposing platform-specific exceptions. */
enum class CompilationErrorCode {
    INVALID_WORKFLOW,
    UNSUPPORTED_CAPABILITY,
    LIMIT_EXCEEDED,
}

data class CompilationError(
    val code: CompilationErrorCode,
    val path: String,
    val message: String,
    val capability: EngineCapability? = null,
    val validationCode: com.veyarc.model.ValidationErrorCode? = null,
)

data class WorkflowCompilationResult(
    val plan: ExecutionPlan?,
    val errors: List<CompilationError>,
) {
    val isSuccess: Boolean
        get() = plan != null && errors.isEmpty()

    init {
        require((plan == null) == errors.isNotEmpty()) {
            "A compilation result must contain either a plan or errors"
        }
    }
}

class WorkflowCompilationException(
    val errors: List<CompilationError>,
) : Exception(
    errors.joinToString(separator = "; ") { "${it.path}: ${it.message}" },
)

internal fun <T> immutableList(values: List<T>): List<T> = Collections.unmodifiableList(values.toList())

internal fun <K, V> immutableMap(values: Map<K, V>): Map<K, V> = Collections.unmodifiableMap(values.toMap())

internal fun <T> immutableSet(values: Set<T>): Set<T> = Collections.unmodifiableSet(values.toSet())
