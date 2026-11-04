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
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.veyarc.automation.android.AndroidUnsafeDeviceState
import com.veyarc.automation.android.ReplayFloatingController
import com.veyarc.data.storage.StoredRunEvent
import com.veyarc.data.storage.StoredRunRecord
import com.veyarc.data.storage.StoredRunStatus
import com.veyarc.feature.editor.DraftIssue
import com.veyarc.feature.editor.StoredRunDetailScreen
import com.veyarc.feature.editor.WorkflowDraft
import com.veyarc.feature.editor.issueDisplay
import com.veyarc.model.bundle.WorkflowBundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

@Composable
fun WorkflowRunScreen(
    bundle: WorkflowBundle,
    controller: WorkflowRunController,
    canStart: Boolean = true,
    startBlockMessage: String? = null,
    onStart: () -> Unit = controller::start,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenConnectionSettings: () -> Unit = {},
    onOpenLatchSettings: () -> Unit = {},
    onOpenEditor: ((String?, DraftIssue?) -> Unit)? = null,
    autoStartRequested: Boolean = false,
    onAutoStartConsumed: () -> Unit = {},
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val state by controller.state.collectAsState()
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    DisposableEffect(context, controller) {
        val stopObserving = ReplayFloatingController.observeEnabled(context) { enabled ->
            if (controller.state.value.review.latchEnabled != enabled) controller.refreshReview()
        }
        onDispose { stopObserving() }
    }
    var permissionRequestPending by remember { mutableStateOf(false) }
    var projectionRequestPending by remember { mutableStateOf(false) }
    var projectionResult by rememberSaveable(controller) { mutableStateOf<ActivityResult?>(null) }
    val windowInfo = LocalWindowInfo.current
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
        projectionResult = result
    }

    LaunchedEffect(controller, projectionResult) {
        val result = projectionResult ?: return@LaunchedEffect
        try {
            // Android can deliver the result before its dialog has released focus and window metadata.
            withTimeout(5_000) {
                snapshotFlow {
                    lifecycleState.isAtLeast(Lifecycle.State.RESUMED) && windowInfo.isWindowFocused &&
                        state.review.capabilities.unsafeDeviceState != AndroidUnsafeDeviceState.SYSTEM_WINDOW_ACTIVE
                }.first { it }
            }
            controller.startAfterMediaProjectionConsent(result.resultCode, result.data)
        } catch (_: TimeoutCancellationException) {
            controller.reportMediaProjectionFailure("Android's sharing dialog has not closed. Try starting again.")
        }
        projectionRequestPending = false
        projectionResult = null
    }

    requestStart = startRequest@{
        val current = controller.state.value
        if (!canStart || !current.startingValuesValid || current.phase != WorkflowRunPhase.REVIEW || permissionRequestPending || projectionRequestPending || projectionResult != null) return@startRequest
        onAutoStartConsumed()
        if (!current.notificationReady) {
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
            runCatching { controller.createScreenCaptureRequest() }
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

    LaunchedEffect(controller, autoStartRequested, canStart, state.review.isRunnable, state.startingValuesValid, lifecycleState) {
        if (autoStartRequested && bundle.document.variables.isNotEmpty()) {
            onAutoStartConsumed()
            return@LaunchedEffect
        }
        if (autoStartRequested && canStart && state.review.isRunnable && state.startingValuesValid && lifecycleState.isAtLeast(Lifecycle.State.RESUMED) && state.phase == WorkflowRunPhase.REVIEW) {
            requestStart()
        }
    }

    if (state.phase == WorkflowRunPhase.REVIEW) {
        ReviewContent(
            bundle = bundle,
            state = state,
            onStart = requestStart,
            onRefresh = controller::refreshReview,
            onStartingValueChange = controller::updateStartingValue,
            onResetStartingValues = controller::resetStartingValues,
            onOpenIssue = onOpenEditor?.let { open -> { issue -> open(null, issue) } },
            onBack = onBack,
            onOpenConnectionSettings = onOpenConnectionSettings,
            onOpenLatchSettings = onOpenLatchSettings,
            canStart = canStart,
            startBlockMessage = startBlockMessage,
            requiresMediaProjectionConsent = controller.requiresMediaProjectionConsent(),
            pendingMessage = when {
                permissionRequestPending -> "Waiting for notification permission…"
                projectionRequestPending || projectionResult != null -> "Waiting for screen-capture consent…"
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
            totalSteps = state.review.plan?.analysis?.estimatedStepCount,
            eventHistory = state.eventHistory.map { StoredRunEvent(it.atMs, it.message, it.blockLabel, it.contextLabel, it.stepId) },
            hasStoredDetails = true,
            folderId = null,
        ),
        onBack = onBack,
        onOpenBlock = onOpenEditor?.takeIf { state.phase in setOf(WorkflowRunPhase.COMPLETED, WorkflowRunPhase.FAILED, WorkflowRunPhase.CANCELLED, WorkflowRunPhase.STOPPED) }?.let { open -> { id -> open(id, null) } },
        onOpenWorkflowRevision = onOpenEditor?.takeIf { state.phase in setOf(WorkflowRunPhase.COMPLETED, WorkflowRunPhase.FAILED, WorkflowRunPhase.CANCELLED, WorkflowRunPhase.STOPPED) }?.let { open -> { open(null, null) } },
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
    onStartingValueChange: (String, String) -> Unit,
    onResetStartingValues: () -> Unit,
    onOpenIssue: ((DraftIssue) -> Unit)?,
    onBack: () -> Unit,
    onOpenConnectionSettings: () -> Unit,
    onOpenLatchSettings: () -> Unit,
    canStart: Boolean,
    startBlockMessage: String?,
    requiresMediaProjectionConsent: Boolean,
    pendingMessage: String?,
    modifier: Modifier,
) {
    val reviewDraft = remember(bundle.document) { WorkflowDraft.fromBundle(bundle) }
    BoxWithConstraints(modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        val wide = maxWidth >= 600.dp
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, top = 12.dp, end = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (state.sequenceName == null) "Review workflow" else "Review sequence", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                IconButton(onClick = onBack) { Icon(Icons.Outlined.Close, contentDescription = "Close workflow review") }
            }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (wide) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        WorkflowReviewSummary(bundle, state, requiresMediaProjectionConsent, Modifier.weight(0.46f))
                        WorkflowReviewChecks(state, onOpenConnectionSettings, onOpenLatchSettings, startBlockMessage, Modifier.weight(0.54f))
                    }
                } else {
                    WorkflowReviewSummary(bundle, state, requiresMediaProjectionConsent, Modifier.fillMaxWidth())
                    WorkflowReviewChecks(state, onOpenConnectionSettings, onOpenLatchSettings, startBlockMessage, Modifier.fillMaxWidth())
                }
                if (bundle.document.variables.isNotEmpty()) {
                    RunStartingValuesCard(bundle.document, state.startingValues, onStartingValueChange, onResetStartingValues)
                }
                val compilationErrors = state.review.visibleCompilationErrors
                if (compilationErrors.isNotEmpty()) {
                    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.errorContainer) {
                        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Fix these before running", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                            compilationErrors.forEach { error ->
                                val issue = DraftIssue(error.path, error.message)
                                val display = reviewDraft.issueDisplay(issue)
                                Column(Modifier.fillMaxWidth().clickable(enabled = onOpenIssue != null, indication = null, interactionSource = null) { onOpenIssue?.invoke(issue) }.padding(vertical = 6.dp)) {
                                    Text(display.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                                    Text(display.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                                }
                            }
                        }
                    }
                }
                pendingMessage?.let { message -> Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onRefresh) { Icon(Icons.Outlined.Refresh, contentDescription = "Refresh checks") }
                Button(
                    enabled = state.review.isRunnable && canStart && state.startingValuesValid && pendingMessage == null,
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
            Text(state.sequenceName ?: state.workflowName, style = MaterialTheme.typography.titleLarge)
            if (state.sequenceName != null) Text(state.workflowName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val orientation = bundle.document.viewport.recordedOrientation.name.lowercase().replaceFirstChar { it.titlecase() }
            Text("Revision ${state.revision} · $orientation", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            Text("Allowed apps", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Text(appNames.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
            state.review.plan?.analysis?.estimatedStepCount?.let { count ->
                Text("Up to $count ${if (count == 1L) "step" else "steps"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (requiresMediaProjectionConsent) {
                Text("Screen capture", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                val captureError = state.message?.takeIf { it.startsWith("Screen capture") || it.startsWith("Screen-capture") }
                Text(captureError ?: "Android will ask for permission when you start.", style = MaterialTheme.typography.bodyMedium, color = if (captureError == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun WorkflowReviewChecks(state: WorkflowRunUiState, onOpenConnectionSettings: () -> Unit, onOpenLatchSettings: () -> Unit, startBlockMessage: String?, modifier: Modifier) {
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
                message = review.capabilities.runBlockReason,
                onClick = onOpenConnectionSettings,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            ReviewCheckRow(
                "Edge latch",
                review.latchEnabled,
                if (review.latchEnabled) "On" else "Off",
                message = state.message?.takeIf { it.startsWith("Veyarc edge-latch controller") }
                    ?: if (review.latchEnabled) null else "Turn on the edge latch in Settings.",
                onClick = onOpenLatchSettings,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            ReviewCheckRow(
                "Orientation",
                review.orientationBlockReason == null,
                review.currentOrientation?.name?.lowercase()?.replaceFirstChar { it.titlecase() } ?: "Unknown",
                message = review.orientationBlockReason,
                onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS)) } },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            ReviewCheckRow(
                "Notifications",
                state.notificationReady,
                if (state.notificationReady) "On" else "Off",
                message = if (state.notificationReady) null else "Enable notifications for the emergency Stop control.",
                onClick = onOpenConnectionSettings,
            )
            startBlockMessage?.let {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                ReviewCheckRow("Current activity", false, "Wait", message = it, onClick = null)
            }
            state.message?.takeIf {
                it == "The workflow is no longer ready to start. Refresh the run checks."
            }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun ReviewCheckRow(title: String, ready: Boolean, detail: String, message: String? = null, onClick: (() -> Unit)?) {
    val color = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    Column(Modifier.fillMaxWidth().then(if (onClick == null) Modifier else Modifier.clickable(onClick = onClick)), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(if (ready) Icons.Outlined.CheckCircle else Icons.Outlined.WarningAmber, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.labelLarge, color = color)
        }
        message?.let { Text(it, Modifier.padding(start = 30.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
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
