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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.veyarc.data.storage.FolderSummary
import com.veyarc.data.storage.RevisionSummary
import com.veyarc.data.storage.WorkflowRepository
import com.veyarc.data.storage.WorkflowRunActivity
import com.veyarc.data.storage.WorkflowSummary
import com.veyarc.model.DisplayOrientation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

internal data class ManagedWorkflow(
    val summary: WorkflowSummary,
    val orientation: DisplayOrientation?,
    val activity: WorkflowRunActivity = WorkflowRunActivity(0, null),
)

internal enum class WorkflowSortOption(val label: String) {
    UPDATED("Recently updated"),
    NAME("Name"),
    RECENTLY_RUN("Recently run"),
    MOST_RUN("Most run"),
}

internal enum class WorkflowOrientationFilter(val label: String) {
    ANY("Any orientation"),
    PORTRAIT("Portrait"),
    LANDSCAPE("Landscape"),
}

@Composable
fun WorkflowManagerScreen(
    repository: WorkflowRepository,
    refreshGeneration: Int,
    onCreateWorkflow: (String?) -> Unit,
    onRecordWorkflow: (String?) -> Unit,
    onEditWorkflow: (WorkflowSummary) -> Unit,
    onPlayWorkflow: (WorkflowSummary) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var workflows by remember { mutableStateOf<List<ManagedWorkflow>>(emptyList()) }
    var folders by remember { mutableStateOf<List<FolderSummary>>(emptyList()) }
    var selectedView by remember { mutableStateOf(ALL_VIEW) }
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(WorkflowSortOption.UPDATED) }
    var orientation by remember { mutableStateOf(WorkflowOrientationFilter.ANY) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var isBusy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var localRefresh by remember { mutableIntStateOf(0) }
    var folderEditor by remember { mutableStateOf<FolderSummary?>(null) }
    var creatingFolder by remember { mutableStateOf(false) }
    var pendingFolderDelete by remember { mutableStateOf<FolderSummary?>(null) }
    var pendingWorkflowDelete by remember { mutableStateOf<List<ManagedWorkflow>>(emptyList()) }
    var moveSelection by remember { mutableStateOf(false) }
    var revisionDialog by remember { mutableStateOf<Pair<WorkflowSummary, List<RevisionSummary>>?>(null) }
    var sortMenuOpen by remember { mutableStateOf(false) }
    var orientationMenuOpen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(repository, refreshGeneration, localRefresh) {
        isBusy = true
        error = null
        runCatching {
            withContext(Dispatchers.IO) {
                val summaries = repository.listWorkflows()
                val activity = repository.listWorkflowRunActivity()
                val loaded = summaries.map { summary ->
                    ManagedWorkflow(
                        summary = summary,
                        orientation = runCatching {
                            repository.loadRevision(summary.workflowId, summary.latestRevision)
                                .document.viewport.recordedOrientation
                        }.getOrNull(),
                        activity = activity[summary.workflowId] ?: WorkflowRunActivity(0, null),
                    )
                }
                loaded to repository.listWorkflowFolders()
            }
        }.onSuccess { (loadedWorkflows, loadedFolders) ->
            workflows = loadedWorkflows
            folders = loadedFolders
            selectedIds = selectedIds.intersect(loadedWorkflows.mapTo(mutableSetOf()) { it.summary.workflowId })
            if (selectedView !in setOf(ALL_VIEW, FAVORITES_VIEW, UNFILED_VIEW) &&
                loadedFolders.none { it.folderId == selectedView }
            ) {
                selectedView = ALL_VIEW
            }
        }.onFailure {
            error = "Workflows could not be loaded."
        }
        isBusy = false
    }

    fun mutate(successMessage: String, operation: suspend () -> Unit) {
        scope.launch {
            isBusy = true
            error = null
            runCatching { withContext(Dispatchers.IO) { operation() } }
                .onSuccess {
                    error = successMessage
                    selectedIds = emptySet()
                    localRefresh += 1
                }
                .onFailure { failure -> error = failure.message ?: "The change could not be saved." }
            isBusy = false
        }
    }

    val visible = remember(workflows, selectedView, query, sort, orientation) {
        filterAndSortWorkflows(workflows, selectedView, query, sort, orientation)
    }
    val destinationFolderId = selectedView.takeIf { id -> folders.any { it.folderId == id } }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("WORKFLOWS", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                Text("Workflow Manager", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Find, organize, edit, and run saved automations.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { onCreateWorkflow(destinationFolderId) }) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("New")
                    }
                    OutlinedButton(onClick = { onRecordWorkflow(destinationFolderId) }) {
                        Text("Record")
                    }
                }
            }
        }

        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { CollectionChip("All", ALL_VIEW, selectedView) { selectedView = it } }
                item { CollectionChip("Favorites", FAVORITES_VIEW, selectedView) { selectedView = it } }
                item { CollectionChip("Unfiled", UNFILED_VIEW, selectedView) { selectedView = it } }
                items(folders, key = FolderSummary::folderId) { folder ->
                    CollectionChip(folder.name, folder.folderId, selectedView) { selectedView = it }
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

        if (destinationFolderId != null) {
            item {
                val folder = folders.first { it.folderId == destinationFolderId }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { folderEditor = folder }) { Text("Rename ${folder.name}") }
                    TextButton(onClick = { pendingFolderDelete = folder }) { Text("Delete folder") }
                }
            }
        }

        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Search workflows") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            )
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    OutlinedButton(
                        onClick = { sortMenuOpen = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Sort: ${sort.shortLabel}") }
                    DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                        WorkflowSortOption.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.label) },
                                onClick = {
                                    sort = option
                                    sortMenuOpen = false
                                },
                            )
                        }
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    OutlinedButton(
                        onClick = { orientationMenuOpen = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Screen: ${orientation.shortLabel}") }
                    DropdownMenu(expanded = orientationMenuOpen, onDismissRequest = { orientationMenuOpen = false }) {
                        WorkflowOrientationFilter.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.label) },
                                onClick = {
                                    orientation = option
                                    orientationMenuOpen = false
                                },
                            )
                        }
                    }
                }
            }
        }

        if (selectedIds.isNotEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text("${selectedIds.size} selected", style = MaterialTheme.typography.titleSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { moveSelection = true }) { Text("Move") }
                            TextButton(
                                onClick = {
                                    pendingWorkflowDelete = workflows.filter {
                                        it.summary.workflowId in selectedIds
                                    }
                                },
                            ) { Text("Delete") }
                            TextButton(onClick = { selectedIds = emptySet() }) { Text("Clear") }
                        }
                    }
                }
            }
        }

        if (isBusy) item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
        error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.primary) } }

        if (!isBusy && visible.isEmpty()) {
            item { EmptyWorkflowManagerCard(if (workflows.isEmpty()) "No workflows yet." else "No workflows match these filters.") }
        }

        items(visible, key = { it.summary.workflowId }) { workflow ->
            WorkflowManagerCard(
                workflow = workflow,
                selected = workflow.summary.workflowId in selectedIds,
                enabled = !isBusy,
                onSelectedChange = { selected ->
                    selectedIds = if (selected) selectedIds + workflow.summary.workflowId else selectedIds - workflow.summary.workflowId
                },
                onFavorite = {
                    mutate(if (workflow.summary.isFavorite) "Removed from favorites." else "Added to favorites.") {
                        repository.setWorkflowFavorite(workflow.summary.workflowId, !workflow.summary.isFavorite)
                    }
                },
                onEdit = { onEditWorkflow(workflow.summary) },
                onPlay = { onPlayWorkflow(workflow.summary) },
                onRevisions = {
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                repository.listRevisions(workflow.summary.workflowId)
                            }
                        }.onSuccess { revisions ->
                            revisionDialog = workflow.summary to revisions
                        }.onFailure {
                            error = "Revision history could not be loaded."
                        }
                    }
                },
                onDelete = { pendingWorkflowDelete = listOf(workflow) },
            )
        }
    }

    if (creatingFolder || folderEditor != null) {
        FolderNameDialog(
            title = if (creatingFolder) "New folder" else "Rename folder",
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
                    if (existing == null) {
                        repository.createWorkflowFolder(name)
                    } else {
                        repository.renameWorkflowFolder(existing.folderId, name)
                    }
                }
            },
        )
    }

    pendingFolderDelete?.let { folder ->
        AlertDialog(
            onDismissRequest = { pendingFolderDelete = null },
            title = { Text("Delete ${folder.name}?") },
            text = { Text("The folder will be removed. Its workflows will remain in Unfiled.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingFolderDelete = null
                    selectedView = UNFILED_VIEW
                    mutate("Folder deleted; its workflows are now unfiled.") {
                        repository.deleteWorkflowFolder(folder.folderId)
                    }
                }) { Text("Delete folder") }
            },
            dismissButton = { TextButton(onClick = { pendingFolderDelete = null }) { Text("Cancel") } },
        )
    }

    if (pendingWorkflowDelete.isNotEmpty()) {
        val targets = pendingWorkflowDelete
        AlertDialog(
            onDismissRequest = { pendingWorkflowDelete = emptyList() },
            title = { Text(if (targets.size == 1) "Delete ${targets.single().summary.name}?" else "Delete ${targets.size} workflows?") },
            text = { Text("This permanently removes the selected workflows, their revisions, and run history from this phone.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingWorkflowDelete = emptyList()
                    mutate("${targets.size} workflow${if (targets.size == 1) "" else "s"} deleted.") {
                        targets.forEach { repository.deleteWorkflow(it.summary.workflowId) }
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingWorkflowDelete = emptyList() }) { Text("Cancel") } },
        )
    }

    if (moveSelection) {
        AlertDialog(
            onDismissRequest = { moveSelection = false },
            title = { Text("Move ${selectedIds.size} workflows") },
            text = {
                Column {
                    TextButton(onClick = {
                        moveSelection = false
                        mutate("Moved to Unfiled.") {
                            selectedIds.forEach { repository.moveWorkflowToFolder(it, null) }
                        }
                    }) { Text("Unfiled") }
                    folders.forEach { folder ->
                        TextButton(onClick = {
                            moveSelection = false
                            mutate("Moved to ${folder.name}.") {
                                selectedIds.forEach { repository.moveWorkflowToFolder(it, folder.folderId) }
                            }
                        }) { Text(folder.name) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { moveSelection = false }) { Text("Cancel") } },
        )
    }

    revisionDialog?.let { (workflow, revisions) ->
        AlertDialog(
            onDismissRequest = { revisionDialog = null },
            title = { Text("${workflow.name} revisions") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    revisions.forEach { revision ->
                        Text("Revision ${revision.revision} · ${formatTimestamp(revision.createdAtMs)}")
                    }
                }
            },
            confirmButton = { TextButton(onClick = { revisionDialog = null }) { Text("Done") } },
        )
    }
}

