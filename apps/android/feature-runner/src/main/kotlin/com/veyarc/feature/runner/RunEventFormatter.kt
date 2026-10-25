package com.veyarc.feature.runner

import com.veyarc.engine.RunEvent
import com.veyarc.engine.StepCompletionDetails
import com.veyarc.model.AndCondition
import com.veyarc.model.BooleanValue
import com.veyarc.model.CaptureImage
import com.veyarc.model.CapturedImageCondition
import com.veyarc.model.ComparisonOperator
import com.veyarc.model.Condition
import com.veyarc.model.ForegroundPackageCondition
import com.veyarc.model.IfElse
import com.veyarc.model.ImageAbsentCondition
import com.veyarc.model.ImageMatchMode
import com.veyarc.model.ImagePresentCondition
import com.veyarc.model.ImageResultField
import com.veyarc.model.ImageResultOperand
import com.veyarc.model.Jump
import com.veyarc.model.LaunchApp
import com.veyarc.model.LiteralOperand
import com.veyarc.model.LongPress
import com.veyarc.model.NormalizedCoordinate
import com.veyarc.model.NotCondition
import com.veyarc.model.NumberValue
import com.veyarc.model.Operand
import com.veyarc.model.OrCondition
import com.veyarc.model.OrientationCondition
import com.veyarc.model.PixelColorCondition
import com.veyarc.model.Repeat
import com.veyarc.model.ReusableSection
import com.veyarc.model.RunSection
import com.veyarc.model.ScalarValue
import com.veyarc.model.SetVariable
import com.veyarc.model.StageBoundary
import com.veyarc.model.StepResultCondition
import com.veyarc.model.Stop
import com.veyarc.model.StringValue
import com.veyarc.model.SwipePath
import com.veyarc.model.TapPoint
import com.veyarc.model.TapRegion
import com.veyarc.model.VariableComparisonCondition
import com.veyarc.model.VariableOperand
import com.veyarc.model.Wait
import com.veyarc.model.WaitUntil
import com.veyarc.model.WhileStep
import com.veyarc.model.WorkflowDocument
import com.veyarc.model.WorkflowStep
import com.veyarc.model.isFullScreenCapture
import com.veyarc.model.resolvePoint
import com.veyarc.model.reusableSections
import java.util.Locale
import kotlin.math.roundToInt

/** Produces local run history with bounded value summaries, never screen captures. */
internal class RunEventFormatter(private val document: WorkflowDocument) {
    private val sections = document.steps.reusableSections()
    private val stepsById = mutableMapOf<String, WorkflowStep>()
    private val blockLabels = mutableMapOf<String, String>()
    private val blockContexts = mutableMapOf<String, String>()
    private val conditionLocations = mutableMapOf<String, String>()
    private val conditionsByPath = mutableMapOf<String, Condition>()
    private val assetNames = document.assets.associate { it.assetId.value to (it.displayName ?: "Selected image") }

    init {
        indexSteps(document.steps)
    }

    fun startingValues(values: Map<String, ScalarValue>, atMs: Long): List<RedactedRunEvent> = values.mapNotNull { (name, value) ->
        if (value == document.variables[name]?.initialValue) null else RedactedRunEvent(atMs, "$name: ${scalarText(value)}", "Starting values", "Workflow")
    }

    fun event(event: RunEvent): RedactedRunEvent {
        val stepId = when (event) {
            is RunEvent.StepStarted -> event.stepId
            is RunEvent.StepFinished -> event.stepId
            is RunEvent.ConditionEvaluated -> event.stepId
            else -> null
        }
        val conditionLocation = (event as? RunEvent.ConditionEvaluated)?.path?.let(conditionLocations::get)
        val callId = when (event) {
            is RunEvent.StepStarted -> event.sectionCallId
            is RunEvent.StepFinished -> event.sectionCallId
            is RunEvent.ConditionEvaluated -> event.sectionCallId
            else -> null
        }
        val callPath = when (event) {
            is RunEvent.StepStarted -> event.sectionCallPath
            is RunEvent.StepFinished -> event.sectionCallPath
            is RunEvent.ConditionEvaluated -> event.sectionCallPath
            else -> emptyList()
        }.ifEmpty { listOfNotNull(callId) }
        val bodyContext = stepId?.let(blockContexts::get)?.let { if (callPath.isEmpty()) it else it.removePrefix("Workflow").removePrefix(" › ").takeIf(String::isNotBlank) }
        val context = (
            listOfNotNull(callPath.firstOrNull()?.let(blockContexts::get)) + callPath.mapNotNull(blockLabels::get) +
                listOfNotNull(bodyContext, conditionLocation?.takeIf(String::isNotBlank))
            ).joinToString(" › ").ifBlank { null }
        return RedactedRunEvent(event.atMs, format(event), stepId?.let(blockLabels::get), context)
    }

