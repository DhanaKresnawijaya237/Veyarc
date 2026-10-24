package com.veyarc.engine

import com.veyarc.model.Condition
import com.veyarc.model.PackageName
import com.veyarc.model.ScalarValue
import com.veyarc.model.StepOutcome
import com.veyarc.model.StopReason
import java.util.Collections

/** Sends platform-neutral touch actions to the active device adapter. */
interface GestureDispatcher {
    fun tap(point: DisplayCoordinate)

    fun longPress(point: DisplayCoordinate, durationMs: Long)

    fun swipePath(points: List<DisplayTimedPoint>, durationMs: Long)
}

/** Starts an explicitly declared application. */
interface AppLauncher {
    fun launchApp(packageName: PackageName)
}

/** Reports the current foreground package for safety checks. */
interface ForegroundPackageSource {
    fun currentForegroundPackage(): PackageName?
}

/** Reports the current full-display geometry and windowing mode. */
interface ViewportSource {
    fun currentViewport(): ActiveViewport?
}

/** Observes conditions that require platform state, such as pixels or orientation. */
interface ConditionObserver {
    fun observe(condition: Condition): ConditionObservation
}

interface ImageSearch {
    fun findImage(condition: com.veyarc.model.ImagePresentCondition): ConditionObservation = throw UnsupportedOperationException("Image search is unavailable")
}

interface RuntimeImageCapture {
    fun captureImage(name: com.veyarc.model.VariableName, region: com.veyarc.model.NormalizedRect): Unit = throw UnsupportedOperationException("Runtime image capture is unavailable")
}

data class ColorMatchObservation(
    val targetArgb: Long,
    val closestArgb: Long,
    val difference: Int,
    val tolerance: Int,
) {
    init {
        require(targetArgb in 0..0xFFFFFFFFL && closestArgb in 0..0xFFFFFFFFL)
        require(difference in 0..255 && tolerance in 0..255)
    }
}

data class ConditionObservation(
    val result: Boolean,
    val matchScore: Double? = null,
    val threshold: Double? = null,
    val colorMatch: ColorMatchObservation? = null,
    val matchedArea: com.veyarc.model.NormalizedRect? = null,
) {
    init {
        require(matchScore == null || matchScore in 0.0..1.0) {
            "Condition match score must be between 0 and 1"
        }
        require(threshold == null || threshold in 0.0..1.0) {
            "Condition threshold must be between 0 and 1"
        }
    }
}

/** Composite driver used by the runner. Android will implement this with platform adapters. */
interface AutomationDriver :
    GestureDispatcher,
    AppLauncher,
    ForegroundPackageSource,
    ViewportSource,
    ConditionObserver,
    RuntimeImageCapture,
    ImageSearch

/** Monotonic time and sleeping are injected so tests do not depend on wall-clock timing. */
interface ExecutionClock {
    fun nowMs(): Long

    fun sleep(durationMs: Long)
}

fun interface CancellationSignal {
    fun isCancellationRequested(): Boolean
}

object CancellationSignals {
    val NONE: CancellationSignal = CancellationSignal { false }
}

fun interface PauseSignal {
    fun isPauseRequested(): Boolean
}

object PauseSignals {
    val NONE: PauseSignal = PauseSignal { false }
}

/** Thread-safe controls shared by Android run buttons and the deterministic runner. */
class MutableExecutionControl :
    CancellationSignal,
    PauseSignal {
    private val cancellationRequested = java.util.concurrent.atomic.AtomicBoolean(false)
    private val pauseRequested = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun isCancellationRequested(): Boolean = cancellationRequested.get()

    override fun isPauseRequested(): Boolean = pauseRequested.get() && !isCancellationRequested()

    fun requestPause() {
        if (!isCancellationRequested()) pauseRequested.set(true)
    }

    fun resume() {
        pauseRequested.set(false)
    }

    fun cancel() {
        cancellationRequested.set(true)
        pauseRequested.set(false)
    }
}

enum class RunOutcome {
    COMPLETED,
    FAILED,
    CANCELLED,
    TIMED_OUT,
    STOPPED,
}

enum class RunFailureCode {
    ALREADY_RUNNING,
    CANCELLATION_REQUESTED,
    RUNTIME_LIMIT_EXCEEDED,
    STEP_TIMEOUT,
    CONDITION_TIMEOUT,
    FOREGROUND_TIMEOUT,
    FOREGROUND_UNAVAILABLE,
    UNEXPECTED_FOREGROUND_PACKAGE,
    VIEWPORT_UNAVAILABLE,
    UNSUPPORTED_WINDOW_MODE,
    ORIENTATION_MISMATCH,
    DISPLAY_ROTATION_CHANGED_DURING_RUN,
    VIEWPORT_CHANGED_DURING_GESTURE,
    DRIVER_ERROR,
    INVALID_VARIABLE_VALUE,
}

data class RunFailure(
    val code: RunFailureCode,
    val stepId: String?,
    val path: String,
    val message: String,
)

data class ComparisonValues(val left: ScalarValue, val right: ScalarValue)

sealed interface StepCompletionDetails {
    data class VariableChange(val before: ScalarValue?, val after: ScalarValue) : StepCompletionDetails

    data class WaitDuration(val durationMs: Long) : StepCompletionDetails

    data class LoopResult(val iterations: Int, val limitReached: Boolean = false) : StepCompletionDetails
}

sealed interface RunEvent {
    val atMs: Long

    data class Started(
        override val atMs: Long,
        val workflowId: String,
        val workflowRevision: Int,
    ) : RunEvent

    data class StepStarted(
        override val atMs: Long,
        val stepId: String,
    ) : RunEvent

    data class ConditionEvaluated(
        override val atMs: Long,
        val stepId: String,
        val path: String,
        val result: Boolean,
        val matchScore: Double? = null,
        val threshold: Double? = null,
        val colorMatch: ColorMatchObservation? = null,
        val matchedArea: com.veyarc.model.NormalizedRect? = null,
        val comparison: ComparisonValues? = null,
    ) : RunEvent

    data class StepFinished(
        override val atMs: Long,
        val stepId: String,
        val outcome: StepOutcome,
        val details: StepCompletionDetails? = null,
    ) : RunEvent

    data class Paused(
        override val atMs: Long,
    ) : RunEvent

    data class Resumed(
        override val atMs: Long,
    ) : RunEvent

    data class Finished(
        override val atMs: Long,
        val outcome: RunOutcome,
        val failure: RunFailure?,
        val stopReason: StopReason?,
    ) : RunEvent
}

data class WorkflowRunResult(
    val outcome: RunOutcome,
    val events: List<RunEvent>,
    val stepOutcomes: Map<String, StepOutcome>,
    val variables: Map<String, ScalarValue>,
    val failure: RunFailure? = null,
    val stopReason: StopReason? = null,
) {
    val isSuccess: Boolean
        get() = outcome == RunOutcome.COMPLETED

    init {
        require((outcome == RunOutcome.FAILED || outcome == RunOutcome.CANCELLED || outcome == RunOutcome.TIMED_OUT) == (failure != null)) {
            "Failure details must match the run outcome"
        }
        require((outcome == RunOutcome.STOPPED) == (stopReason != null)) {
            "Stop reason must match the run outcome"
        }
        require(events.isNotEmpty()) { "A run result must contain at least one event" }
    }
}

internal fun <T> immutableRuntimeList(values: List<T>): List<T> = Collections.unmodifiableList(values.toList())

internal fun <K, V> immutableRuntimeMap(values: Map<K, V>): Map<K, V> = Collections.unmodifiableMap(values.toMap())
