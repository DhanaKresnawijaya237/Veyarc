package com.veyarc.feature.runner

import android.content.Context
import android.util.Log
import com.veyarc.automation.android.AccessibilityEmergencyOverlay
import com.veyarc.automation.android.AccessibilityGestureDispatcher
import com.veyarc.automation.android.AndroidAutomationCapabilities
import com.veyarc.automation.android.AndroidCapabilitySnapshot
import com.veyarc.automation.android.AndroidWorkflowExecutor
import com.veyarc.automation.android.ReplayControllerActions
import com.veyarc.automation.android.ReplayFloatingController
import com.veyarc.data.storage.StoredRunStatus
import com.veyarc.data.storage.WorkflowRepository
import com.veyarc.engine.MutableExecutionControl
import com.veyarc.engine.RunEvent
import com.veyarc.engine.RunFailureCode
import com.veyarc.engine.RunOutcome
import com.veyarc.engine.WorkflowCompilationResult
import com.veyarc.engine.WorkflowCompiler
import com.veyarc.model.WorkflowDocument
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

enum class WorkflowRunPhase {
    REVIEW,
    STARTING,
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
) {
    val isValid: Boolean
        get() = compilation.isSuccess

    val isRunnable: Boolean
        get() = isValid && capabilities.serviceConnected

    val plan
        get() = compilation.plan
}

data class RedactedRunEvent(
    val atMs: Long,
    val message: String,
)

data class WorkflowRunUiState(
    val phase: WorkflowRunPhase,
    val workflowName: String,
    val revision: Int,
    val review: WorkflowRunReview,
    val runId: String? = null,
    val completedSteps: Int = 0,
    val message: String? = null,
    val failureMessage: String? = null,
    val eventHistory: List<RedactedRunEvent> = emptyList(),
    val notificationReady: Boolean,
)

