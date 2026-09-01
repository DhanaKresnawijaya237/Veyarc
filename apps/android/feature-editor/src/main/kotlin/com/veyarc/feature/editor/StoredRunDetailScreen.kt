@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.veyarc.feature.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.veyarc.data.storage.StoredRunRecord
import com.veyarc.data.storage.StoredRunStatus

@Composable
internal fun StoredRunDetailScreen(
    record: StoredRunRecord,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Recorded workflow") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(runTitle(record.status), style = MaterialTheme.typography.headlineSmall)
                    Text(
                        runMessage(record),
                        color = if (record.status == StoredRunStatus.FAILED) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                    Text(
                        completedStepsText(record),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    record.finishedAtMs?.let { finishedAtMs ->
                        Text(
                            "Duration: ${formatDuration(finishedAtMs - record.startedAtMs)}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "Revision ${record.revision} · run ${record.runId}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(onClick = onBack) { Text("Back to editor") }
                    }
                    HorizontalDivider()
                    Text("Run events", style = MaterialTheme.typography.titleMedium)
                }
            }

            if (record.eventHistory.isNotEmpty()) {
                items(record.eventHistory) { event ->
                    Text(
                        "${event.atMs} ms · ${event.message}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            } else {
                item {
                    Text(
                        if (record.hasStoredDetails) {
                            "No events were recorded for this run."
                        } else {
                            "Detailed events were not saved for this older run. Its summary remains available above."
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun runTitle(status: StoredRunStatus): String = when (status) {
    StoredRunStatus.RUNNING -> "Run in progress"
    StoredRunStatus.COMPLETED -> "Run completed"
    StoredRunStatus.FAILED -> "Run failed"
    StoredRunStatus.CANCELLED -> "Run cancelled"
    StoredRunStatus.STOPPED -> "Run stopped"
    StoredRunStatus.INTERRUPTED -> "Run interrupted"
}

private fun runMessage(record: StoredRunRecord): String = record.interruptionReason ?: when (record.status) {
    StoredRunStatus.RUNNING -> "This run has not reached a terminal state."
    StoredRunStatus.COMPLETED -> "Workflow completed"
    StoredRunStatus.FAILED -> "Workflow failed"
    StoredRunStatus.CANCELLED -> "Stopped by user"
    StoredRunStatus.STOPPED -> "Workflow stopped"
    StoredRunStatus.INTERRUPTED -> "The run ended unexpectedly"
}

private fun completedStepsText(record: StoredRunRecord): String = record.totalSteps?.let { total ->
    "Completed steps: ${record.completedSteps} / $total"
} ?: "Completed steps: ${record.completedSteps}"

private fun formatDuration(durationMs: Long): String {
    val safeDurationMs = durationMs.coerceAtLeast(0L)
    val totalSeconds = safeDurationMs / 1_000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "%02d:%02d".format(minutes, seconds)
}