    fun format(event: RunEvent): String = when (event) {
        is RunEvent.Started -> "Run started"

        is RunEvent.StepStarted -> "${stepDescription(event.stepId)} started"

        is RunEvent.ConditionEvaluated -> {
            val decision = if (event.path == "${event.stepId}/condition") {
                when (stepsById[event.stepId]) {
                    is IfElse -> if (event.result) "Following If true" else "Following If false"
                    is WhileStep -> if (event.result) "Continuing loop" else "Leaving loop"
                    else -> null
                }
            } else {
                null
            }
            listOfNotNull(conditionResult(event), decision).joinToString("\n")
        }

        is RunEvent.StepFinished -> completionResult(event)

        is RunEvent.Paused -> "Run paused"

        is RunEvent.Resumed -> "Run resumed"

        is RunEvent.Finished -> "Run finished: ${event.outcome.name.lowercase(Locale.ROOT)}"
    }

    private fun completionResult(event: RunEvent.StepFinished): String {
        val step = stepsById[event.stepId]
        if (step is RunSection) return "${sections[step.sectionId]?.name ?: "Section"} ${event.outcome.name.lowercase(Locale.ROOT)}"
        when (val detail = event.details) {
            is StepCompletionDetails.GestureResult -> {
                val point = "At (${detail.point.x.roundToInt()}, ${detail.point.y.roundToInt()} px on screen)"
                return if (step is LongPress) "Hold completed\n${detail.durationMs} ms · $point" else "Tap completed\n$point"
            }

            is StepCompletionDetails.VariableChange -> if (step is SetVariable) {
                val before = detail.before?.let(::scalarText) ?: "Not set"
                return "${step.name.value} updated\n$before → ${scalarText(detail.after)}"
            }

            is StepCompletionDetails.WaitDuration -> if (step is Wait) {
                return "Wait completed\n${step.durationVariable?.value}: ${detail.durationMs} ms"
            }

            is StepCompletionDetails.LoopResult -> {
                val count = "${detail.iterations} ${if (detail.iterations == 1) "repeat" else "repeats"}"
                if (step is WhileStep) return "Loop finished · $count\n${if (detail.limitReached) "Repeat limit reached" else "Condition not met"}"
                if (step is Repeat) return "Repeat finished · $count" + step.countVariable?.let { "\n${it.value} was ${detail.iterations} when the loop started" }.orEmpty()
            }

            null -> Unit
        }
        if (step is CaptureImage && event.outcome == com.veyarc.model.StepOutcome.COMPLETED) {
            return "${step.name.value} captured\n${if (step.isFullScreenCapture()) "Full screen" else "Cropped area"} · Available for this run"
        }
        return "${stepName(event.stepId)} ${event.outcome.name.lowercase(Locale.ROOT)}"
    }

    private fun conditionResult(event: RunEvent.ConditionEvaluated): String {
        val condition = conditionsByPath[event.path]
        if (condition is VariableComparisonCondition) {
            val values = event.comparison
            val expression = "${operandText(condition.left, values?.left)} ${operatorText(condition.operator)} ${operandText(condition.right, values?.right)}"
            return "Condition ${if (event.result) "met" else "not met"}\n$expression"
        }
        (stepsById[event.stepId] as? com.veyarc.model.FindImage)?.let { search ->
            return "${search.name.value} · ${if (event.result) "Found" else "Not found"}\nScore ${event.matchScore?.let(::formatPercent) ?: "unavailable"} · Threshold ${formatPercent(search.image.matchThreshold)}" + event.matchedArea?.let { "\nCenter ${formatPoint(com.veyarc.model.NormalizedCoordinate(it.x + it.width / 2, it.y + it.height / 2))}" }.orEmpty()
        }
        event.colorMatch?.let { match ->
            val location = (condition as? PixelColorCondition)?.let { if (it.region == null) " at ${formatPoint(it.point)}" else " in the search area" }.orEmpty()
            val closest = String.format(Locale.ROOT, "#%08X", match.closestArgb)
            val target = String.format(Locale.ROOT, "#%08X", match.targetArgb)
            return "Colour ${if (event.result) "found" else "not found"}$location\n" +
                "Closest $closest · Target $target\n" +
                "Difference ${match.difference} · Tolerance ±${match.tolerance}"
        }
        val base = condition?.let(::conditionName) ?: "Condition"
        val threshold = event.threshold ?: condition?.matchThresholdOrNull()
        if (threshold == null) return "$base: ${event.result}"

        val score = event.matchScore
        val imageNotFound = (condition is CapturedImageCondition && event.result != condition.present) ||
            (condition is ImageAbsentCondition && event.result) ||
            (condition is ImagePresentCondition && !event.result)
        val match = when {
            score != null && imageNotFound && (condition as? ImagePresentCondition)?.matchMode != ImageMatchMode.SCREEN_STATE ->
                "best checked match ${formatPercent(score)}"

            score != null -> "match ${formatPercent(score)}"

            imageNotFound -> "image not found · score unavailable"

            else -> "score not reported"
        }
        return "$base: ${event.result} · $match · threshold ${formatPercent(threshold)}"
    }