@Composable
private fun CollectionChip(label: String, key: String, selected: String, onSelect: (String) -> Unit) {
    FilterChip(selected = key == selected, onClick = { onSelect(key) }, label = { Text(label) })
}

@Composable
private fun WorkflowManagerCard(
    workflow: ManagedWorkflow,
    selected: Boolean,
    enabled: Boolean,
    onSelectedChange: (Boolean) -> Unit,
    onFavorite: () -> Unit,
    onEdit: () -> Unit,
    onPlay: () -> Unit,
    onRevisions: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onEdit)) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = selected, onCheckedChange = onSelectedChange, enabled = enabled)
                Column(modifier = Modifier.weight(1f)) {
                    Text(workflow.summary.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "Revision ${workflow.summary.latestRevision} · ${workflow.orientation?.displayName() ?: "Unknown orientation"}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "${workflow.activity.runCount} runs · Updated ${formatTimestamp(workflow.summary.updatedAtMs)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                IconButton(onClick = onFavorite, enabled = enabled) {
                    Icon(
                        if (workflow.summary.isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = if (workflow.summary.isFavorite) "Remove from favorites" else "Add to favorites",
                    )
                }
                Column {
                    IconButton(onClick = { menuOpen = true }, enabled = enabled) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More workflow actions")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Revision history") },
                            leadingIcon = { Icon(Icons.Filled.History, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onRevisions()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Delete") },
                            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onDelete()
                            },
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPlay, enabled = enabled) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Play")
                }
                OutlinedButton(onClick = onEdit, enabled = enabled) {
                    Icon(Icons.Filled.Edit, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Edit")
                }
            }
        }
    }
}

