package com.veyarc.feature.recorder

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.veyarc.automation.android.AndroidDeviceSafety
import com.veyarc.automation.android.AndroidForegroundPackage
import com.veyarc.automation.android.AndroidRecordingViewportSnapshot
import com.veyarc.automation.android.ReplayControllerActions
import com.veyarc.automation.android.ReplayFloatingController
import com.veyarc.automation.android.ReplayFreezeCapability
import com.veyarc.automation.android.ShizukuInputReplay
import com.veyarc.automation.android.ShizukuInputReplaySnapshot
import com.veyarc.automation.android.ShizukuMotionAction
import com.veyarc.automation.android.ShizukuMotionFrame
import com.veyarc.automation.android.currentRecordingViewportSnapshot
import com.veyarc.model.PackageName
import com.veyarc.recording.RecordingRawTrace
import com.veyarc.recording.RecordingTouchAction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable

data class CoordinateRawTraceReplayState(
    val running: Boolean = false,
    val paused: Boolean = false,
    val completed: Boolean = false,
    val cancelled: Boolean = false,
    val message: String = "Raw replay has not started.",
    val error: String? = null,
)

/** Owns replay separately from recording and fails closed before submitting unsafe input. */
class CoordinateRawTraceReplayController(
    context: Context,
) : Closeable {
    private val appContext = context.applicationContext
    private val replay = ShizukuInputReplay(appContext)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(CoordinateRawTraceReplayState())
    val state: StateFlow<CoordinateRawTraceReplayState> = _state.asStateFlow()

    @Volatile
    private var requestStarted = false

    private var pendingTrace: RecordingRawTrace? = null
    private var pendingTargetPackage: PackageName? = null
    private var pendingReplayTrace: RecordingRawTrace? = null
    private var pendingReplayTargetPackage: PackageName? = null
    private var pendingReplayViewport: AndroidRecordingViewportSnapshot? = null
    private var foregroundWaitDeadline = 0L
    private var emergencyControlDeadline = 0L
    private var stableViewport: AndroidRecordingViewportSnapshot? = null
    private var stableViewportSampleCount = 0
    private var activeTargetPackage: PackageName? = null
    private var activeReplayViewport: AndroidRecordingViewportSnapshot? = null

    private val floatingControllerActions = ReplayControllerActions(
        onResume = ::resume,
        onStop = { cancel("Replay cancelled from the Veyarc edge-latch control center.") },
        onServiceLost = { cancel("Replay cancelled because the accessibility service disconnected.") },
    )

    private val replaySafetyRunnable = object : Runnable {
        override fun run() {
            if (!requestStarted) return
            unsafeReplayReason()?.let { reason ->
                cancel(reason)
                return
            }
            val targetPackage = activeTargetPackage
            if (targetPackage != null && AndroidForegroundPackage.current() != targetPackage) {
                cancel("Replay cancelled because the foreground app changed from $targetPackage.")
                return
            }
            val expectedViewport = activeReplayViewport
            if (expectedViewport != null && currentRecordingViewportSnapshot(appContext) != expectedViewport) {
                cancel("Replay cancelled because the display changed during replay.")
                return
            }
            mainHandler.postDelayed(this, REPLAY_SAFETY_POLL_INTERVAL_MILLIS)
        }
    }

    private val dispatchReplayRunnable = object : Runnable {
        override fun run() {
            if (!requestStarted) return
            unsafeReplayReason()?.let { reason ->
                refuse(reason)
                return
            }
            val trace = pendingReplayTrace ?: return
            val targetPackage = pendingReplayTargetPackage ?: return
            val expectedViewport = pendingReplayViewport ?: return
            if (!RawReplayForegroundService.isReady(targetPackage.value)) {
                if (SystemClock.elapsedRealtime() >= emergencyControlDeadline) {
                    pendingReplayTrace = null
                    pendingReplayTargetPackage = null
                    pendingReplayViewport = null
                    requestStarted = false
                    stopReplaySurfaces()
                    refuse(
                        "Replay emergency control did not become ready in time. No input was sent.",
                    )
                } else {
                    mainHandler.postDelayed(this, REPLAY_SERVICE_READY_POLL_MILLIS)
                }
                return
            }
            if (AndroidForegroundPackage.current() != targetPackage) {
                pendingReplayTrace = null
                pendingReplayTargetPackage = null
                pendingReplayViewport = null
                requestStarted = false
                stopReplaySurfaces()
                refuse("The target app changed before replay dispatch. No input was sent.")
                return
            }
            val currentViewport = currentRecordingViewportSnapshot(appContext)
            if (currentViewport == null || currentViewport != expectedViewport) {
                pendingReplayTrace = null
                pendingReplayTargetPackage = null
                pendingReplayViewport = null
                stopReplaySurfaces()
                retryAfterViewportChange(trace, targetPackage)
                return
            }
            val remappedFrames = runCatching { trace.remapTo(currentViewport.viewport) }.getOrElse { error ->
                requestStarted = false
                stopReplaySurfaces()
                refuse(error.message ?: "The recording cannot be safely mapped to the current display.")
                return
            }
            val frames = remappedFrames.map { frame ->
                ShizukuMotionFrame(
                    action = when (frame.action) {
                        RecordingTouchAction.DOWN -> ShizukuMotionAction.DOWN
                        RecordingTouchAction.MOVE -> ShizukuMotionAction.MOVE
                        RecordingTouchAction.UP -> ShizukuMotionAction.UP
                    },
                    x = frame.xPx.toFloat(),
                    y = frame.yPx.toFloat(),
                    delayMillis = frame.delayMillis,
                )
            }
            if (frames.any { ReplayFloatingController.isScreenPointOnSurface(it.x, it.y) }) {
                requestStarted = false
                stopReplaySurfaces()
                refuse(
                    "Replay was refused because the recorded path intersects the Veyarc edge-latch " +
                        "control surface. Move the latch or edit the trace before retrying.",
                )
                return
            }
            pendingReplayTrace = null
            pendingReplayTargetPackage = null
            pendingReplayViewport = null
            activeReplayViewport = expectedViewport
            runCatching { replay.dispatchRawMotionTrace(frames, null) }.onFailure { error ->
                requestStarted = false
                stopReplaySurfaces()
                refuse(error.message ?: "The raw replay request was rejected.")
            }
        }
    }

    private val foregroundWaitRunnable = object : Runnable {
        override fun run() {
            if (!requestStarted) return
            unsafeReplayReason()?.let { reason ->
                refuse(reason)
                return
            }
            val targetPackage = pendingTargetPackage ?: return
            if (SystemClock.elapsedRealtime() >= foregroundWaitDeadline) {
                pendingTrace = null
                pendingTargetPackage = null
                resetStableViewport()
                requestStarted = false
                refuse("$targetPackage did not become the foreground app in time. No input was sent.")
                return
            }
            if (AndroidForegroundPackage.current() != targetPackage) {
                resetStableViewport()
                _state.value = CoordinateRawTraceReplayState(
                    running = true,
                    message = "Waiting for $targetPackage to become foreground…",
                )
                mainHandler.postDelayed(this, FOREGROUND_POLL_INTERVAL_MILLIS)
                return
            }
            val currentViewport = currentRecordingViewportSnapshot(appContext)
            if (currentViewport == null) {
                resetStableViewport()
                _state.value = CoordinateRawTraceReplayState(
                    running = true,
                    message = "Waiting for the target display to settle…",
                )
                mainHandler.postDelayed(this, FOREGROUND_POLL_INTERVAL_MILLIS)
                return
            }
            if (stableViewport == currentViewport) {
                stableViewportSampleCount += 1
            } else {
                stableViewport = currentViewport
                stableViewportSampleCount = 1
            }
            if (stableViewportSampleCount < REQUIRED_STABLE_VIEWPORT_SAMPLES) {
                _state.value = CoordinateRawTraceReplayState(
                    running = true,
                    message = "Waiting for the target display to settle…",
                )
                mainHandler.postDelayed(this, FOREGROUND_POLL_INTERVAL_MILLIS)
                return
            }
            val trace = pendingTrace
            pendingTrace = null
            pendingTargetPackage = null
            resetStableViewport()
            if (trace != null) submitWhenForeground(trace, targetPackage, currentViewport)
            return
        }
    }

    init {
        CoordinateRawTraceReplayRuntime.register(this)
        replay.setCallback(::onReplaySnapshot)
    }

    /** Starts only after the caller has shown an explicit confirmation dialog. */
    fun start(
        trace: RecordingRawTrace,
        expectedTargetPackage: PackageName?,
        inputGuardEnabled: Boolean = false,
    ) {
        if (_state.value.running) return
        unsafeReplayReason()?.let { reason ->
            refuse(reason)
            return
        }
        val targetPackage = expectedTargetPackage ?: run {
            refuse("Raw replay requires one known recorded target application.")
            return
        }
        if (inputGuardEnabled) {
            val availability = ReplayFreezeCapability.availability()
            if (!availability.available) {
                refuse(availability.reason ?: "Strict screen freeze is unavailable safely.")
                return
            }
        }
        if (targetPackage.value == appContext.packageName) {
            refuse("Raw replay cannot target Veyarc's own control surface.")
            return
        }
        ReplayFloatingController.setReplayActions(floatingControllerActions)
        if (!ReplayFloatingController.showReplay()) {
            ReplayFloatingController.clearReplayActions()
            refuse("The Veyarc edge-latch controller could not be created safely. No input was sent.")
            return
        }
        val currentPackage = AndroidForegroundPackage.current()
        activeTargetPackage = targetPackage
        requestStarted = true
        pendingTrace = trace
        pendingTargetPackage = targetPackage
        foregroundWaitDeadline = SystemClock.elapsedRealtime() + FOREGROUND_WAIT_TIMEOUT_MILLIS
        resetStableViewport()
        if (currentPackage != targetPackage) {
            val launchIntent = appContext.packageManager.getLaunchIntentForPackage(targetPackage.value)
            if (launchIntent == null) {
                pendingTrace = null
                pendingTargetPackage = null
                requestStarted = false
                resetStableViewport()
                refuse(
                    "Veyarc could not launch $targetPackage. Open it manually, then start replay " +
                        "again from the edge-latch control center.",
                )
                return
            }
            _state.value = CoordinateRawTraceReplayState(
                running = true,
                message = "Opening $targetPackage before replay…",
            )
            runCatching {
                appContext.startActivity(
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure { error ->
                pendingTrace = null
                pendingTargetPackage = null
                requestStarted = false
                resetStableViewport()
                refuse(error.message ?: "The target app could not be opened. No input was sent.")
                return
            }
            mainHandler.removeCallbacks(foregroundWaitRunnable)
            mainHandler.post(foregroundWaitRunnable)
            return
        }
        _state.value = CoordinateRawTraceReplayState(
            running = true,
            message = "Waiting for the target display to settle…",
        )
        mainHandler.removeCallbacks(foregroundWaitRunnable)
        mainHandler.post(foregroundWaitRunnable)
    }

    /** Requests a pause that the helper applies only between complete gestures. */
    fun pause() {
        if (!requestStarted || !_state.value.running || _state.value.paused) return
        replay.pause()
        _state.value = _state.value.copy(message = "Pausing after the current gesture…")
    }

    /** Resumes a replay that is paused between gestures. */
    fun resume() {
        if (!requestStarted || !_state.value.running || !_state.value.paused) return
        unsafeReplayReason()?.let { reason ->
            cancel(reason)
            return
        }
        if (!ReplayFloatingController.showReplay()) {
            cancel("Replay cancelled because the edge-latch controller could not be restored safely.")
            return
        }
        replay.resume()
        _state.value = _state.value.copy(
            paused = false,
            message = "Resuming replay…",
        )
    }

    private fun submitWhenForeground(
        trace: RecordingRawTrace,
        targetPackage: PackageName,
        expectedViewport: AndroidRecordingViewportSnapshot,
    ) {
        unsafeReplayReason()?.let { reason ->
            requestStarted = false
            refuse(reason)
            return
        }
        if (AndroidForegroundPackage.current() != targetPackage) {
            requestStarted = false
            refuse("The target app changed before replay started. No input was sent.")
            return
        }
        val currentViewport = currentRecordingViewportSnapshot(appContext)
        if (currentViewport == null || currentViewport != expectedViewport) {
            retryAfterViewportChange(trace, targetPackage)
            return
        }
        RawReplayForegroundService.notificationUnavailableReason(appContext)?.let { reason ->
            requestStarted = false
            refuse(reason)
            return
        }
        pendingReplayTrace = trace
        pendingReplayTargetPackage = targetPackage
        pendingReplayViewport = expectedViewport
        _state.value = CoordinateRawTraceReplayState(
            running = true,
            message = "Replay is starting. Use the emergency notification if you need to stop it.",
        )
        RawReplayForegroundService.start(appContext, targetPackage.value)
        emergencyControlDeadline = SystemClock.elapsedRealtime() + REPLAY_SERVICE_READY_TIMEOUT_MILLIS
        mainHandler.removeCallbacks(dispatchReplayRunnable)
        mainHandler.removeCallbacks(replaySafetyRunnable)
        mainHandler.post(replaySafetyRunnable)
        mainHandler.post(dispatchReplayRunnable)
    }

    private fun retryAfterViewportChange(trace: RecordingRawTrace, targetPackage: PackageName) {
        if (!requestStarted) return
        if (SystemClock.elapsedRealtime() >= foregroundWaitDeadline) {
            pendingTrace = null
            pendingTargetPackage = null
            resetStableViewport()
            requestStarted = false
            refuse("The target display did not settle before replay timed out. No input was sent.")
            return
        }
        pendingTrace = trace
        pendingTargetPackage = targetPackage
        resetStableViewport()
        _state.value = CoordinateRawTraceReplayState(
            running = true,
            message = "Waiting for the target display to settle…",
        )
        mainHandler.removeCallbacks(foregroundWaitRunnable)
        mainHandler.post(foregroundWaitRunnable)
    }

    private fun resetStableViewport() {
        stableViewport = null
        stableViewportSampleCount = 0
    }

    fun cancel(
        reason: String = "Raw replay cancelled safely.",
    ) {
        if (!requestStarted && !_state.value.running) return
        requestStarted = false
        mainHandler.removeCallbacks(foregroundWaitRunnable)
        mainHandler.removeCallbacks(dispatchReplayRunnable)
        pendingTrace = null
        pendingTargetPackage = null
        pendingReplayTrace = null
        pendingReplayTargetPackage = null
        pendingReplayViewport = null
        activeReplayViewport = null
        resetStableViewport()
        replay.cancel()
        stopReplaySurfaces()
        activeTargetPackage = null
        restoreFloatingController()
        _state.value = CoordinateRawTraceReplayState(
            cancelled = true,
            message = reason,
        )
    }

    override fun close() {
        requestStarted = false
        mainHandler.removeCallbacks(foregroundWaitRunnable)
        mainHandler.removeCallbacks(dispatchReplayRunnable)
        pendingTrace = null
        pendingTargetPackage = null
        pendingReplayTrace = null
        pendingReplayTargetPackage = null
        pendingReplayViewport = null
        activeReplayViewport = null
        resetStableViewport()
        CoordinateRawTraceReplayRuntime.unregister(this)
        replay.cancel()
        stopReplaySurfaces()
        activeTargetPackage = null
        restoreFloatingController()
        replay.close()
    }

    private fun onReplaySnapshot(snapshot: ShizukuInputReplaySnapshot) {
        if (!requestStarted) return
        RawReplayForegroundService.updatePaused(appContext, snapshot.paused)
        when {
            snapshot.running -> {
                if (snapshot.paused) {
                    ReplayFloatingController.showPaused(floatingControllerActions)
                } else if (!ReplayFloatingController.showReplay()) {
                    cancel("Replay cancelled because the edge-latch controller could not be restored safely.")
                    return
                }
                _state.value = CoordinateRawTraceReplayState(
                    running = true,
                    paused = snapshot.paused,
                    message = snapshot.message,
                )
            }

            snapshot.lastError != null || isFailureMessage(snapshot.message) -> {
                requestStarted = false
                stopReplaySurfaces()
                activeTargetPackage = null
                activeReplayViewport = null
                restoreFloatingController()
                val targetWindowLost = listOfNotNull(snapshot.lastError, snapshot.message)
                    .any { it.contains("Targeted input event injection", ignoreCase = true) }
                if (targetWindowLost) {
                    _state.value = CoordinateRawTraceReplayState(
                        cancelled = true,
                        message = "Replay cancelled safely because the target app window changed " +
                            "or closed before the next frame. Return to the target app and retry.",
                    )
                } else {
                    _state.value = CoordinateRawTraceReplayState(
                        message = snapshot.message,
                        error = snapshot.lastError ?: snapshot.message,
                    )
                }
            }

            snapshot.message.contains("completed", ignoreCase = true) -> {
                requestStarted = false
                stopReplaySurfaces()
                activeTargetPackage = null
                activeReplayViewport = null
                restoreFloatingController()
                _state.value = CoordinateRawTraceReplayState(
                    completed = true,
                    message = snapshot.message,
                )
            }

            else -> {
                _state.value = CoordinateRawTraceReplayState(
                    running = true,
                    paused = snapshot.paused,
                    message = snapshot.message,
                )
            }
        }
    }

    private fun refuse(reason: String) {
        requestStarted = false
        stopReplaySurfaces()
        activeTargetPackage = null
        activeReplayViewport = null
        restoreFloatingController()
        _state.value = CoordinateRawTraceReplayState(message = "Raw replay refused.", error = reason)
    }

    private fun restoreFloatingController() {
        ReplayFloatingController.clearReplayActions()
        ReplayFloatingController.showIdle()
    }

    private fun stopReplaySurfaces() {
        mainHandler.removeCallbacks(replaySafetyRunnable)
        RawReplayForegroundService.stop(appContext)
    }

    private fun unsafeReplayReason(): String? = AndroidDeviceSafety.current(appContext)?.let { unsafe ->
        "Replay cancelled safely because ${unsafe.reason}."
    }

    private fun isFailureMessage(message: String): Boolean = listOf(
        "could not",
        "cannot",
        "not running",
        "not granted",
        "grant veyarc permission",
        "failed",
        "disconnected",
        "stopped",
        "outside",
        "rejected",
    ).any { marker -> message.contains(marker, ignoreCase = true) }

    private companion object {
        const val FOREGROUND_WAIT_TIMEOUT_MILLIS = 5_000L
        const val FOREGROUND_POLL_INTERVAL_MILLIS = 250L
        const val REPLAY_SAFETY_POLL_INTERVAL_MILLIS = 250L
        const val REPLAY_SERVICE_READY_POLL_MILLIS = 50L
        const val REPLAY_SERVICE_READY_TIMEOUT_MILLIS = 2_000L
        const val REQUIRED_STABLE_VIEWPORT_SAMPLES = 3
    }
}
