package com.veyarc.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.veyarc.data.storage.FolderSummary
import com.veyarc.data.storage.StoredRunRecord
import com.veyarc.data.storage.StoredRunStatus
import com.veyarc.data.storage.WorkflowRepository
import com.veyarc.data.storage.WorkflowSummary
import com.veyarc.feature.editor.StoredRunDetailScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

internal data class ManagedRun(
    val record: StoredRunRecord,
    val workflowName: String,
    val revisionAvailable: Boolean = true,
)

internal enum class RunSortOption(val label: String) {
    NEWEST("Newest"),
    DURATION("Duration"),
    STATUS("Status"),
}

internal enum class RunDateFilter(val label: String, val ageMs: Long?) {
    ANY("Any date", null),
    TODAY("Last 24 hours", TimeUnit.DAYS.toMillis(1)),
    WEEK("Last 7 days", TimeUnit.DAYS.toMillis(7)),
    MONTH("Last 30 days", TimeUnit.DAYS.toMillis(30)),
}

@Composable
fun RunManagerScreen(
    repository: WorkflowRepository,
    refreshGeneration: Int,
    onOpenWorkflowRevision: (StoredRunRecord) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var runs by remember { mutableStateOf<List<ManagedRun>>(emptyList()) }
    var workflows by remember { mutableStateOf<List<WorkflowSummary>>(emptyList()) }
    var folders by remember { mutableStateOf<List<FolderSummary>>(emptyList()) }
    var selectedView by rememberSaveable { mutableStateOf(RUNS_ALL_VIEW) }
    var selectedRunId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var query by rememberSaveable { mutableStateOf("") }
    var statusFilter by rememberSaveable { mutableStateOf<StoredRunStatus?>(null) }
    var workflowFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var dateFilter by rememberSaveable { mutableStateOf(RunDateFilter.ANY) }
    var revisionFilter by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(RunSortOption.NEWEST) }
    var localRefresh by remember { mutableIntStateOf(0) }
    var isBusy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var creatingFolder by remember { mutableStateOf(false) }
    var folderEditor by remember { mutableStateOf<FolderSummary?>(null) }
    var pendingFolderDelete by remember { mutableStateOf<FolderSummary?>(null) }
    var pendingRunDelete by remember { mutableStateOf<List<StoredRunRecord>>(emptyList()) }
    var moveSelection by remember { mutableStateOf(false) }
    var sortMenuOpen by remember { mutableStateOf(false) }
    var statusMenuOpen by remember { mutableStateOf(false) }
    var workflowMenuOpen by remember { mutableStateOf(false) }
    var dateMenuOpen by remember { mutableStateOf(false) }

    LaunchedEffect(repository, refreshGeneration, localRefresh) {
        isBusy = true
        message = null
        runCatching {
            withContext(Dispatchers.IO) {
                val loadedWorkflows = repository.listWorkflows()
                val names = loadedWorkflows.associate { it.workflowId to it.name }
                Triple(
                    repository.listRunHistory(limit = 1_000).map { run ->
                        ManagedRun(
                            record = run,
                            workflowName = names[run.workflowId] ?: "Deleted workflow",
                            revisionAvailable = repository.hasRevision(run.workflowId, run.revision),
                        )
                    },
                    loadedWorkflows,
                    repository.listRunFolders(),
                )
            }
        }.onSuccess { (loadedRuns, loadedWorkflows, loadedFolders) ->
            runs = loadedRuns
            workflows = loadedWorkflows
            folders = loadedFolders
            selectedIds = terminalRunIds(selectedIds, loadedRuns)
            if (selectedRunId != null && loadedRuns.none { it.record.runId == selectedRunId }) selectedRunId = null
            if (selectedView.isFolderView() && loadedFolders.none { it.folderId == selectedView.folderId() }) {
                selectedView = RUNS_ALL_VIEW
            }
            if (workflowFilter != null && loadedWorkflows.none { it.workflowId == workflowFilter }) workflowFilter = null
        }.onFailure { message = "Runs could not be loaded." }
        isBusy = false
    }

    fun mutate(success: String, operation: suspend () -> Unit) {
        scope.launch {
            isBusy = true
            message = null
            runCatching { withContext(Dispatchers.IO) { operation() } }
                .onSuccess {
                    message = success
                    selectedIds = emptySet()
                    localRefresh += 1
                }
                .onFailure { message = it.message ?: "The change could not be saved." }
            isBusy = false
        }
    }

    runs.firstOrNull { it.record.runId == selectedRunId }?.let { managedRun ->
        val run = managedRun.record
        StoredRunDetailScreen(
            record = run,
            onBack = { selectedRunId = null },
            onOpenWorkflowRevision = managedRun.takeIf(ManagedRun::revisionAvailable)?.let {
                { onOpenWorkflowRevision(run) }
            },
        )
        return
    }

    val visible = remember(
        runs,
        selectedView,
        query,
        statusFilter,
        workflowFilter,
        dateFilter,
        revisionFilter,
        sort,
    ) {
        filterAndSortRuns(
            runs = runs,
            selectedView = selectedView,
            query = query,
            status = statusFilter,
            workflowId = workflowFilter,
            date = dateFilter,
            revision = revisionFilter.toIntOrNull(),
            sort = sort,
            nowMs = System.currentTimeMillis(),
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("RUNS", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                Text("Runs Manager", style = MaterialTheme.typography.headlineMedium)
                Text("Inspect, filter, and organize saved run history.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { RunViewChip("All runs", RUNS_ALL_VIEW, selectedView) { selectedView = it } }
                item { RunViewChip("By workflow", RUNS_BY_WORKFLOW_VIEW, selectedView) { selectedView = it } }
                StoredRunStatus.entries.forEach { status ->
                    item { RunViewChip(status.label(), status.viewKey(), selectedView) { selectedView = it } }
                }
                item { RunViewChip("Unfiled", RUNS_UNFILED_VIEW, selectedView) { selectedView = it } }
                items(folders, key = FolderSummary::folderId) { folder ->
                    RunViewChip(folder.name, folder.viewKey(), selectedView) { selectedView = it }
                }
                item {
                    FilterChip(
                        selected = false,
                        onClick = { creatingFolder = true },
                        label = { Text("New folder") },
                        leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    )
                }
            }
        }

        selectedView.folderId()?.let { folderId ->
            folders.firstOrNull { it.folderId == folderId }?.let { folder ->
                item {
                    val index = folders.indexOf(folder)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { folderEditor = folder }) { Text("Rename") }
                        TextButton(
                            enabled = index > 0 && !isBusy,
                            onClick = {
                                val reordered = folders.toMutableList().apply { add(index - 1, removeAt(index)) }
                                mutate("Folder moved up.") { repository.reorderRunFolders(reordered.map { it.folderId }) }
                            },
                        ) { Text("Move up") }
                        TextButton(
                            enabled = index < folders.lastIndex && !isBusy,
                            onClick = {
                                val reordered = folders.toMutableList().apply { add(index + 1, removeAt(index)) }
                                mutate("Folder moved down.") { repository.reorderRunFolders(reordered.map { it.folderId }) }
                            },
                        ) { Text("Move down") }
                        TextButton(onClick = { pendingFolderDelete = folder }) { Text("Delete") }
                    }
                }
            }
        }

        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Search workflow or run ID") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            )
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterMenuButton(
                    modifier = Modifier.weight(1f),
                    text = "Status: ${statusFilter?.label() ?: "Any"}",
                    expanded = statusMenuOpen,
                    onOpen = { statusMenuOpen = true },
                    onDismiss = { statusMenuOpen = false },
                ) {
                    DropdownMenuItem(text = { Text("Any status") }, onClick = {
                        statusFilter = null
                        statusMenuOpen = false
                    })
                    StoredRunStatus.entries.forEach { status ->
                        DropdownMenuItem(text = { Text(status.label()) }, onClick = {
                            statusFilter = status
                            statusMenuOpen = false
                        })
                    }
                }
                FilterMenuButton(
                    modifier = Modifier.weight(1f),
                    text = "Workflow: ${workflows.firstOrNull { it.workflowId == workflowFilter }?.name ?: "Any"}",
                    expanded = workflowMenuOpen,
                    onOpen = { workflowMenuOpen = true },
                    onDismiss = { workflowMenuOpen = false },
                ) {
                    DropdownMenuItem(text = { Text("Any workflow") }, onClick = {
                        workflowFilter = null
                        workflowMenuOpen = false
                    })
                    workflows.forEach { workflow ->
                        DropdownMenuItem(text = { Text(workflow.name) }, onClick = {
                            workflowFilter = workflow.workflowId
                            workflowMenuOpen = false
                        })
                    }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterMenuButton(
                    modifier = Modifier.weight(1f),
                    text = "Date: ${dateFilter.label}",
                    expanded = dateMenuOpen,
                    onOpen = { dateMenuOpen = true },
                    onDismiss = { dateMenuOpen = false },
                ) {
                    RunDateFilter.entries.forEach { option ->
                        DropdownMenuItem(text = { Text(option.label) }, onClick = {
                            dateFilter = option
                            dateMenuOpen = false
                        })
                    }
                }
                FilterMenuButton(
                    modifier = Modifier.weight(1f),
                    text = "Sort: ${sort.label}",
                    expanded = sortMenuOpen,
                    onOpen = { sortMenuOpen = true },
                    onDismiss = { sortMenuOpen = false },
                ) {
                    RunSortOption.entries.forEach { option ->
                        DropdownMenuItem(text = { Text(option.label) }, onClick = {
                            sort = option
                            sortMenuOpen = false
                        })
                    }
                }
            }
        }

        item {
            OutlinedTextField(
                value = revisionFilter,
                onValueChange = { revisionFilter = it.filter(Char::isDigit) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Revision (optional)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }

        if (selectedIds.isNotEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("${selectedIds.size} selected", modifier = Modifier.weight(1f))
                        TextButton(onClick = { moveSelection = true }) { Text("Move") }
                        TextButton(onClick = { pendingRunDelete = runs.filter { it.record.runId in selectedIds }.map { it.record } }) { Text("Delete") }
                        TextButton(onClick = { selectedIds = emptySet() }) { Text("Clear") }
                    }
                }
            }
        }

        if (isBusy) item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
        message?.let { item { Text(it, color = MaterialTheme.colorScheme.primary) } }
        if (!isBusy && visible.isEmpty()) item { Card { Text("No runs match these filters.", modifier = Modifier.padding(18.dp)) } }

        if (selectedView == RUNS_BY_WORKFLOW_VIEW) {
            visible.groupBy { it.record.workflowId }.forEach { (_, workflowRuns) ->
                item { Text(workflowRuns.first().workflowName, style = MaterialTheme.typography.titleMedium) }
                items(workflowRuns, key = { it.record.runId }) { managed ->
                    RunCard(managed, selectedIds, isBusy, { selectedRunId = managed.record.runId }) { checked ->
                        selectedIds = if (checked) selectedIds + managed.record.runId else selectedIds - managed.record.runId
                    }
                }
            }
        } else {
            items(visible, key = { it.record.runId }) { managed ->
                RunCard(managed, selectedIds, isBusy, { selectedRunId = managed.record.runId }) { checked ->
                    selectedIds = if (checked) selectedIds + managed.record.runId else selectedIds - managed.record.runId
                }
            }
        }
    }

    if (creatingFolder || folderEditor != null) {
        RunFolderNameDialog(
            title = if (creatingFolder) "New run folder" else "Rename run folder",
            initialName = folderEditor?.name.orEmpty(),
            onDismiss = {
                creatingFolder = false
                folderEditor = null
            },
            onConfirm = { name ->
                val existing = folderEditor
                creatingFolder = false
                folderEditor = null
                mutate(if (existing == null) "Folder created." else "Folder renamed.") {
                    if (existing == null) repository.createRunFolder(name) else repository.renameRunFolder(existing.folderId, name)
                }
            },
        )
    }

    pendingFolderDelete?.let { folder ->
        AlertDialog(
            onDismissRequest = { pendingFolderDelete = null },
            title = { Text("Delete ${folder.name}?") },
            text = { Text("The folder will be removed. Its runs will remain in Unfiled.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingFolderDelete = null
                    selectedView = RUNS_UNFILED_VIEW
                    mutate("Folder deleted; its runs are now unfiled.") { repository.deleteRunFolder(folder.folderId) }
                }) { Text("Delete folder") }
            },
            dismissButton = { TextButton(onClick = { pendingFolderDelete = null }) { Text("Cancel") } },
        )
    }

    if (moveSelection) {
        AlertDialog(
            onDismissRequest = { moveSelection = false },
            title = { Text("Move ${selectedIds.size} runs") },
            text = {
                Column {
                    TextButton(onClick = {
                        moveSelection = false
                        mutate("Moved to Unfiled.") { selectedIds.forEach { repository.moveRunToFolder(it, null) } }
                    }) { Text("Unfiled") }
                    folders.forEach { folder ->
                        TextButton(onClick = {
                            moveSelection = false
                            mutate("Moved to ${folder.name}.") { selectedIds.forEach { repository.moveRunToFolder(it, folder.folderId) } }
                        }) { Text(folder.name) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { moveSelection = false }) { Text("Cancel") } },
        )
    }

    if (pendingRunDelete.isNotEmpty()) {
        val targets = pendingRunDelete
        AlertDialog(
            onDismissRequest = { pendingRunDelete = emptyList() },
            title = { Text("Delete ${targets.size} saved run${if (targets.size == 1) "" else "s"}?") },
            text = { Text("This permanently removes the selected run history from this phone.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingRunDelete = emptyList()
                    mutate("${targets.size} run${if (targets.size == 1) "" else "s"} deleted.") {
                        targets.forEach { repository.deleteRun(it.runId) }
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingRunDelete = emptyList() }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun FilterMenuButton(
    modifier: Modifier = Modifier,
    text: String,
    expanded: Boolean,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier) {
        OutlinedButton(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
            Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, content = { content() })
    }
}

@Composable
private fun RunViewChip(label: String, key: String, selected: String, onSelect: (String) -> Unit) {
    FilterChip(selected = key == selected, onClick = { onSelect(key) }, label = { Text(label) })
}

@Composable
private fun RunCard(
    run: ManagedRun,
    selectedIds: Set<String>,
    isBusy: Boolean,
    onOpen: () -> Unit,
    onSelectedChange: (Boolean) -> Unit,
) {
    val active = run.record.status == StoredRunStatus.RUNNING
    Card(modifier = Modifier.fillMaxWidth().clickable(enabled = !isBusy, onClick = onOpen)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = run.record.runId in selectedIds,
                onCheckedChange = onSelectedChange,
                enabled = !active && !isBusy,
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(run.workflowName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${run.record.status.label()} · revision ${run.record.revision}",
                    color = if (run.record.status == StoredRunStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
                Text(
                    "Started ${formatRunTimestamp(run.record.startedAtMs)} · ${run.record.durationText()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (active) "Active runs can be inspected, but not moved or deleted." else "Run ${run.record.runId}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun RunFolderNameDialog(
    title: String,
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Folder name") }) },
        confirmButton = { TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

internal fun filterAndSortRuns(
    runs: List<ManagedRun>,
    selectedView: String,
    query: String,
    status: StoredRunStatus?,
    workflowId: String?,
    date: RunDateFilter,
    revision: Int?,
    sort: RunSortOption,
    nowMs: Long,
): List<ManagedRun> = runs.asSequence()
    .filter { managed ->
        when {
            selectedView == RUNS_ALL_VIEW || selectedView == RUNS_BY_WORKFLOW_VIEW -> true
            selectedView == RUNS_UNFILED_VIEW -> managed.record.folderId == null
            selectedView.startsWith(RUNS_STATUS_PREFIX) -> managed.record.status.name == selectedView.removePrefix(RUNS_STATUS_PREFIX)
            selectedView.isFolderView() -> managed.record.folderId == selectedView.folderId()
            else -> false
        }
    }
    .filter { managed ->
        val needle = query.trim()
        needle.isEmpty() || managed.workflowName.contains(needle, ignoreCase = true) || managed.record.runId.contains(needle, ignoreCase = true)
    }
    .filter { status == null || it.record.status == status }
    .filter { workflowId == null || it.record.workflowId == workflowId }
    .filter { date.ageMs == null || it.record.startedAtMs >= nowMs - date.ageMs }
    .filter { revision == null || it.record.revision == revision }
    .sortedWith(
        when (sort) {
            RunSortOption.NEWEST -> compareByDescending<ManagedRun> { it.record.startedAtMs }
            RunSortOption.DURATION -> compareByDescending { it.record.durationMs() ?: Long.MIN_VALUE }
            RunSortOption.STATUS -> compareBy<ManagedRun> { it.record.status.ordinal }
        }.thenByDescending { it.record.startedAtMs }.thenBy { it.record.runId },
    )
    .toList()

internal fun terminalRunIds(selectedIds: Set<String>, runs: List<ManagedRun>): Set<String> = runs.asSequence()
    .filter { it.record.runId in selectedIds && it.record.status != StoredRunStatus.RUNNING }
    .mapTo(mutableSetOf()) { it.record.runId }

private fun StoredRunRecord.durationMs(): Long? = finishedAtMs?.minus(startedAtMs)?.coerceAtLeast(0L)

private fun StoredRunRecord.durationText(): String = durationMs()?.let { duration ->
    val seconds = duration / 1_000L
    "%02d:%02d".format(seconds / 60L, seconds % 60L)
} ?: "In progress"

private fun StoredRunStatus.label(): String = name.lowercase().replaceFirstChar(Char::uppercase)
private fun StoredRunStatus.viewKey(): String = RUNS_STATUS_PREFIX + name
private fun FolderSummary.viewKey(): String = RUNS_FOLDER_PREFIX + folderId
private fun String.isFolderView(): Boolean = startsWith(RUNS_FOLDER_PREFIX)
private fun String.folderId(): String? = takeIf(String::isFolderView)?.removePrefix(RUNS_FOLDER_PREFIX)
private fun formatRunTimestamp(timestampMs: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timestampMs))

internal const val RUNS_ALL_VIEW = "system:all"
internal const val RUNS_BY_WORKFLOW_VIEW = "system:workflow"
internal const val RUNS_UNFILED_VIEW = "system:unfiled"
private const val RUNS_STATUS_PREFIX = "status:"
private const val RUNS_FOLDER_PREFIX = "folder:"
