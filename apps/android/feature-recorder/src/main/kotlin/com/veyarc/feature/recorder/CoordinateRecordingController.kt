package com.veyarc.feature.recorder

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ViewConfiguration
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.veyarc.automation.android.AndroidDeviceSafety
import com.veyarc.automation.android.AndroidForegroundPackage
import com.veyarc.automation.android.AndroidUnsafeDeviceState
import com.veyarc.automation.android.ControlCenterRecordingState
import com.veyarc.automation.android.MediaProjectionCaptureRuntime
import com.veyarc.automation.android.MediaProjectionCaptureService
import com.veyarc.automation.android.RecordingControllerActions
import com.veyarc.automation.android.ReplayControllerWorkflow
import com.veyarc.automation.android.ReplayFloatingController
import com.veyarc.automation.android.ReplayWorkflowCatalog
import com.veyarc.automation.android.ShizukuTouchRecording
import com.veyarc.automation.android.ShizukuTouchRecordingEvent
import com.veyarc.automation.android.currentDisplayRotation
import com.veyarc.automation.android.currentRecordingViewportSnapshot
import com.veyarc.data.storage.AesGcmFileCodec
import com.veyarc.data.storage.AndroidKeystoreKeySource
import com.veyarc.data.storage.EncryptedRecordingJournalStore
import com.veyarc.data.storage.WorkflowRepository
import com.veyarc.feature.editor.RecordingImageDraft
import com.veyarc.feature.editor.RecordingInsertion
import com.veyarc.feature.editor.StepListPath
import com.veyarc.feature.editor.WorkflowDraft
import com.veyarc.feature.editor.WorkflowEditorSession
import com.veyarc.feature.editor.encryptTemplateBitmap
import com.veyarc.feature.editor.insertRecording
import com.veyarc.model.PackageName
import com.veyarc.model.bundle.WorkflowBundle
import com.veyarc.recording.RawTouchCoordinateMapper
import com.veyarc.recording.RecordingAnalyzer
import com.veyarc.recording.RecordingClassificationConfig
import com.veyarc.recording.RecordingCoordinateSpace
import com.veyarc.recording.RecordingJournalLine
import com.veyarc.recording.RecordingJournalSnapshot
import com.veyarc.recording.RecordingOrientationPolicy
import com.veyarc.recording.RecordingRawTrace
import com.veyarc.recording.RecordingTouchAction
import com.veyarc.recording.RecordingWorkflowConverter
import com.veyarc.recording.TouchDisplayRotation
import com.veyarc.recording.segmentAfter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
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
    var sourceDraft: WorkflowDraft? = null
        private set
    val editorSession = WorkflowEditorSession()
    val repository: WorkflowRepository get() = workflowRepository
    private val _builder = MutableStateFlow(RecordingBuilderState())
    val builder: StateFlow<RecordingBuilderState> = _builder.asStateFlow()

    @Volatile private var preparingSegment = false

    @Volatile private var finishAfterSegment = false
    private var consumedEvents = 0
    private var lastSegmentPackage: String? = null
    private var insertion by mutableStateOf(RecordingInsertion())
    private var openBuilderOnPause = false
    private var pendingAdd = false
    private var pendingStartFolderId: String? = null
    private var pendingWorkflowStart: (() -> Unit)? = null
    private var interruptedDraft = false
    private val captureScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var captureJob: Job? = null
    private var imageRequestId: String? = null

    @Volatile private var captureOwner: Any? = null
    private var finalSegmentReady = false
    private var pausedAtRealtime = 0L

    @Volatile private var pausedDurationMillis = 0L
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
    private var resumeRequested = false

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
    private var controlPackages = emptySet<String>()

    // ponytail: exclusion boundaries are session-only; persist them if journal re-import is added.
    private val excludedControlTransitions = mutableSetOf<Long>()

    /** App-process monotonic origin for frame timing; avoids device/helper clock mismatches. */
    private var recordingStartReceivedTimestampMicros: Long? = null
    private var minX = 0L
    private var maxX = 0L
    private var minY = 0L
    private var maxY = 0L
    private var frameCount = 0L
    private var sourceError: String? = null
    private var stopFailureReason: String? = null
    private var destinationWorkflowFolderId: String? = null

    /**
     * Touch frames stay in memory until the raw contact ends. This lets us discard an entire
     * gesture when the user opens Quick Settings, the launcher, or another control surface
     * halfway through it instead of persisting only its DOWN/MOVE prefix.
     */
    private var pendingTouchLines: MutableList<RecordingJournalLine>? = null
    private var pendingTouchSawControlSurface = false
    private var ignoredTouchActive = false
    private var activeTouchRotation: TouchDisplayRotation? = null
    private var sessionRotation: TouchDisplayRotation? = null

    private val controlCenterActions = RecordingControllerActions(
        onStart = ::start,
        onPause = ::pause,
        onResume = ::resume,
        onStop = ::stop,
        onServiceLost = { onRecordingServiceLost() },
        onAdd = { requestImageBlock() },
        onSteps = ::openBuilder,
    )

    private val stopLatchObservation = ReplayFloatingController.observeEnabled(appContext) { enabled ->
        if (!enabled && active) stop()
    }

    init {
        ReplayFloatingController.setRecordingActions(controlCenterActions)
    }

    /** True while the global recorder is accepting a new recording session. */
    fun isSessionActive(): Boolean = active && !stopRequested

    /** True while the helper is shutting down after an explicit stop request. */
    fun isSessionStopping(): Boolean = active && stopRequested

    /** True while the journal remains open but raw-input observation is paused. */
    fun isSessionPaused(): Boolean = active && paused && !stopRequested

    /** A completed recording is opened in the editor, not retained as a second review page. */
    fun dismissReview() {
        if (!active && _state.value is CoordinateRecordingState.Review) _state.value = CoordinateRecordingState.Idle
    }

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
                observeForeground(AndroidForegroundPackage.currentForRecording())
                val safety = AndroidDeviceSafety.current(appContext)
                if (safety == AndroidUnsafeDeviceState.ACCESSIBILITY_DISABLED || safety == AndroidUnsafeDeviceState.ACCESSIBILITY_DISCONNECTED) {
                    failSession("Recording stopped because Accessibility disconnected.")
                    return@enqueue
                }
                if (active && isKeyguardLocked()) {
                    failSession("Recording stopped because the lock screen became active.")
                    return@enqueue
                }
                if (active && !isCurrentSessionRotation()) {
                    failSession("Recording stopped because the display rotated during recording.")
                }
            }
            mainHandler.postDelayed(this, FOREGROUND_POLL_INTERVAL_MILLIS)
        }
    }

    private val startSourceWhenEmergencyReadyRunnable = object : Runnable {
        override fun run() {
            if (!active || stopRequested || paused || pauseRequested) return
            if (RecordingForegroundService.isReady()) {
                startInputSource(MAX_RECORDING_DURATION_MILLIS - elapsedMillis())
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

    fun start() = start(destinationFolderId = null)

    fun startInWorkflow(baseDraft: WorkflowDraft, destination: RecordingInsertion, assets: List<com.veyarc.model.bundle.BundleAsset>): Boolean {
        if (active) return false
        val prepared = baseDraft.copy(assets = assets)
        require(destination.isValid(prepared)) { "Choose a valid recording section." }
        val actual = currentRecordingViewportSnapshot(appContext)?.viewport
        if (actual != prepared.viewport) {
            showUnavailable("Record in this workflow's original orientation and screen size.")
            return false
        }
        if (interruptedDraft) {
            pendingWorkflowStart = { startInWorkflow(baseDraft, destination, assets) }
            _builder.value = _builder.value.copy(confirmRestart = true)
            openRecorderActivity()
            return false
        }
        startNew(destinationFolderId = null, initiallyPaused = true)
        if (!isSessionActive()) return false
        editorSession.openRecordingDraft(prepared)
        sourceDraft = baseDraft
        insertion = destination.anchored(prepared)
        _builder.value = _builder.value.copy(isOpen = true, destination = destination.label(prepared))
        return true
    }

    fun start(destinationFolderId: String?) {
        if (active) return
        if (interruptedDraft) {
            pendingWorkflowStart = null
            pendingStartFolderId = destinationFolderId
            _builder.value = _builder.value.copy(confirmRestart = true)
            openRecorderActivity()
            return
        }
        startNew(destinationFolderId)
    }

    fun dismissRestart() {
        pendingWorkflowStart = null
        _builder.value = _builder.value.copy(confirmRestart = false)
    }

    fun confirmRestart() {
        val workflowStart = pendingWorkflowStart
        dismissRestart()
        interruptedDraft = false
        if (workflowStart != null) workflowStart() else startNew(pendingStartFolderId)
    }

    private fun startNew(destinationFolderId: String?, initiallyPaused: Boolean = false) {
        if (active) return
        if (!ReplayFloatingController.isEnabled(appContext)) {
            showUnavailable("Turn on the edge latch before recording.")
            return
        }
        AndroidDeviceSafety.current(appContext)?.takeUnless { it == AndroidUnsafeDeviceState.SYSTEM_WINDOW_ACTIVE }?.let {
            showUnavailable("Recording unavailable: ${it.reason}.")
            return
        }
        val sessionControlPackages = runCatching {
            setOf(appContext.packageName, SYSTEM_UI_PACKAGE, "android") + appContext.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY).map { it.activityInfo.packageName }
        }.getOrElse {
            showUnavailable("Android's Home screen could not be identified safely.")
            return
        }
        val notificationUnavailableReason = RecordingForegroundService.notificationUnavailableReason(appContext)
        if (notificationUnavailableReason != null) {
            showUnavailable(notificationUnavailableReason)
            return
        }
        val recordingSnapshot = currentRecordingViewportSnapshot(appContext)
        val recordingRotation = recordingSnapshot?.displayRotation
            ?.let(TouchDisplayRotation::fromAndroidRotation)
        if (recordingSnapshot == null || recordingRotation == null) {
            showUnavailable(
                "Recording requires a full-display viewport. Close split-screen or freeform windows and try again.",
            )
            return
        }
        val recordingViewport = recordingSnapshot.viewport
        val shizukuUnavailableReason = source.prepareForRecording()
        if (shizukuUnavailableReason != null) {
            showUnavailable(shizukuUnavailableReason)
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
            showUnavailable(
                "Could not create the encrypted recording journal: ${error.message ?: error.javaClass.simpleName}",
            )
            return
        }

        sourceDraft = null
        interruptedDraft = false
        editorSession.createForAssistant(recordingViewport)
        editorSession.update(requireNotNull(editorSession.draft).copy(name = "Recorded workflow"))
        _builder.value = RecordingBuilderState()
        insertion = RecordingInsertion()
        consumedEvents = 0
        lastSegmentPackage = null
        preparingSegment = false
        finishAfterSegment = false
        finalSegmentReady = false
        pausedDurationMillis = 0L
        pausedAtRealtime = if (initiallyPaused) SystemClock.elapsedRealtime() else 0L
        sessionId = id
        journalFile = file
        journal = openedJournal
        viewport = recordingViewport
        lastForegroundPackage = null
        firstThirdPartyForegroundPackage = null
        lastThirdPartyForegroundPackage = null
        observedThirdPartyPackages.clear()
        excludedControlTransitions.clear()
        controlPackages = sessionControlPackages
        hasForegroundObservation = false
        recordingStartReceivedTimestampMicros = null
        frameCount = 0L
        sourceError = null
        pendingTouchLines = null
        pendingTouchSawControlSurface = false
        ignoredTouchActive = false
        activeTouchRotation = null
        sessionRotation = recordingRotation
        stopRequested = false
        pauseRequested = false
        paused = initiallyPaused
        resumeRequested = false
        stopFailureReason = null
        if (!sessionCleanup.begin()) {
            runCatching { openedJournal.close() }
            showUnavailable(
                "The previous recording is still cleaning up. Try again in a moment.",
            )
            return
        }
        destinationWorkflowFolderId = destinationFolderId
        active = true
        sessionStartedElapsedRealtime = SystemClock.elapsedRealtime()
        lastElapsedMillis = 0L
        _state.value = if (initiallyPaused) CoordinateRecordingState.Paused(null, 0, 0) else CoordinateRecordingState.Arming(elapsedMillis = 0L)
        ReplayFloatingController.setRecordingState(if (initiallyPaused) ControlCenterRecordingState.PAUSED else ControlCenterRecordingState.RECORDING)
        try {
            RecordingForegroundService.start(
                appContext,
                if (initiallyPaused) "Recording paused" else "Global recording armed. Stop from Quick Settings or this notification.",
            )
            emergencyControlDeadline =
                SystemClock.elapsedRealtime() + EMERGENCY_CONTROL_READY_TIMEOUT_MILLIS
            mainHandler.removeCallbacks(startSourceWhenEmergencyReadyRunnable)
            if (!initiallyPaused) mainHandler.post(startSourceWhenEmergencyReadyRunnable)
        } catch (error: Throwable) {
            failImmediately("Could not start recording safely: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun showUnavailable(reason: String) {
        _state.value = CoordinateRecordingState.Unavailable(reason)
        Toast.makeText(appContext, reason, Toast.LENGTH_LONG).show()
    }

    private fun startInputSource(durationMillis: Long) {
        if (!active || stopRequested || paused || pauseRequested) return
        if (!ReplayFloatingController.isEnabled(appContext)) {
            stop()
            return
        }
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

    fun openBuilder() {
        if (!isSessionActive()) return
        if (captureJob?.isActive == true) cancelImageCapture()
        openBuilderOnPause = true
        dismissAdd()
        if (!paused) pause()
        if (!pauseRequested && !preparingSegment) showBuilder()
    }

    fun closeBuilder() {
        _builder.value = _builder.value.copy(isOpen = false, showAdd = false)
    }

    fun discard() {
        if (active) requestStop(RecordingStopCause.USER_STOP, "Recording cancelled.")
    }

    fun dismissAdd(token: String? = null) {
        if (token != null && imageRequestId != token) return
        imageRequestId = null
        pendingAdd = false
        _builder.value = _builder.value.copy(showAdd = false)
    }

    fun requestImageBlock(): Boolean {
        if (!isSessionActive() || captureJob?.isActive == true || _builder.value.showAdd) return false
        if (_builder.value.image != null) {
            showBuilder()
            return false
        }
        if (editorSession.draft?.let(insertion::isValid) != true) {
            warnPaused("Choose a recording section with Record here first.")
            return false
        }
        if (!isCurrentSessionRotation()) {
            warnPaused("Rotate back to the recording orientation before taking a screenshot.")
            return false
        }
        pendingAdd = true
        if (!paused) pause()
        if (!pauseRequested && !preparingSegment) showImageBlockMenu()
        return true
    }

    private fun showImageBlockMenu() {
        if (!isSessionPaused() || preparingSegment || pauseRequested) return
        pendingAdd = false
        openBuilderOnPause = false
        val token = UUID.randomUUID().toString()
        imageRequestId = token
        _builder.value = _builder.value.copy(showAdd = true, error = null)
        val intent = Intent().setClassName(appContext.packageName, "${appContext.packageName}.RecordingImageCaptureActivity")
            .putExtra(EXTRA_IMAGE_REQUEST, token).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { appContext.startActivity(intent) }.onFailure {
            dismissAdd()
            warnPaused("Could not open screenshot controls. Try again.")
        }
    }

    fun acceptsImageCapture(token: String?): Boolean = token != null && token == imageRequestId && isSessionPaused()

    fun dismissImage() {
        _builder.value = _builder.value.copy(image = null, imageKind = null)
    }

    /** Start while the consent activity is visible; it finishes before the frame is sampled. */
    fun captureImageBlock(token: String, kind: RecordingImageBlock, resultCode: Int, data: Intent) {
        if (!acceptsImageCapture(token) || !_builder.value.showAdd || captureJob?.isActive == true) return
        val expected = viewport ?: return
        val owner = Any()
        captureOwner = owner
        captureJob = captureScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                check(!MediaProjectionCaptureRuntime.isActive) { "Finish the current screen capture first." }
                check(isCurrentSessionRotation()) { "Rotate back to the recording orientation and try again." }
                check(ReplayFloatingController.hideForRecordingScreenshot(owner)) { "Pause recording before taking a screenshot." }
                MediaProjectionCaptureService.start(appContext, resultCode, data, owner = owner)
                _builder.value = _builder.value.copy(showAdd = false, preparing = true)
                runInterruptible(Dispatchers.IO) { check(MediaProjectionCaptureRuntime.awaitActive(5000)) { "Screen capture did not start." } }
                delay(350)
                withTimeout(2500) {
                    while (AndroidForegroundPackage.currentForRecording()?.value.let { it == null || it in controlPackages }) delay(50)
                }
                check(!isKeyguardLocked() && isCurrentSessionRotation()) { "Keep the phone unlocked in the recording orientation." }
                val background = runInterruptible(Dispatchers.Default) {
                    requireNotNull(MediaProjectionCaptureRuntime.captureLatestFrame()) { "No screenshot arrived. Try again." }.use { frame ->
                        val bitmap = frame.copyBitmap()
                        if (bitmap.width != expected.recordedWidthPx || bitmap.height != expected.recordedHeightPx) {
                            bitmap.recycle()
                            error("Keep the recording screen size and orientation.")
                        }
                        encryptTemplateBitmap(bitmap)
                    }
                }
                if (acceptsImageCapture(token)) {
                    _builder.value = _builder.value.copy(image = RecordingImageDraft(background), imageKind = kind, preparing = false)
                    showBuilder()
                }
            } catch (failure: Exception) {
                if (failure is CancellationException && failure !is TimeoutCancellationException) throw failure
                if (acceptsImageCapture(token)) {
                    warnPaused(if (failure is TimeoutCancellationException) "Open the recorded app and try taking the screenshot again." else failure.message ?: "Could not capture this screen. Try again.")
                    _builder.value = _builder.value.copy(preparing = false, showAdd = false)
                    showBuilder()
                }
            } finally {
                MediaProjectionCaptureService.stop(appContext, owner = owner)
                ReplayFloatingController.finishRecordingScreenshot(owner, returnToSteps = false)
                if (captureOwner === owner) captureOwner = null
                if (acceptsImageCapture(token)) _builder.value = _builder.value.copy(preparing = false, showAdd = false)
                if (imageRequestId == token) imageRequestId = null
            }
        }
    }

    private fun cancelImageCapture() {
        captureJob?.cancel()
        captureOwner?.let { owner ->
            MediaProjectionCaptureService.stop(appContext, owner = owner)
            ReplayFloatingController.finishRecordingScreenshot(owner, returnToSteps = false)
        }
    }

    private fun warnPaused(message: String) {
        _builder.value = _builder.value.copy(error = message)
        mainHandler.post { Toast.makeText(appContext, message, Toast.LENGTH_LONG).show() }
    }

    fun dismissWarning() {
        _builder.value = _builder.value.copy(error = null)
    }

    fun setDestination(path: StepListPath, index: Int) {
        if (!isSessionPaused() || pauseRequested || preparingSegment) return
        val draft = editorSession.draft ?: return
        val chosen = RecordingInsertion(path, index).anchored(draft)
        if (!chosen.isValid(draft)) return
        insertion = chosen
        _builder.value = _builder.value.copy(destination = chosen.label(draft), error = null)
    }

    fun continueOutside() {
        val draft = editorSession.draft ?: return
        val outside = insertion.outside(draft) ?: return
        val parent = insertion.path.segments.last().parentId
        insertion = outside
        editorSession.update(draft.copy(collapsedStepIds = draft.collapsedStepIds + parent))
        _builder.value = _builder.value.copy(destination = outside.label(draft), error = null)
    }

    val selectedInsertion: RecordingInsertion get() = insertion
    val isInsideSection: Boolean get() = selectedInsertion.path.segments.isNotEmpty()
    val recordingDestination: String get() = editorSession.draft?.let(insertion::label) ?: "Workflow"

    fun addBlock(step: com.veyarc.model.WorkflowStep, assets: List<com.veyarc.model.bundle.BundleAsset> = emptyList(), imageName: String = "", editCondition: Boolean = false) {
        if (!isSessionPaused() || pauseRequested || preparingSegment) return
        val current = editorSession.draft ?: return
        if (!insertion.isValid(current)) {
            _builder.value = _builder.value.copy(error = "Choose a recording section first.")
            return
        }
        insertion = requireNotNull(insertion.resolve(current))
        val addedAt = insertion
        var draft = current
        for ((index, asset) in assets.withIndex()) draft = draft.addAsset(asset, if (index == 0) "Crop" else "Background")
        if (imageName.isNotBlank() && assets.isNotEmpty()) draft = draft.renameAsset(assets.first().assetId, imageName)
        draft = draft.insertSteps(insertion.path, insertion.index, listOf(step))
        val branch = when (step) {
            is com.veyarc.model.IfElse -> com.veyarc.feature.editor.NestedBranch.IF_THEN
            is com.veyarc.model.WhileStep -> com.veyarc.feature.editor.NestedBranch.WHILE_BODY
            is com.veyarc.model.Repeat -> com.veyarc.feature.editor.NestedBranch.REPEAT_BODY
            else -> null
        }
        insertion = if (branch != null) RecordingInsertion(insertion.path.child(step.id, branch), 0) else insertion.copy(index = insertion.index + 1).anchored(draft)
        editorSession.update(draft.copy(collapsedStepIds = draft.collapsedStepIds - step.id))
        if (editCondition) editorSession.editConditionAt(addedAt.path, addedAt.index)
        _builder.value = _builder.value.copy(showAdd = false, image = null, imageKind = null, destination = insertion.label(draft), error = null)
    }

    private fun showBuilder() {
        if (!isSessionPaused() || preparingSegment || pauseRequested) return
        _builder.value = _builder.value.copy(isOpen = true, showAdd = false, preparing = false)
        openBuilderOnPause = false
        pendingAdd = false
        imageRequestId = null
        openRecorderActivity()
    }

    private fun openRecorderActivity() {
        val intent = Intent(ACTION_OPEN_RECORDING).setClassName(appContext.packageName, "${appContext.packageName}.MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        runCatching { appContext.startActivity(intent) }
    }

    /** The event worker freezes the journal first; only the main thread edits the live draft. */
    private fun prepareSegment() {
        if (!active || preparingSegment) return
        preparingSegment = true
        val id = sessionId
        val snapshot = journal?.snapshot() ?: run {
            failSession("The recording journal is unavailable.")
            return
        }
        val segment = RecordingJournalSnapshot(snapshot.header, snapshot.events, snapshot.cursor, 0, 0, true).segmentAfter(consumedEvents)
        val originalFirst = snapshot.events.getOrNull(consumedEvents)
        val origin = when (originalFirst) {
            is RecordingJournalLine.Touch -> originalFirst.elapsedMillis
            is RecordingJournalLine.ForegroundPackage -> originalFirst.elapsedMillis
            else -> 0L
        }
        val analysis = runCatching {
            RecordingAnalyzer.analyze(
                segment,
                RecordingClassificationConfig(
                    ViewConfiguration.get(appContext).scaledTouchSlop.toDouble(),
                    ViewConfiguration.getTapTimeout().toLong(),
                    ViewConfiguration.getLongPressTimeout().toLong(),
                ),
            )
        }.getOrElse {
            failSession("Could not prepare recorded actions: ${it.message}")
            return
        }
        val converted = if (segment.events.any { it is RecordingJournalLine.Touch } || analysis.foregroundTransitions.any { it.currentPackage in observedThirdPartyPackages }) {
            runCatching { RecordingWorkflowConverter.convert(analysis, observedThirdPartyPackages.toList(), excludedControlTransitions = excludedControlTransitions.mapTo(mutableSetOf()) { it - origin }) }
                .getOrElse {
                    failSession("Could not prepare recorded actions: ${it.message}")
                    return
                }
        } else {
            null
        }
        val lastPackage = lastThirdPartyForegroundPackage?.value
        mainHandler.post {
            if (!active || sessionId != id) return@post
            try {
                if (converted != null) {
                    val merged = requireNotNull(editorSession.draft).insertRecording(converted.bundle, insertion, lastSegmentPackage)
                    editorSession.update(merged.first)
                    insertion = merged.second
                    lastSegmentPackage = lastPackage
                }
                consumedEvents = snapshot.events.size
                preparingSegment = false
                _builder.value = _builder.value.copy(preparing = false, destination = editorSession.draft?.let(insertion::label) ?: "Workflow")
                if (finishAfterSegment) {
                    finalSegmentReady = true
                    enqueue { finishSession() }
                } else if (pendingAdd) {
                    showImageBlockMenu()
                } else if (openBuilderOnPause) {
                    showBuilder()
                } else if (resumeRequested) {
                    resume()
                }
            } catch (error: Exception) {
                failSession("Could not place recorded actions: ${error.message}")
            }
        }
    }

    /** Pauses raw observation without closing the encrypted journal. */
    fun pause() {
        if (!active || stopRequested || paused || pauseRequested) return
        pauseRequested = true
        paused = true
        pausedAtRealtime = SystemClock.elapsedRealtime()
        _builder.value = _builder.value.copy(preparing = true)
        mainHandler.removeCallbacks(startSourceWhenEmergencyReadyRunnable)
        mainHandler.removeCallbacks(timerRunnable)
        mainHandler.removeCallbacks(foregroundPollRunnable)
        _state.value = CoordinateRecordingState.Paused(
            foregroundPackage = lastThirdPartyForegroundPackage?.value,
            elapsedMillis = elapsedMillis(),
            frameCount = frameCount,
        )
        ReplayFloatingController.setRecordingState(ControlCenterRecordingState.PAUSED)
        RecordingForegroundService.update(appContext, "Recording paused")
        if (!source.stop()) enqueue { completePause() }
    }

    /** Resumes raw observation using the same encrypted journal and session clock. */
    fun resume() {
        if (!active || stopRequested || !paused) return
        if (!ReplayFloatingController.isEnabled(appContext)) {
            stop()
            return
        }
        if (captureJob?.isActive == true || _builder.value.image != null || _builder.value.showAdd) return
        if (pauseRequested || preparingSegment) {
            resumeRequested = true
            return
        }
        resumeRequested = false
        if (isKeyguardLocked()) {
            failSession("Recording stopped because the lock screen became active.")
            return
        }
        if (!isCurrentSessionRotation()) {
            warnPaused("Rotate back to the recording orientation before continuing. Your recording is still paused.")
            return
        }
        val elapsed = elapsedMillis()
        val remaining = MAX_RECORDING_DURATION_MILLIS - elapsed
        if (remaining < MIN_RESUME_DURATION_MILLIS) {
            requestStop(
                cause = RecordingStopCause.TIMEOUT,
                failureReason = "Recording reached its five-minute safety limit while paused.",
            )
            return
        }
        val draft = editorSession.draft
        if (draft == null || !insertion.isValid(draft)) {
            _builder.value = _builder.value.copy(isOpen = true, error = "Choose Record here in a section before resuming.")
            return
        }
        insertion = requireNotNull(insertion.resolve(draft))
        pausedDurationMillis += (SystemClock.elapsedRealtime() - pausedAtRealtime).coerceAtLeast(0L)
        pausedAtRealtime = 0L
        _builder.value = _builder.value.copy(isOpen = false, error = null, destination = insertion.label(draft))
        finalSegmentReady = false
        paused = false
        recordingStartReceivedTimestampMicros = null
        pendingTouchLines = null
        pendingTouchSawControlSurface = false
        ignoredTouchActive = false
        activeTouchRotation = null
        sourceError = null
        _state.value = CoordinateRecordingState.Arming(elapsedMillis = elapsed)
        ReplayFloatingController.setRecordingState(ControlCenterRecordingState.RECORDING)
        RecordingForegroundService.update(appContext, "Recording resumed")
        emergencyControlDeadline = SystemClock.elapsedRealtime() + EMERGENCY_CONTROL_READY_TIMEOUT_MILLIS
        mainHandler.post(startSourceWhenEmergencyReadyRunnable)
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
        cancelImageCapture()
        _builder.value = _builder.value.copy(isOpen = false, showAdd = false, preparing = true)
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
                if (!active || stopRequested) return@enqueue
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
                observeForeground(AndroidForegroundPackage.currentForRecording())
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
                    completePause()
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

    private fun completePause() {
        if (!active || stopRequested || !pauseRequested) return
        settlePendingTouchBeforeClose(preservePartialTouch = false)
        observeForeground(null, isPause = true)
        if (!active) return
        _state.value = CoordinateRecordingState.Paused(
            foregroundPackage = lastThirdPartyForegroundPackage?.value,
            elapsedMillis = elapsedMillis(),
            frameCount = frameCount,
        )
        ReplayFloatingController.setRecordingState(ControlCenterRecordingState.PAUSED)
        RecordingForegroundService.update(appContext, "Recording paused")
        pauseRequested = false
        prepareSegment()
    }

    private fun processFrame(event: ShizukuTouchRecordingEvent.Frame) {
        if (stopRequested || paused || pauseRequested) return
        val foreground = AndroidForegroundPackage.currentForRecording()
        observeForeground(foreground)
        if (!active) return
        if (isKeyguardLocked()) {
            failSession("Recording stopped because the lock screen became active.")
            return
        }
        if (recordingStartReceivedTimestampMicros == null) return
        val currentViewport = viewport ?: return
        val startingTouch = pendingTouchLines == null && event.action == ShizukuTouchRecordingEvent.Action.DOWN
        val rotation = currentDisplayRotation(appContext)?.let(TouchDisplayRotation::fromAndroidRotation)
            ?: run {
                failSession("The display rotation could not be read safely during recording.")
                return
            }
        val initialRotation = sessionRotation
        if (initialRotation == null ||
            !RecordingOrientationPolicy.captureRotationUnchanged(initialRotation, rotation)
        ) {
            failSession("Recording stopped because the display rotated during recording.")
            return
        }
        // Restarting observation can leave an orphan MOVE/UP from the resume control tap.
        if (!startingTouch && pendingTouchLines == null && !ignoredTouchActive) return
        if (startingTouch) {
            activeTouchRotation = rotation
        } else if (activeTouchRotation != rotation) {
            failSession("Display rotation changed during an active touch; recording stopped safely.")
            return
        }
        // The helper's kernel timestamp and the app process can use different clock domains on
        // OEM builds. The wrapper captures an app-process monotonic timestamp for every callback,
        // so journal timing remains ordered and reflects the real gesture duration.
        val elapsed = (event.receivedTimestampMicros / 1_000L - sessionStartedElapsedRealtime - pausedDurationMillis).coerceAtLeast(0L)
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
            if (event.action == ShizukuTouchRecordingEvent.Action.UP) {
                ignoredTouchActive = false
                activeTouchRotation = null
            }
            return
        }

        if (pendingTouchLines == null && packageControlSurface) {
            if (event.action == ShizukuTouchRecordingEvent.Action.DOWN) ignoredTouchActive = true
            return
        }
        if (startingTouch) {
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

    private fun observeForeground(packageName: PackageName?, isPause: Boolean = false) {
        if (hasForegroundObservation && packageName == lastForegroundPackage) return
        val line = RecordingJournalLine.ForegroundPackage(
            elapsedMillis = appendElapsed(elapsedMillis()),
            packageName = packageName,
        )
        if (isPause) excludedControlTransitions += line.elapsedMillis
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
            lines.filterIsInstance<RecordingJournalLine.ForegroundPackage>().forEach {
                if (isControlSurface(it.packageName)) excludedControlTransitions += it.elapsedMillis
                journal?.append(it)
            }
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
        if (!active) return
        if (!finalSegmentReady) {
            finishAfterSegment = true
            if (!preparingSegment) {
                settlePendingTouchBeforeClose(preservePartialTouch = false)
                prepareSegment()
            }
            return
        }
        if (!sessionCleanup.claimTerminal()) return
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
            val workflowConversion = runCatching {
                val build = requireNotNull(editorSession.draft).buildBundle()
                com.veyarc.recording.RecordingWorkflowDraft(
                    requireNotNull(build.bundle) { build.issues.firstOrNull()?.message ?: "Recording is not ready to save." },
                    analysis?.foregroundTransitions.orEmpty(),
                    emptyList(),
                )
            }
            val convertedWorkflow = workflowConversion.getOrNull()
            val workflowSave = convertedWorkflow?.let { draft ->
                runCatching {
                    workflowRepository.saveRevision(draft.bundle, destinationWorkflowFolderId)
                }
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
                    draftUnavailableReason = workflowConversion.exceptionOrNull()?.message
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
        interruptedDraft = true
        val stopCause = sessionCleanup.currentStopCause()
        active = false
        cancelImageCapture()
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
        interruptedDraft = true
        cancelImageCapture()
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
        cancelImageCapture()
        captureScope.cancel()
        stopLatchObservation()
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
        imageRequestId = null
        preparingSegment = false
        finishAfterSegment = false
        openBuilderOnPause = false
        pendingAdd = false
        _builder.value = RecordingBuilderState()
        sessionId = null
        viewport = null
        lastForegroundPackage = null
        firstThirdPartyForegroundPackage = null
        lastThirdPartyForegroundPackage = null
        observedThirdPartyPackages.clear()
        excludedControlTransitions.clear()
        hasForegroundObservation = false
        recordingStartReceivedTimestampMicros = null
        sourceError = null
        stopFailureReason = null
        destinationWorkflowFolderId = null
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
        sessionRotation = null
        stopRequested = false
        pauseRequested = false
        paused = false
        resumeRequested = false
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
            if (sawControlSurface && line is RecordingJournalLine.ForegroundPackage && isControlSurface(line.packageName)) excludedControlTransitions += line.elapsedMillis
            journal?.append(line)
            if (line is RecordingJournalLine.Touch) frameCount += 1L
        }
    }

    private fun appendElapsed(rawElapsed: Long): Long {
        val next = rawElapsed.coerceAtLeast(lastElapsedMillis)
        lastElapsedMillis = next
        return next
    }

    private fun elapsedMillis(): Long = ((if (paused && pausedAtRealtime > 0L) pausedAtRealtime else SystemClock.elapsedRealtime()) - sessionStartedElapsedRealtime - pausedDurationMillis).coerceAtLeast(0L)

    private fun isCurrentSessionRotation(): Boolean {
        val initial = sessionRotation ?: return false
        val current = currentDisplayRotation(appContext)?.let(TouchDisplayRotation::fromAndroidRotation)
            ?: return false
        return RecordingOrientationPolicy.captureRotationUnchanged(initial, current)
    }

    private fun enqueue(block: () -> Unit) {
        if (!active) return
        try {
            eventExecutor.execute(block)
        } catch (_: RejectedExecutionException) {
            if (active) failSession("Recording input arrived faster than the bounded safety queue can process; recording stopped.")
        }
    }

    private fun isKeyguardLocked(): Boolean = appContext.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != false

    private fun isControlSurface(packageName: PackageName?): Boolean = packageName == null || packageName.value in controlPackages

    private fun notificationText(foregroundPackage: String?, elapsedMillis: Long): String = if (foregroundPackage == null) {
        "Global recording ${elapsedMillis / 1_000}s · foreground unknown"
    } else {
        "Global recording ${elapsedMillis / 1_000}s · $foregroundPackage"
    }

    companion object {
        const val ACTION_OPEN_RECORDING = "com.veyarc.action.OPEN_RECORDING"
        const val EXTRA_IMAGE_REQUEST = "imageRequest"
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
