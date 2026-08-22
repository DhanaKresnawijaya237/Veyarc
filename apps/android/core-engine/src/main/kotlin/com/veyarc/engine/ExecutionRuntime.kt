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
    fun observe(condition: Condition): Boolean
}

/** Composite driver used by the runner. Android will implement this with platform adapters. */
interface AutomationDriver :
    GestureDispatcher,
    AppLauncher,
    ForegroundPackageSource,
    ViewportSource,
    ConditionObserver

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
    VIEWPORT_CHANGED_DURING_GESTURE,
    DRIVER_ERROR,
}

data class RunFailure(
    val code: RunFailureCode,
    val stepId: String?,
    val path: String,
    val message: String,
)

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
    ) : RunEvent

    data class StepFinished(
        override val atMs: Long,
        val stepId: String,
        val outcome: StepOutcome,
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
