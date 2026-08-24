package com.veyarc.feature.recorder

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.veyarc.model.bundle.WorkflowBundle

@Composable
fun CoordinateRecorderScreen(
    controller: CoordinateRecordingController,
    onBack: () -> Unit,
    onEditWorkflow: (WorkflowBundle, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by controller.state.collectAsState()
    val context = LocalContext.current
    val active = state is CoordinateRecordingState.Arming || state is CoordinateRecordingState.Recording
    BackHandler {
        if (active) controller.stop()
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
            "Arm recording here, then use any apps normally. Veyarc records a global raw single-pointer " +
                "touch timeline and foreground-package transitions; it does not inspect UI content, " +
                "replay while recording, or install a touch-intercepting overlay.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "Stop from the Quick Settings Veyarc tile, the persistent notification, or this screen. " +
                "Control-surface touches are excluded; switching between third-party apps stays in " +
                "the same encrypted journal.",
            style = MaterialTheme.typography.bodyMedium,
        )

        when (val current = state) {
            CoordinateRecordingState.Idle -> {
                Button(onClick = controller::start) { Text("Arm recording") }
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
                Button(onClick = controller::start) { Text("Try again") }
            }

            is CoordinateRecordingState.Arming -> {
                Text("Recording armed", style = MaterialTheme.typography.titleMedium)
                Text("Elapsed: ${formatElapsed(current.elapsedMillis)}")
                Text("Use any third-party apps now. Foreground transitions will be logged automatically.")
                OutlinedButton(onClick = controller::stop) { Text("Stop and review") }
            }

            is CoordinateRecordingState.Recording -> {
                Text("Recording", style = MaterialTheme.typography.titleMedium)
                Text("Foreground: ${current.foregroundPackage ?: "unknown"}")
                Text("Elapsed: ${formatElapsed(current.elapsedMillis)}")
                Text("Captured frames: ${current.frameCount}")
                OutlinedButton(onClick = controller::stop) { Text("Stop and review") }
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
                review.editableWorkflow?.let { bundle ->
                    Button(onClick = { onEditWorkflow(bundle, review.journalFileName) }) {
                        Text("Edit as workflow")
                    }
                }
                Button(onClick = controller::start) { Text("Record another") }
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
}

private fun formatElapsed(durationMillis: Long): String {
    val totalSeconds = (durationMillis.coerceAtLeast(0L) / 1_000L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "%02d:%02d".format(minutes, seconds)
}