    private fun operandText(operand: Operand, value: ScalarValue?): String = when (operand) {
        is LiteralOperand -> scalarText(value ?: operand.value)
        is VariableOperand -> operand.name.value + value?.let { " (${scalarText(it)})" }.orEmpty()
        is ImageResultOperand -> "${operand.name.value} ${if (operand.field == ImageResultField.FOUND) "found" else "score"}" + value?.let { " (${scalarText(it)})" }.orEmpty()
    }

    private fun scalarText(value: ScalarValue): String = when (value) {
        is BooleanValue -> if (value.value) "True" else "False"

        is NumberValue -> java.math.BigDecimal.valueOf(value.value).stripTrailingZeros().toPlainString()

        is StringValue -> {
            val text = value.value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t")
            "\"${if (text.length > 160) text.take(159) + "…" else text}\""
        }
    }

    private fun operatorText(operator: ComparisonOperator): String = when (operator) {
        ComparisonOperator.EQUALS -> "="
        ComparisonOperator.NOT_EQUALS -> "≠"
        ComparisonOperator.LESS_THAN -> "<"
        ComparisonOperator.LESS_THAN_OR_EQUAL -> "≤"
        ComparisonOperator.GREATER_THAN -> ">"
        ComparisonOperator.GREATER_THAN_OR_EQUAL -> "≥"
    }

    private fun stepDescription(stepId: String): String = when (val step = stepsById[stepId]) {
        is ReusableSection -> "Section ${step.name}"

        is RunSection -> "Run ${sections[step.sectionId]?.name ?: "section"}"

        is TapPoint -> step.imagePosition?.let { "Tap ${it.name.value} / Offset ${it.offsetXVariable?.value ?: it.offsetXPx}, ${it.offsetYVariable?.value ?: it.offsetYPx} px" } ?: "Tap at ${formatPoint(step.point)}"

        is TapRegion -> "Tap region at ${formatPoint(step.resolvePoint())}"

        is LongPress -> step.imagePosition?.let { "Hold ${it.name.value} for ${step.durationVariable?.value ?: step.durationMs.value} ms / Offset ${it.offsetXVariable?.value ?: it.offsetXPx}, ${it.offsetYVariable?.value ?: it.offsetYPx} px" } ?: "Long press at ${formatPoint(step.point)} for ${step.durationVariable?.value ?: step.durationMs.value} ms"

        is SwipePath ->
            "Swipe from ${formatPoint(step.points.first().point)} to ${formatPoint(step.points.last().point)} " +
                "with ${step.points.size} points over ${step.durationMs.value} ms"

        is Wait -> "Wait ${step.durationVariable?.value ?: step.durationMs.value} ms"

        is StageBoundary -> "Stage boundary"

        is Jump -> "Continue from ${stepName(step.targetStepId)} up to ${step.maxVisits} times"

        is WaitUntil -> "Wait for ${conditionName(step.condition)}"

        is IfElse -> "If ${conditionName(step.condition)}"

        is Repeat -> "Repeat ${step.countVariable?.value ?: step.count} times"

        is WhileStep -> "While ${conditionName(step.condition)}"

        is LaunchApp -> "Launch ${step.packageName.value}"

        is Stop -> "Stop workflow"

        is com.veyarc.model.FindImage -> "Find image / ${step.name.value}"

        is CaptureImage -> "Capture ${step.name.value}"

        is SetVariable -> "Set variable ${step.name.value}"

        null -> "Workflow step"
    }

