package com.veyarc.app

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
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
import java.text.DateFormat
import java.util.Date
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
    var latestRecording by remember { mutableStateOf<WorkflowSummary?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(repository, refreshGeneration) {
        error = null
        runCatching {
            withContext(Dispatchers.IO) {
                val storedWorkflows = repository.listWorkflows()
                Triple(
                    repository.loadRunDashboard(),
                    storedWorkflows,
                    storedWorkflows.firstOrNull { workflow ->
                        runCatching {
                            repository.loadRevision(workflow.workflowId, workflow.latestRevision)
                                .producerVersion == RECORDER_PRODUCER_VERSION
                        }.getOrDefault(false)
                    },
                )
            }
        }.onSuccess { (loadedDashboard, loadedWorkflows, loadedRecording) ->
            dashboard = loadedDashboard
            workflows = loadedWorkflows
            latestRecording = loadedRecording
        }.onFailure {
            error = "Dashboard data could not be loaded."
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Home", style = MaterialTheme.typography.headlineSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onRecord) { Text("Record") }
                    OutlinedButton(
                        onClick = { latestRecording?.let(onPlayWorkflow) },
                        enabled = latestRecording != null,
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Play")
                    }
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
            val names = workflows.associate { it.workflowId to it.name }
            item { RunOutcomeChart(loaded.recentRuns, names) }
            item { StatusDistribution(loaded) }

            val favorites = workflows.filter { it.isFavorite }
            if (favorites.isNotEmpty()) {
                item { SectionTitle("Favorite workflows") }
                items(favorites, key = WorkflowSummary::workflowId) { workflow ->
                    WorkflowQuickPlayCard(workflow, onPlayWorkflow)
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
private fun RunOutcomeChart(
    runs: List<StoredRunRecord>,
    workflowNames: Map<String, String>,
) {
    var selectedIndex by remember(runs) { mutableStateOf<Int?>(null) }
    var tooltipVisible by remember(runs) { mutableStateOf(false) }
    val tooltipAlpha by animateFloatAsState(
        targetValue = if (tooltipVisible) 1f else 0f,
        animationSpec = tween(90),
        label = "Run details tooltip",
    )
    var chartWidthPx by remember { mutableIntStateOf(0) }
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
                BoxWithConstraints(modifier = Modifier.fillMaxWidth().height(150.dp)) {
                    Canvas(
                        modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .align(Alignment.BottomCenter)
                            .onSizeChanged { chartWidthPx = it.width }
                            .pointerInput(plottedRuns, chartWidthPx) {
                                detectTapGestures(
                                    onTap = {
                                        tooltipVisible = false
                                        selectedIndex = null
                                    },
                                    onPress = {
                                        tryAwaitRelease()
                                        tooltipVisible = false
                                        kotlinx.coroutines.delay(100)
                                        selectedIndex = null
                                    },
                                    onLongPress = { point ->
                                        if (chartWidthPx > 0) {
                                            selectedIndex =
                                                ((point.x / chartWidthPx) * plottedRuns.size)
                                                    .toInt()
                                                    .coerceIn(plottedRuns.indices)
                                            tooltipVisible = true
                                        }
                                    },
                                )
                            }
                            .semantics {
                                contentDescription = "Recent run duration chart: $description. Long press a bar for details."
                            },
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
                    selectedIndex?.let { index ->
                        val run = plottedRuns[index]
                        val slotWidth = maxWidth / plottedRuns.size
                        androidx.compose.material3.Surface(
                            modifier = Modifier
                                .graphicsLayer(alpha = tooltipAlpha)
                                .padding(
                                    start = (slotWidth * index)
                                        .coerceIn(0.dp, (maxWidth - 190.dp).coerceAtLeast(0.dp)),
                                )
                                .width(190.dp),
                            shape = MaterialTheme.shapes.small,
                            tonalElevation = 6.dp,
                            shadowElevation = 4.dp,
                        ) {
                            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    workflowNames[run.workflowId] ?: "Deleted workflow",
                                    style = MaterialTheme.typography.labelLarge,
                                )
                                Text(
                                    "${run.status.displayName()} · ${formatDuration(runDuration(run))}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    formatRunTime(run.startedAtMs),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
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
                                drawStopIndicator = {},
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
private fun SectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleLarge)
}

private fun runDuration(run: StoredRunRecord): Long = ((run.finishedAtMs ?: run.startedAtMs) - run.startedAtMs).coerceAtLeast(0L)

private fun StoredRunStatus.displayName(): String = name.lowercase().replaceFirstChar(Char::uppercase)

private fun formatRunTime(timestampMs: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timestampMs))

internal fun formatDuration(durationMs: Long?): String {
    if (durationMs == null) return "—"
    val seconds = durationMs / 1000
    return if (seconds < 60) "${seconds}s" else "${seconds / 60}m ${seconds % 60}s"
}

private const val RECORDER_PRODUCER_VERSION = "android-recorder"