@Composable
private fun FolderNameDialog(title: String, initialName: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Folder name") },
                singleLine = true,
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun EmptyWorkflowManagerCard(message: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Text(message, modifier = Modifier.padding(18.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

internal fun filterAndSortWorkflows(
    workflows: List<ManagedWorkflow>,
    selectedView: String,
    query: String,
    sort: WorkflowSortOption,
    orientation: WorkflowOrientationFilter,
): List<ManagedWorkflow> = workflows.asSequence()
    .filter { workflow ->
        when (selectedView) {
            ALL_VIEW -> true
            FAVORITES_VIEW -> workflow.summary.isFavorite
            UNFILED_VIEW -> workflow.summary.folderId == null
            else -> workflow.summary.folderId == selectedView
        }
    }
    .filter { it.summary.name.contains(query.trim(), ignoreCase = true) }
    .filter { workflow ->
        when (orientation) {
            WorkflowOrientationFilter.ANY -> true
            WorkflowOrientationFilter.PORTRAIT -> workflow.orientation == DisplayOrientation.PORTRAIT
            WorkflowOrientationFilter.LANDSCAPE -> workflow.orientation == DisplayOrientation.LANDSCAPE
        }
    }
    .sortedWith(
        when (sort) {
            WorkflowSortOption.UPDATED -> compareByDescending<ManagedWorkflow> { it.summary.updatedAtMs }
            WorkflowSortOption.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.summary.name }
            WorkflowSortOption.RECENTLY_RUN -> compareByDescending { it.activity.lastRunAtMs ?: Long.MIN_VALUE }
            WorkflowSortOption.MOST_RUN -> compareByDescending { it.activity.runCount }
        }.thenBy { it.summary.workflowId },
    )
    .toList()

private fun DisplayOrientation.displayName(): String = name.lowercase().replaceFirstChar(Char::uppercase)

private val WorkflowOrientationFilter.shortLabel: String
    get() = if (this == WorkflowOrientationFilter.ANY) "Any" else label

private val WorkflowSortOption.shortLabel: String
    get() = when (this) {
        WorkflowSortOption.UPDATED -> "Updated"
        WorkflowSortOption.NAME -> "Name"
        WorkflowSortOption.RECENTLY_RUN -> "Recent run"
        WorkflowSortOption.MOST_RUN -> "Most run"
    }

private fun formatTimestamp(timestampMs: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timestampMs))

internal const val ALL_VIEW = "system:all"
internal const val FAVORITES_VIEW = "system:favorites"
internal const val UNFILED_VIEW = "system:unfiled"
