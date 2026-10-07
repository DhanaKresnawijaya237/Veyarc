package com.veyarc.feature.runner

import com.veyarc.engine.RunEvent
import com.veyarc.model.AndCondition
import com.veyarc.model.Condition
import com.veyarc.model.ForegroundPackageCondition
import com.veyarc.model.IfElse
import com.veyarc.model.ImageAbsentCondition
import com.veyarc.model.ImageMatchMode
import com.veyarc.model.ImagePresentCondition
import com.veyarc.model.Jump
import com.veyarc.model.LaunchApp
import com.veyarc.model.LongPress
import com.veyarc.model.NormalizedCoordinate
import com.veyarc.model.NotCondition
import com.veyarc.model.OrCondition
import com.veyarc.model.OrientationCondition
import com.veyarc.model.PixelColorCondition
import com.veyarc.model.Repeat
import com.veyarc.model.SetVariable
import com.veyarc.model.StageBoundary
import com.veyarc.model.StepResultCondition
import com.veyarc.model.Stop
import com.veyarc.model.SwipePath
import com.veyarc.model.TapPoint
import com.veyarc.model.TapRegion
import com.veyarc.model.VariableComparisonCondition
import com.veyarc.model.Wait
import com.veyarc.model.WaitUntil
import com.veyarc.model.WhileStep
import com.veyarc.model.WorkflowDocument
import com.veyarc.model.WorkflowStep
import com.veyarc.model.resolvePoint
import java.util.Locale
import kotlin.math.roundToInt

/** Produces local, human-readable run history without exposing variable values or screen pixels. */
internal class RunEventFormatter(private val document: WorkflowDocument) {
    private val stepsById = mutableMapOf<String, WorkflowStep>()
    private val conditionsByPath = mutableMapOf<String, Condition>()
    private val assetNames = document.assets.associate { it.assetId.value to (it.displayName ?: "Selected image") }

    init {
        indexSteps(document.steps)
    }

    fun format(event: RunEvent): String = when (event) {
        is RunEvent.Started -> "Run started"

        is RunEvent.StepStarted -> "${stepDescription(event.stepId)} started"

        is RunEvent.ConditionEvaluated -> conditionResult(event)

        is RunEvent.StepFinished ->
            "${stepName(event.stepId)} ${event.outcome.name.lowercase(Locale.ROOT)}"

        is RunEvent.Paused -> "Run paused"

        is RunEvent.Resumed -> "Run resumed"

        is RunEvent.Finished -> "Run finished: ${event.outcome.name.lowercase(Locale.ROOT)}"
    }

    private fun conditionResult(event: RunEvent.ConditionEvaluated): String {
        val condition = conditionsByPath[event.path]
        val base = condition?.let(::conditionName) ?: "Condition"
        val threshold = event.threshold ?: condition?.matchThresholdOrNull()
        if (threshold == null) return "$base: ${event.result}"

        val match = event.matchScore?.let(::formatPercent) ?: "unavailable"
        return "$base: ${event.result} · match $match · threshold ${formatPercent(threshold)}"
    }

    private fun stepDescription(stepId: String): String = when (val step = stepsById[stepId]) {
        is TapPoint -> "Tap at ${formatPoint(step.point)}"

        is TapRegion -> "Tap region at ${formatPoint(step.resolvePoint())}"

        is LongPress -> "Long press at ${formatPoint(step.point)} for ${step.durationMs.value} ms"

        is SwipePath ->
            "Swipe from ${formatPoint(step.points.first().point)} to ${formatPoint(step.points.last().point)} " +
                "with ${step.points.size} points over ${step.durationMs.value} ms"

        is Wait -> "Wait ${step.durationMs.value} ms"

        is StageBoundary -> "Stage boundary"

        is Jump -> "Continue from ${stepName(step.targetStepId)} up to ${step.maxVisits} times"

        is WaitUntil -> "Wait for ${conditionName(step.condition)}"

        is IfElse -> "If ${conditionName(step.condition)}"

        is Repeat -> "Repeat ${step.count} times"

        is WhileStep -> "While ${conditionName(step.condition)}"

        is LaunchApp -> "Launch ${step.packageName.value}"

        is Stop -> "Stop workflow"

        is SetVariable -> "Set variable ${step.name.value}"

        null -> "Workflow step"
    }

