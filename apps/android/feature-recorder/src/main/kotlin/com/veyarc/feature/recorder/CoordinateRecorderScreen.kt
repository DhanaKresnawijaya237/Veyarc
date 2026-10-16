package com.veyarc.feature.recorder

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.veyarc.automation.android.ReplayFloatingController
import com.veyarc.model.bundle.WorkflowBundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun CoordinateRecorderScreen(
    controller: CoordinateRecordingController,
    onBack: () -> Unit,
    onEditWorkflow: (WorkflowBundle, String) -> Unit,
    destinationFolderId: String? = null,
    modifier: Modifier = Modifier,
) {
    val state by controller.state.collectAsState()
    val context = LocalContext.current
    var latchEnabled by remember(context) { mutableStateOf(ReplayFloatingController.isEnabled(context)) }
    DisposableEffect(context) {
        val stopObservation = ReplayFloatingController.observeEnabled(context) { latchEnabled = it }
        onDispose { stopObservation() }
    }
    LaunchedEffect(state) {
        val review = (state as? CoordinateRecordingState.Review)?.review ?: return@LaunchedEffect
        val bundle = review.editableWorkflow ?: return@LaunchedEffect
        controller.dismissReview()
        onEditWorkflow(bundle, review.journalFileName)
    }
    val close = {
        controller.stop()
        onBack()
    }
    BackHandler(onBack = close)
    RecordingPanel(
        state = state,
        onStart = { controller.start(destinationFolderId) },
        onPause = controller::pause,
        onResume = controller::resume,
        onStop = controller::stop,
        onClose = close,
        onNotificationSettings = { RecordingForegroundService.openNotificationSettings(context) },
        modifier = modifier,
        latchEnabled = latchEnabled,
    )
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
                    Text(if (latchEnabled) "Use apps normally. Pause or stop from the edge latch." else "Turn on the edge latch before recording.", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(if (paused) onResume else onPause, Modifier.weight(1f), enabled = !paused || latchEnabled) {
                            Icon(if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, null, Modifier.padding(end = 8.dp))
                            Text(if (paused) "Resume" else "Pause")
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
                    Button(onStart, Modifier.fillMaxWidth(), enabled = latchEnabled) { Text(if (reason == null) "Start recording" else "Try again") }
                }
            }
        }
    }
}

private fun formatElapsed(durationMillis: Long): String {
    val seconds = durationMillis.coerceAtLeast(0L) / 1_000L
    return "%02d:%02d".format(seconds / 60L, seconds % 60L)
}
