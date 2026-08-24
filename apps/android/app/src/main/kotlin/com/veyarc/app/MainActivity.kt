package com.veyarc.app

import android.app.Application
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
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var resumeGeneration by mutableIntStateOf(0)
    private var showAccessibilityHarness by mutableStateOf(false)
    private var showRecorder by mutableStateOf(false)
    private var workflowToRun by mutableStateOf<WorkflowBundle?>(null)
    private val runSession: MainRunSession by lazy {
        ViewModelProvider(this)[MainRunSession::class.java]
    }
    private val workflowRepository: WorkflowRepository
        get() = runSession.repository
    private val recordingSession: MainRecordingSession by lazy {
        ViewModelProvider(this)[MainRecordingSession::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeGeneration += 1
    }

    private fun openRunScreen(bundle: WorkflowBundle) {
        runSession.open(bundle)
        workflowToRun = bundle
    }

    private fun leaveRunScreen() {
        runSession.leave()
        workflowToRun = null
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
            } catch (error: Exception) {
                Log.e(TAG, "Could not recover unfinished workflow runs", error)
                _recoveryState.value = RunRecoveryState.FAILED
            }
        }
    }

    fun open(bundle: WorkflowBundle) {
        controller?.close("a different workflow was opened")
        controller = WorkflowRunController(getApplication(), repository, bundle.document)
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
