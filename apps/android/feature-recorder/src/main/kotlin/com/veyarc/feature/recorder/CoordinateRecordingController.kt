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
import com.veyarc.automation.android.ShizukuTouchRecording
import com.veyarc.automation.android.ShizukuTouchRecordingEvent
import com.veyarc.automation.android.currentRecordingViewport
import com.veyarc.data.storage.AesGcmFileCodec
import com.veyarc.data.storage.AndroidKeystoreKeySource
import com.veyarc.data.storage.EncryptedRecordingJournalStore
import com.veyarc.model.PackageName
import com.veyarc.recording.RecordingAnalyzer
import com.veyarc.recording.RecordingClassificationConfig
import com.veyarc.recording.RecordingJournalLine
import com.veyarc.recording.RecordingJournalSnapshot
import com.veyarc.recording.RecordingTouchAction
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
    private var sessionStartedElapsedRealtime = 0L

    private var lastElapsedMillis = 0L
    private var sessionId: String? = null
    private var journalFile: File? = null
    private var journal: EncryptedRecordingJournalStore? = null
    private var viewport: com.veyarc.model.ViewportSpec? = null
    private var lastForegroundPackage: PackageName? = null
    private var firstThirdPartyForegroundPackage: PackageName? = null
    private var lastThirdPartyForegroundPackage: PackageName? = null
    private var hasForegroundObservation = false

    /** False until a complete touch starts after the session source is armed. */
    private var captureReady = false
    private var sourceStartTimestampMicros: Long? = null
    private var minX = 0L
    private var maxX = 0L
    private var minY = 0L
    private var maxY = 0L
    private var frameCount = 0L
    private var sourceError: String? = null

    /**
     * Touch frames stay in memory until the raw contact ends. This lets us discard an entire
     * gesture when the user opens Quick Settings, the launcher, or another control surface
     * halfway through it instead of persisting only its DOWN/MOVE prefix.
     */
    private var pendingTouchLines: MutableList<RecordingJournalLine>? = null
    private var pendingTouchSawControlSurface = false
    private var ignoredTouchActive = false

    /** True while the global recorder is accepting a new recording session. */
    fun isSessionActive(): Boolean = active && !stopRequested

    /** True while the helper is shutting down after an explicit stop request. */
    fun isSessionStopping(): Boolean = active && stopRequested

    private val timerRunnable = object : Runnable {
        override fun run() {
            if (!active || stopRequested) return
            val elapsed = elapsedMillis()
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

                else -> Unit
            }
            mainHandler.postDelayed(this, TIMER_INTERVAL_MILLIS)
        }
    }

    private val stopFallbackRunnable = Runnable {
        if (active && stopRequested) enqueue { finishSession() }
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
        hasForegroundObservation = false
        captureReady = false
        sourceStartTimestampMicros = null
        frameCount = 0L
        sourceError = null
        pendingTouchLines = null
        pendingTouchSawControlSurface = false
        ignoredTouchActive = false
        stopRequested = false
        active = true
        sessionStartedElapsedRealtime = SystemClock.elapsedRealtime()
        lastElapsedMillis = 0L
        _state.value = CoordinateRecordingState.Arming(elapsedMillis = 0L)
        try {
            RecordingForegroundService.start(
                appContext,
                "Global recording armed. Stop from Quick Settings or this notification.",
            )
            val startError = source.start(
                durationMillis = MAX_RECORDING_DURATION_MILLIS,
                listener = ::onSourceEvent,
            )
            if (startError != null) {
                failImmediately(startError)
                return
            }
            mainHandler.removeCallbacks(timerRunnable)
            mainHandler.post(timerRunnable)
            mainHandler.removeCallbacks(stopFallbackRunnable)
            mainHandler.postDelayed(::checkForeground, FOREGROUND_POLL_INTERVAL_MILLIS)
        } catch (error: Throwable) {
            failImmediately("Could not start recording safely: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    /** Cooperative stop used by Quick Settings, the notification, back navigation, and cleanup. */
    fun stop() {
        if (!active) {
            if (_state.value is CoordinateRecordingState.Arming ||
                _state.value is CoordinateRecordingState.Recording
            ) {
                _state.value = CoordinateRecordingState.Idle
            }
            return
        }
        stopRequested = true
        mainHandler.removeCallbacks(timerRunnable)
        source.stop()
        RecordingQuickSettingsTileService.requestRefresh(appContext)
        mainHandler.removeCallbacks(stopFallbackRunnable)
        mainHandler.postDelayed(stopFallbackRunnable, STOP_FALLBACK_DELAY_MILLIS)
    }

    private fun onSourceEvent(event: ShizukuTouchRecordingEvent) {
        when (event) {
            is ShizukuTouchRecordingEvent.Started -> enqueue {
                if (!active) return@enqueue
                sourceStartTimestampMicros = event.startTimestampMicros
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
                if (sourceError == null) finishSession() else failSession(sourceError!!)
            }
        }
    }

    private fun processFrame(event: ShizukuTouchRecordingEvent.Frame) {
        if (stopRequested) return
        val foreground = AndroidForegroundPackage.current()
        observeForeground(foreground)
        if (!active) return
        if (isKeyguardLocked()) {
            failSession("Recording stopped because the lock screen became active.")
            return
        }
        // Veyarc, the unlocked notification shade, the launcher, and unknown windows are
        // control surfaces. Their package transitions remain review metadata, but their
        // control taps must not become workflow gestures.
        val controlSurface = isControlSurface(foreground)
        if (ignoredTouchActive) {
            if (event.action == ShizukuTouchRecordingEvent.Action.UP) {
                ignoredTouchActive = false
            }
            return
        }

        // A start action can leave a final control-tap UP in the raw stream. Do not append
        // that orphan; begin the global trace at the next complete third-party touch.
        if (!captureReady && event.action != ShizukuTouchRecordingEvent.Action.DOWN) return
        if (pendingTouchLines == null && controlSurface) {
            if (event.action == ShizukuTouchRecordingEvent.Action.DOWN) {
                ignoredTouchActive = true
            }
            return
        }
        val startingTouch = pendingTouchLines == null && event.action == ShizukuTouchRecordingEvent.Action.DOWN
        if (startingTouch) {
            captureReady = true
            pendingTouchLines = ArrayList(PENDING_TOUCH_FRAME_LIMIT)
            pendingTouchSawControlSurface = false
        }
        val pendingLines = pendingTouchLines ?: return
        if (!startingTouch && event.action == ShizukuTouchRecordingEvent.Action.DOWN) {
            failSession("The raw-input helper delivered a second touch before the previous one ended.")
            return
        }
        if (controlSurface) pendingTouchSawControlSurface = true

        val startTimestamp = sourceStartTimestampMicros ?: return
        val currentViewport = viewport ?: return
        val elapsed = ((event.timestampMicros - startTimestamp) / 1_000L).coerceAtLeast(0L)
        val monotonicElapsed = appendElapsed(elapsed)
        val xRange = maxX - minX
        val yRange = maxY - minY
        if (xRange <= 0L || yRange <= 0L) {
            failSession("The touchscreen coordinate range became invalid during recording.")
            return
        }
        val x = ((event.x.coerceIn(minX, maxX) - minX).toDouble() / xRange) *
            (currentViewport.recordedWidthPx - 1).toDouble()
        val y = ((event.y.coerceIn(minY, maxY) - minY).toDouble() / yRange) *
            (currentViewport.recordedHeightPx - 1).toDouble()
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

    private fun checkForeground() {
        if (!active) return
        enqueue {
            if (!active || stopRequested) return@enqueue
            observeForeground(AndroidForegroundPackage.current())
            if (active && isKeyguardLocked()) {
                failSession("Recording stopped because the lock screen became active.")
            }
        }
        mainHandler.postDelayed(::checkForeground, FOREGROUND_POLL_INTERVAL_MILLIS)
    }

    private fun finishSession() {
        if (!active) return
        active = false
        mainHandler.removeCallbacks(timerRunnable)
        mainHandler.removeCallbacks(stopFallbackRunnable)
        source.stop()
        val store = journal
        val file = journalFile
        val id = sessionId
        val currentForeground = lastThirdPartyForegroundPackage?.value
        var reviewSucceeded = false
        try {
            // An explicit stop has already discarded the control-surface gesture. A helper
            // timeout/completion may still leave a genuine third-party touch without UP; keep
            // that partial prefix in the encrypted journal so review can report it honestly.
            settlePendingTouchBeforeClose(preservePartialTouch = !stopRequested)
            val encryptedSnapshot = store?.snapshot()
            val analysis = encryptedSnapshot?.let { snapshot ->
                RecordingAnalyzer.analyze(
                    RecordingJournalSnapshot(
                        header = snapshot.header,
                        events = snapshot.events,
                        cursor = snapshot.cursor,
                        trailingPartialBytes = 0,
                        validPrefixBytes = 0,
                        endsWithNewline = true,
                    ),
                    RecordingClassificationConfig(
                        touchSlopPx = ViewConfiguration.get(appContext).scaledTouchSlop.toDouble(),
                        tapTimeoutMillis = ViewConfiguration.getTapTimeout().toLong(),
                        longPressTimeoutMillis = ViewConfiguration.getLongPressTimeout().toLong(),
                    ),
                )
            }
            store?.close()
            journal = null
            RecordingForegroundService.stop(appContext)
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
                ),
            )
            reviewSucceeded = true
        } catch (error: Throwable) {
            runCatching { journal?.close() }
            journal = null
            RecordingForegroundService.stop(appContext)
            _state.value = CoordinateRecordingState.Failed(
                "Recording review could not be generated: ${error.message ?: error.javaClass.simpleName}",
                journalFile?.name,
            )
        } finally {
            clearSessionFields(keepJournalFile = !reviewSucceeded)
        }
    }

    private fun failSession(reason: String) {
        if (!active) return
        active = false
        mainHandler.removeCallbacks(timerRunnable)
        mainHandler.removeCallbacks(stopFallbackRunnable)
        source.stop()
        runCatching { settlePendingTouchBeforeClose(preservePartialTouch = !stopRequested) }
        runCatching { journal?.close() }
        journal = null
        RecordingForegroundService.stop(appContext)
        _state.value = CoordinateRecordingState.Failed(reason, journalFile?.name)
        clearSessionFields(keepJournalFile = true)
    }

    private fun failImmediately(reason: String) {
        runCatching { source.stop() }
        runCatching { journal?.close() }
        active = false
        mainHandler.removeCallbacks(timerRunnable)
        mainHandler.removeCallbacks(stopFallbackRunnable)
        RecordingForegroundService.stop(appContext)
        _state.value = CoordinateRecordingState.Failed(reason, journalFile?.name)
        clearSessionFields(keepJournalFile = true)
    }

    /** Process-lifecycle cleanup; the activity ViewModel calls this only when the process exits. */
    fun close() {
        if (active) {
            active = false
            stopRequested = true
            mainHandler.removeCallbacks(timerRunnable)
            mainHandler.removeCallbacks(stopFallbackRunnable)
            runCatching { source.close() }
            runCatching { settlePendingTouchBeforeClose(preservePartialTouch = true) }
            runCatching { journal?.close() }
            journal = null
            RecordingForegroundService.stop(appContext)
            _state.value = CoordinateRecordingState.Failed(
                "Recording ended because Veyarc was closed.",
                journalFile?.name,
            )
            clearSessionFields(keepJournalFile = true)
        } else {
            runCatching { source.close() }
        }
        eventExecutor.shutdownNow()
    }

    private fun clearSessionFields(keepJournalFile: Boolean = false) {
        sessionId = null
        viewport = null
        lastForegroundPackage = null
        firstThirdPartyForegroundPackage = null
        lastThirdPartyForegroundPackage = null
        hasForegroundObservation = false
        captureReady = false
        sourceStartTimestampMicros = null
        sourceError = null
        minX = 0L
        maxX = 0L
        minY = 0L
        maxY = 0L
        frameCount = 0L
        lastElapsedMillis = 0L
        pendingTouchLines = null
        pendingTouchSawControlSurface = false
        ignoredTouchActive = false
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
        const val TIMER_INTERVAL_MILLIS = 250L
        const val FOREGROUND_POLL_INTERVAL_MILLIS = 250L
        const val STOP_FALLBACK_DELAY_MILLIS = 1_500L
        const val PENDING_TOUCH_FRAME_LIMIT = 16_384
    }
}
