package com.veyarc.feature.recorder

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ViewConfiguration
import com.veyarc.automation.android.AndroidForegroundPackage
import com.veyarc.automation.android.ControlCenterRecordingState
import com.veyarc.automation.android.RecordingControllerActions
import com.veyarc.automation.android.ReplayControllerWorkflow
import com.veyarc.automation.android.ReplayFloatingController
import com.veyarc.automation.android.ReplayWorkflowCatalog
import com.veyarc.automation.android.ShizukuTouchRecording
import com.veyarc.automation.android.ShizukuTouchRecordingEvent
import com.veyarc.automation.android.currentDisplayRotation
import com.veyarc.automation.android.currentRecordingViewport
import com.veyarc.data.storage.AesGcmFileCodec
import com.veyarc.data.storage.AndroidKeystoreKeySource
import com.veyarc.data.storage.EncryptedRecordingJournalStore
import com.veyarc.data.storage.WorkflowRepository
import com.veyarc.model.PackageName
import com.veyarc.recording.RawTouchCoordinateMapper
import com.veyarc.recording.RecordingAnalyzer
import com.veyarc.recording.RecordingClassificationConfig
import com.veyarc.recording.RecordingCoordinateSpace
import com.veyarc.recording.RecordingJournalLine
import com.veyarc.recording.RecordingJournalSnapshot
import com.veyarc.recording.RecordingRawTrace
import com.veyarc.recording.RecordingTouchAction
import com.veyarc.recording.RecordingWorkflowConverter
import com.veyarc.recording.TouchDisplayRotation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Coordinates one bounded global recording session without a touch-intercepting overlay. */
class CoordinateRecordingController(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val source = ShizukuTouchRecording(appContext)
    private val workflowRepository = WorkflowRepository(appContext)
    private val sessionCleanup = RecordingSessionCleanup()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val eventExecutor: ExecutorService = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(512),
        Executors.defaultThreadFactory(),
        ThreadPoolExecutor.AbortPolicy(),
    )
    private val _state = MutableStateFlow<CoordinateRecordingState>(CoordinateRecordingState.Idle)
    val state: StateFlow<CoordinateRecordingState> = _state.asStateFlow()

    @Volatile
    private var active = false

    @Volatile
    private var stopRequested = false

    @Volatile
    private var pauseRequested = false

    @Volatile
    private var paused = false

    @Volatile
    private var sessionStartedElapsedRealtime = 0L

    private var emergencyControlDeadline = 0L
    private var lastElapsedMillis = 0L
    private var sessionId: String? = null
    private var journalFile: File? = null
    private var journal: EncryptedRecordingJournalStore? = null
    private var viewport: com.veyarc.model.ViewportSpec? = null
    private var lastForegroundPackage: PackageName? = null
    private var firstThirdPartyForegroundPackage: PackageName? = null
    private var lastThirdPartyForegroundPackage: PackageName? = null
    private val observedThirdPartyPackages = LinkedHashSet<PackageName>()
    private var hasForegroundObservation = false

    /** False until a complete touch starts after the session source is armed. */
    private var captureReady = false

    /** App-process monotonic origin for frame timing; avoids device/helper clock mismatches. */
    private var recordingStartReceivedTimestampMicros: Long? = null
    private var minX = 0L
    private var maxX = 0L
    private var minY = 0L
    private var maxY = 0L
    private var frameCount = 0L
    private var sourceError: String? = null
    private var stopFailureReason: String? = null

    /**
     * Touch frames stay in memory until the raw contact ends. This lets us discard an entire
     * gesture when the user opens Quick Settings, the launcher, or another control surface
     * halfway through it instead of persisting only its DOWN/MOVE prefix.
     */
    private var pendingTouchLines: MutableList<RecordingJournalLine>? = null
    private var pendingTouchSawControlSurface = false
    private var ignoredTouchActive = false
    private var activeTouchRotation: TouchDisplayRotation? = null

    private val controlCenterActions = RecordingControllerActions(
        onStart = ::start,
        onPause = ::pause,
        onResume = ::resume,
        onStop = ::stop,
        onServiceLost = { onRecordingServiceLost() },
    )

    init {
        ReplayFloatingController.setRecordingActions(controlCenterActions)
    }

    /** True while the global recorder is accepting a new recording session. */
    fun isSessionActive(): Boolean = active && !stopRequested

    /** True while the helper is shutting down after an explicit stop request. */
    fun isSessionStopping(): Boolean = active && stopRequested

    /** True while the journal remains open but raw-input observation is paused. */
    fun isSessionPaused(): Boolean = active && paused && !stopRequested

    private val timerRunnable = object : Runnable {
        override fun run() {
            if (!active || stopRequested || paused) return
            val elapsed = elapsedMillis()
            if (elapsed >= MAX_RECORDING_DURATION_MILLIS) {
                requestStop(
                    cause = RecordingStopCause.TIMEOUT,
                    failureReason = "Recording stopped after the five-minute safety limit.",
                )
                return
            }
            when (val current = _state.value) {
                is CoordinateRecordingState.Arming -> _state.value = current.copy(elapsedMillis = elapsed)

                is CoordinateRecordingState.Recording -> {
                    _state.value = current.copy(
                        foregroundPackage = lastThirdPartyForegroundPackage?.value,
                        elapsedMillis = elapsed,
                        frameCount = frameCount,
                    )
                    RecordingForegroundService.update(
                        appContext,
                        notificationText(lastThirdPartyForegroundPackage?.value, elapsed),
                    )
                }

                is CoordinateRecordingState.Paused -> Unit

                else -> Unit
            }
            mainHandler.postDelayed(this, TIMER_INTERVAL_MILLIS)
        }
    }

    private val stopFallbackRunnable = Runnable {
        if (active && stopRequested) {
            enqueue {
                if (!active || !stopRequested) return@enqueue
                val reason = stopFailureReason ?: sourceError
                if (reason == null) finishSession() else failSession(reason)
            }
        }
    }

    private val foregroundPollRunnable = object : Runnable {
        override fun run() {
            if (!active || stopRequested || paused || pauseRequested) return
            enqueue {
                if (!active || stopRequested || paused || pauseRequested) return@enqueue
                observeForeground(AndroidForegroundPackage.current())
                if (active && isKeyguardLocked()) {
                    failSession("Recording stopped because the lock screen became active.")
                }
            }
            mainHandler.postDelayed(this, FOREGROUND_POLL_INTERVAL_MILLIS)
        }
    }

    private val startSourceWhenEmergencyReadyRunnable = object : Runnable {
        override fun run() {
            if (!active || stopRequested) return
            if (RecordingForegroundService.isReady()) {
                startInputSource(MAX_RECORDING_DURATION_MILLIS)
                return
            }
            if (SystemClock.elapsedRealtime() >= emergencyControlDeadline) {
                failImmediately(
                    "The recording emergency control did not become ready in time. No input was recorded.",
                )
                return
            }
            mainHandler.postDelayed(this, EMERGENCY_CONTROL_READY_POLL_MILLIS)
        }
    }

    fun start() {
        if (active) return
        val notificationUnavailableReason = RecordingForegroundService.notificationUnavailableReason(appContext)
        if (notificationUnavailableReason != null) {
            _state.value = CoordinateRecordingState.Unavailable(notificationUnavailableReason)
            return
        }
        val recordingViewport = currentRecordingViewport(appContext)
        if (recordingViewport == null) {
            _state.value = CoordinateRecordingState.Unavailable(
                "Recording requires a full-display viewport. Close split-screen or freeform windows and try again.",
            )
            return
        }
        if (!source.isReady()) {
            _state.value = CoordinateRecordingState.Unavailable(
                "Start Shizuku and grant Veyarc permission before recording.",
            )
            return
        }

        val id = UUID.randomUUID().toString()
        val fileName = "$id.vrec"
        val file = File(appContext.filesDir, "recordings/$fileName")
        val header = com.veyarc.recording.RecordingJournalHeader(
            sessionId = id,
            startedAtEpochMillis = System.currentTimeMillis(),
            viewport = recordingViewport,
            coordinateSpace = RecordingCoordinateSpace.LOGICAL_DISPLAY_NORMALIZED,
        )
        val openedJournal = runCatching {
            EncryptedRecordingJournalStore.open(
                file = file,
                header = header,
                codec = AesGcmFileCodec(AndroidKeystoreKeySource()),
            )
        }.getOrElse { error ->
            _state.value = CoordinateRecordingState.Unavailable(
                "Could not create the encrypted recording journal: ${error.message ?: error.javaClass.simpleName}",
            )
            return
        }

        sessionId = id
        journalFile = file
        journal = openedJournal
        viewport = recordingViewport
        lastForegroundPackage = null
        firstThirdPartyForegroundPackage = null
        lastThirdPartyForegroundPackage = null
        observedThirdPartyPackages.clear()
        hasForegroundObservation = false
        captureReady = false
        recordingStartReceivedTimestampMicros = null
        frameCount = 0L
        sourceError = null
        pendingTouchLines = null
        pendingTouchSawControlSurface = false
        ignoredTouchActive = false
        activeTouchRotation = null
        stopRequested = false
        pauseRequested = false
        paused = false
        stopFailureReason = null
        if (!sessionCleanup.begin()) {
            runCatching { openedJournal.close() }
            _state.value = CoordinateRecordingState.Unavailable(
                "The previous recording is still cleaning up. Try again in a moment.",
            )
            return
        }
        active = true
        sessionStartedElapsedRealtime = SystemClock.elapsedRealtime()
        lastElapsedMillis = 0L
        _state.value = CoordinateRecordingState.Arming(elapsedMillis = 0L)
        ReplayFloatingController.setRecordingState(ControlCenterRecordingState.RECORDING)
        try {
            RecordingForegroundService.start(
                appContext,
                "Global recording armed. Stop from Quick Settings or this notification.",
            )
            emergencyControlDeadline =
                SystemClock.elapsedRealtime() + EMERGENCY_CONTROL_READY_TIMEOUT_MILLIS
            mainHandler.removeCallbacks(startSourceWhenEmergencyReadyRunnable)
            mainHandler.post(startSourceWhenEmergencyReadyRunnable)
        } catch (error: Throwable) {
            failImmediately("Could not start recording safely: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun startInputSource(durationMillis: Long) {
        if (!active || stopRequested) return
        val startError = runCatching {
            source.start(durationMillis = durationMillis, listener = ::onSourceEvent)
        }.getOrElse { error ->
            "Could not start recording safely: ${error.message ?: error.javaClass.simpleName}"
        }
        if (startError != null) {
            failImmediately(startError)
            return
        }
        mainHandler.removeCallbacks(timerRunnable)
        mainHandler.post(timerRunnable)
        mainHandler.removeCallbacks(stopFallbackRunnable)
        mainHandler.removeCallbacks(foregroundPollRunnable)
        mainHandler.postDelayed(foregroundPollRunnable, FOREGROUND_POLL_INTERVAL_MILLIS)
    }

    /** Cooperative stop used by Quick Settings, the notification, back navigation, and cleanup. */
    fun stop() {
        if (!active) {
            if (_state.value is CoordinateRecordingState.Arming ||
                _state.value is CoordinateRecordingState.Recording ||
                _state.value is CoordinateRecordingState.Paused
            ) {
                _state.value = CoordinateRecordingState.Idle
            }
            return
        }
        requestStop(RecordingStopCause.USER_STOP)
    }

    /** Pauses raw observation without closing the encrypted journal. */
    fun pause() {
        if (!active || stopRequested || paused || pauseRequested) return
        pauseRequested = true
        paused = true
        mainHandler.removeCallbacks(timerRunnable)
        mainHandler.removeCallbacks(foregroundPollRunnable)
        _state.value = CoordinateRecordingState.Paused(
            foregroundPackage = lastThirdPartyForegroundPackage?.value,
            elapsedMillis = elapsedMillis(),
            frameCount = frameCount,
        )
        ReplayFloatingController.setRecordingState(ControlCenterRecordingState.PAUSED)
        RecordingForegroundService.update(appContext, "Recording paused")
        runCatching { source.stop() }
    }

    /** Resumes raw observation using the same encrypted journal and session clock. */
    fun resume() {
        if (!active || stopRequested || !paused || pauseRequested) return
        val elapsed = elapsedMillis()
        val remaining = MAX_RECORDING_DURATION_MILLIS - elapsed
        if (remaining < MIN_RESUME_DURATION_MILLIS) {
            requestStop(
                cause = RecordingStopCause.TIMEOUT,
                failureReason = "Recording reached its five-minute safety limit while paused.",
            )
            return
        }
        paused = false
        captureReady = false
        pendingTouchLines = null
        pendingTouchSawControlSurface = false
        ignoredTouchActive = false
        activeTouchRotation = null
        sourceError = null
        _state.value = CoordinateRecordingState.Arming(elapsedMillis = elapsed)
        ReplayFloatingController.setRecordingState(ControlCenterRecordingState.RECORDING)
        RecordingForegroundService.update(appContext, "Recording resumed")
        startInputSource(remaining)
    }

    /** Stops safely when Android destroys the foreground-service control unexpectedly. */
    fun onRecordingServiceLost(reason: String = "The recording foreground service stopped unexpectedly.") {
        if (!active) return
        if (!requestStop(RecordingStopCause.SERVICE_DISCONNECT, reason)) return
        source.abort()
        enqueue {
            if (active) failSession(reason)
        }
    }

    private fun requestStop(
        cause: RecordingStopCause,
        failureReason: String? = null,
    ): Boolean {
        if (!sessionCleanup.requestStop(cause)) return false
        stopRequested = true
        stopFailureReason = failureReason
        mainHandler.removeCallbacks(startSourceWhenEmergencyReadyRunnable)
        mainHandler.removeCallbacks(timerRunnable)
        mainHandler.removeCallbacks(foregroundPollRunnable)
        runCatching { source.stop() }
        RecordingQuickSettingsTileService.requestRefresh(appContext)
        mainHandler.removeCallbacks(stopFallbackRunnable)
        mainHandler.postDelayed(stopFallbackRunnable, STOP_FALLBACK_DELAY_MILLIS)
        return true
    }

    private fun onSourceEvent(event: ShizukuTouchRecordingEvent) {
        when (event) {
            is ShizukuTouchRecordingEvent.Started -> enqueue {
                if (!active) return@enqueue
                if (pauseRequested || paused) return@enqueue
                recordingStartReceivedTimestampMicros = event.receivedTimestampMicros
                minX = event.minX
                maxX = event.maxX
                minY = event.minY
                maxY = event.maxY
                if (maxX <= minX || maxY <= minY) {
                    failSession("The raw-input helper returned an invalid touchscreen range.")
                    return@enqueue
                }
                observeForeground(AndroidForegroundPackage.current())
                if (!active) return@enqueue
                _state.value = CoordinateRecordingState.Recording(
                    foregroundPackage = lastThirdPartyForegroundPackage?.value,
                    elapsedMillis = elapsedMillis(),
                    frameCount = frameCount,
                )
                ReplayFloatingController.setRecordingState(ControlCenterRecordingState.RECORDING)
            }

            is ShizukuTouchRecordingEvent.Frame -> enqueue {
                if (!active) return@enqueue
                processFrame(event)
            }

            is ShizukuTouchRecordingEvent.Error -> enqueue {
                if (!active) return@enqueue
                sourceError = event.message
                failSession(event.message)
            }

            ShizukuTouchRecordingEvent.Completed -> enqueue {
                if (!active) return@enqueue
                if (pauseRequested && !stopRequested) {
                    pauseRequested = false
                    paused = true
                    settlePendingTouchBeforeClose(preservePartialTouch = false)
                    _state.value = CoordinateRecordingState.Paused(
                        foregroundPackage = lastThirdPartyForegroundPackage?.value,
                        elapsedMillis = elapsedMillis(),
                        frameCount = frameCount,
                    )
                    ReplayFloatingController.setRecordingState(ControlCenterRecordingState.PAUSED)
                    RecordingForegroundService.update(appContext, "Recording paused")
                    return@enqueue
                }
                val reason = sourceError ?: stopFailureReason ?: if (!stopRequested) {
                    if (elapsedMillis() >= MAX_RECORDING_DURATION_MILLIS) {
                        "Recording stopped after the five-minute safety limit."
                    } else {
                        "The raw-input helper ended before the recording was stopped safely."
                    }
                } else {
                    null
                }
                if (reason == null) finishSession() else failSession(reason)
            }
        }
    }

    private fun processFrame(event: ShizukuTouchRecordingEvent.Frame) {
        if (stopRequested || paused || pauseRequested) return
        val foreground = AndroidForegroundPackage.current()
        observeForeground(foreground)
        if (!active) return
        if (isKeyguardLocked()) {
            failSession("Recording stopped because the lock screen became active.")
            return
        }
        val startTimestamp = recordingStartReceivedTimestampMicros ?: return
        val currentViewport = viewport ?: return
        val startingTouch = pendingTouchLines == null && event.action == ShizukuTouchRecordingEvent.Action.DOWN
        val rotation = currentDisplayRotation(appContext)?.let(TouchDisplayRotation::fromAndroidRotation)
            ?: run {
                failSession("The display rotation could not be read safely during recording.")
                return
            }
        if (startingTouch) {
            activeTouchRotation = rotation
        } else if (activeTouchRotation != rotation) {
            failSession("Display rotation changed during an active touch; recording stopped safely.")
            return
        }
        // The helper's kernel timestamp and the app process can use different clock domains on
        // OEM builds. The wrapper captures an app-process monotonic timestamp for every callback,
        // so journal timing remains ordered and reflects the real gesture duration.
        val elapsed = ((event.receivedTimestampMicros - startTimestamp) / 1_000L).coerceAtLeast(0L)
        val monotonicElapsed = appendElapsed(elapsed)
        val xRange = maxX - minX
        val yRange = maxY - minY
        if (xRange <= 0L || yRange <= 0L) {
            failSession("The touchscreen coordinate range became invalid during recording.")
            return
        }
        val normalized = RawTouchCoordinateMapper.toLogicalNormalized(
            rawX = event.x,
            rawY = event.y,
            minX = minX,
            maxX = maxX,
            minY = minY,
            maxY = maxY,
            rotation = rotation,
        )
        // Veyarc, the unlocked notification shade, the launcher, unknown windows, and the edge
        // latch bounds are control surfaces. Their package transitions remain review
        // metadata, but their control taps must not become workflow gestures.
        val packageControlSurface = isControlSurface(foreground)
        val controllerControlSurface = ReplayFloatingController.isNormalizedPointOnSurface(
            normalizedX = normalized.x,
            normalizedY = normalized.y,
        )
        val controlSurface = packageControlSurface || controllerControlSurface
        if (ignoredTouchActive) {
            if (event.action == ShizukuTouchRecordingEvent.Action.UP) ignoredTouchActive = false
            return
        }

        // A start action can leave a final control-tap UP in the raw stream. Do not append
        // that orphan; begin the global trace at the next complete third-party touch.
        if (!captureReady && event.action != ShizukuTouchRecordingEvent.Action.DOWN) return
        if (pendingTouchLines == null && packageControlSurface) {
            if (event.action == ShizukuTouchRecordingEvent.Action.DOWN) ignoredTouchActive = true
            return
        }
        if (startingTouch) {
            captureReady = true
            pendingTouchLines = ArrayList(PENDING_TOUCH_FRAME_LIMIT)
            pendingTouchSawControlSurface = false
            if (controllerControlSurface) {
                ignoredTouchActive = true
                pendingTouchLines = null
                return
            }
        }
        val pendingLines = pendingTouchLines ?: return
        if (!startingTouch && event.action == ShizukuTouchRecordingEvent.Action.DOWN) {
            failSession("The raw-input helper delivered a second touch before the previous one ended.")
            return
        }
        if (controlSurface) pendingTouchSawControlSurface = true
        val x = normalized.x * (currentViewport.recordedWidthPx - 1).toDouble()
        val y = normalized.y * (currentViewport.recordedHeightPx - 1).toDouble()
        val action = when (event.action) {
            ShizukuTouchRecordingEvent.Action.DOWN -> RecordingTouchAction.DOWN
            ShizukuTouchRecordingEvent.Action.MOVE -> RecordingTouchAction.MOVE
            ShizukuTouchRecordingEvent.Action.UP -> RecordingTouchAction.UP
        }
        if (pendingLines.size >= PENDING_TOUCH_FRAME_LIMIT) {
            failSession("A single touch exceeded the safe recording frame limit.")
            return
        }
        pendingLines += RecordingJournalLine.Touch(
            elapsedMillis = monotonicElapsed,
            action = action,
            xPx = x,
            yPx = y,
        )
        if (event.action == ShizukuTouchRecordingEvent.Action.UP) {
            activeTouchRotation = null
            val shouldDiscard = pendingTouchSawControlSurface
            val completedLines = pendingTouchLines
            pendingTouchLines = null
            pendingTouchSawControlSurface = false
            if (shouldDiscard) {
                // The complete control-surface gesture is intentionally omitted. Its
                // foreground metadata was already buffered by observeForeground().
                appendForegroundTransitions(completedLines.orEmpty())
            } else {
                appendPendingTouch(completedLines.orEmpty())
            }
        }
    }

    private fun observeForeground(packageName: PackageName?) {
        if (hasForegroundObservation && packageName == lastForegroundPackage) return
        val line = RecordingJournalLine.ForegroundPackage(
            elapsedMillis = appendElapsed(elapsedMillis()),
            packageName = packageName,
        )
        lastForegroundPackage = packageName
        if (!isControlSurface(packageName)) {
            if (firstThirdPartyForegroundPackage == null) {
                firstThirdPartyForegroundPackage = packageName
            }
            lastThirdPartyForegroundPackage = packageName
            packageName?.let(observedThirdPartyPackages::add)
        }
        hasForegroundObservation = true
        try {
            val pendingLines = pendingTouchLines
            if (pendingLines != null) {
                if (isControlSurface(packageName)) pendingTouchSawControlSurface = true
                pendingLines += line
            } else {
                journal?.append(line)
            }
        } catch (error: Throwable) {
            failSession("Recording could not record the foreground transition safely: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    /** Writes a completed, non-control touch and any foreground transitions observed during it. */
    private fun appendPendingTouch(lines: List<RecordingJournalLine>) {
        try {
            lines.forEach { line ->
                journal?.append(line)
                if (line is RecordingJournalLine.Touch) frameCount += 1L
            }
            updateRecordingFrameState()
        } catch (error: Throwable) {
            failSession("Recording could not append a touch frame safely: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    /** Preserves metadata but never persists touch frames from a control-surface gesture. */
    private fun appendForegroundTransitions(lines: List<RecordingJournalLine>) {
        try {
            lines.filterIsInstance<RecordingJournalLine.ForegroundPackage>().forEach { journal?.append(it) }
        } catch (error: Throwable) {
            failSession("Recording could not append a foreground transition safely: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun updateRecordingFrameState() {
        if (_state.value is CoordinateRecordingState.Recording) {
            _state.value = CoordinateRecordingState.Recording(
                foregroundPackage = lastThirdPartyForegroundPackage?.value,
                elapsedMillis = elapsedMillis(),
                frameCount = frameCount,
            )
        }
    }

    private fun finishSession() {
        if (!active || !sessionCleanup.claimTerminal()) return
        val stopCause = sessionCleanup.currentStopCause()
        active = false
        mainHandler.removeCallbacks(startSourceWhenEmergencyReadyRunnable)
        mainHandler.removeCallbacks(timerRunnable)
        mainHandler.removeCallbacks(foregroundPollRunnable)
        mainHandler.removeCallbacks(stopFallbackRunnable)
        source.abort()
        val store = journal
        val file = journalFile
        val id = sessionId
        val currentForeground = lastThirdPartyForegroundPackage?.value
        var reviewSucceeded = false
        try {
            // An explicit stop has already discarded the control-surface gesture. A helper
            // timeout/completion may still leave a genuine third-party touch without UP; keep
            // that partial prefix in the encrypted journal so review can report it honestly.
            settlePendingTouchBeforeClose(preservePartialTouch = stopCause?.preservePartialTouch ?: true)
            val encryptedSnapshot = store?.snapshot()
            val journalSnapshot = encryptedSnapshot?.let { snapshot ->
                RecordingJournalSnapshot(
                    header = snapshot.header,
                    events = snapshot.events,
                    cursor = snapshot.cursor,
                    trailingPartialBytes = 0,
                    validPrefixBytes = 0,
                    endsWithNewline = true,
                )
            }
            val analysis = journalSnapshot?.let { snapshot ->
                RecordingAnalyzer.analyze(
                    snapshot,
                    RecordingClassificationConfig(
                        touchSlopPx = ViewConfiguration.get(appContext).scaledTouchSlop.toDouble(),
                        tapTimeoutMillis = ViewConfiguration.getTapTimeout().toLong(),
                        longPressTimeoutMillis = ViewConfiguration.getLongPressTimeout().toLong(),
                    ),
                )
            }
            val rawReplayAttempt = journalSnapshot?.let { snapshot ->
                runCatching { RecordingRawTrace.fromJournal(snapshot) }
            }
            val rawReplayTrace = rawReplayAttempt?.getOrNull()
            val rawReplayUnavailableReason = when {
                rawReplayTrace == null -> rawReplayAttempt?.exceptionOrNull()?.message
                    ?: "The recording did not contain a complete raw touch trace."

                observedThirdPartyPackages.size != 1 ->
                    "Raw replay currently requires exactly one recorded third-party target app. " +
                        "Edit the trace into a workflow before replaying across app switches."

                else -> null
            }
            val workflowConversion = analysis?.let { recordingAnalysis ->
                runCatching {
                    RecordingWorkflowConverter.convert(
                        analysis = recordingAnalysis,
                        allowedPackages = observedThirdPartyPackages.toList(),
                    )
                }
            }
            val convertedWorkflow = workflowConversion?.getOrNull()
            val workflowSave = convertedWorkflow?.let { draft ->
                runCatching { workflowRepository.saveRevision(draft.bundle) }
            }
            workflowSave?.getOrNull()?.let {
                runCatching {
                    ReplayWorkflowCatalog.addOrReplace(
                        appContext,
                        ReplayControllerWorkflow(
                            workflowId = convertedWorkflow.bundle.document.id,
                            revision = convertedWorkflow.bundle.document.revision,
                            name = convertedWorkflow.bundle.document.name,
                        ),
                    )
                }
            }
            store?.close()
            journal = null
            RecordingForegroundService.stop(appContext)
            ReplayFloatingController.setRecordingState(ControlCenterRecordingState.IDLE)
            ReplayFloatingController.showIdle()
            _state.value = CoordinateRecordingState.Review(
                CoordinateRecordingReview(
                    sessionId = requireNotNull(id),
                    initialForegroundPackage = firstThirdPartyForegroundPackage?.value,
                    currentForegroundPackage = currentForeground,
                    journalFileName = requireNotNull(file).name,
                    durationMillis = elapsedMillis(),
                    frameCount = frameCount,
                    gestureCount = analysis?.gestures?.size ?: 0,
                    inferredWaitCount = analysis?.inferredWaits?.size ?: 0,
                    foregroundTransitionCount = analysis?.foregroundTransitions?.size ?: 0,
                    incompleteTouchIgnored = analysis?.incompleteTouchIgnored ?: false,
                    editableWorkflow = convertedWorkflow?.bundle,
                    draftWarnings = convertedWorkflow?.warnings.orEmpty(),
                    draftUnavailableReason = workflowConversion?.exceptionOrNull()?.message
                        ?: if (analysis == null) "The recording did not produce reviewable events." else null,
                    workflowSaved = workflowSave?.isSuccess == true,
                    workflowSaveError = workflowSave?.exceptionOrNull()?.message,
                    rawReplayTrace = rawReplayTrace,
                    rawReplayTargetPackage = observedThirdPartyPackages.singleOrNull()
                        ?.takeIf { rawReplayUnavailableReason == null },
                    rawReplayUnavailableReason = rawReplayUnavailableReason,
                ),
            )
            reviewSucceeded = true
        } catch (error: Throwable) {
            runCatching { journal?.close() }
            journal = null
            RecordingForegroundService.stop(appContext)
            ReplayFloatingController.setRecordingState(ControlCenterRecordingState.IDLE)
            ReplayFloatingController.showIdle()
            _state.value = CoordinateRecordingState.Failed(
                "Recording review could not be generated: ${error.message ?: error.javaClass.simpleName}",
                journalFile?.name,
            )
        } finally {
            clearSessionFields(keepJournalFile = !reviewSucceeded)
        }
    }

    private fun failSession(reason: String) {
        if (!active || !sessionCleanup.claimTerminal()) return
        val stopCause = sessionCleanup.currentStopCause()
        active = false
        mainHandler.removeCallbacks(startSourceWhenEmergencyReadyRunnable)
        mainHandler.removeCallbacks(timerRunnable)
        mainHandler.removeCallbacks(foregroundPollRunnable)
        mainHandler.removeCallbacks(stopFallbackRunnable)
        source.abort()
        runCatching {
            settlePendingTouchBeforeClose(preservePartialTouch = stopCause?.preservePartialTouch ?: true)
        }
        runCatching { journal?.close() }
        journal = null
        RecordingForegroundService.stop(appContext)
        ReplayFloatingController.setRecordingState(ControlCenterRecordingState.IDLE)
        ReplayFloatingController.showIdle()
        _state.value = CoordinateRecordingState.Failed(reason, journalFile?.name)
        clearSessionFields(keepJournalFile = true)
    }

    private fun failImmediately(reason: String) {
        sessionCleanup.claimTerminal()
        runCatching { source.abort() }
        runCatching { journal?.close() }
        active = false
        mainHandler.removeCallbacks(startSourceWhenEmergencyReadyRunnable)
        mainHandler.removeCallbacks(timerRunnable)
        mainHandler.removeCallbacks(foregroundPollRunnable)
        mainHandler.removeCallbacks(stopFallbackRunnable)
        RecordingForegroundService.stop(appContext)
        ReplayFloatingController.setRecordingState(ControlCenterRecordingState.IDLE)
        ReplayFloatingController.showIdle()
        _state.value = CoordinateRecordingState.Failed(reason, journalFile?.name)
        clearSessionFields(keepJournalFile = true)
    }

    /** Process-lifecycle cleanup; the activity ViewModel calls this only when the process exits. */
    fun close() {
        if (active) {
            sessionCleanup.requestStop(RecordingStopCause.PROCESS_CLOSE)
            if (!sessionCleanup.claimTerminal()) return
            active = false
            stopRequested = true
            mainHandler.removeCallbacks(startSourceWhenEmergencyReadyRunnable)
            mainHandler.removeCallbacks(timerRunnable)
            mainHandler.removeCallbacks(foregroundPollRunnable)
            mainHandler.removeCallbacks(stopFallbackRunnable)
            runCatching { source.close() }
            runCatching {
                settlePendingTouchBeforeClose(
                    preservePartialTouch = sessionCleanup.currentStopCause()?.preservePartialTouch ?: true,
                )
            }
            runCatching { journal?.close() }
            journal = null
            RecordingForegroundService.stop(appContext)
            ReplayFloatingController.setRecordingState(ControlCenterRecordingState.IDLE)
            ReplayFloatingController.showIdle()
            _state.value = CoordinateRecordingState.Failed(
                "Recording ended because Veyarc was closed.",
                journalFile?.name,
            )
            clearSessionFields(keepJournalFile = true)
        } else {
            runCatching { source.close() }
        }
        ReplayFloatingController.clearRecordingActions()
        runCatching { workflowRepository.close() }
        eventExecutor.shutdownNow()
    }

    private fun clearSessionFields(keepJournalFile: Boolean = false) {
        sessionId = null
        viewport = null
        lastForegroundPackage = null
        firstThirdPartyForegroundPackage = null
        lastThirdPartyForegroundPackage = null
        observedThirdPartyPackages.clear()
        hasForegroundObservation = false
        captureReady = false
        recordingStartReceivedTimestampMicros = null
        sourceError = null
        stopFailureReason = null
        minX = 0L
        maxX = 0L
        minY = 0L
        maxY = 0L
        frameCount = 0L
        lastElapsedMillis = 0L
        pendingTouchLines = null
        pendingTouchSawControlSurface = false
        ignoredTouchActive = false
        activeTouchRotation = null
        stopRequested = false
        pauseRequested = false
        paused = false
        sessionCleanup.reset()
        if (!keepJournalFile) journalFile = null
    }

    /**
     * Settles the in-memory tail before the journal is closed. Control-surface touches are
     * never persisted; a genuine partial third-party touch is retained only when the source
     * ended unexpectedly, not after an explicit user stop.
     */
    private fun settlePendingTouchBeforeClose(preservePartialTouch: Boolean) {
        val lines = pendingTouchLines ?: return
        val sawControlSurface = pendingTouchSawControlSurface
        pendingTouchLines = null
        pendingTouchSawControlSurface = false
        activeTouchRotation = null
        val retainedLines = if (sawControlSurface || !preservePartialTouch) {
            lines.filterIsInstance<RecordingJournalLine.ForegroundPackage>()
        } else {
            lines
        }
        retainedLines.forEach { line ->
            journal?.append(line)
            if (line is RecordingJournalLine.Touch) frameCount += 1L
        }
    }

    private fun appendElapsed(rawElapsed: Long): Long {
        val next = rawElapsed.coerceAtLeast(lastElapsedMillis)
        lastElapsedMillis = next
        return next
    }

    private fun elapsedMillis(): Long = (SystemClock.elapsedRealtime() - sessionStartedElapsedRealtime).coerceAtLeast(0L)

    private fun enqueue(block: () -> Unit) {
        if (!active) return
        try {
            eventExecutor.execute(block)
        } catch (_: RejectedExecutionException) {
            if (active) failSession("Recording input arrived faster than the bounded safety queue can process; recording stopped.")
        }
    }

    private fun isKeyguardLocked(): Boolean = appContext.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

    private fun isControlSurface(packageName: PackageName?): Boolean {
        if (packageName == null ||
            packageName.value == appContext.packageName ||
            packageName.value == SYSTEM_UI_PACKAGE ||
            packageName.value == "android"
        ) {
            return true
        }
        val homePackage = runCatching {
            appContext.packageManager.resolveActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_DEFAULT_ONLY,
            )?.activityInfo?.packageName
        }.getOrNull()
        return packageName.value == homePackage
    }

    private fun notificationText(foregroundPackage: String?, elapsedMillis: Long): String = if (foregroundPackage == null) {
        "Global recording ${elapsedMillis / 1_000}s · foreground unknown"
    } else {
        "Global recording ${elapsedMillis / 1_000}s · $foregroundPackage"
    }

    private companion object {
        const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        const val MAX_RECORDING_DURATION_MILLIS = 5L * 60L * 1_000L
        const val MIN_RESUME_DURATION_MILLIS = 1_000L
        const val TIMER_INTERVAL_MILLIS = 250L
        const val FOREGROUND_POLL_INTERVAL_MILLIS = 250L
        const val EMERGENCY_CONTROL_READY_POLL_MILLIS = 50L
        const val EMERGENCY_CONTROL_READY_TIMEOUT_MILLIS = 2_000L
        const val STOP_FALLBACK_DELAY_MILLIS = 1_500L
        const val PENDING_TOUCH_FRAME_LIMIT = 16_384
    }
}
