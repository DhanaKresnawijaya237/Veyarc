package com.veyarc.app

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.veyarc.automation.android.ReplayFloatingController
import com.veyarc.automation.android.ReplayWorkflowCatalog
import com.veyarc.data.storage.RunDashboardSummary
import com.veyarc.data.storage.StoredRunRecord
import com.veyarc.data.storage.StoredRunStatus
import com.veyarc.data.storage.WorkflowRepository
import com.veyarc.data.storage.WorkflowSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
    usageRepository: com.veyarc.data.storage.AssistantUsageRepository? = null,
    onOpenGuide: (() -> Unit)? = null,
) {
    var dashboard by pageState.dashboard
    var workflows by pageState.workflows
    var quickPlayWorkflow by pageState.quickPlayWorkflow
    var error by remember { mutableStateOf<String?>(null) }

    val pageActive = LocalMainPageActive.current
    val context = LocalContext.current
    var aiUsage by pageState.aiUsage
    LaunchedEffect(usageRepository, pageActive) {
        if (usageRepository == null || !pageActive) return@LaunchedEffect
        try {
            usageRepository.observe().collect { aiUsage = it }
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            error = "AI usage could not be loaded."
        }
    }
    DisposableEffect(context, workflows, pageActive) {
        val preferences = context.getSharedPreferences("replay_controller", Context.MODE_PRIVATE)
        fun refreshPlayTarget() {
            ReplayWorkflowCatalog.load(context)
            val target = ReplayWorkflowCatalog.selected(ReplayFloatingController.playTarget(context))
            quickPlayWorkflow = target?.let { selected ->
                workflows.firstOrNull { it.workflowId == selected.workflowId }?.copy(latestRevision = selected.revision)
            }
        }
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key in setOf("play_target", "workflow_catalog", "most_recently_played", "most_recently_favorited")) refreshPlayTarget()
        }
        if (pageActive) {
            refreshPlayTarget()
            preferences.registerOnSharedPreferenceChangeListener(listener)
        }
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    LaunchedEffect(repository, refreshGeneration, pageActive) {
        if (!pageActive) return@LaunchedEffect
        error = null
        runCatching {
            withContext(Dispatchers.IO) {
                val storedWorkflows = repository.listWorkflowSummaries()
                Pair(
                    repository.loadRunDashboard(recentLimit = 20),
                    storedWorkflows,
                )
            }
        }.onSuccess { (loadedDashboard, loadedWorkflows) ->
            dashboard = loadedDashboard
            workflows = loadedWorkflows
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
        item(key = "quick-start") { HomeQuickStart(quickPlayWorkflow, onRecord, onPlayWorkflow) }
        if (aiUsage.isNotEmpty()) item(key = "ai-usage") { AssistantUsageCard(aiUsage) }

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
            if (workflows.isEmpty() && loaded.totalRuns == 0L && aiUsage.isEmpty() && onOpenGuide != null) {
                item(key = "guide") {
                    TextButton(onClick = onOpenGuide, modifier = Modifier.fillMaxWidth()) {
                        Text("Open guide")
                    }
                }
            }
            item(key = "metrics") { DashboardMetrics(loaded) }
            val names = workflows.associate { it.workflowId to it.name }
            val loadEarlier: suspend (Int) -> Int = { count ->
                val expected = dashboard ?: throw CancellationException()
                val oldest = expected.recentRuns.lastOrNull() ?: throw CancellationException()
                val earlier = withContext(Dispatchers.IO) { repository.listEarlierRunSummaries(oldest, count) }
                if (dashboard !== expected) throw CancellationException()
                dashboard = expected.copy(recentRuns = expected.recentRuns + earlier)
                earlier.size
            }
            item(key = "activity") {
                BoxWithConstraints {
                    if (maxWidth >= 720.dp) {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            RunOutcomeChart(loaded.recentRuns, names, Modifier.weight(1.3f), loaded.recentRuns.size < loaded.totalRuns, loadEarlier)
                            StatusDistribution(loaded, Modifier.weight(1f))
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            RunOutcomeChart(loaded.recentRuns, names, hasEarlier = loaded.recentRuns.size < loaded.totalRuns, onLoadEarlier = loadEarlier)
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
private fun HomeQuickStart(target: WorkflowSummary?, onRecord: () -> Unit, onPlay: (WorkflowSummary) -> Unit) {
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
            Surface(onClick = { target?.let(onPlay) }, enabled = target != null, modifier = Modifier.weight(1f).fillMaxHeight(), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.PlayArrow, null, Modifier.size(28.dp), tint = if (target == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
                    Text("Play", style = MaterialTheme.typography.titleLarge, color = if (target == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                    Text(target?.name ?: "No matching workflow", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
internal fun RunOutcomeChart(
    runs: List<StoredRunRecord>,
    workflowNames: Map<String, String>,
    modifier: Modifier = Modifier,
    hasEarlier: Boolean = false,
    onLoadEarlier: suspend (Int) -> Int = { 0 },
) {
    val scope = rememberCoroutineScope()
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var pageStart by remember(runs.firstOrNull()?.runId) { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var exhausted by remember(runs.firstOrNull()?.runId) { mutableStateOf(false) }
    var pageError by remember { mutableStateOf(false) }
    Surface(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        BoxWithConstraints(Modifier.padding(18.dp)) {
            val pageSize = (maxWidth / 42.dp).toInt().coerceIn(1, 20)
            val shownStart = (pageStart / pageSize).coerceAtMost((runs.size - 1).coerceAtLeast(0) / pageSize) * pageSize
            val plottedRuns = remember(runs, shownStart, pageSize) { runs.subList(shownStart, minOf(shownStart + pageSize, runs.size)).reversed() }
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Recent run duration", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    IconButton(enabled = !loading && runs.isNotEmpty() && (shownStart + pageSize < runs.size || (hasEarlier && !exhausted)), onClick = {
                        val nextStart = shownStart + pageSize
                        pageError = false
                        if (nextStart + pageSize <= runs.size || !hasEarlier || exhausted) {
                            pageStart = nextStart
                        } else {
                            loading = true
                            scope.launch {
                                try {
                                    val count = onLoadEarlier(pageSize)
                                    exhausted = count < pageSize
                                    if (nextStart < runs.size + count) pageStart = nextStart
                                } catch (error: Exception) {
                                    if (error is CancellationException) throw error
                                    pageError = true
                                } finally {
                                    loading = false
                                }
                            }
                        }
                    }, modifier = Modifier.semantics { contentDescription = "Show earlier runs" }) {
                        if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Outlined.ChevronLeft, null)
                    }
                    IconButton(enabled = !loading && shownStart > 0, onClick = {
                        pageError = false
                        pageStart = (shownStart - pageSize).coerceAtLeast(0)
                    }) {
                        Icon(Icons.Outlined.ChevronRight, "Show newer runs")
                    }
                }
                if (pageError) Text("Earlier runs could not be loaded. Try again.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                if (runs.isEmpty()) {
                    Text("Run a workflow to start the chart.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    val completedColor = MaterialTheme.colorScheme.primary
                    val failedColor = MaterialTheme.colorScheme.error
                    val otherColor = MaterialTheme.colorScheme.secondary
                    val maximum = remember(runs) { runs.maxOf { runDuration(it).coerceAtLeast(1L) }.toFloat() }
                    Crossfade(plottedRuns, Modifier.fillMaxWidth().height(if (landscape) 120.dp else 150.dp).testTag("recent-run-bars"), animationSpec = tween(140), label = "Recent run batch") { visibleRuns ->
                        var selectedIndex by remember { mutableStateOf<Int?>(null) }
                        var tooltipVisible by remember { mutableStateOf(false) }
                        val tooltipAlpha by animateFloatAsState(if (tooltipVisible) 1f else 0f, tween(90), label = "Run details tooltip")
                        var chartWidthPx by remember { mutableIntStateOf(0) }
                        val description = remember(visibleRuns) { visibleRuns.joinToString { "${it.status.name.lowercase()} ${runDuration(it)} ms" } }
                        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                            Canvas(
                                modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .height(if (landscape) 96.dp else 120.dp)
                                    .align(Alignment.BottomCenter)
                                    .onSizeChanged { chartWidthPx = it.width }
                                    .pointerInput(visibleRuns, chartWidthPx) {
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
                                                        ((point.x / chartWidthPx) * visibleRuns.size)
                                                            .toInt()
                                                            .coerceIn(visibleRuns.indices)
                                                    tooltipVisible = true
                                                }
                                            },
                                        )
                                    }
                                    .semantics {
                                        contentDescription = "Recent run duration chart: $description. Long press a bar for details."
                                    },
                            ) {
                                val slotWidth = size.width / visibleRuns.size
                                val barWidth = (slotWidth * 0.58f).coerceAtMost(28.dp.toPx())
                                for (index in visibleRuns.indices) {
                                    val run = visibleRuns[index]
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
                                val run = visibleRuns[index]
                                val slotWidth = maxWidth / visibleRuns.size
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
}

@Composable
private fun StatusDistribution(dashboard: RunDashboardSummary, modifier: Modifier = Modifier) {
    val counts = remember(dashboard.statusCounts) { dashboard.statusCounts.entries.filter { it.value > 0 }.sortedByDescending { it.value } }
    val colors = MaterialTheme.colorScheme
    val statusColors = mapOf(
        StoredRunStatus.COMPLETED to colors.primary,
        StoredRunStatus.FAILED to colors.error,
        StoredRunStatus.RUNNING to colors.tertiary,
        StoredRunStatus.STOPPED to colors.secondary,
        StoredRunStatus.CANCELLED to colors.outline,
        StoredRunStatus.INTERRUPTED to colors.error.copy(alpha = 0.65f),
    )
    Surface(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Run status", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Text("${dashboard.totalRuns} ${if (dashboard.totalRuns == 1L) "run" else "runs"}", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            }
            if (dashboard.totalRuns == 0L) {
                Text("No run history yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Canvas(Modifier.fillMaxWidth().height(12.dp).clip(CircleShape)) {
                    var start = 0f
                    counts.forEach { (status, count) ->
                        val width = size.width * count.toFloat() / dashboard.totalRuns
                        drawRect(statusColors.getValue(status), androidx.compose.ui.geometry.Offset(start, 0f), androidx.compose.ui.geometry.Size(width, size.height))
                        start += width
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    counts.forEach { (status, count) ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Spacer(Modifier.size(8.dp).clip(CircleShape).background(statusColors.getValue(status)))
                            Text(status.displayName(), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Text(count.toString(), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text("${(count * 100.0 / dashboard.totalRuns).roundToInt()}%", Modifier.width(44.dp), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.End)
                        }
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
