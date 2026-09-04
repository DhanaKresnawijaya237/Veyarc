package com.veyarc.app

import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.veyarc.app.ui.theme.VeyarcTheme
import com.veyarc.automation.android.ReplayControllerWorkflow
import com.veyarc.automation.android.ReplayFloatingController
import com.veyarc.automation.android.ReplayWorkflowCatalog
import com.veyarc.data.storage.WorkflowRepository
import com.veyarc.feature.editor.WorkflowEditorScreen
import com.veyarc.feature.recorder.CoordinateRecorderScreen
import com.veyarc.feature.recorder.CoordinateRecordingRuntime
import com.veyarc.feature.runner.AccessibilitySpikeScreen
import com.veyarc.feature.runner.WorkflowRunController
import com.veyarc.feature.runner.WorkflowRunScreen
import com.veyarc.model.bundle.WorkflowBundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private var resumeGeneration by mutableIntStateOf(0)
    private var showAccessibilityHarness by mutableStateOf(false)
    private var showRecorder by mutableStateOf(false)
    private var workflowToRun by mutableStateOf<WorkflowBundle?>(null)
    private var recordedWorkflowToEdit by mutableStateOf<WorkflowBundle?>(null)
    private var recordedJournalFileName by mutableStateOf<String?>(null)
    private val runSession: MainRunSession by lazy {
        ViewModelProvider(this)[MainRunSession::class.java]
    }
    private val workflowRepository: WorkflowRepository
        get() = runSession.repository
    private val recordingSession: MainRecordingSession by lazy {
        ViewModelProvider(this)[MainRecordingSession::class.java]
    }
    private val catalogScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Register the process-owned recording actions before the user leaves Veyarc. This lets
        // the fixed service-owned control center start a recording from a third-party app instead
        // of requiring the recorder screen to have been opened first.
        recordingSession.controller
        workflowToRun = runSession.bundle
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (showAccessibilityHarness) {
                        showAccessibilityHarness = false
                    } else if (showRecorder) {
                        recordingSession.controller.stop()
                        showRecorder = false
                    } else if (workflowToRun != null) {
                        leaveRunScreen()
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            },
        )
        setContent {
            val recoveryState by runSession.recoveryState.collectAsState()
            VeyarcTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val bundle = workflowToRun
                    when {
                        showAccessibilityHarness -> AccessibilitySpikeScreen(
                            resumeGeneration = resumeGeneration,
                            onBack = { showAccessibilityHarness = false },
                            onStartRecording = {
                                showAccessibilityHarness = false
                                showRecorder = true
                            },
                        )

                        showRecorder -> CoordinateRecorderScreen(
                            controller = recordingSession.controller,
                            onBack = { showRecorder = false },
                            onEditWorkflow = { bundle, journalFileName ->
                                recordedWorkflowToEdit = bundle
                                recordedJournalFileName = journalFileName
                                showRecorder = false
                            },
                        )

                        bundle != null -> WorkflowRunScreen(
                            bundle = bundle,
                            controller = requireNotNull(runSession.controller) {
                                "A workflow run screen requires an active run controller"
                            },
                            canStart = recoveryState == RunRecoveryState.READY,
                            startBlockMessage = recoveryState.message,
                            onStart = runSession::startRun,
                            onBack = ::leaveRunScreen,
                        )

                        else -> WorkflowEditorScreen(
                            repository = workflowRepository,
                            onOpenAutomationHarness = { showAccessibilityHarness = true },
                            onRunWorkflow = ::openRunScreen,
                            initialBundle = recordedWorkflowToEdit,
                            initialRecordingJournalFileName = recordedJournalFileName,
                            onInitialBundleConsumed = {
                                recordedWorkflowToEdit = null
                                recordedJournalFileName = null
                            },
                        )
                    }
                }
            }
        }
        handleControllerIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        resumeGeneration += 1
        refreshReplayWorkflowCatalog()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleControllerIntent(intent)
    }

    override fun onDestroy() {
        catalogScope.cancel()
        super.onDestroy()
    }

    private fun openRunScreen(bundle: WorkflowBundle, autoStart: Boolean = false) {
        runSession.open(bundle)
        workflowToRun = bundle
        if (autoStart) startControllerReplayWhenReady()
    }

    /** Starts a controller-requested replay only after recovery and the normal safety checks pass. */
    private fun startControllerReplayWhenReady() {
        catalogScope.launch {
            val recovery = runSession.recoveryState.first { it != RunRecoveryState.RECOVERING }
            if (recovery != RunRecoveryState.READY || workflowToRun == null) return@launch
            val controller = runSession.controller ?: return@launch
            controller.refreshNotificationState()
            val state = controller.state.value
            // A service-owned emergency notification is mandatory. If it is unavailable, leave
            // the normal review screen visible rather than starting an unprotected replay.
            if (state.review.isRunnable && state.notificationReady) runSession.startRun()
        }
    }

    private fun leaveRunScreen() {
        runSession.leave()
        workflowToRun = null
    }

    private fun refreshReplayWorkflowCatalog() {
        catalogScope.launch {
            val workflows = runCatching {
                withContext(Dispatchers.IO) {
                    workflowRepository.listWorkflows().map { summary ->
                        ReplayControllerWorkflow(
                            workflowId = summary.workflowId,
                            revision = summary.latestRevision,
                            name = summary.name,
                        )
                    }
                }
            }.getOrElse { emptyList() }
            ReplayWorkflowCatalog.replace(applicationContext, workflows)
        }
    }

    private fun handleControllerIntent(intent: Intent?) {
        if (intent?.action != ReplayFloatingController.ACTION_OPEN_WORKFLOW) return
        val workflowId = intent.getStringExtra(ReplayFloatingController.EXTRA_WORKFLOW_ID)
            ?.takeIf { it.isNotBlank() }
        val revision = intent.getIntExtra(ReplayFloatingController.EXTRA_WORKFLOW_REVISION, -1)
        val autoStart = intent.getBooleanExtra(ReplayFloatingController.EXTRA_AUTO_START, false)
        if (workflowId == null || revision <= 0) {
            // The library action is intentionally separate from Replay-latest. CLEAR_TOP/
            // SINGLE_TOP can deliver it to an existing run screen, so explicitly return to the
            // editor when the user asks for the library.
            showAccessibilityHarness = false
            if (showRecorder) recordingSession.controller.stop()
            showRecorder = false
            leaveRunScreen()
            return
        }
        catalogScope.launch {
            val bundle = runCatching {
                withContext(Dispatchers.IO) {
                    workflowRepository.loadRevision(workflowId, revision)
                }
            }.getOrNull() ?: return@launch
            openRunScreen(bundle, autoStart = autoStart)
        }
    }
}

