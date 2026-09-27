package com.veyarc.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.veyarc.automation.android.ReplayControllerWorkflow
import com.veyarc.automation.android.ReplayWorkflowCatalog
import com.veyarc.data.storage.FolderSummary
import com.veyarc.data.storage.RevisionSummary
import com.veyarc.data.storage.WorkflowRepository
import com.veyarc.data.storage.WorkflowRunActivity
import com.veyarc.data.storage.WorkflowSummary
import com.veyarc.feature.editor.VeyarcChoice
import com.veyarc.feature.editor.VeyarcChoicePicker
import com.veyarc.model.DisplayOrientation
import com.veyarc.model.WorkflowIds
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
internal fun WorkflowManagerScreen(
    repository: WorkflowRepository,
    refreshGeneration: Int,
    onEditWorkflow: (WorkflowSummary) -> Unit,
    onPlayWorkflow: (WorkflowSummary) -> Unit,
    onSelectionBarChange: (ManagerSelectionBarState?) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var workflows by remember { mutableStateOf<List<ManagedWorkflow>>(emptyList()) }
    var folders by remember { mutableStateOf<List<FolderSummary>>(emptyList()) }
    var folderOrder by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var selectedView by rememberSaveable { mutableStateOf(ALL_VIEW) }
    var query by rememberSaveable { mutableStateOf("") }
    var searchActive by rememberSaveable { mutableStateOf(false) }
    var sort by rememberSaveable { mutableStateOf(WorkflowSortOption.UPDATED) }
    var orientation by rememberSaveable { mutableStateOf(WorkflowOrientationFilter.ANY) }
    var selectionMode by rememberSaveable { mutableStateOf(false) }
    var selectedIds by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var isBusy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var localRefresh by remember { mutableIntStateOf(0) }
    var folderManager by remember { mutableStateOf<FolderSummary?>(null) }
    var creatingFolder by remember { mutableStateOf(false) }
    var pendingFolderDelete by remember { mutableStateOf<FolderSummary?>(null) }
    var pendingWorkflowDelete by remember { mutableStateOf<List<ManagedWorkflow>>(emptyList()) }
    var moveSelection by remember { mutableStateOf(false) }
    var copySelection by remember { mutableStateOf(false) }
    var renameSelection by remember { mutableStateOf(false) }
    var detailsSelection by remember { mutableStateOf(false) }
    var revisionDialog by remember { mutableStateOf<Pair<WorkflowSummary, List<RevisionSummary>>?>(null) }
    var sortMenuOpen by remember { mutableStateOf(false) }
    var orientationMenuOpen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(repository, refreshGeneration, localRefresh) {
        isBusy = true
        error = null
        runCatching {
            withContext(Dispatchers.IO) {
                val summaries = repository.listWorkflowSummaries()
                val activity = repository.listWorkflowRunActivity()
                val loaded = summaries.map { summary ->
                    ManagedWorkflow(
                        summary = summary,
                        orientation = summary.recordedOrientation,
                        activity = activity[summary.workflowId] ?: WorkflowRunActivity(0, null),
                    )
                }
                Triple(loaded, repository.listWorkflowFolders(), AppPreferences.workflowFolderOrder(context))
            }
        }.onSuccess { (loadedWorkflows, loadedFolders, savedOrder) ->
            workflows = loadedWorkflows
            folders = loadedFolders
            val availableKeys = listOf(ALL_VIEW, FAVORITES_VIEW, UNFILED_VIEW) + loadedFolders.map(FolderSummary::folderId)
            folderOrder = reconcileFolderOrder(savedOrder, availableKeys)
            selectedIds = selectedIds.filter { id -> loadedWorkflows.any { it.summary.workflowId == id } }
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
                    selectedIds = emptyList()
                    localRefresh += 1
                }
                .onFailure { failure -> error = failure.message ?: "The change could not be saved." }
            isBusy = false
        }
    }

    val visible = remember(workflows, selectedView, query, sort, orientation) {
        filterAndSortWorkflows(workflows, selectedView, query, sort, orientation)
    }
    val folderItems = remember(folders, folderOrder) {
        val custom = folders.associateBy(FolderSummary::folderId)
        reconcileFolderOrder(folderOrder, listOf(ALL_VIEW, FAVORITES_VIEW, UNFILED_VIEW) + custom.keys).mapNotNull { key ->
            when (key) {
                ALL_VIEW -> ManagerFolderItem(key, "All")
                FAVORITES_VIEW -> ManagerFolderItem(key, "Favorites")
                UNFILED_VIEW -> ManagerFolderItem(key, "Unfiled")
                else -> custom[key]?.let { ManagerFolderItem(key, it.name, it.colorArgb, it) }
            }
        }
    }

    fun persistFolderOrder(order: List<String>) {
        folderOrder = order
        val customIds = folders.mapTo(mutableSetOf(), FolderSummary::folderId)
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    AppPreferences.setWorkflowFolderOrder(context, order)
                    repository.reorderWorkflowFolders(order.filter(customIds::contains))
                }
            }.onFailure { error = it.message ?: "Folder order could not be saved." }
        }
    }
    fun toggleFavorite(workflow: ManagedWorkflow) {
        val favorite = !workflow.summary.isFavorite
        workflows = workflows.map { item ->
            if (item.summary.workflowId == workflow.summary.workflowId) {
                item.copy(summary = item.summary.copy(isFavorite = favorite))
            } else {
                item
            }
        }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    repository.setWorkflowFavorite(workflow.summary.workflowId, favorite)
                }
                ReplayWorkflowCatalog.markFavorite(
                    context,
                    ReplayControllerWorkflow(
                        workflowId = workflow.summary.workflowId,
                        revision = workflow.summary.latestRevision,
                        name = workflow.summary.name,
                        updatedAtMs = workflow.summary.updatedAtMs,
                        isFavorite = favorite,
                    ),
                    favorite,
                )
            }.onFailure {
                workflows = workflows.map { item ->
                    if (item.summary.workflowId == workflow.summary.workflowId) workflow else item
                }
                error = "Favorite could not be saved."
            }
        }
    }

    LaunchedEffect(selectionMode, selectedIds, selectedView, isBusy) {
        onSelectionBarChange(
            if (!selectionMode) {
                null
            } else {
                ManagerSelectionBarState(
                    selectedCount = selectedIds.size,
                    onCopy = { copySelection = true }.takeIf {
                        !isBusy && selectedIds.isNotEmpty() && folders.any { it.folderId == selectedView }
                    },
                    onRename = { renameSelection = true }.takeIf { !isBusy && selectedIds.size == 1 },
                    onMove = { moveSelection = true },
                    onDelete = { pendingWorkflowDelete = workflows.filter { it.summary.workflowId in selectedIds } },
                    onDetails = { detailsSelection = true }.takeIf { selectedIds.isNotEmpty() },
                )
            },
        )
    }
    DisposableEffect(Unit) {
        onDispose { onSelectionBarChange(null) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            CompactManagerHeader(
                title = "Workflow Manager",
                searchActive = searchActive,
                query = query,
                selectionMode = selectionMode,
                selectedCount = selectedIds.size,
                onQueryChange = { query = it },
                onSearchActiveChange = { searchActive = it },
                onSelectionModeChange = { enabled ->
                    selectionMode = enabled
                    if (!enabled) selectedIds = emptyList()
                },
            )
        }

        item {
            ReorderableFolderRow(
                folders = folderItems,
                selectedKey = selectedView,
                onSelect = { selectedView = it },
                onManage = { folderManager = it },
                onOrderChanged = ::persistFolderOrder,
                onCreate = { creatingFolder = true },
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    OutlinedButton(
                        onClick = { sortMenuOpen = true },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                    ) { Text("Sort: ${sort.shortLabel}", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    if (sortMenuOpen) {
                        VeyarcChoicePicker(
                            title = "Sort workflows",
                            options = WorkflowSortOption.entries.map { VeyarcChoice(it.name, it.label) },
                            selectedId = sort.name,
                            onDismiss = { sortMenuOpen = false },
                            onSelect = { choice ->
                                sort = WorkflowSortOption.entries.first { it.name == choice.id }
                                sortMenuOpen = false
                            },
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    OutlinedButton(
                        onClick = { orientationMenuOpen = true },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                    ) {
                        Text(
                            "Screen: ${orientation.shortLabel}",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (orientationMenuOpen) {
                        VeyarcChoicePicker(
                            title = "Screen orientation",
                            options = WorkflowOrientationFilter.entries.map { VeyarcChoice(it.name, it.label) },
                            selectedId = orientation.name,
                            onDismiss = { orientationMenuOpen = false },
                            onSelect = { choice ->
                                orientation = WorkflowOrientationFilter.entries.first { it.name == choice.id }
                                orientationMenuOpen = false
                            },
                        )
                    }
                }
                AnimatedVisibility(
                    visible = searchActive,
                    enter = fadeIn(tween(180)) + expandHorizontally(tween(180)),
                    exit = fadeOut(tween(180)) + shrinkHorizontally(tween(180)),
                ) {
                    IconButton(onClick = {
                        selectionMode = !selectionMode
                        if (!selectionMode) selectedIds = emptyList()
                    }) {
                        Icon(
                            if (selectionMode) Icons.Filled.Close else Icons.Filled.CheckBox,
                            contentDescription = if (selectionMode) "Leave selection mode" else "Select items",
                        )
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
                selectionMode = selectionMode,
                selected = workflow.summary.workflowId in selectedIds,
                enabled = !isBusy,
                onSelectedChange = { selected ->
                    selectedIds = if (selected) {
                        (selectedIds + workflow.summary.workflowId).distinct()
                    } else {
                        selectedIds - workflow.summary.workflowId
                    }
                },
                onLongPress = {
                    selectionMode = true
                    selectedIds = (selectedIds + workflow.summary.workflowId).distinct()
                },
                onFavorite = { toggleFavorite(workflow) },
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

    if (creatingFolder) {
        FolderNameDialog(
            title = "New folder",
            initialName = "",
            onDismiss = {
                creatingFolder = false
            },
            onConfirm = { name ->
                creatingFolder = false
                mutate("Folder created.") {
                    repository.createWorkflowFolder(name)
                }
            },
        )
    }
    folderManager?.let { folder ->
        FolderManagementDialog(
            folder = folder,
            usedColors = folders.filterNot { it.folderId == folder.folderId }.mapNotNull { it.colorArgb }.toSet(),
            onDismiss = { folderManager = null },
            onSave = { name, colorArgb ->
                folderManager = null
                mutate("Folder updated.") {
                    if (name.trim() != folder.name) repository.renameWorkflowFolder(folder.folderId, name)
                    if (colorArgb != folder.colorArgb) {
                        repository.setWorkflowFolderColor(folder.folderId, colorArgb)
                    }
                }
            },
            onDelete = {
                folderManager = null
                pendingFolderDelete = folder
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
            text = { Text("This permanently removes the selected workflows, their revisions, run history, and associated chats from this phone.") },
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

    if (copySelection) {
        AlertDialog(
            onDismissRequest = { copySelection = false },
            title = { Text("Copy ${selectedIds.size} workflow${if (selectedIds.size == 1) "" else "s"}") },
            text = {
                Column {
                    folders.forEach { folder ->
                        TextButton(onClick = {
                            copySelection = false
                            val targets = workflows.filter { it.summary.workflowId in selectedIds }
                            mutate("Copied ${targets.size} workflow${if (targets.size == 1) "" else "s"}.") {
                                val usedNames = repository.listWorkflowSummaries().mapTo(mutableSetOf()) { it.name }
                                targets.forEach { target ->
                                    val source = repository.loadRevisionDocument(
                                        target.summary.workflowId,
                                        target.summary.latestRevision,
                                    )
                                    val copyName = uniqueWorkflowCopyName(target.summary.name, usedNames)
                                    usedNames += copyName
                                    repository.saveRevisionFromExistingAssets(
                                        sourceWorkflowId = target.summary.workflowId,
                                        sourceRevision = target.summary.latestRevision,
                                        document = source.document.copy(
                                            id = WorkflowIds.newUuid(),
                                            revision = 1,
                                            name = copyName,
                                        ),
                                        destinationFolderId = folder.folderId,
                                    )
                                }
                            }
                        }) { Text(folder.name) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { copySelection = false }) { Text("Cancel") } },
        )
    }

    if (renameSelection) {
        val target = workflows.firstOrNull { it.summary.workflowId == selectedIds.singleOrNull() }
        if (target != null) {
            FolderNameDialog(
                title = "Rename workflow",
                initialName = target.summary.name,
                fieldLabel = "Workflow name",
                onDismiss = { renameSelection = false },
                onConfirm = { name ->
                    renameSelection = false
                    mutate("Workflow renamed.") {
                        val source = repository.loadRevisionDocument(target.summary.workflowId, target.summary.latestRevision)
                        repository.saveRevisionFromExistingAssets(
                            sourceWorkflowId = target.summary.workflowId,
                            sourceRevision = target.summary.latestRevision,
                            document = source.document.copy(
                                revision = target.summary.latestRevision + 1,
                                name = name.trim(),
                            ),
                        )
                    }
                },
            )
        } else {
            renameSelection = false
        }
    }

    if (detailsSelection) {
        val selected = workflows.filter { it.summary.workflowId in selectedIds }
        ManagerSelectionDetailsDialog(
            title = if (selected.size == 1) selected.single().summary.name else "${selected.size} workflows",
            lines = if (selected.size == 1) {
                val workflow = selected.single()
                listOf(
                    "Revision ${workflow.summary.latestRevision}",
                    workflow.orientation?.name?.lowercase()?.replaceFirstChar(Char::uppercase) ?: "Orientation unavailable",
                    "${workflow.activity.runCount} saved runs",
                    "Updated ${DateFormat.getDateTimeInstance().format(Date(workflow.summary.updatedAtMs))}",
                )
            } else {
                listOf("${selected.count { it.summary.isFavorite }} favorites", "${selected.sumOf { it.activity.runCount }} saved runs")
            },
            onDismiss = { detailsSelection = false },
        )
    }
}

internal fun uniqueWorkflowCopyName(sourceName: String, usedNames: Set<String>): String {
    val base = "$sourceName copy"
    if (base !in usedNames) return base
    var suffix = 2
    while ("$base $suffix" in usedNames) suffix += 1
    return "$base $suffix"
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun WorkflowManagerCard(
    workflow: ManagedWorkflow,
    selectionMode: Boolean,
    selected: Boolean,
    enabled: Boolean,
    onSelectedChange: (Boolean) -> Unit,
    onLongPress: () -> Unit,
    onFavorite: () -> Unit,
    onEdit: () -> Unit,
    onPlay: () -> Unit,
    onRevisions: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier =
        Modifier
            .fillMaxWidth()
            .combinedClickable(
                enabled = enabled,
                onClick = { if (selectionMode) onSelectedChange(!selected) else onEdit() },
                onLongClick = onLongPress,
            ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectionMode) {
                Checkbox(checked = selected, onCheckedChange = onSelectedChange, enabled = enabled)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(workflow.summary.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "Revision ${workflow.summary.latestRevision} · ${workflow.orientation?.displayName() ?: "Unknown"} · ${workflow.activity.runCount} runs",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onFavorite, enabled = enabled) {
                Icon(
                    if (workflow.summary.isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = if (workflow.summary.isFavorite) "Remove from favorites" else "Add to favorites",
                    tint = if (workflow.summary.isFavorite) Color(0xFFE53935) else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onPlay, enabled = enabled) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "Play ${workflow.summary.name}")
            }
            Column {
                IconButton(onClick = { menuOpen = true }, enabled = enabled) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More workflow actions")
                }
                if (menuOpen) {
                    VeyarcChoicePicker(
                        title = workflow.summary.name,
                        options = listOf(VeyarcChoice("history", "Revision history"), VeyarcChoice("delete", "Delete", destructive = true)),
                        onDismiss = { menuOpen = false },
                        onSelect = { choice ->
                            menuOpen = false
                            if (choice.id == "history") onRevisions() else onDelete()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun FolderNameDialog(
    title: String,
    initialName: String,
    fieldLabel: String = "Folder name",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(fieldLabel) },
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
