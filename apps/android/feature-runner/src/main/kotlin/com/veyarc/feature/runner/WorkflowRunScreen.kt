@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.veyarc.feature.runner

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.veyarc.automation.android.AccessibilityGestureDispatcher
import com.veyarc.automation.android.MediaProjectionConsent
import com.veyarc.model.bundle.WorkflowBundle

@Composable
fun WorkflowRunScreen(
    bundle: WorkflowBundle,
    controller: WorkflowRunController,
    canStart: Boolean = true,
    startBlockMessage: String? = null,
    onStart: () -> Unit = controller::start,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val state by controller.state.collectAsState()
    var permissionRequestPending by remember { mutableStateOf(false) }
    var projectionRequestPending by remember { mutableStateOf(false) }
    lateinit var requestStart: () -> Unit
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionRequestPending = false
        controller.refreshNotificationState()
        if (granted) requestStart() else openNotificationSettings(context)
    }
    val projectionConsentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        projectionRequestPending = false
        controller.startAfterMediaProjectionConsent(result.resultCode, result.data)
    }

    requestStart = startRequest@{
        if (!canStart) return@startRequest
        if (!state.notificationReady) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionRequestPending = true
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                openNotificationSettings(context)
            }
            return@startRequest
        }
        if (controller.requiresMediaProjectionConsent()) {
            runCatching { MediaProjectionConsent.createRequestIntent(context) }
                .onSuccess { intent ->
                    projectionRequestPending = true
                    projectionConsentLauncher.launch(intent)
                }
                .onFailure { error ->
                    controller.reportMediaProjectionFailure(
                        error.message ?: "Android MediaProjection is unavailable",
                    )
                }
        } else {
            onStart()
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(state.workflowName) },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = phaseTitle(state.phase),
                style = MaterialTheme.typography.headlineSmall,
            )
            state.message?.let { message ->
                Text(message, color = if (state.phase == WorkflowRunPhase.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }

            if (state.phase == WorkflowRunPhase.REVIEW) {
                ReviewContent(
                    state = state,
                    onStart = requestStart,
                    onRefresh = controller::refreshReview,
                    onOpenNotificationSettings = { openNotificationSettings(context) },
                    canStart = canStart,
                    startBlockMessage = startBlockMessage,
                    requiresMediaProjectionConsent = controller.requiresMediaProjectionConsent(),
                )
            } else {
                RunContent(state = state, onPause = controller::pause, onResume = controller::resume, onStop = controller::stop, onBack = onBack)
            }

            if (permissionRequestPending) {
                Text("Waiting for notification permission…", style = MaterialTheme.typography.labelMedium)
            }
            if (projectionRequestPending) {
                Text("Waiting for screen-capture consent…", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun ReviewContent(
    state: WorkflowRunUiState,
    onStart: () -> Unit,
    onRefresh: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    canStart: Boolean,
    startBlockMessage: String?,
    requiresMediaProjectionConsent: Boolean,
) {
    val context = LocalContext.current
    val plan = state.review.plan
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Pre-run review", style = MaterialTheme.typography.titleLarge)
            Text("Revision ${state.revision}")
            Text("Allowed packages: ${state.review.plan?.allowedPackages?.joinToString { it.value } ?: "none"}")
            Text("Maximum runtime: ${plan?.runLimits?.maxRuntimeMs ?: "—"} ms")
            Text("Worst-case steps: ${plan?.analysis?.worstCaseStepCount ?: "—"}")
            Text("Accessibility setting: ${if (state.review.capabilities.accessibilityEnabled) "enabled" else "disabled"}")
            Text("Service connection: ${if (state.review.capabilities.serviceConnected) "ready" else "not ready"}")
            if (requiresMediaProjectionConsent) {
                Text("This workflow uses temporary screen frames. Android will request capture consent for this run.")
            }
            if (!state.review.capabilities.serviceConnected) {
                OutlinedButton(onClick = { AccessibilityGestureDispatcher.openAccessibilitySettings(context) }) {
                    Text("Open accessibility settings")
                }
            }
        }
    }

    if (state.review.compilation.errors.isNotEmpty()) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Cannot run this workflow", color = MaterialTheme.colorScheme.error)
                state.review.compilation.errors.forEach { error ->
                    Text("${error.path}: ${error.message}", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (!state.notificationReady) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Notifications are disabled")
                Text("Veyarc keeps an active-run notification visible so the run can be found and stopped quickly.")
                OutlinedButton(onClick = onOpenNotificationSettings) { Text("Open notification settings") }
            }
        }
    }

    startBlockMessage?.let { message ->
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onRefresh) { Text("Refresh checks") }
        Button(enabled = state.review.isRunnable && canStart, onClick = onStart) {
            Text("Start run")
        }
    }
}

@Composable
private fun RunContent(
    state: WorkflowRunUiState,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onBack: () -> Unit,
) {
    val total = state.review.plan?.analysis?.worstCaseStepCount
    Text(
        text = if (total == null) "Completed steps: ${state.completedSteps}" else "Completed steps: ${state.completedSteps} / $total",
        style = MaterialTheme.typography.titleMedium,
    )
    if (state.phase == WorkflowRunPhase.RUNNING || state.phase == WorkflowRunPhase.PAUSED || state.phase == WorkflowRunPhase.STARTING) {
        Text("Emergency stop overlay is active when the accessibility service is connected.")
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when (state.phase) {
            WorkflowRunPhase.RUNNING -> Button(onClick = onPause) { Text("Pause") }
            WorkflowRunPhase.PAUSED -> Button(onClick = onResume) { Text("Resume") }
            else -> Unit
        }
        if (state.phase == WorkflowRunPhase.RUNNING || state.phase == WorkflowRunPhase.PAUSED || state.phase == WorkflowRunPhase.STARTING) {
            OutlinedButton(onClick = onStop) { Text("Stop") }
        }
        if (state.phase == WorkflowRunPhase.COMPLETED ||
            state.phase == WorkflowRunPhase.FAILED ||
            state.phase == WorkflowRunPhase.CANCELLED ||
            state.phase == WorkflowRunPhase.STOPPED
        ) {
            OutlinedButton(onClick = onBack) { Text("Back to editor") }
        }
    }
    HorizontalDivider()
    Text("Run events", style = MaterialTheme.typography.titleMedium)
    if (state.eventHistory.isEmpty()) {
        Text("No events yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        state.eventHistory.forEach { event ->
            Text("${event.atMs} ms · ${event.message}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun phaseTitle(phase: WorkflowRunPhase): String = when (phase) {
    WorkflowRunPhase.REVIEW -> "Review workflow"
    WorkflowRunPhase.STARTING -> "Starting run"
    WorkflowRunPhase.RUNNING -> "Workflow running"
    WorkflowRunPhase.PAUSED -> "Workflow paused"
    WorkflowRunPhase.COMPLETED -> "Run completed"
    WorkflowRunPhase.FAILED -> "Run failed"
    WorkflowRunPhase.CANCELLED -> "Run cancelled"
    WorkflowRunPhase.STOPPED -> "Run stopped"
}

private fun openNotificationSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
        )
    }
}
