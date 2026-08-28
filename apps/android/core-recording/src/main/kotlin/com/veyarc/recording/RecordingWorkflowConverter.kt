package com.veyarc.recording

import com.veyarc.model.DurationMillis
import com.veyarc.model.LaunchApp
import com.veyarc.model.LongPress
import com.veyarc.model.NormalizedCoordinate
import com.veyarc.model.PackageName
import com.veyarc.model.RunLimits
import com.veyarc.model.SwipePath
import com.veyarc.model.TapPoint
import com.veyarc.model.TimedPoint
import com.veyarc.model.Wait
import com.veyarc.model.WorkflowDocument
import com.veyarc.model.WorkflowIds
import com.veyarc.model.WorkflowStep
import com.veyarc.model.WorkflowValidator
import com.veyarc.model.bundle.WorkflowBundle
import kotlin.math.max

/** A validated workflow bundle plus facts that need user review before saving. */
data class RecordingWorkflowDraft(
    val bundle: WorkflowBundle,
    val foregroundPackageSuggestions: List<RecordingAnalysisEvent.ForegroundPackageTransition>,
    val warnings: List<String>,
)

/** Converts reviewable recording facts into the existing editable workflow contract. */
object RecordingWorkflowConverter {
    private const val DEFAULT_NAME = "Recorded workflow"
    private const val PRODUCER_VERSION = "android-recorder"
    private const val DEFAULT_MAX_TOTAL_STEPS = 100
    private const val DEFAULT_MAX_RUNTIME_MILLIS = 60_000L
    private const val DEFAULT_MAX_LOOP_ITERATIONS = 100
    private const val DEFAULT_MAX_NESTING_DEPTH = 8
    private const val DEFAULT_MAX_PATH_POINTS = 128
    private const val DEFAULT_MAX_STEP_DURATION_MILLIS = 30_000L
    private const val DEFAULT_LAUNCH_FOREGROUND_TIMEOUT_MILLIS = 5_000L

    fun convert(
        analysis: RecordingAnalysis,
        allowedPackages: List<PackageName>,
        workflowId: String = WorkflowIds.newUuid(),
        name: String = DEFAULT_NAME,
    ): RecordingWorkflowDraft {
        val packages = allowedPackages.distinct()
        require(packages.isNotEmpty()) {
            "The recording did not observe a third-party application package."
        }
        val recordedSteps = analysis.events.mapNotNull { event ->
            when (event) {
                is RecordingAnalysisEvent.Gesture -> event.gesture.toWorkflowStep(analysis.header.viewport)

                is RecordingAnalysisEvent.InferredWait -> Wait(
                    id = WorkflowIds.newUuid(),
                    durationMs = DurationMillis(event.durationMillis),
                )

                is RecordingAnalysisEvent.ForegroundPackageTransition -> null
            }
        }
        require(analysis.gestures.isNotEmpty()) {
            "The recording did not contain a complete touch gesture to import."
        }
        // A recorded workflow must be able to establish the same target app when it
        // starts from Veyarc or another controller surface. The raw journal can begin
        // with a controller package, so prefer it only when it is one of the observed
        // third-party packages; otherwise the recorder's insertion order identifies
        // the first third-party target.
        val launchPackage = analysis.initialForegroundPackage
            ?.takeIf { it in packages }
            ?: packages.first()
        val steps = listOf(
            LaunchApp(
                id = WorkflowIds.newUuid(),
                packageName = launchPackage,
                foregroundTimeoutMs = DurationMillis(DEFAULT_LAUNCH_FOREGROUND_TIMEOUT_MILLIS),
            ),
        ) + recordedSteps

        val document = WorkflowDocument(
            schemaVersion = WorkflowDocument.CURRENT_SCHEMA_VERSION,
            id = workflowId,
            revision = 1,
            name = name.trim().ifBlank { DEFAULT_NAME },
            allowedPackages = packages,
            viewport = analysis.header.viewport,
            runLimits = runLimits(steps, analysis),
            variables = emptyMap(),
            assets = emptyList(),
            steps = steps,
        )
        val validationErrors = WorkflowValidator.validate(document)
        require(validationErrors.isEmpty()) {
            "The recorded workflow is invalid: ${validationErrors.joinToString { it.message }}"
        }

        val warnings = buildList {
            if (analysis.incompleteTouchIgnored) {
                add("The final incomplete touch was not imported.")
            }
            if (analysis.foregroundTransitions.isNotEmpty()) {
                add(
                    "The first observed third-party app is launched automatically. Later foreground " +
                        "app changes are suggestions only; add Launch app steps for additional app switches.",
                )
            }
        }
        return RecordingWorkflowDraft(
            bundle = WorkflowBundle(
                document = document,
                assets = emptyList(),
                producerVersion = PRODUCER_VERSION,
            ),
            foregroundPackageSuggestions = analysis.foregroundTransitions,
            warnings = warnings,
        )
    }

