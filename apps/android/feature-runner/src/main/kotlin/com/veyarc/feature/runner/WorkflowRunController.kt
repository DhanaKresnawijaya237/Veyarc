package com.veyarc.feature.runner

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.veyarc.automation.android.AccessibilityEmergencyOverlay
import com.veyarc.automation.android.AccessibilityGestureDispatcher
import com.veyarc.automation.android.AndroidAutomationCapabilities
import com.veyarc.automation.android.AndroidCapabilitySnapshot
import com.veyarc.automation.android.AndroidWorkflowExecutor
import com.veyarc.automation.android.MediaProjectionCaptureRuntime
import com.veyarc.automation.android.MediaProjectionCaptureService
import com.veyarc.automation.android.MediaProjectionConsent
import com.veyarc.automation.android.MediaProjectionDisplaySpec
import com.veyarc.automation.android.ReplayControllerActions
import com.veyarc.automation.android.ReplayFloatingController
import com.veyarc.automation.android.currentRecordingViewport
import com.veyarc.data.storage.STORED_RUN_EVENT_LIMIT
import com.veyarc.data.storage.StoredRunEvent
import com.veyarc.data.storage.StoredRunStatus
import com.veyarc.data.storage.WorkflowRepository
import com.veyarc.engine.CompilationErrorCode
import com.veyarc.engine.EngineCapability
import com.veyarc.engine.MutableExecutionControl
import com.veyarc.engine.RunEvent
import com.veyarc.engine.RunFailureCode
import com.veyarc.engine.RunOutcome
import com.veyarc.engine.WorkflowCompilationResult
import com.veyarc.engine.WorkflowCompiler
import com.veyarc.engine.referencedImageAssets
import com.veyarc.model.DisplayOrientation
import com.veyarc.model.WorkflowDocument
import com.veyarc.model.bundle.BundleAsset
import com.veyarc.model.usesVisualConditions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable

enum class WorkflowRunPhase {
    REVIEW,
    PREPARING,
    RUNNING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED,
    STOPPED,
}

data class WorkflowRunReview(
    val capabilities: AndroidCapabilitySnapshot,
    val compilation: WorkflowCompilationResult,
    val currentOrientation: DisplayOrientation?,
    val orientationBlockReason: String?,
    val latchEnabled: Boolean,
) {
    val isValid: Boolean
        get() = compilation.isSuccess

    val isRunnable: Boolean
        get() = isValid && capabilities.runReady && latchEnabled && orientationBlockReason == null

    val visibleCompilationErrors
        get() = compilation.errors.filterNot {
            !capabilities.gestureDispatchReady && it.code == CompilationErrorCode.UNSUPPORTED_CAPABILITY &&
                it.capability in ACCESSIBILITY_CAPABILITIES
        }

    val plan
        get() = compilation.plan

    private companion object {
        val ACCESSIBILITY_CAPABILITIES = setOf(
            EngineCapability.TAP_POINT,
            EngineCapability.TAP_REGION,
            EngineCapability.LONG_PRESS,
            EngineCapability.SWIPE_PATH,
            EngineCapability.FOREGROUND_PACKAGE_CONDITION,
        )
    }
}

data class RedactedRunEvent(
    val atMs: Long,
    val message: String,
    val blockLabel: String? = null,
    val contextLabel: String? = null,
    val stepId: String? = null,
)

data class WorkflowRunUiState(
    val phase: WorkflowRunPhase,
    val workflowName: String,
    val revision: Int,
    val review: WorkflowRunReview,
    val runId: String? = null,
    val completedSteps: Int = 0,
    val preparationDurationMs: Long? = null,
    val message: String? = null,
    val failureMessage: String? = null,
    val eventHistory: List<RedactedRunEvent> = emptyList(),
    val notificationReady: Boolean,
    val startedAtMs: Long = System.currentTimeMillis(),
    val finishedAtMs: Long? = null,
    val startingValues: Map<String, String> = emptyMap(),
    val startingValuesValid: Boolean = true,
    val sequenceName: String? = null,
)