enum class RunRecoveryState(val message: String?) {
    RECOVERING("Recovering unfinished runs before a new run can start…"),
    READY(null),
    FAILED("Previous-run recovery failed. Restart Veyarc before starting a new run."),
}

class MainRunSession(application: Application) : AndroidViewModel(application) {
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _recoveryState = MutableStateFlow(RunRecoveryState.RECOVERING)

    val repository = WorkflowRepository(application)
    val recoveryState: StateFlow<RunRecoveryState> = _recoveryState.asStateFlow()
    var bundle: WorkflowBundle? = null
        private set
    var controller: WorkflowRunController? = null
        private set

    init {
        sessionScope.launch {
            try {
                repository.recoverInterruptedRuns()
                _recoveryState.value = RunRecoveryState.READY
            } catch (_: Exception) {
                Log.e(TAG, "Could not recover unfinished workflow runs")
                _recoveryState.value = RunRecoveryState.FAILED
            }
        }
    }

    fun open(bundle: WorkflowBundle) {
        controller?.close("a different workflow was opened")
        controller = WorkflowRunController(getApplication(), repository, bundle.document, bundle.assets)
        this.bundle = bundle
    }

    fun leave() {
        controller?.close("you left the run screen")
        controller = null
        bundle = null
    }

    fun startRun() {
        if (_recoveryState.value == RunRecoveryState.READY) {
            controller?.start()
        }
    }

    override fun onCleared() {
        controller?.close("the activity was finished")
        sessionScope.cancel()
        repository.close()
        super.onCleared()
    }

    private companion object {
        const val TAG = "Veyarc.RunSession"
    }
}

class MainRecordingSession(application: Application) : AndroidViewModel(application) {
    // The controller is process-owned so destroying/recreating MainActivity does not stop a
    // recording that is intentionally running in another app.
    val controller = CoordinateRecordingRuntime.controller(application)

    override fun onCleared() {
        CoordinateRecordingRuntime.close()
        super.onCleared()
    }
}