    private fun ClassifiedTouchGesture.toWorkflowStep(viewport: com.veyarc.model.ViewportSpec): WorkflowStep {
        val id = WorkflowIds.newUuid()
        return when (kind) {
            ClassifiedGestureKind.TAP -> TapPoint(id, startPoint.toNormalized(viewport))

            ClassifiedGestureKind.HOLD -> LongPress(
                id = id,
                point = startPoint.toNormalized(viewport),
                durationMs = DurationMillis(durationMillis),
            )

            ClassifiedGestureKind.PATH -> SwipePath(
                id = id,
                durationMs = DurationMillis(durationMillis),
                points = points.map { timedPoint ->
                    TimedPoint(
                        point = timedPoint.point.toNormalized(viewport),
                        offsetMs = DurationMillis(timedPoint.offsetMillis),
                    )
                },
            )
        }
    }

    private fun RecordedPoint.toNormalized(viewport: com.veyarc.model.ViewportSpec): NormalizedCoordinate {
        val widthDenominator = max(1, viewport.recordedWidthPx - 1).toDouble()
        val heightDenominator = max(1, viewport.recordedHeightPx - 1).toDouble()
        return NormalizedCoordinate(
            x = (xPx / widthDenominator).coerceIn(0.0, 1.0),
            y = (yPx / heightDenominator).coerceIn(0.0, 1.0),
        )
    }

    private fun runLimits(steps: List<WorkflowStep>, analysis: RecordingAnalysis): RunLimits {
        val lastRecordedMillis = analysis.events.maxOfOrNull { event ->
            when (event) {
                is RecordingAnalysisEvent.Gesture -> event.gesture.endElapsedMillis
                is RecordingAnalysisEvent.InferredWait -> event.endElapsedMillis
                is RecordingAnalysisEvent.ForegroundPackageTransition -> event.elapsedMillis
            }
        } ?: 0L
        val longestStepMillis = steps.maxOfOrNull(::stepDurationMillis) ?: 1L
        // The compiler budgets the timeout owned by every timed step, including the
        // automatically inserted LaunchApp. Base the document limit on that same sequence
        // instead of only on the recorded event timeline; otherwise a valid recording can
        // fail preflight by the launch timeout alone.
        val staticRuntimeMillis = steps.fold(0L) { total, step ->
            saturatingAdd(total, stepRuntimeMillis(step))
        }
        val longestPathPoints = steps.filterIsInstance<SwipePath>().maxOfOrNull { it.points.size } ?: 2
        return RunLimits(
            maxTotalSteps = max(DEFAULT_MAX_TOTAL_STEPS, steps.size + 16),
            maxRuntimeMs = max(
                DEFAULT_MAX_RUNTIME_MILLIS,
                maxOf(
                    saturatingAdd(lastRecordedMillis, 1_000L),
                    saturatingAdd(staticRuntimeMillis, 1_000L),
                ),
            ),
            maxLoopIterations = DEFAULT_MAX_LOOP_ITERATIONS,
            maxNestingDepth = DEFAULT_MAX_NESTING_DEPTH,
            maxPathPoints = max(DEFAULT_MAX_PATH_POINTS, longestPathPoints),
            maxStepDurationMs = max(DEFAULT_MAX_STEP_DURATION_MILLIS, longestStepMillis),
        )
    }

    private fun stepDurationMillis(step: WorkflowStep): Long = when (step) {
        is TapPoint -> 1L
        is LongPress -> step.durationMs.value
        is SwipePath -> step.durationMs.value
        is Wait -> step.durationMs.value
        is LaunchApp -> step.foregroundTimeoutMs.value
        else -> 1L
    }

    private fun stepRuntimeMillis(step: WorkflowStep): Long = when (step) {
        is LongPress -> step.durationMs.value
        is SwipePath -> step.durationMs.value
        is Wait -> step.durationMs.value
        is LaunchApp -> step.foregroundTimeoutMs.value
        else -> 0L
    }

    private fun saturatingAdd(first: Long, second: Long): Long = if (second > 0L && first > Long.MAX_VALUE - second) Long.MAX_VALUE else first + second
}
