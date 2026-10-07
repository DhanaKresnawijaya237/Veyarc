package com.veyarc.feature.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.veyarc.data.storage.StoredRunRecord
import com.veyarc.data.storage.StoredRunStatus
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun StoredRunDetailScreen(
    record: StoredRunRecord,
    onBack: () -> Unit,
    onOpenWorkflowRevision: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    workflowName: String = "Workflow run",
) {
    BackHandler(onBack = onBack)
    val timestamp = remember(record.startedAtMs) { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(record.startedAtMs)) }
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val statusColor = when (record.status) {
        StoredRunStatus.COMPLETED -> if (dark) Color(0xFF83BF9A) else Color(0xFF2E7D32)
        StoredRunStatus.FAILED -> MaterialTheme.colorScheme.error
        StoredRunStatus.INTERRUPTED -> if (dark) Color(0xFFD5AD75) else Color(0xFF8A5C17)
        else -> MaterialTheme.colorScheme.primary
    }
    LazyColumn(modifier.fillMaxSize().testTag("run-details"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back to runs") }
                Column(Modifier.weight(1f).padding(start = 4.dp)) {
                    Text(record.name ?: workflowName, style = MaterialTheme.typography.titleLarge)
                    if (record.name != null && record.name != workflowName) Text(workflowName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Surface(shape = RoundedCornerShape(12.dp), color = statusColor.copy(alpha = 0.12f)) {
                        Text(runTitle(record.status), Modifier.padding(horizontal = 12.dp, vertical = 7.dp), color = statusColor, style = MaterialTheme.typography.titleSmall)
                    }
                    Text(timestamp, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        RunFact(record.completedSteps.toString(), "Steps finished", Modifier.weight(1f))
                        RunFact(record.finishedAtMs?.let { formatDuration(it - record.startedAtMs) } ?: "In progress", "Duration", Modifier.weight(1f))
                        RunFact(record.revision.toString(), "Revision", Modifier.weight(1f))
                    }
                    record.interruptionReason?.takeIf { it.isNotBlank() }?.let {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        Text(readableRunMessage(it), style = MaterialTheme.typography.bodyMedium, color = if (record.status == StoredRunStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    }
                    onOpenWorkflowRevision?.let { open ->
                        TextButton(onClick = open, modifier = Modifier.fillMaxWidth()) { Text("Open revision ${record.revision}") }
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Activity", style = MaterialTheme.typography.titleMedium)
                if (record.eventHistory.size > 1) Text("Time between events", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        itemsIndexed(record.eventHistory) { index, event ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp).testTag("run-event-$index"), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.padding(top = 8.dp).size(6.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.65f), CircleShape))
                Text(readableRunMessage(event.message), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                if (index > 0) Text("+${formatEventGap(event.atMs - record.eventHistory[index - 1].atMs)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (record.eventHistory.isEmpty()) {
            item {
                Text(if (record.hasStoredDetails) "No activity recorded." else "Activity wasn't saved for this older run.", Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun RunFact(value: String, label: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun runTitle(status: StoredRunStatus): String = when (status) {
    StoredRunStatus.RUNNING -> "In progress"
    StoredRunStatus.COMPLETED -> "Completed"
    StoredRunStatus.FAILED -> "Failed"
    StoredRunStatus.CANCELLED -> "Cancelled"
    StoredRunStatus.STOPPED -> "Stopped"
    StoredRunStatus.INTERRUPTED -> "Interrupted"
}

// Historical messages remain intact in storage; technical identifiers aren't useful in this view.
internal fun readableRunMessage(message: String): String = message.replace(runIdentifier, "").replace(Regex(" {2,}"), " ").trim()
private val runIdentifier = Regex("(?i)\\b(?:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|[0-9a-f]{64})\\b")

internal fun formatEventGap(durationMs: Long): String = String.format(Locale.getDefault(), "%.1fs", durationMs.coerceAtLeast(0L) / 1000.0)

private fun formatDuration(durationMs: Long): String {
    val seconds = durationMs.coerceAtLeast(0L) / 1_000
    return String.format(Locale.getDefault(), "%d:%02d", seconds / 60, seconds % 60)
}