    private fun stepName(stepId: String): String = when (stepsById[stepId]) {
        is TapPoint -> "Tap"
        is TapRegion -> "Tap region"
        is LongPress -> "Long press"
        is SwipePath -> "Swipe"
        is Wait -> "Wait"
        is StageBoundary -> "Stage boundary"
        is Jump -> "Go to"
        is WaitUntil -> "Wait for condition"
        is IfElse -> "If branch"
        is Repeat -> "Repeat"
        is WhileStep -> "While loop"
        is LaunchApp -> "Launch app"
        is Stop -> "Stop workflow"
        is SetVariable -> "Set variable"
        null -> "Step"
    }

    private fun conditionName(condition: Condition): String = when (condition) {
        is PixelColorCondition -> "Pixel color at ${formatPoint(condition.point)}"

        is ImagePresentCondition -> when (condition.matchMode) {
            ImageMatchMode.SCREEN_STATE -> assetNames[condition.assetId.value]?.let { "Screen matches $it" } ?: "Screen state"
            ImageMatchMode.APPEARS_WITHIN_REGION -> "${assetNames[condition.assetId.value] ?: "Selected image"} appears"
        }

        is ImageAbsentCondition -> "${assetNames[condition.assetId.value] ?: "Selected image"} is absent"

        is ForegroundPackageCondition -> "Foreground package ${condition.packageName.value}"

        is OrientationCondition -> "Orientation ${condition.orientation.name.lowercase(Locale.ROOT)}"

        is VariableComparisonCondition -> "Variable comparison"

        is StepResultCondition -> "Step result ${condition.outcome.name.lowercase(Locale.ROOT)}"

        is AndCondition -> "All conditions"

        is OrCondition -> "Any condition"

        is NotCondition -> "Not ${conditionName(condition.condition)}"
    }

    private fun Condition.matchThresholdOrNull(): Double? = when (this) {
        is ImagePresentCondition -> matchThreshold
        is ImageAbsentCondition -> matchThreshold
        else -> null
    }

    private fun formatPoint(point: NormalizedCoordinate): String {
        val xPx = (point.x * (document.viewport.recordedWidthPx - 1)).roundToInt()
        val yPx = (point.y * (document.viewport.recordedHeightPx - 1)).roundToInt()
        return "($xPx, $yPx px recorded)"
    }

    private fun indexSteps(steps: List<WorkflowStep>) {
        steps.forEach { step ->
            stepsById[step.id] = step
            when (step) {
                is WaitUntil -> indexCondition(step.condition, "${step.id}/condition")

                is IfElse -> {
                    indexCondition(step.condition, "${step.id}/condition")
                    indexSteps(step.thenSteps)
                    indexSteps(step.elseSteps)
                }

                is Repeat -> indexSteps(step.bodySteps)

                is WhileStep -> {
                    indexCondition(step.condition, "${step.id}/condition")
                    indexSteps(step.bodySteps)
                }

                else -> Unit
            }
        }
    }

    private fun indexCondition(condition: Condition, path: String) {
        conditionsByPath[path] = condition
        when (condition) {
            is AndCondition -> condition.conditions.forEachIndexed { index, child ->
                indexCondition(child, "$path/conditions/$index")
            }

            is OrCondition -> condition.conditions.forEachIndexed { index, child ->
                indexCondition(child, "$path/conditions/$index")
            }

            is NotCondition -> indexCondition(condition.condition, "$path/condition")

            else -> Unit
        }
    }

    private companion object {
        fun formatPercent(value: Double): String = String.format(Locale.ROOT, "%.1f%%", value * 100.0)
    }
}
