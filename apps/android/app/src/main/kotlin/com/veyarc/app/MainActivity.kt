package com.veyarc.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.veyarc.app.ui.theme.VeyarcTheme
import com.veyarc.data.storage.WorkflowRepository
import com.veyarc.feature.editor.WorkflowEditorScreen
import com.veyarc.feature.runner.AccessibilitySpikeScreen
import com.veyarc.feature.runner.WorkflowRunScreen
import com.veyarc.model.bundle.WorkflowBundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var resumeGeneration by mutableIntStateOf(0)
    private var showAccessibilityHarness by mutableStateOf(false)
    private var workflowToRun by mutableStateOf<WorkflowBundle?>(null)
    private lateinit var workflowRepository: WorkflowRepository
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        workflowRepository = WorkflowRepository(applicationContext)
        activityScope.launch { runCatching { workflowRepository.recoverInterruptedRuns() } }
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (showAccessibilityHarness) {
                        showAccessibilityHarness = false
                    } else if (workflowToRun != null) {
                        workflowToRun = null
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            },
        )
        setContent {
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
                        )

                        bundle != null -> WorkflowRunScreen(
                            bundle = bundle,
                            repository = workflowRepository,
                            onBack = { workflowToRun = null },
                        )

                        else -> WorkflowEditorScreen(
                            repository = workflowRepository,
                            onOpenAutomationHarness = { showAccessibilityHarness = true },
                            onRunWorkflow = { workflowToRun = it },
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

    override fun onDestroy() {
        activityScope.cancel()
        workflowRepository.close()
        super.onDestroy()
    }
}
