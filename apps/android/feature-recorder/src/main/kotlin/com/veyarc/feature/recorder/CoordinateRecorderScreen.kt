package com.veyarc.feature.recorder

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.veyarc.automation.android.ReplayFloatingController
import com.veyarc.feature.editor.VeyarcChoice
import com.veyarc.feature.editor.VeyarcChoicePicker
import com.veyarc.model.bundle.WorkflowBundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun CoordinateRecorderScreen(
    controller: CoordinateRecordingController,
    onBack: () -> Unit,
    onEditWorkflow: (WorkflowBundle, String) -> Unit,
    onEditDraft: ((com.veyarc.feature.editor.WorkflowDraft) -> Unit)? = null,
    destinationFolderId: String? = null,
    modifier: Modifier = Modifier,
) {
    val state by controller.state.collectAsState()
    val builder by controller.builder.collectAsState()
    var confirmClose by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var latchEnabled by remember(context) { mutableStateOf(ReplayFloatingController.isEnabled(context)) }
    DisposableEffect(context) {
        val stopObservation = ReplayFloatingController.observeEnabled(context) { latchEnabled = it }
        onDispose { stopObservation() }
    }
    LaunchedEffect(state) {
        val review = (state as? CoordinateRecordingState.Review)?.review ?: return@LaunchedEffect
        val bundle = review.editableWorkflow
        if (bundle != null) {
            controller.dismissReview()
            onEditWorkflow(bundle, review.journalFileName)
        } else {
            val draft = controller.editorSession.draft?.takeIf { it.steps.isNotEmpty() } ?: return@LaunchedEffect
            onEditDraft?.let { open ->
                controller.dismissReview()
                open(draft)
            }
        }
    }
    val close = {
        if (controller.isSessionActive()) confirmClose = true else onBack()
    }
    val resume = {
        controller.resume()
        if (controller.isSessionActive() && !controller.isSessionPaused()) {
            var host = context
            while (host is android.content.ContextWrapper && host !is android.app.Activity) host = host.baseContext
            (host as? android.app.Activity)?.moveTaskToBack(true)
        }
        Unit
    }
    val addImageBlock = {
        if (controller.requestImageBlock()) {
            var host = context
            while (host is android.content.ContextWrapper && host !is android.app.Activity) host = host.baseContext
            (host as? android.app.Activity)?.moveTaskToBack(true)
        }
        Unit
    }
    BackHandler(onBack = close)
    AnimatedContent(builder.isOpen, transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(100)) }, label = "recording steps") { open ->
        if (open) {
            RecordingBuilderScreen(controller, builder, resume, addImageBlock)
        } else {
            RecordingPanel(
                state = state,
                onStart = { controller.start(destinationFolderId) },
                onPause = controller::pause,
                onResume = resume,
                onStop = controller::stop,
                onClose = close,
                onNotificationSettings = { RecordingForegroundService.openNotificationSettings(context) },
                modifier = modifier,
                latchEnabled = latchEnabled,
                onAdd = addImageBlock,
                onSteps = controller::openBuilder,
                preparing = builder.preparing,
                destination = controller.recordingDestination,
            )
        }
    }
    if (builder.error != null) {
        AlertDialog(onDismissRequest = controller::dismissWarning, title = { Text("Recording paused") }, text = { Text(requireNotNull(builder.error)) }, confirmButton = {
            TextButton(controller::dismissWarning) { Text("OK") }
        })
    }
    if (builder.resumeApps.isNotEmpty()) {
        val apps = remember(builder.resumeApps) {
            builder.resumeApps.map { packageName ->
                val label = runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(packageName, 0)).toString() }.getOrDefault(packageName)
                VeyarcChoice(packageName, label)
            }
        }
        VeyarcChoicePicker("Continue in", apps, onDismiss = controller::dismissResumeApps, onSelect = { controller.chooseResumeApp(it.id) })
    }
    if (builder.confirmRestart) {
        AlertDialog(onDismissRequest = controller::dismissRestart, title = { Text("Start a new recording?") }, text = { Text("This replaces the previous recording. Its unfinished changes will be lost.") }, confirmButton = {
            TextButton(controller::confirmRestart) { Text("New recording") }
        }, dismissButton = { TextButton(controller::dismissRestart) { Text("Cancel") } })
    }
    if (confirmClose) {
        AlertDialog(
            onDismissRequest = { confirmClose = false },
            title = { Text("Leave recording?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClose = false
                    controller.stop()
                }) { Text("Finish") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        confirmClose = false
                        controller.discard()
                        onBack()
                    }) { Text("Discard") }
                    TextButton(onClick = { confirmClose = false }) { Text("Keep recording") }
                }
            },
        )
    }
}