/** Coordinates review, deterministic execution, safety controls, notification, and run metadata. */
class WorkflowRunController(
    context: Context,
    private val repository: WorkflowRepository,
    private val document: WorkflowDocument,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val notifications = RunNotificationController(appContext)
    private val executor = AndroidWorkflowExecutor(appContext)
    private val _state = MutableStateFlow(initialState())
    private var runJob: Job? = null
    private var control: MutableExecutionControl? = null

    @Volatile private var closed = false

    @Volatile private var closeReason: String? = null

    private val floatingControllerActions = ReplayControllerActions(
        onResume = ::resume,
        onStop = ::stop,
        onServiceLost = { stop() },
        onPause = ::pause,
    )

    val state: StateFlow<WorkflowRunUiState> = _state.asStateFlow()

    fun refreshNotificationState() {
        _state.update { it.copy(notificationReady = notifications.isReady()) }
    }

    fun refreshReview() {
        if (_state.value.phase != WorkflowRunPhase.REVIEW) return
        val capabilities = AndroidAutomationCapabilities.snapshot(appContext)
        val compilation = WorkflowCompiler.compile(document, capabilities.engineCapabilities)
        _state.update {
            it.copy(
                review = WorkflowRunReview(capabilities, compilation),
                message = if (compilation.isSuccess) "Review workflow before starting" else "Workflow cannot run",
                notificationReady = notifications.isReady(),
            )
        }
    }

    fun start() {
        val current = _state.value
        if (current.phase != WorkflowRunPhase.REVIEW || !current.review.isRunnable || !current.notificationReady) return
        if (runJob?.isActive == true) return

        ReplayFloatingController.setReplayActions(floatingControllerActions)
        if (!ReplayFloatingController.showReplay()) {
            _state.update { it.copy(message = "Veyarc edge-latch controller could not be created safely. Run was not started.") }
            return
        }
        WorkflowRunRuntime.register(this)
        val executionControl = MutableExecutionControl()
        control = executionControl
        _state.update {
            it.copy(
                phase = WorkflowRunPhase.STARTING,
                runId = null,
                completedSteps = 0,
                message = "Starting workflow…",
                failureMessage = null,
                eventHistory = emptyList(),
            )
        }
        runJob = scope.launch {
            execute(executionControl)
        }
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
        _state.update { state ->
            if (state.phase == WorkflowRunPhase.RUNNING || state.phase == WorkflowRunPhase.PAUSED) {
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
        scope.cancel()
    }

    private suspend fun execute(executionControl: MutableExecutionControl) {
        var runId: String? = null
        var terminalStatus = StoredRunStatus.FAILED
        var terminalReason: String? = null
        try {
            runId = withContext(Dispatchers.IO) {
                repository.beginRun(document.id, document.revision)
            }
            _state.update { it.copy(runId = runId, phase = WorkflowRunPhase.RUNNING, message = "Running") }
            AccessibilityEmergencyOverlay.show(::stop)
            notifications.showRunning(document.name, 0, _state.value.review.plan?.analysis?.worstCaseStepCount)

            val execution = withContext(Dispatchers.Default) {
                executor.execute(
                    document = document,
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
            Log.e(TAG, "Workflow execution failed for ${document.id}", error)
            terminalStatus = StoredRunStatus.FAILED
            terminalReason = "EXECUTION_ERROR: $detail"
            updateTerminal(WorkflowRunPhase.FAILED, displayMessage)
        } finally {
            if (runId != null) {
                val completedRunId = runId
                val status = terminalStatus
                val reason = terminalReason
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching {
                        repository.finishRun(completedRunId, status, interruptionReason = reason)
                    }
                }
            }
            AccessibilityEmergencyOverlay.hide()
            ReplayFloatingController.clearReplayActions()
            ReplayFloatingController.showIdle()
            WorkflowRunRuntime.unregister(this)
            if (!closed) {
                notifications.showTerminal(_state.value.message ?: "Workflow finished")
            }
            control = null
        }
    }

    private fun updateTerminal(phase: WorkflowRunPhase, message: String?) {
        _state.update { it.copy(phase = phase, message = message, failureMessage = if (phase == WorkflowRunPhase.FAILED) message else null) }
    }

    private fun onEvent(event: RunEvent) {
        val redacted = RedactedRunEvent(event.atMs, redactEvent(event))
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
                    current.review.plan?.analysis?.worstCaseStepCount,
                    paused = true,
                )
            }

            is RunEvent.Resumed -> {
                if (ReplayFloatingController.showReplay()) {
                    val current = _state.value
                    notifications.showRunning(
                        document.name,
                        current.completedSteps,
                        current.review.plan?.analysis?.worstCaseStepCount,
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
            notifications.showRunning(document.name, current.completedSteps, current.review.plan?.analysis?.worstCaseStepCount)
        }
    }

    private fun initialState(): WorkflowRunUiState {
        val capabilities = AndroidAutomationCapabilities.snapshot(appContext)
        val compilation = WorkflowCompiler.compile(document, capabilities.engineCapabilities)
        return WorkflowRunUiState(
            phase = WorkflowRunPhase.REVIEW,
            workflowName = document.name,
            revision = document.revision,
            review = WorkflowRunReview(capabilities, compilation),
            message = if (compilation.isSuccess) "Review workflow before starting" else "Workflow cannot run",
            notificationReady = notifications.isReady(),
        )
    }

    private companion object {
        const val TAG = "Veyarc.WorkflowRun"
        const val MAX_EVENT_HISTORY = 50

        fun redactEvent(event: RunEvent): String = when (event) {
            is RunEvent.Started -> "Run started"
            is RunEvent.StepStarted -> "Step started"
            is RunEvent.ConditionEvaluated -> "Condition evaluated: ${event.result}"
            is RunEvent.StepFinished -> "Step ${event.outcome.name.lowercase()}"
            is RunEvent.Paused -> "Paused"
            is RunEvent.Resumed -> "Resumed"
            is RunEvent.Finished -> "Run finished: ${event.outcome.name.lowercase()}"
        }
    }
}
