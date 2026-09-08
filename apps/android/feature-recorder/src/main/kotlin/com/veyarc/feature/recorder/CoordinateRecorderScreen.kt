package com.veyarc.feature.recorder

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.veyarc.automation.android.ReplayFreezeCapability
import com.veyarc.model.bundle.WorkflowBundle

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
    val rawReplayController = remember(context) { CoordinateRawTraceReplayController(context) }
    val rawReplayState by rawReplayController.state.collectAsState()
    var pendingReplayReview by remember { mutableStateOf<CoordinateRecordingReview?>(null) }
    var replayInputGuardEnabled by remember { mutableStateOf(false) }
    val replayFreezeAvailability = ReplayFreezeCapability.availability()
    val active = state is CoordinateRecordingState.Arming ||
        state is CoordinateRecordingState.Recording ||
        state is CoordinateRecordingState.Paused
    DisposableEffect(rawReplayController) {
        onDispose { rawReplayController.close() }
    }
    BackHandler {
        if (active) controller.stop()
        if (rawReplayState.running) rawReplayController.cancel()
        onBack()
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Coordinate recording", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Start recording here or from the Veyarc edge-latch control center, then use any apps normally. " +
                "Veyarc records a global raw single-pointer " +
                "touch timeline and foreground-package transitions; it does not inspect UI content, " +
                "replay while recording, or install a full-screen touch-intercepting overlay.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "Use the edge-latch control center to start, pause, resume, or stop and save. Quick Settings and the " +
                "persistent notification remain independent emergency fallbacks. Control-surface touches are " +
                "excluded; switching between third-party apps stays in the same encrypted journal.",
            style = MaterialTheme.typography.bodyMedium,
        )

        when (val current = state) {
            CoordinateRecordingState.Idle -> {
                Button(onClick = { controller.start(destinationFolderId) }) { Text("Arm recording") }
            }

            is CoordinateRecordingState.Unavailable -> {
                Text("Recording unavailable", style = MaterialTheme.typography.titleMedium)
                Text(current.reason, color = MaterialTheme.colorScheme.error)
                if (current.reason.contains("notification", ignoreCase = true)) {
                    Button(
                        onClick = { RecordingForegroundService.openNotificationSettings(context) },
                    ) {
                        Text("Open notification settings")
                    }
                }
                Button(onClick = { controller.start(destinationFolderId) }) { Text("Try again") }
            }

            is CoordinateRecordingState.Arming -> {
                Text("Recording armed", style = MaterialTheme.typography.titleMedium)
                Text("Elapsed: ${formatElapsed(current.elapsedMillis)}")
                Text("Use any third-party apps now. Foreground transitions will be logged automatically.")
                OutlinedButton(onClick = controller::pause) { Text("Pause recording") }
                OutlinedButton(onClick = controller::stop) { Text("Stop and review") }
            }

            is CoordinateRecordingState.Recording -> {
                Text("Recording", style = MaterialTheme.typography.titleMedium)
                Text("Foreground: ${current.foregroundPackage ?: "unknown"}")
                Text("Elapsed: ${formatElapsed(current.elapsedMillis)}")
                Text("Captured frames: ${current.frameCount}")
                OutlinedButton(onClick = controller::pause) { Text("Pause recording") }
                OutlinedButton(onClick = controller::stop) { Text("Stop and review") }
            }

            is CoordinateRecordingState.Paused -> {
                Text("Recording paused", style = MaterialTheme.typography.titleMedium)
                Text("Foreground: ${current.foregroundPackage ?: "unknown"}")
                Text("Elapsed: ${formatElapsed(current.elapsedMillis)}")
                Text("Captured frames: ${current.frameCount}")
                Button(onClick = controller::resume) { Text("Resume recording") }
                OutlinedButton(onClick = controller::stop) { Text("Stop and save") }
            }

            is CoordinateRecordingState.Review -> {
                val review = current.review
                Text("Recording review", style = MaterialTheme.typography.titleMedium)
                Text("Initial third-party app: ${review.initialForegroundPackage ?: "unknown"}")
                Text("Final third-party app: ${review.currentForegroundPackage ?: "unknown"}")
                Text("Duration: ${formatElapsed(review.durationMillis)}")
                Text("Raw touch frames: ${review.frameCount}")
                Text("Gestures: ${review.gestureCount} · inferred waits: ${review.inferredWaitCount}")
                Text("Foreground transitions: ${review.foregroundTransitionCount}")
                if (review.incompleteTouchIgnored) {
                    Text(
                        "The final touch was incomplete and was left out of classified gestures.",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text("Encrypted journal: ${review.journalFileName}")
                review.draftWarnings.forEach { warning ->
                    Text(warning, color = MaterialTheme.colorScheme.error)
                }
                review.draftUnavailableReason?.let { reason ->
                    Text("Workflow draft unavailable: $reason", color = MaterialTheme.colorScheme.error)
                }
                if (review.workflowSaved && review.editableWorkflow != null) {
                    Text(
                        "Saved as workflow revision ${review.editableWorkflow.document.revision}.",
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                review.workflowSaveError?.let { reason ->
                    Text("Workflow could not be saved automatically: $reason", color = MaterialTheme.colorScheme.error)
                }
                review.editableWorkflow?.let { bundle ->
                    Button(
                        enabled = !rawReplayState.running,
                        onClick = { onEditWorkflow(bundle, review.journalFileName) },
                    ) {
                        Text(if (review.workflowSaved) "Edit saved workflow" else "Edit as workflow")
                    }
                }
                review.rawReplayUnavailableReason?.let { reason ->
                    Text("Raw replay unavailable: $reason", color = MaterialTheme.colorScheme.error)
                }
                if (review.rawReplayTrace != null && review.rawReplayUnavailableReason == null) {
                    Text("Raw replay target: ${review.rawReplayTargetPackage?.value ?: "unknown"}")
                    Text(
                        "Use the edge-latch control center for Pause, Resume, or Stop. The persistent Veyarc replay notification remains an independent fallback.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Freeze target-app input during replay",
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = replayInputGuardEnabled,
                            enabled = !rawReplayState.running && replayFreezeAvailability.available,
                            onCheckedChange = { replayInputGuardEnabled = it },
                        )
                    }
                    Text(
                        if (replayFreezeAvailability.available) {
                            "Strict freeze is capability-gated. It blocks target-app touches while " +
                                "keeping system navigation and the emergency notification available."
                        } else {
                            "Strict screen freeze is disabled on this build because Android cannot " +
                                "safely block human touches and route the same synthetic gesture " +
                                "to a third-party app without a privileged input-filter backend. " +
                                "Normal replay remains available with human touches enabled."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(
                        enabled = !rawReplayState.running,
                        onClick = { pendingReplayReview = review },
                    ) {
                        Text("Replay raw trace")
                    }
                    if (rawReplayState.running) {
                        Text(rawReplayState.message)
                        if (rawReplayState.paused) {
                            Button(onClick = rawReplayController::resume) { Text("Resume replay") }
                        } else {
                            OutlinedButton(onClick = rawReplayController::pause) {
                                Text("Pause after current gesture")
                            }
                        }
                        OutlinedButton(onClick = rawReplayController::cancel) { Text("Cancel replay") }
                    }
                    rawReplayState.error?.let { error ->
                        Text(error, color = MaterialTheme.colorScheme.error)
                    }
                    if (rawReplayState.completed) Text(rawReplayState.message)
                }
                Button(enabled = !rawReplayState.running, onClick = controller::start) {
                    Text("Record another")
                }
            }

            is CoordinateRecordingState.Failed -> {
                Text("Recording stopped safely", style = MaterialTheme.typography.titleMedium)
                Text(current.reason, color = MaterialTheme.colorScheme.error)
                current.journalFileName?.let { Text("Partial encrypted journal preserved: $it") }
                Button(onClick = controller::start) { Text("Try again") }
            }
        }

        OutlinedButton(onClick = onBack) { Text("Back") }
    }

    pendingReplayReview?.let { review ->
        AlertDialog(
            onDismissRequest = { pendingReplayReview = null },
            title = { Text("Replay raw touch trace?") },
            text = {
                Text(
                    "Veyarc will send the preserved DOWN, MOVE, and UP frames to " +
                        "${review.rawReplayTargetPackage?.value ?: "the current app"}. " +
                        "Veyarc will open that app and wait for it to become foreground. No input " +
                        "is sent unless the Shizuku helper proves fixed motionevent support. " +
                        if (replayInputGuardEnabled && replayFreezeAvailability.available) {
                            "Strict freeze is enabled and will consume target-app touches during replay."
                        } else {
                            "Strict freeze is unavailable on this build; human touches remain enabled during replay."
                        },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingReplayReview = null
                        val trace = review.rawReplayTrace
                        val target = review.rawReplayTargetPackage
                        if (trace != null) {
                            rawReplayController.start(
                                trace = trace,
                                expectedTargetPackage = target,
                                inputGuardEnabled = replayInputGuardEnabled,
                            )
                        }
                    },
                ) {
                    Text("Replay")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingReplayReview = null }) { Text("Cancel") }
            },
        )
    }
}

private fun formatElapsed(durationMillis: Long): String {
    val totalSeconds = (durationMillis.coerceAtLeast(0L) / 1_000L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "%02d:%02d".format(minutes, seconds)
}
