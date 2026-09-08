package com.veyarc.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.veyarc.data.storage.RunDashboardSummary
import com.veyarc.data.storage.StoredRunRecord
import com.veyarc.data.storage.StoredRunStatus
import com.veyarc.data.storage.WorkflowRepository
import com.veyarc.data.storage.WorkflowSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
fun HomeDashboardScreen(
    repository: WorkflowRepository,
    refreshGeneration: Int,
    onRecord: () -> Unit,
    onPlayWorkflow: (WorkflowSummary) -> Unit,
) {
    var dashboard by remember { mutableStateOf<RunDashboardSummary?>(null) }
    var workflows by remember { mutableStateOf<List<WorkflowSummary>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(repository, refreshGeneration) {
        error = null
        runCatching {
            withContext(Dispatchers.IO) {
                repository.loadRunDashboard() to repository.listWorkflows()
            }
        }.onSuccess { (loadedDashboard, loadedWorkflows) ->
            dashboard = loadedDashboard
            workflows = loadedWorkflows
        }.onFailure {
            error = "Dashboard data could not be loaded."
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "VEYARC",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                )
                Text("Your automation overview", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Record a workflow or replay a favorite without digging through the library.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Button(onClick = onRecord, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Start recording")
                }
            }
        }

        error?.let { message ->
            item { Text(message, color = MaterialTheme.colorScheme.error) }
        }

        val loaded = dashboard
        if (loaded == null) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().height(160.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator()
                }
            }
        } else {
            item { DashboardMetrics(loaded) }
            item { RunOutcomeChart(loaded.recentRuns) }
            item { StatusDistribution(loaded) }

            val favorites = workflows.filter { it.isFavorite }
            item { SectionTitle("Favorite workflows") }
            if (favorites.isEmpty()) {
                item { EmptyCard("Favorite workflows from the Workflow Manager will appear here.") }
            } else {
                items(favorites, key = WorkflowSummary::workflowId) { workflow ->
                    WorkflowQuickPlayCard(workflow, onPlayWorkflow)
                }
            }

            val names = workflows.associate { it.workflowId to it.name }
            val failures = loaded.recentRuns.filter(::isFailure).take(3)
            item { SectionTitle("Recent failures") }
            if (failures.isEmpty()) {
                item { EmptyCard("No recent failures.") }
            } else {
                items(failures, key = StoredRunRecord::runId) { run ->
                    FailureCard(run, names[run.workflowId] ?: "Deleted workflow")
                }
            }
        }
    }
}

@Composable
private fun DashboardMetrics(dashboard: RunDashboardSummary) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard("Total runs", dashboard.totalRuns.toString(), Modifier.weight(1f))
            MetricCard(
                "Completion rate",
                "${(dashboard.completionRate * 100).roundToInt()}%",
                Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val failed = dashboard.statusCounts.getOrDefault(StoredRunStatus.FAILED, 0L)
            MetricCard("Failed", failed.toString(), Modifier.weight(1f))
            MetricCard(
                "Average duration",
                formatDuration(dashboard.averageFinishedRunDurationMs),
                Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun MetricCard(
    label: String,
    value: String,
    modifier: Modifier,
) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineMedium)
        }
    }
}

@Composable
private fun RunOutcomeChart(runs: List<StoredRunRecord>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Recent run duration", style = MaterialTheme.typography.titleMedium)
            if (runs.isEmpty()) {
                Text("Run a workflow to start the chart.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                val completedColor = MaterialTheme.colorScheme.primary
                val failedColor = MaterialTheme.colorScheme.error
                val otherColor = MaterialTheme.colorScheme.secondary
                val plottedRuns = runs.take(12).reversed()
                val description = plottedRuns.joinToString { "${it.status.name.lowercase()} ${runDuration(it)} ms" }
                Canvas(
                    modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .semantics { contentDescription = "Recent run duration chart: $description" },
                ) {
                    val maximum = plottedRuns.maxOf { runDuration(it).coerceAtLeast(1L) }.toFloat()
                    val slotWidth = size.width / plottedRuns.size
                    val barWidth = (slotWidth * 0.58f).coerceAtMost(28.dp.toPx())
                    plottedRuns.forEachIndexed { index, run ->
                        val ratio = runDuration(run).coerceAtLeast(1L) / maximum
                        val height = (size.height * ratio).coerceAtLeast(8.dp.toPx())
                        val color = when (run.status) {
                            StoredRunStatus.COMPLETED -> completedColor
                            StoredRunStatus.FAILED, StoredRunStatus.INTERRUPTED -> failedColor
                            else -> otherColor
                        }
                        drawRoundRect(
                            color = color,
                            topLeft = androidx.compose.ui.geometry.Offset(
                                x = index * slotWidth + (slotWidth - barWidth) / 2f,
                                y = size.height - height,
                            ),
                            size = androidx.compose.ui.geometry.Size(barWidth, height),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusDistribution(dashboard: RunDashboardSummary) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Run status", style = MaterialTheme.typography.titleMedium)
            if (dashboard.totalRuns == 0L) {
                Text("No run history yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                dashboard.statusCounts.entries
                    .sortedByDescending { it.value }
                    .forEach { (status, count) ->
                        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Row(modifier = Modifier.fillMaxWidth()) {
                                Text(status.displayName(), modifier = Modifier.weight(1f))
                                Text(count.toString(), fontWeight = FontWeight.SemiBold)
                            }
                            LinearProgressIndicator(
                                progress = { count.toFloat() / dashboard.totalRuns },
                                modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small),
                                strokeCap = StrokeCap.Round,
                            )
                        }
                    }
            }
        }
    }
}

@Composable
private fun WorkflowQuickPlayCard(
    workflow: WorkflowSummary,
    onPlayWorkflow: (WorkflowSummary) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(workflow.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "Revision ${workflow.latestRevision}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            OutlinedButton(onClick = { onPlayWorkflow(workflow) }) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Play")
            }
        }
    }
}

@Composable
private fun FailureCard(run: StoredRunRecord, workflowName: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(workflowName, style = MaterialTheme.typography.titleMedium)
            Text(run.status.displayName(), color = MaterialTheme.colorScheme.error)
            run.interruptionReason?.let {
                Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleLarge)
}

@Composable
private fun EmptyCard(message: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Text(
            message,
            modifier = Modifier.padding(18.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun runDuration(run: StoredRunRecord): Long = ((run.finishedAtMs ?: run.startedAtMs) - run.startedAtMs).coerceAtLeast(0L)

private fun isFailure(run: StoredRunRecord): Boolean = run.status == StoredRunStatus.FAILED || run.status == StoredRunStatus.INTERRUPTED

private fun StoredRunStatus.displayName(): String = name.lowercase().replaceFirstChar(Char::uppercase)

internal fun formatDuration(durationMs: Long?): String {
    if (durationMs == null) return "—"
    val seconds = durationMs / 1000
    return if (seconds < 60) "${seconds}s" else "${seconds / 60}m ${seconds % 60}s"
}