    private fun stepName(stepId: String): String = when (stepsById[stepId]) {
        is ReusableSection -> "Reusable section"
        is RunSection -> "Run saved blocks"
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
        is com.veyarc.model.FindImage -> "Find image"
        is CaptureImage -> "Capture image"
        is SetVariable -> "Set variable"
        null -> "Step"
    }

    private fun conditionName(condition: Condition): String = when (condition) {
        is CapturedImageCondition -> "Captured ${condition.name.value} ${if (condition.present) "appears" else "is absent"}"

        is PixelColorCondition -> if (condition.region == null) "Pixel color at ${formatPoint(condition.point)}" else "Color appears in the search area"

        is ImagePresentCondition -> when (condition.matchMode) {
            ImageMatchMode.SCREEN_STATE -> assetNames[condition.assetId?.value]?.let { "Screen matches $it" } ?: "Screen state"
            ImageMatchMode.APPEARS_WITHIN_REGION -> "${condition.capturedImage?.value ?: assetNames[condition.assetId?.value] ?: "Selected image"} appears"
        }

        is ImageAbsentCondition -> "${condition.capturedImage?.value ?: assetNames[condition.assetId?.value] ?: "Selected image"} is absent"

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
        is CapturedImageCondition -> matchThreshold
        is ImageAbsentCondition -> matchThreshold
        else -> null
    }

    private fun formatPoint(point: NormalizedCoordinate): String {
        val xPx = (point.x * (document.viewport.recordedWidthPx - 1)).roundToInt()
        val yPx = (point.y * (document.viewport.recordedHeightPx - 1)).roundToInt()
        return "($xPx, $yPx px recorded)"
    }

    private fun indexSteps(steps: List<WorkflowStep>, parent: String = "Workflow") {
        var number = 0
        var stage = 0
        steps.forEach { step ->
            stepsById[step.id] = step
            if (step is ReusableSection) {
                blockLabels[step.id] = "Saved blocks · ${step.name}"
                blockContexts[step.id] = parent
                indexSteps(step.bodySteps, "Saved blocks · ${step.name}")
                return@forEach
            }
            val label = if (step is StageBoundary) "Stage ${++stage}" else "Block ${++number} · ${stepName(step.id)}"
            val context = if (stage > 0 && step !is StageBoundary) "$parent › Stage $stage" else parent
            blockLabels[step.id] = label
            blockContexts[step.id] = context
            when (step) {
                is ReusableSection -> indexSteps(step.bodySteps, "Section · ${step.name}")

                is com.veyarc.model.FindImage -> indexCondition(step.condition, "${step.id}/condition")

                is WaitUntil -> indexCondition(step.condition, "${step.id}/condition")

                is IfElse -> {
                    indexCondition(step.condition, "${step.id}/condition")
                    indexSteps(step.thenSteps, "$context › $label › If true")
                    indexSteps(step.elseSteps, "$context › $label › If false")
                }

                is Repeat -> indexSteps(step.bodySteps, "$context › $label › Body")

                is WhileStep -> {
                    indexCondition(step.condition, "${step.id}/condition")
                    indexSteps(step.bodySteps, "$context › $label › Body")
                }

                else -> Unit
            }
        }
    }

    private fun indexCondition(condition: Condition, path: String, location: String = "") {
        conditionsByPath[path] = condition
        conditionLocations[path] = location
        when (condition) {
            is AndCondition -> condition.conditions.forEachIndexed { index, child ->
                indexCondition(child, "$path/conditions/$index", listOf(location, "Condition ${index + 1}").filter(String::isNotBlank).joinToString(" › "))
            }

            is OrCondition -> condition.conditions.forEachIndexed { index, child ->
                indexCondition(child, "$path/conditions/$index", listOf(location, "Condition ${index + 1}").filter(String::isNotBlank).joinToString(" › "))
            }

            is NotCondition -> indexCondition(condition.condition, "$path/condition", listOf(location, "Not").filter(String::isNotBlank).joinToString(" › "))

            else -> Unit
        }
    }

    private companion object {
        fun formatPercent(value: Double): String = String.format(Locale.ROOT, "%.1f%%", value * 100.0)
    }
}