@Composable
internal fun RecordingPanel(
    state: CoordinateRecordingState,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onClose: () -> Unit,
    onNotificationSettings: () -> Unit,
    modifier: Modifier = Modifier,
    latchEnabled: Boolean = true,
    onAdd: () -> Unit = {},
    onSteps: () -> Unit = {},
    preparing: Boolean = false,
    destination: String = "Workflow",
) {
    val context = LocalContext.current
    val active = state is CoordinateRecordingState.Arming || state is CoordinateRecordingState.Recording || state is CoordinateRecordingState.Paused
    val paused = state is CoordinateRecordingState.Paused
    val foreground = when (state) {
        is CoordinateRecordingState.Recording -> state.foregroundPackage
        is CoordinateRecordingState.Paused -> state.foregroundPackage
        else -> null
    }
    val appName by produceState<String?>(null, foreground) {
        value = null
        value = foreground?.let { packageName ->
            withContext(Dispatchers.IO) {
                runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(packageName, 0)).toString() }.getOrNull()
            }
        }
    }
    val elapsed = when (state) {
        is CoordinateRecordingState.Arming -> state.elapsedMillis
        is CoordinateRecordingState.Recording -> state.elapsedMillis
        is CoordinateRecordingState.Paused -> state.elapsedMillis
        else -> 0L
    }
    Column(modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Record", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
            IconButton(onClose) { Icon(Icons.Filled.Close, "Close recorder") }
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(Modifier.verticalScroll(rememberScrollState()).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (active) Text(destination, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
                        Icon(if (paused) Icons.Filled.Pause else Icons.Filled.FiberManualRecord, null, Modifier.size(40.dp), tint = if (active && !paused) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                        Text(if (active) formatElapsed(elapsed) else "Record a workflow", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                        Text(
                            if (paused) {
                                "Paused"
                            } else if (active) {
                                appName ?: "Open an app to begin"
                            } else {
                                "Turn your actions into editable steps."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                if (active) {
                    if (preparing) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextButton(onAdd, Modifier.weight(1f), enabled = !preparing) {
                            Icon(Icons.Filled.Add, null)
                            Text("Add")
                        }
                        TextButton(onSteps, Modifier.weight(1f), enabled = !preparing) {
                            Icon(Icons.Filled.FormatListBulleted, null)
                            Text("Steps")
                        }
                    }
                    Text(if (latchEnabled) "Use apps normally. Pause or stop from the edge latch." else "Turn on the edge latch before recording.", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(if (paused) onResume else onPause, Modifier.weight(1f), enabled = !preparing && (!paused || latchEnabled)) {
                            Icon(if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, null, Modifier.padding(end = 8.dp))
                            Text(if (paused) "Continue recording" else "Pause")
                        }
                        Button(onStop, Modifier.weight(1f)) {
                            Icon(Icons.Filled.Stop, null, Modifier.padding(end = 8.dp))
                            Text("Finish")
                        }
                    }
                } else {
                    val reason = when (state) {
                        is CoordinateRecordingState.Unavailable -> state.reason
                        is CoordinateRecordingState.Failed -> state.reason
                        is CoordinateRecordingState.Review -> state.review.draftUnavailableReason
                        else -> null
                    }
                    reason?.let { Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
                    if (reason?.contains("notification", ignoreCase = true) == true) {
                        OutlinedButton(onNotificationSettings) { Text("Notification settings") }
                    }
                    if (!latchEnabled && reason == null) Text("Turn on the edge latch before recording.", color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                    Button(onStart, Modifier.fillMaxWidth(), enabled = latchEnabled) {
                        Icon(Icons.Filled.FiberManualRecord, null, Modifier.size(18.dp).padding(end = 4.dp))
                        Text(if (reason == null) "Start recording" else "Try again")
                    }
                }
            }
        }
    }
}

private fun formatElapsed(durationMillis: Long): String {
    val seconds = durationMillis.coerceAtLeast(0L) / 1_000L
    return "%02d:%02d".format(seconds / 60L, seconds % 60L)
}
