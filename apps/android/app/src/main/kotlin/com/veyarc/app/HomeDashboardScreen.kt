package com.veyarc.app

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.veyarc.data.storage.RunDashboardSummary
import com.veyarc.data.storage.StoredRunRecord
import com.veyarc.data.storage.StoredRunStatus
import com.veyarc.data.storage.WorkflowRepository
import com.veyarc.data.storage.WorkflowSummary
import kotlinx.coroutines.CancellationException
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
    pageState: HomeDashboardState = remember { HomeDashboardState() },
) {
    var dashboard by pageState.dashboard
    var workflows by pageState.workflows
    var latestRecording by pageState.latestRecording
    var error by remember { mutableStateOf<String?>(null) }

    val pageActive = LocalMainPageActive.current
    LaunchedEffect(repository, refreshGeneration, pageActive) {
        if (!pageActive) return@LaunchedEffect
        error = null
        runCatching {
            withContext(Dispatchers.IO) {
                val storedWorkflows = repository.listWorkflowSummaries()
                Triple(
                    repository.loadRunDashboard(recentLimit = 100),
                    storedWorkflows,
                    storedWorkflows.firstOrNull { it.producerVersion == RECORDER_PRODUCER_VERSION }
                        ?: storedWorkflows.firstOrNull(),
                )
            }
        }.onSuccess { (loadedDashboard, loadedWorkflows, loadedRecording) ->
            dashboard = loadedDashboard
            workflows = loadedWorkflows
            latestRecording = loadedRecording
        }.onFailure {
            if (it is CancellationException) throw it

            error = "Dashboard data could not be loaded."
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("home-dashboard"),
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
                if (dashboard != null) Text("${workflows.size} ${if (workflows.size == 1) "workflow" else "workflows"}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item(key = "quick-start") { HomeQuickStart(latestRecording, onRecord, onPlayWorkflow) }

        error?.let { message ->
            item { Text(message, color = MaterialTheme.colorScheme.error) }
        }

        val loaded = dashboard
        if (loaded == null && error == null) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().height(160.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator()
                }
            }
        } else if (loaded != null) {
            item(key = "metrics") { DashboardMetrics(loaded) }
            val names = workflows.associate { it.workflowId to it.name }
            item(key = "activity") {
                BoxWithConstraints {
                    if (maxWidth >= 720.dp) {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            RunOutcomeChart(loaded.recentRuns, names, Modifier.weight(1.3f))
                            StatusDistribution(loaded, Modifier.weight(1f))
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            RunOutcomeChart(loaded.recentRuns, names)
                            StatusDistribution(loaded)
                        }
                    }
                }
            }

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
private fun HomeQuickStart(latest: WorkflowSummary?, onRecord: () -> Unit, onPlay: (WorkflowSummary) -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.surfaceContainerLow))).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Quick start", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(onClick = onRecord, modifier = Modifier.weight(1f).fillMaxHeight(), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.FiberManualRecord, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.error)
                    Text("Record", style = MaterialTheme.typography.titleLarge)
                    Text("Capture a workflow", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Surface(onClick = { latest?.let(onPlay) }, enabled = latest != null, modifier = Modifier.weight(1f).fillMaxHeight(), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.PlayArrow, null, Modifier.size(28.dp), tint = if (latest == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
                    Text("Play", style = MaterialTheme.typography.titleLarge, color = if (latest == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                    Text(latest?.name ?: "No workflow yet", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun DashboardMetrics(dashboard: RunDashboardSummary) {
    val metrics = listOf(
        "Total runs" to dashboard.totalRuns.toString(),
        "Completion rate" to "${(dashboard.completionRate * 100).roundToInt()}%",
        "Failed" to dashboard.statusCounts.getOrDefault(StoredRunStatus.FAILED, 0L).toString(),
        "Average duration" to formatDuration(dashboard.averageFinishedRunDurationMs),
    )
    BoxWithConstraints {
        val columns = if (maxWidth >= 600.dp) 4 else 2
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            metrics.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { (label, value) -> MetricCard(label, value, Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun MetricCard(
    label: String,
    value: String,
    modifier: Modifier,
) {
    Surface(modifier = modifier, shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(value, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RunOutcomeChart(
    runs: List<StoredRunRecord>,
    workflowNames: Map<String, String>,
    modifier: Modifier = Modifier,
) {
    var selectedIndex by remember(runs) { mutableStateOf<Int?>(null) }
    var tooltipVisible by remember(runs) { mutableStateOf(false) }
    val tooltipAlpha by animateFloatAsState(
        targetValue = if (tooltipVisible) 1f else 0f,
        animationSpec = tween(90),
        label = "Run details tooltip",
    )
    var chartWidthPx by remember { mutableIntStateOf(0) }
    val chartScroll = rememberScrollState()
    var revealed by remember(runs) { mutableStateOf(false) }
    val reveal by animateFloatAsState(if (revealed) 1f else 0f, tween(220), label = "Run chart reveal")
    LaunchedEffect(runs) { revealed = true }
    Surface(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Recent run duration", style = MaterialTheme.typography.titleMedium)
            if (runs.isEmpty()) {
                Text("Run a workflow to start the chart.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                val completedColor = MaterialTheme.colorScheme.primary
                val failedColor = MaterialTheme.colorScheme.error
                val otherColor = MaterialTheme.colorScheme.secondary
                val plottedRuns = runs.reversed()
                val maximum = remember(runs) { runs.maxOf { runDuration(it).coerceAtLeast(1L) }.toFloat() }
                val description = plottedRuns.joinToString { "${it.status.name.lowercase()} ${runDuration(it)} ms" }
                LaunchedEffect(chartScroll.maxValue, plottedRuns.size) {
                    chartScroll.scrollTo(chartScroll.maxValue)
                }
                BoxWithConstraints(modifier = Modifier.fillMaxWidth().horizontalScroll(chartScroll)) {
                    BoxWithConstraints(modifier = Modifier.width(42.dp * plottedRuns.size).height(150.dp)) {
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
                            val slotWidth = size.width / plottedRuns.size
                            val barWidth = (slotWidth * 0.58f).coerceAtMost(28.dp.toPx())
                            plottedRuns.forEachIndexed { index, run ->
                                val ratio = runDuration(run).coerceAtLeast(1L) / maximum
                                val height = (size.height * ratio).coerceAtLeast(8.dp.toPx()) * reveal
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
                Text("Hold a bar for details", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun StatusDistribution(dashboard: RunDashboardSummary, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Run status", style = MaterialTheme.typography.titleMedium)
            if (dashboard.totalRuns == 0L) {
                Text("No run history yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                dashboard.statusCounts.entries
                    .sortedByDescending { it.value }
                    .forEach { (status, count) ->
                        val progress by animateFloatAsState(count.toFloat() / dashboard.totalRuns, tween(220), label = "Run status count")
                        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Row(modifier = Modifier.fillMaxWidth()) {
                                Text(status.displayName(), modifier = Modifier.weight(1f))
                                Text(count.toString(), fontWeight = FontWeight.SemiBold)
                            }
                            LinearProgressIndicator(
                                progress = { progress },
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
    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
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
            Surface(onClick = { onPlayWorkflow(workflow) }, shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                androidx.compose.foundation.layout.Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "Play ${workflow.name}", tint = MaterialTheme.colorScheme.primary)
                }
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
