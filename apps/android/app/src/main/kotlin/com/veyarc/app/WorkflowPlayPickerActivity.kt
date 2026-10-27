package com.veyarc.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.veyarc.app.ui.theme.VeyarcTheme
import com.veyarc.automation.android.ReplayFloatingController
import com.veyarc.data.storage.WorkflowRepository
import com.veyarc.data.storage.WorkflowSummary
import com.veyarc.feature.editor.VeyarcChoice
import com.veyarc.feature.editor.VeyarcChoicePicker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Uses a normal dismissible window; choosing a workflow still goes through run preflight. */
class WorkflowPlayPickerActivity : ComponentActivity() {
    private var opening = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val preferences by remember { AppPreferences.observe(this@WorkflowPlayPickerActivity) }.collectAsState(initial = AppUiPreferences())
            var workflows by remember { mutableStateOf<List<WorkflowSummary>?>(null) }
            var error by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(Unit) {
                try {
                    workflows = withContext(Dispatchers.IO) {
                        val repository = WorkflowRepository(applicationContext)
                        try {
                            repository.listWorkflowSummaries().sortedByDescending { it.updatedAtMs }
                        } finally {
                            repository.close()
                        }
                    }
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    error = "Workflows could not be loaded."
                }
            }
            VeyarcTheme(
                darkTheme = when (preferences.theme) {
                    ThemePreference.SYSTEM -> isSystemInDarkTheme()
                    ThemePreference.LIGHT -> false
                    ThemePreference.DARK -> true
                },
                particlesEnabled = false,
            ) {
                val loaded = workflows
                if (!loaded.isNullOrEmpty()) {
                    VeyarcChoicePicker(
                        title = "Play workflow",
                        options = loaded.map { VeyarcChoice(it.workflowId, it.name) },
                        onDismiss = ::closePopup,
                        onSelect = { choice -> loaded.firstOrNull { it.workflowId == choice.id }?.let(::play) },
                    )
                } else {
                    AlertDialog(
                        onDismissRequest = ::closePopup,
                        title = { Text("Play workflow") },
                        text = { if (loaded == null && error == null) CircularProgressIndicator() else Text(error ?: "No saved workflows yet.") },
                        confirmButton = { TextButton(onClick = ::closePopup) { Text("Close") } },
                    )
                }
            }
        }
    }

    private fun play(workflow: WorkflowSummary) {
        if (opening) return
        opening = true
        try {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .setAction(ReplayFloatingController.ACTION_OPEN_WORKFLOW)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(ReplayFloatingController.EXTRA_WORKFLOW_ID, workflow.workflowId)
                    .putExtra(ReplayFloatingController.EXTRA_WORKFLOW_REVISION, workflow.latestRevision)
                    .putExtra(ReplayFloatingController.EXTRA_AUTO_START, true),
            )
            closePopup()
        } catch (_: android.content.ActivityNotFoundException) {
            opening = false
            android.widget.Toast.makeText(this, "Veyarc could not open this workflow.", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun closePopup() {
        if (!isFinishing) finishAndRemoveTask()
    }
}
