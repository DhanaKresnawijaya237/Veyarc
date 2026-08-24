package com.veyarc.feature.recorder

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.veyarc.automation.android.AndroidForegroundPackage
import com.veyarc.automation.android.ShizukuInputReplay
import com.veyarc.automation.android.ShizukuInputReplaySnapshot
import com.veyarc.automation.android.ShizukuMotionAction
import com.veyarc.automation.android.ShizukuMotionFrame
import com.veyarc.automation.android.currentRecordingViewport
import com.veyarc.model.PackageName
import com.veyarc.recording.RecordingRawTrace
import com.veyarc.recording.RecordingTouchAction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable

data class CoordinateRawTraceReplayState(
    val running: Boolean = false,
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
    private var pendingReplayFrames: List<ShizukuMotionFrame>? = null
    private var foregroundWaitDeadline = 0L

    private val dispatchReplayRunnable = Runnable {
        if (!requestStarted) return@Runnable
        val frames = pendingReplayFrames ?: return@Runnable
        pendingReplayFrames = null
        runCatching { replay.dispatchRawMotionTrace(frames) }.onFailure { error ->
            requestStarted = false
            RawReplayForegroundService.stop(appContext)
            refuse(error.message ?: "The raw replay request was rejected.")
        }
    }

    private val foregroundWaitRunnable = object : Runnable {
        override fun run() {
            if (!requestStarted) return
            val targetPackage = pendingTargetPackage ?: return
            if (AndroidForegroundPackage.current() == targetPackage) {
                val trace = pendingTrace
                pendingTrace = null
                pendingTargetPackage = null
                if (trace != null) submitWhenForeground(trace, targetPackage)
                return
            }
            if (SystemClock.elapsedRealtime() >= foregroundWaitDeadline) {
                pendingTrace = null
                pendingTargetPackage = null
                requestStarted = false
                refuse("$targetPackage did not become the foreground app in time. No input was sent.")
                return
            }
            _state.value = CoordinateRawTraceReplayState(
                running = true,
                message = "Waiting for $targetPackage to become foreground…",
            )
            mainHandler.postDelayed(this, FOREGROUND_POLL_INTERVAL_MILLIS)
        }
    }

    init {
        CoordinateRawTraceReplayRuntime.register(this)
        replay.setCallback(::onReplaySnapshot)
    }

    /** Starts only after the caller has shown an explicit confirmation dialog. */
    fun start(trace: RecordingRawTrace, expectedTargetPackage: PackageName?) {
        if (_state.value.running) return
        val targetPackage = expectedTargetPackage ?: run {
            refuse("Raw replay requires one known recorded target application.")
            return
        }
        val currentPackage = AndroidForegroundPackage.current()
        if (currentPackage != targetPackage) {
            val launchIntent = appContext.packageManager.getLaunchIntentForPackage(targetPackage.value)
            if (launchIntent == null) {
                refuse(
                    "Veyarc could not launch $targetPackage. Open it manually, then start replay " +
                        "from a future replay control.",
                )
                return
            }
            requestStarted = true
            pendingTrace = trace
            pendingTargetPackage = targetPackage
            foregroundWaitDeadline = SystemClock.elapsedRealtime() + FOREGROUND_WAIT_TIMEOUT_MILLIS
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
                refuse(error.message ?: "The target app could not be opened. No input was sent.")
                return
            }
            mainHandler.removeCallbacks(foregroundWaitRunnable)
            mainHandler.post(foregroundWaitRunnable)
            return
        }
        requestStarted = true
        submitWhenForeground(trace, targetPackage)
    }

    private fun submitWhenForeground(trace: RecordingRawTrace, targetPackage: PackageName) {
        if (AndroidForegroundPackage.current() != targetPackage) {
            requestStarted = false
            refuse("The target app changed before replay started. No input was sent.")
            return
        }
        val currentViewport = currentRecordingViewport(appContext) ?: run {
            requestStarted = false
            refuse("Raw replay requires a full-display viewport. Close split-screen or freeform windows.")
            return
        }
        RawReplayForegroundService.notificationUnavailableReason(appContext)?.let { reason ->
            requestStarted = false
            refuse(reason)
            return
        }
        val remappedFrames = runCatching { trace.remapTo(currentViewport) }.getOrElse { error ->
            requestStarted = false
            refuse(error.message ?: "The recording cannot be safely mapped to the current display.")
            return
        }
        val replayFrames = remappedFrames.map { frame ->
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
        pendingReplayFrames = replayFrames
        _state.value = CoordinateRawTraceReplayState(
            running = true,
            message = "Starting the visible replay emergency control…",
        )
        RawReplayForegroundService.start(appContext, targetPackage.value)
        mainHandler.removeCallbacks(dispatchReplayRunnable)
        mainHandler.postDelayed(dispatchReplayRunnable, REPLAY_SERVICE_START_DELAY_MILLIS)
    }

    fun cancel(reason: String = "Raw replay cancelled. Any active touch was sent CANCEL best-effort.") {
        if (!requestStarted && !_state.value.running) return
        requestStarted = false
        mainHandler.removeCallbacks(foregroundWaitRunnable)
        mainHandler.removeCallbacks(dispatchReplayRunnable)
        pendingTrace = null
        pendingTargetPackage = null
        pendingReplayFrames = null
        replay.cancel()
        RawReplayForegroundService.stop(appContext)
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
        pendingReplayFrames = null
        CoordinateRawTraceReplayRuntime.unregister(this)
        RawReplayForegroundService.stop(appContext)
        replay.cancel()
        replay.close()
    }

    private fun onReplaySnapshot(snapshot: ShizukuInputReplaySnapshot) {
        if (!requestStarted) return
        when {
            snapshot.running -> {
                _state.value = CoordinateRawTraceReplayState(
                    running = true,
                    message = snapshot.message,
                )
            }

            snapshot.lastError != null || isFailureMessage(snapshot.message) -> {
                requestStarted = false
                RawReplayForegroundService.stop(appContext)
                _state.value = CoordinateRawTraceReplayState(
                    message = snapshot.message,
                    error = snapshot.lastError ?: snapshot.message,
                )
            }

            snapshot.message.contains("completed", ignoreCase = true) -> {
                requestStarted = false
                RawReplayForegroundService.stop(appContext)
                _state.value = CoordinateRawTraceReplayState(
                    completed = true,
                    message = snapshot.message,
                )
            }

            else -> {
                _state.value = CoordinateRawTraceReplayState(
                    running = true,
                    message = snapshot.message,
                )
            }
        }
    }

    private fun refuse(reason: String) {
        _state.value = CoordinateRawTraceReplayState(message = "Raw replay refused.", error = reason)
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
        const val REPLAY_SERVICE_START_DELAY_MILLIS = 250L
    }
}
