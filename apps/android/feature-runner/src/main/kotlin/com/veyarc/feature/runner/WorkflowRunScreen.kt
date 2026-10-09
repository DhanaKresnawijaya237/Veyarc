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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.veyarc.automation.android.AccessibilityGestureDispatcher
import com.veyarc.automation.android.MediaProjectionConsent
import com.veyarc.data.storage.StoredRunEvent
import com.veyarc.data.storage.StoredRunRecord
import com.veyarc.data.storage.StoredRunStatus
import com.veyarc.feature.editor.StoredRunDetailScreen
import com.veyarc.model.bundle.WorkflowBundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

    if (state.phase == WorkflowRunPhase.REVIEW) {
        ReviewContent(
            bundle = bundle,
            state = state,
            onStart = requestStart,
            onRefresh = controller::refreshReview,
            onBack = onBack,
            onOpenNotificationSettings = { openNotificationSettings(context) },
            canStart = canStart,
            startBlockMessage = startBlockMessage,
            requiresMediaProjectionConsent = controller.requiresMediaProjectionConsent(),
            pendingMessage = when {
                permissionRequestPending -> "Waiting for notification permission…"
                projectionRequestPending -> "Waiting for screen-capture consent…"
                else -> null
            },
            modifier = modifier,
        )
        return
    }

    StoredRunDetailScreen(
        record = StoredRunRecord(
            runId = state.runId.orEmpty(),
            workflowId = bundle.document.id,
            revision = state.revision,
            status = when (state.phase) {
                WorkflowRunPhase.COMPLETED -> StoredRunStatus.COMPLETED
                WorkflowRunPhase.FAILED -> StoredRunStatus.FAILED
                WorkflowRunPhase.CANCELLED -> StoredRunStatus.CANCELLED
                WorkflowRunPhase.STOPPED -> StoredRunStatus.STOPPED
                else -> StoredRunStatus.RUNNING
            },
            startedAtMs = state.startedAtMs,
            finishedAtMs = state.finishedAtMs,
            interruptionReason = state.failureMessage ?: state.message?.takeUnless { it == state.eventHistory.lastOrNull()?.message || it == "Running" || it == "Workflow completed" },
            completedSteps = state.completedSteps,
            totalSteps = state.review.plan?.analysis?.worstCaseStepCount,
            eventHistory = state.eventHistory.map { StoredRunEvent(it.atMs, it.message) },
            hasStoredDetails = true,
            folderId = null,
        ),
        onBack = onBack,
        modifier = modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
        workflowName = state.workflowName,
        statusLabel = phaseTitle(state.phase),
        controls = if (state.phase == WorkflowRunPhase.PREPARING || state.phase == WorkflowRunPhase.RUNNING || state.phase == WorkflowRunPhase.PAUSED) {
            {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (state.phase == WorkflowRunPhase.RUNNING) Button(onClick = controller::pause, modifier = Modifier.weight(1f)) { Text("Pause") }
                    if (state.phase == WorkflowRunPhase.PAUSED) Button(onClick = controller::resume, modifier = Modifier.weight(1f)) { Text("Resume") }
                    OutlinedButton(onClick = controller::stop, modifier = Modifier.weight(1f)) { Text("Stop") }
                }
            }
        } else {
            null
        },
    )
}