/** Coordinates review, deterministic execution, safety controls, notification, and run metadata. */
class WorkflowRunController(
    context: Context,
    private val repository: WorkflowRepository,
    private val document: WorkflowDocument,
    private val assets: List<BundleAsset>? = null,
    val entrySectionId: String? = null,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val notifications = RunNotificationController(appContext)
    private val executor = AndroidWorkflowExecutor(appContext)
    private val eventFormatter = RunEventFormatter(document)
    private val _state = MutableStateFlow(initialState())
    private var runJob: Job? = null
    private var control: MutableExecutionControl? = null
    private var mediaProjectionOwned = false
    private var mediaProjectionListener: Closeable? = null
    private val savedEvents = ArrayDeque<RedactedRunEvent>()
    private var savedEventCharacters = 0

    @Volatile private var closed = false

    @Volatile private var closeReason: String? = null

    private val floatingControllerActions = ReplayControllerActions(
        onResume = ::resume,
        onStop = ::stop,
        onServiceLost = { stop() },
        onPause = ::pause,
    )

    init {
        if (document.usesVisualConditions(entrySectionId)) {
            mediaProjectionListener = MediaProjectionCaptureRuntime.registerTerminationListener(::onMediaProjectionTerminated)
        }
    }

    val state: StateFlow<WorkflowRunUiState> = _state.asStateFlow()

    fun refreshNotificationState() {
        _state.update { it.copy(notificationReady = notifications.isReady()) }
    }

    fun updateStartingValue(name: String, text: String) {
        if (closed || name !in document.variables) return
        _state.update { current ->
            if (current.phase != WorkflowRunPhase.REVIEW) return@update current
            val values = current.startingValues + (name to text)
            current.copy(startingValues = values, startingValuesValid = document.withStartingValues(values) != null)
        }
    }

    fun resetStartingValues() {
        if (closed) return
        _state.update { current ->
            if (current.phase != WorkflowRunPhase.REVIEW) current else current.copy(startingValues = document.startingValues(), startingValuesValid = true)
        }
    }

    fun refreshReview() {
        if (closed || _state.value.phase != WorkflowRunPhase.REVIEW) return
        val capabilities = AndroidAutomationCapabilities.snapshot(appContext)
        val latchEnabled = ReplayFloatingController.isEnabled(appContext)
        val previousReview = _state.value.review
        val compilation = if (capabilities.engineCapabilities == previousReview.capabilities.engineCapabilities) previousReview.compilation else WorkflowCompiler.compile(document, capabilities.engineCapabilities, entrySectionId)
        val currentOrientation = currentRecordingViewport(appContext)?.recordedOrientation
        val orientationBlockReason = workflowOrientationBlockReason(
            required = document.viewport.recordedOrientation,
            current = currentOrientation,
        )
        _state.update {
            it.copy(
                review = WorkflowRunReview(
                    capabilities,
                    compilation,
                    currentOrientation,
                    orientationBlockReason,
                    latchEnabled,
                ),
                message = reviewMessage(capabilities, compilation, orientationBlockReason, latchEnabled),
                notificationReady = notifications.isReady(),
            )
        }
    }

    fun requiresMediaProjectionConsent(): Boolean = document.usesVisualConditions(entrySectionId)

    /** Handles the one-shot ActivityResult returned by Android's screen-capture consent dialog. */
    fun startAfterMediaProjectionConsent(resultCode: Int, data: Intent?) {
        if (closed) return
        refreshReview()
        if (!MediaProjectionConsent.wasGranted(resultCode, data)) {
            _state.update {
                it.copy(message = "Screen-capture consent was denied. The workflow did not start.")
            }
            return
        }
        if (closed || !isStartReady()) {
            if (!closed) {
                _state.update { it.copy(message = "The workflow is no longer ready to start. Refresh the run checks.") }
            }
            return
        }
        try {
            MediaProjectionCaptureService.start(
                context = appContext,
                resultCode = resultCode,
                resultData = requireNotNull(data),
                displaySpec = MediaProjectionDisplaySpec.fromContext(appContext),
            )
            mediaProjectionOwned = true
            start()
            // Consent can return after navigation, capability loss, or notification changes.
            // If start() rejected the request, do not leave a projection service running.
            if (_state.value.phase == WorkflowRunPhase.REVIEW) {
                stopOwnedMediaProjection()
            }
        } catch (error: Exception) {
            stopOwnedMediaProjection()
            _state.update {
                it.copy(
                    message = "Screen capture could not start: ${error.message ?: error.javaClass.simpleName}",
                )
            }
        }
    }

    fun reportMediaProjectionFailure(reason: String) {
        _state.update { it.copy(message = "Screen capture could not start: $reason") }
    }

    fun start() {
        if (!isStartReady()) return
        val runDocument = document.withStartingValues(_state.value.startingValues) ?: return
        val currentOrientation = currentRecordingViewport(appContext)?.recordedOrientation
        val orientationBlockReason = workflowOrientationBlockReason(
            required = document.viewport.recordedOrientation,
            current = currentOrientation,
        )
        if (orientationBlockReason != null) {
            _state.update {
                it.copy(
                    review = it.review.copy(
                        currentOrientation = currentOrientation,
                        orientationBlockReason = orientationBlockReason,
                    ),
                    message = orientationBlockReason,
                )
            }
            return
        }
        if (requiresMediaProjectionConsent() && !mediaProjectionOwned) {
            _state.update { it.copy(message = "Screen-capture consent is required before this workflow can start.") }
            return
        }
        if (runJob?.isActive == true) return

        ReplayFloatingController.setReplayActions(floatingControllerActions)
        if (!ReplayFloatingController.showReplay()) {
            _state.update { it.copy(message = "Veyarc edge-latch controller could not be created safely. Run was not started.") }
            return
        }
        WorkflowRunRuntime.register(this)
        val executionControl = MutableExecutionControl()
        control = executionControl
        synchronized(savedEvents) {
            savedEvents.clear()
            savedEventCharacters = 0
        }
        _state.update {
            it.copy(
                phase = WorkflowRunPhase.PREPARING,
                runId = null,
                completedSteps = 0,
                preparationDurationMs = null,
                message = "Preparing workflow…",
                failureMessage = null,
                eventHistory = emptyList(),
                startedAtMs = System.currentTimeMillis(),
                finishedAtMs = null,
            )
        }
        runJob = scope.launch {
            execute(executionControl, runDocument)
        }
    }

    private fun isStartReady(): Boolean {
        if (closed) return false
        refreshReview()
        val current = _state.value
        return current.phase == WorkflowRunPhase.REVIEW &&
            current.review.isRunnable &&
            current.startingValuesValid &&
            current.notificationReady
    }

    fun pause() {
        control?.requestPause()
        _state.update { state ->
            if (state.phase == WorkflowRunPhase.RUNNING) state.copy(message = "Pausing at the next safe boundary…") else state
        }
    }

    fun resume() {
        control?.resume()
        _state.update { state ->
            if (state.phase == WorkflowRunPhase.PAUSED) state.copy(message = "Resuming…") else state
        }
    }

    fun stop() {
        control?.cancel()
        AccessibilityGestureDispatcher.cancelActiveGesture()
        stopOwnedMediaProjection()
        _state.update { state ->
            if (
                state.phase == WorkflowRunPhase.PREPARING ||
                state.phase == WorkflowRunPhase.RUNNING ||
                state.phase == WorkflowRunPhase.PAUSED
            ) {
                state.copy(message = "Stopping…")
            } else {
                state
            }
        }
    }

    fun close(reason: String = "the controller was closed") {
        if (closed) return
        closeReason = reason
        closed = true
        control?.cancel()
        AccessibilityGestureDispatcher.cancelActiveGesture()
        AccessibilityEmergencyOverlay.hide()
        ReplayFloatingController.clearReplayActions()
        ReplayFloatingController.showIdle()
        WorkflowRunRuntime.unregister(this)
        notifications.dismiss()
        stopOwnedMediaProjection()
        mediaProjectionListener?.close()
        mediaProjectionListener = null
        scope.cancel()
    }

    private suspend fun execute(executionControl: MutableExecutionControl, runDocument: WorkflowDocument) {
        var runId: String? = null
        var templateSource: BundleImageTemplateSource? = null
        var terminalStatus = StoredRunStatus.FAILED
        var terminalReason: String? = null
        try {
            val preparationStartedAtMs = SystemClock.elapsedRealtime()
            val capabilities = AndroidAutomationCapabilities.snapshot(appContext)
            val compilation = WorkflowCompiler.compile(runDocument, capabilities.engineCapabilities, entrySectionId)
            val plan = compilation.plan ?: error(
                compilation.errors.joinToString(separator = "; ") { "${it.path}: ${it.message}" },
            )
            val requirements = plan.referencedImageAssets()
            if (requiresMediaProjectionConsent()) {
                check(MediaProjectionCaptureRuntime.awaitActive(MEDIA_PROJECTION_START_TIMEOUT_MILLIS)) {
                    "MediaProjection capture did not become ready before the workflow started"
                }
            }
            if (requirements.isNotEmpty()) {
                val memoryBudget = runTemplateMemoryBudgetBytes()
                templateSource = withContext(Dispatchers.IO) {
                    val transientAssets = assets
                    if (transientAssets == null) {
                        BundleImageTemplateSource.prepareStored(
                            repository.prepareRunAssets(
                                document.id,
                                document.revision,
                                requirements.keys,
                                memoryBudget,
                            ),
                            requirements,
                        )
                    } else {
                        BundleImageTemplateSource.prepareBundle(
                            assets = transientAssets,
                            requirements = requirements,
                            viewportWidthPx = document.viewport.recordedWidthPx,
                            viewportHeightPx = document.viewport.recordedHeightPx,
                            maxDecodedBytes = memoryBudget,
                        )
                    }
                }
            }
            val preparationDurationMs = SystemClock.elapsedRealtime() - preparationStartedAtMs
            if (executionControl.isCancellationRequested()) throw CancellationException("Run cancelled during preparation")
            val preparationEvent = RedactedRunEvent(
                SystemClock.elapsedRealtime(),
                "Preparation completed in $preparationDurationMs ms",
            )
            rememberEvent(preparationEvent)
            val startingEvents = eventFormatter.startingValues(runDocument.variables.mapValues { it.value.initialValue }, SystemClock.elapsedRealtime())
            startingEvents.forEach(::rememberEvent)
            _state.update {
                it.copy(
                    review = it.review.copy(capabilities = capabilities, compilation = compilation),
                    preparationDurationMs = preparationDurationMs,
                    eventHistory = (listOf(preparationEvent) + startingEvents).takeLast(MAX_EVENT_HISTORY),
                )
            }
            runId = withContext(Dispatchers.IO) {
                repository.beginRun(document.id, document.revision, name = _state.value.sequenceName?.let { "Sequence: $it" })
            }
            _state.update { it.copy(runId = runId, phase = WorkflowRunPhase.RUNNING, message = "Running") }
            AccessibilityEmergencyOverlay.show(::stop)
            notifications.showRunning(document.name, 0, _state.value.review.plan?.analysis?.estimatedStepCount)

            val execution = withContext(Dispatchers.Default) {
                executor.execute(
                    document = runDocument,
                    preparedCompilation = compilation,
                    templateSource = templateSource,
                    cancellation = executionControl,
                    pause = executionControl,
                    eventSink = ::onEvent,
                )
            }
            val run = execution.run
            if (run == null) {
                terminalStatus = StoredRunStatus.FAILED
                terminalReason = "COMPILATION_FAILED"
                updateTerminal(WorkflowRunPhase.FAILED, terminalReason)
            } else {
                when (run.outcome) {
                    RunOutcome.COMPLETED -> {
                        terminalStatus = StoredRunStatus.COMPLETED
                        updateTerminal(WorkflowRunPhase.COMPLETED, "Workflow completed")
                    }

                    RunOutcome.CANCELLED -> {
                        terminalStatus = StoredRunStatus.CANCELLED
                        terminalReason = "Stopped by user"
                        updateTerminal(WorkflowRunPhase.CANCELLED, terminalReason)
                    }

                    RunOutcome.STOPPED -> {
                        terminalStatus = StoredRunStatus.STOPPED
                        terminalReason = run.stopReason?.name ?: "Workflow stopped"
                        updateTerminal(WorkflowRunPhase.STOPPED, terminalReason)
                    }

                    RunOutcome.FAILED,
                    RunOutcome.TIMED_OUT,
                    -> {
                        val failure = run.failure
                        terminalStatus = StoredRunStatus.FAILED
                        val failureCode = failure?.code?.name ?: "EXECUTION_FAILED"
                        val displayMessage = when (failure?.code) {
                            RunFailureCode.INVALID_VARIABLE_VALUE -> failure.message

                            RunFailureCode.UNEXPECTED_FOREGROUND_PACKAGE ->
                                "${failure.message}. Add that app's package to Allowed Android packages, or make Launch app the first block."

                            RunFailureCode.FOREGROUND_UNAVAILABLE ->
                                "The foreground app could not be identified. Return to the target app and refresh the run checks."

                            else -> failure?.let { "${it.code.name}: ${it.message}" } ?: failureCode
                        }
                        terminalReason = displayMessage
                        updateTerminal(WorkflowRunPhase.FAILED, displayMessage)
                    }
                }
            }
        } catch (cancellation: CancellationException) {
            terminalStatus = StoredRunStatus.CANCELLED
            terminalReason = closeReason?.let { "Run cancelled because $it" } ?: "Run cancelled"
            if (!closed) {
                updateTerminal(WorkflowRunPhase.CANCELLED, terminalReason)
            }
            throw cancellation
        } catch (error: Exception) {
            val exceptionType = error.javaClass.simpleName.ifBlank { "Unknown exception" }
            val detail = error.message
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { "$exceptionType: $it" }
                ?: exceptionType
            val displayMessage = "Execution error: $detail"
            Log.e(TAG, "Workflow execution failed")
            terminalStatus = StoredRunStatus.FAILED
            terminalReason = "EXECUTION_ERROR: $detail"
            updateTerminal(WorkflowRunPhase.FAILED, displayMessage)
        } finally {
            if (runId != null) {
                val completedRunId = runId
                val status = terminalStatus
                val reason = terminalReason
                val terminalState = _state.value
                val savedHistory = synchronized(savedEvents) { savedEvents.map { StoredRunEvent(it.atMs, it.message, it.blockLabel, it.contextLabel, it.stepId) } }
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching {
                        repository.finishRun(
                            runId = completedRunId,
                            status = status,
                            interruptionReason = reason,
                            completedSteps = terminalState.completedSteps,
                            totalSteps = terminalState.review.plan?.analysis?.estimatedStepCount,
                            eventHistory = savedHistory,
                        )
                    }
                }
            }
            AccessibilityEmergencyOverlay.hide()
            ReplayFloatingController.clearReplayActions()
            ReplayFloatingController.showIdle()
            WorkflowRunRuntime.unregister(this)
            stopOwnedMediaProjection()
            templateSource?.close()
            if (!closed) {
                notifications.showTerminal(_state.value.message ?: "Workflow finished")
            }
            control = null
        }
    }

    private fun onMediaProjectionTerminated(reason: String) {
        mediaProjectionOwned = false
        control?.cancel()
        _state.update { state ->
            if (state.phase == WorkflowRunPhase.PREPARING ||
                state.phase == WorkflowRunPhase.RUNNING ||
                state.phase == WorkflowRunPhase.PAUSED
            ) {
                state.copy(message = "Stopping because screen capture ended: $reason")
            } else {
                state.copy(message = reason)
            }
        }
    }

    private fun stopOwnedMediaProjection() {
        if (!mediaProjectionOwned) return
        mediaProjectionOwned = false
        MediaProjectionCaptureService.stop(appContext)
    }

    private fun updateTerminal(phase: WorkflowRunPhase, message: String?) {
        _state.update { it.copy(phase = phase, message = message, failureMessage = if (phase == WorkflowRunPhase.FAILED) message else null, finishedAtMs = System.currentTimeMillis()) }
    }

    private fun runTemplateMemoryBudgetBytes(): Long {
        val memoryClassMiB = appContext.getSystemService(ActivityManager::class.java).memoryClass
        return maxOf(1L, memoryClassMiB.toLong() / RUN_TEMPLATE_MEMORY_DIVISOR) * BYTES_PER_MIB
    }

    private fun rememberEvent(event: RedactedRunEvent) = synchronized(savedEvents) {
        // ponytail: Bound saved activity by count and text size; complete logs need streaming storage.
        val saved = if (event.message.length > MAX_SAVED_EVENT_CHARACTERS) event.copy(message = event.message.take(MAX_SAVED_EVENT_CHARACTERS - 1) + "…") else event
        while (savedEvents.isNotEmpty() && (savedEvents.size >= STORED_RUN_EVENT_LIMIT || savedEventCharacters + saved.message.length > MAX_SAVED_HISTORY_CHARACTERS)) {
            savedEventCharacters -= savedEvents.removeFirst().message.length
        }
        savedEvents.addLast(saved)
        savedEventCharacters += saved.message.length
    }

    private fun onEvent(event: RunEvent) {
        val redacted = eventFormatter.event(event)
        rememberEvent(redacted)
        _state.update { current ->
            val nextPhase = when (event) {
                is RunEvent.Paused -> WorkflowRunPhase.PAUSED
                is RunEvent.Resumed -> WorkflowRunPhase.RUNNING
                else -> current.phase
            }
            val completed = current.completedSteps + if (event is RunEvent.StepFinished) 1 else 0
            current.copy(
                phase = nextPhase,
                completedSteps = completed,
                message = redacted.message,
                eventHistory = (current.eventHistory + redacted).takeLast(MAX_EVENT_HISTORY),
            )
        }
        when (event) {
            is RunEvent.Paused -> {
                ReplayFloatingController.showPaused(floatingControllerActions)
                val current = _state.value
                notifications.showRunning(
                    document.name,
                    current.completedSteps,
                    current.review.plan?.analysis?.estimatedStepCount,
                    paused = true,
                )
            }

            is RunEvent.Resumed -> {
                if (ReplayFloatingController.showReplay()) {
                    val current = _state.value
                    notifications.showRunning(
                        document.name,
                        current.completedSteps,
                        current.review.plan?.analysis?.estimatedStepCount,
                        paused = false,
                    )
                } else {
                    stop()
                }
            }

            else -> Unit
        }
        if (event is RunEvent.StepFinished) {
            val current = _state.value
            notifications.showRunning(document.name, current.completedSteps, current.review.plan?.analysis?.estimatedStepCount)
        }
    }

    private fun initialState(): WorkflowRunUiState {
        val capabilities = AndroidAutomationCapabilities.snapshot(appContext)
        val latchEnabled = ReplayFloatingController.isEnabled(appContext)
        val compilation = WorkflowCompiler.compile(document, capabilities.engineCapabilities, entrySectionId)
        val currentOrientation = currentRecordingViewport(appContext)?.recordedOrientation
        val orientationBlockReason = workflowOrientationBlockReason(
            required = document.viewport.recordedOrientation,
            current = currentOrientation,
        )
        return WorkflowRunUiState(
            phase = WorkflowRunPhase.REVIEW,
            workflowName = document.name,
            sequenceName = document.steps.filterIsInstance<com.veyarc.model.ReusableSection>().firstOrNull { it.id == entrySectionId }?.name,
            revision = document.revision,
            review = WorkflowRunReview(
                capabilities,
                compilation,
                currentOrientation,
                orientationBlockReason,
                latchEnabled,
            ),
            message = reviewMessage(capabilities, compilation, orientationBlockReason, latchEnabled),
            notificationReady = notifications.isReady(),
            startingValues = document.startingValues(),
        )
    }

    private companion object {
        const val TAG = "Veyarc.WorkflowRun"
        const val MAX_EVENT_HISTORY = 50
        const val MAX_SAVED_HISTORY_CHARACTERS = 128 * 1024
        const val MAX_SAVED_EVENT_CHARACTERS = 4096
        const val RUN_TEMPLATE_MEMORY_DIVISOR = 8L
        const val BYTES_PER_MIB = 1024L * 1024L
        const val MEDIA_PROJECTION_START_TIMEOUT_MILLIS = 5_000L
    }
}

private fun reviewMessage(
    capabilities: AndroidCapabilitySnapshot,
    compilation: WorkflowCompilationResult,
    orientationBlockReason: String?,
    latchEnabled: Boolean,
): String {
    capabilities.runBlockReason?.let { return it }
    if (!latchEnabled) return "Turn on the edge latch in Settings before running."
    if (!compilation.isSuccess) return "Workflow cannot run"
    return orientationBlockReason ?: "Review workflow before starting"
}

internal fun workflowOrientationBlockReason(
    required: DisplayOrientation,
    current: DisplayOrientation?,
): String? = when {
    current == null -> "Run unavailable because the current full-display orientation could not be determined."
    current != required -> "Rotate the device to ${required.name.lowercase()} before starting this workflow."
    else -> null
}
