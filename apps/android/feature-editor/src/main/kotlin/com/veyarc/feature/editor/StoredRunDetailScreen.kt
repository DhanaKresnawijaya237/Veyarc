package com.veyarc.feature.editor

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.veyarc.data.storage.StoredRunEvent
import com.veyarc.data.storage.StoredRunRecord
import com.veyarc.data.storage.StoredRunStatus
import kotlinx.coroutines.launch
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
    statusLabel: String? = null,
    controls: (@Composable () -> Unit)? = null,
) {
    BackHandler(onBack = onBack)
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // ponytail: Reveals saved events only; older discarded events need a separate retention change.
    var visibleEvents by remember(record.runId) { mutableIntStateOf(12) }
    val firstEvent = (record.eventHistory.size - visibleEvents).coerceAtLeast(0)
    val packages = remember(record.eventHistory) { record.eventHistory.flatMap { event -> runApplicationReference.findAll(event.message).map { it.groupValues[2] }.toList() }.distinct() }
    val appNames = rememberApplicationNames(packages)
    val sections = remember(record.eventHistory, firstEvent) { runActivitySections(record.eventHistory, firstEvent) }
    var collapsedSections by remember(record.runId) { mutableStateOf(emptySet<Int>()) }
    val timelineColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
    val timestamp = remember(record.startedAtMs) { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(record.startedAtMs)) }
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val statusColor = when (record.status) {
        StoredRunStatus.COMPLETED -> if (dark) Color(0xFF83BF9A) else Color(0xFF2E7D32)
        StoredRunStatus.FAILED -> MaterialTheme.colorScheme.error
        StoredRunStatus.INTERRUPTED -> if (dark) Color(0xFFD5AD75) else Color(0xFF8A5C17)
        else -> MaterialTheme.colorScheme.primary
    }
    LazyColumn(modifier.fillMaxSize().testTag("run-details"), state = listState, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item(key = "title") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 4.dp)) {
                    Text(record.name ?: workflowName, style = MaterialTheme.typography.titleLarge)
                    if (record.name != null && record.name != workflowName) Text(workflowName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onBack) { Icon(Icons.Outlined.Close, "Close run details") }
            }
        }
        item(key = "summary") {
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Surface(modifier = Modifier.weight(1f, fill = false), shape = RoundedCornerShape(12.dp), color = statusColor.copy(alpha = 0.12f)) {
                            Text(statusLabel ?: runTitle(record.status), Modifier.padding(horizontal = 12.dp, vertical = 7.dp), color = statusColor, style = MaterialTheme.typography.titleSmall)
                        }
                        onOpenWorkflowRevision?.let { open ->
                            TextButton(onClick = open) { Text("Open revision ${record.revision}") }
                        }
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
                    controls?.invoke()
                }
            }
        }
        item(key = "activity") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Activity", style = MaterialTheme.typography.titleMedium)
                if (record.eventHistory.size > 1) Text(if (firstEvent > 0) "$visibleEvents of ${record.eventHistory.size} events" else "Time between events", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (firstEvent > 0) {
            item(key = "earlier") {
                TextButton(onClick = {
                    visibleEvents = minOf(record.eventHistory.size, visibleEvents + 20)
                    scope.launch { listState.animateScrollToItem(3) }
                }, modifier = Modifier.fillMaxWidth().testTag("run-earlier-activity")) {
                    Text("Show earlier activity · $firstEvent")
                }
            }
        }
        sections.forEach { section ->
            val sectionId = section.groups.first().first().index
            val collapsed = sectionId in collapsedSections
            item(key = "section-$sectionId") {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("run-section-$sectionId").semantics { stateDescription = if (collapsed) "Collapsed" else "Expanded" }
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClickLabel = if (collapsed) "Expand activity section" else "Collapse activity section") {
                            collapsedSections = if (collapsed) collapsedSections - sectionId else collapsedSections + sectionId
                        },
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(section.context, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        Text("${section.groups.sumOf { it.size }} events", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (!collapsed) {
                items(section.groups, key = { group -> "events-${group.first().index}" }) { group ->
                    val first = group.first().value
                    Column(
                        Modifier.animateItem(fadeInSpec = tween(160), placementSpec = tween(180)).fillMaxWidth()
                            .drawBehind {
                                val x = 7.dp.toPx()
                                drawLine(timelineColor, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
                                drawCircle(timelineColor, 4.dp.toPx(), Offset(x, 18.dp.toPx()))
                            }.padding(start = 24.dp, top = 6.dp, bottom = 10.dp, end = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        first.blockLabel?.let { label ->
                            Text(label, style = MaterialTheme.typography.titleSmall)
                            first.contextLabel?.removePrefix(section.context)?.removePrefix(" › ")?.takeIf { it.isNotBlank() }?.let {
                                Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        group.forEach { (index, event) ->
                            Row(Modifier.fillMaxWidth().testTag("run-event-$index"), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                                Text(readableRunMessage(event.message, appNames).replace(": true", ": met").replace(": false", ": not met"), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                if (index > 0) Text("+${formatEventGap(event.atMs - record.eventHistory[index - 1].atMs)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
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
internal fun readableRunMessage(message: String, appNames: Map<String, String> = emptyMap()): String = message.replace(runIdentifier, "").replace(runApplicationReference) {
    val name = it.groupValues[2]
    val label = appNames[name] ?: name.substringAfterLast('.')
    if (it.groupValues[1] == "Launch") "Open $label" else "$label is open"
}.replace(Regex(" {2,}"), " ").trim()
private val runApplicationReference = Regex("(Launch|Foreground package) ([a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z][a-zA-Z0-9_]*)+)")
private val runIdentifier = Regex("(?i)\\b(?:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|[0-9a-f]{64})\\b")

internal fun formatEventGap(durationMs: Long): String = String.format(Locale.getDefault(), "%.1fs", durationMs.coerceAtLeast(0L) / 1000.0)

private fun formatDuration(durationMs: Long): String {
    val seconds = durationMs.coerceAtLeast(0L) / 1_000
    return String.format(Locale.getDefault(), "%d:%02d", seconds / 60, seconds % 60)
}

/** Group only consecutive events that are known to belong to the same block and branch. */
internal fun runActivityGroups(events: List<StoredRunEvent>, firstEvent: Int): List<List<IndexedValue<StoredRunEvent>>> {
    val groups = mutableListOf<MutableList<IndexedValue<StoredRunEvent>>>()
    events.withIndex().drop(firstEvent).forEach { indexed ->
        val previous = groups.lastOrNull()?.lastOrNull()?.value
        if (indexed.value.blockLabel != null && previous?.blockLabel == indexed.value.blockLabel && previous?.contextLabel == indexed.value.contextLabel && !indexed.value.message.endsWith(" started")) {
            groups.last().add(indexed)
        } else {
            groups.add(mutableListOf(indexed))
        }
    }
    return groups
}

internal data class RunActivitySection(val context: String, val groups: MutableList<List<IndexedValue<StoredRunEvent>>>)

/** Keep branch visits separate in time, including returns from nested loops. */
internal fun runActivitySections(events: List<StoredRunEvent>, firstEvent: Int): List<RunActivitySection> {
    val sections = mutableListOf<RunActivitySection>()
    runActivityGroups(events, firstEvent).forEach { group ->
        val event = group.first().value
        val context = event.contextLabel?.split(" › ")?.takeWhile { !it.startsWith("Condition ") && it != "Not" }
            ?.joinToString(" › ")?.ifBlank { null } ?: if (event.blockLabel == null) "Run activity" else "Workflow"
        if (sections.lastOrNull()?.context == context) {
            sections.last().groups.add(group)
        } else {
            sections.add(RunActivitySection(context, mutableListOf(group)))
        }
    }
    return sections
}
