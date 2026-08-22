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

class MainActivity : ComponentActivity() {
    private var resumeGeneration by mutableIntStateOf(0)
    private var showAccessibilityHarness by mutableStateOf(false)
    private lateinit var workflowRepository: WorkflowRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        workflowRepository = WorkflowRepository(applicationContext)
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (showAccessibilityHarness) {
                        showAccessibilityHarness = false
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
                    if (showAccessibilityHarness) {
                        AccessibilitySpikeScreen(resumeGeneration = resumeGeneration)
                    } else {
                        WorkflowEditorScreen(
                            repository = workflowRepository,
                            onOpenAutomationHarness = { showAccessibilityHarness = true },
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
        workflowRepository.close()
        super.onDestroy()
    }
}