@Composable
private fun ReviewContent(
    bundle: WorkflowBundle,
    state: WorkflowRunUiState,
    onStart: () -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    canStart: Boolean,
    startBlockMessage: String?,
    requiresMediaProjectionConsent: Boolean,
    pendingMessage: String?,
    modifier: Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        val wide = maxWidth >= 600.dp
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, top = 12.dp, end = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Review workflow", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                IconButton(onClick = onBack) { Icon(Icons.Outlined.Close, contentDescription = "Close workflow review") }
            }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (wide) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        WorkflowReviewSummary(bundle, state, requiresMediaProjectionConsent, Modifier.weight(0.46f))
                        WorkflowReviewChecks(state, onOpenNotificationSettings, Modifier.weight(0.54f))
                    }
                } else {
                    WorkflowReviewSummary(bundle, state, requiresMediaProjectionConsent, Modifier.fillMaxWidth())
                    WorkflowReviewChecks(state, onOpenNotificationSettings, Modifier.fillMaxWidth())
                }
                if (state.review.compilation.errors.isNotEmpty()) {
                    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.errorContainer) {
                        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Fix these before running", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                            state.review.compilation.errors.forEach { error ->
                                Text(error.message, color = MaterialTheme.colorScheme.onErrorContainer)
                                Text(error.path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                            }
                        }
                    }
                }
                state.message?.takeUnless { it == "Review workflow before starting" }?.let { message ->
                    Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                startBlockMessage?.let { message -> Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                pendingMessage?.let { message -> Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onRefresh) { Icon(Icons.Outlined.Refresh, contentDescription = "Refresh checks") }
                Button(
                    enabled = state.review.isRunnable && canStart,
                    onClick = onStart,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                    Text("Start run")
                }
            }
        }
    }
}

@Composable
private fun WorkflowReviewSummary(bundle: WorkflowBundle, state: WorkflowRunUiState, requiresMediaProjectionConsent: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    val packages = bundle.document.allowedPackages
    val appNames by produceState(packages.map { it.value.substringAfterLast('.') }, packages, context) {
        value = withContext(Dispatchers.IO) {
            packages.map { name ->
                runCatching {
                    @Suppress("DEPRECATION")
                    val info = context.packageManager.getApplicationInfo(name.value, 0)
                    context.packageManager.getApplicationLabel(info).toString()
                }.getOrElse { name.value.substringAfterLast('.') }
            }
        }
    }
    Surface(modifier, shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(state.workflowName, style = MaterialTheme.typography.titleLarge)
            val orientation = bundle.document.viewport.recordedOrientation.name.lowercase().replaceFirstChar { it.titlecase() }
            Text("Revision ${state.revision} · $orientation", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            Text("Allowed apps", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Text(appNames.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
            state.review.plan?.analysis?.worstCaseStepCount?.let { count ->
                Text("Up to $count ${if (count == 1L) "step" else "steps"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (requiresMediaProjectionConsent) {
                Text("Screen capture", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Text("Android will ask for permission when you start.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun WorkflowReviewChecks(state: WorkflowRunUiState, onOpenNotificationSettings: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val review = state.review
    val accessibilityReady = review.capabilities.gestureDispatchReady
    Surface(modifier, shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Ready to run?", style = MaterialTheme.typography.titleMedium)
            ReviewCheckRow(
                "Accessibility",
                accessibilityReady,
                when {
                    !review.capabilities.accessibilityEnabled -> "Off"
                    !review.capabilities.serviceConnected -> "Disconnected"
                    else -> "Ready"
                },
            )
            if (!accessibilityReady) {
                TextButton(onClick = { AccessibilityGestureDispatcher.openAccessibilitySettings(context) }) { Text("Open accessibility settings") }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            ReviewCheckRow("Orientation", review.orientationBlockReason == null, review.currentOrientation?.name?.lowercase()?.replaceFirstChar { it.titlecase() } ?: "Unknown")
            review.orientationBlockReason?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            ReviewCheckRow("Notifications", state.notificationReady, if (state.notificationReady) "On" else "Off")
            if (!state.notificationReady) {
                Text("Keep run controls within reach.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onOpenNotificationSettings) { Text("Open notification settings") }
            }
        }
    }
}

@Composable
private fun ReviewCheckRow(title: String, ready: Boolean, detail: String) {
    val color = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(if (ready) Icons.Outlined.CheckCircle else Icons.Outlined.WarningAmber, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(detail, style = MaterialTheme.typography.labelLarge, color = color)
    }
}

private fun phaseTitle(phase: WorkflowRunPhase): String = when (phase) {
    WorkflowRunPhase.REVIEW -> "Review workflow"
    WorkflowRunPhase.PREPARING -> "Preparing workflow"
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
