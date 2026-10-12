package com.veyarc.app

import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.veyarc.data.storage.FolderSummary
import com.veyarc.data.storage.StoredRunRecord
import com.veyarc.data.storage.StoredRunStatus
import com.veyarc.data.storage.WorkflowRepository
import com.veyarc.data.storage.WorkflowSummary
import com.veyarc.feature.assistant.AssistantPanel
import com.veyarc.feature.editor.StoredRunDetailScreen
import com.veyarc.feature.editor.VeyarcChoice
import com.veyarc.feature.editor.VeyarcChoicePicker
import kotlinx.coroutines.CancellationException
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
@OptIn(ExperimentalMaterial3Api::class)
internal fun RunManagerScreen(
    repository: WorkflowRepository,
    refreshGeneration: Int,
    onOpenWorkflowRevision: (StoredRunRecord) -> Unit,
    onSelectionBarChange: (ManagerSelectionBarState?) -> Unit = {},
    pageState: RunManagerState = remember { RunManagerState() },
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var runs by pageState.runs
    var workflows by pageState.workflows
    var folders by pageState.folders
    var folderOrder by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var selectedView by rememberSaveable { mutableStateOf(RUNS_ALL_VIEW) }
    var selectedRunId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedRunDetails by remember { mutableStateOf<StoredRunRecord?>(null) }
    var selectionMode by rememberSaveable { mutableStateOf(false) }
    var selectedIds by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var query by rememberSaveable { mutableStateOf("") }
    var searchActive by rememberSaveable { mutableStateOf(false) }
    var statusFilter by rememberSaveable { mutableStateOf<StoredRunStatus?>(null) }
    var workflowFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var dateFilter by rememberSaveable { mutableStateOf(RunDateFilter.ANY) }
    var revisionFilter by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(RunSortOption.NEWEST) }
    var localRefresh by remember { mutableIntStateOf(0) }
    var isBusy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var creatingFolder by remember { mutableStateOf(false) }
    var folderManager by remember { mutableStateOf<FolderSummary?>(null) }
    var pendingFolderDelete by remember { mutableStateOf<FolderSummary?>(null) }
    var pendingRunDelete by remember { mutableStateOf<List<StoredRunRecord>>(emptyList()) }
    var moveSelection by remember { mutableStateOf(false) }
    var renameSelection by remember { mutableStateOf(false) }
    var detailsSelection by remember { mutableStateOf(false) }
    var sortMenuOpen by remember { mutableStateOf(false) }
    var statusMenuOpen by remember { mutableStateOf(false) }
    var workflowMenuOpen by remember { mutableStateOf(false) }
    var dateMenuOpen by remember { mutableStateOf(false) }
    var filtersOpen by remember { mutableStateOf(false) }

    val pageActive = LocalMainPageActive.current
    LaunchedEffect(repository, refreshGeneration, localRefresh, pageActive) {
        if (!pageActive) return@LaunchedEffect
        isBusy = true
        message = null
        runCatching {
            withContext(Dispatchers.IO) {
                val loadedWorkflows = repository.listWorkflowSummaries()
                val names = loadedWorkflows.associate { it.workflowId to it.name }
                val revisionKeys = repository.listRevisionKeys()
                Triple(
                    repository.listRunSummaries(limit = 1_000).map { run ->
                        ManagedRun(
                            record = run,
                            workflowName = names[run.workflowId] ?: "Deleted workflow",
                            revisionAvailable = (run.workflowId to run.revision) in revisionKeys,
                        )
                    },
                    loadedWorkflows,
                    repository.listRunFolders() to AppPreferences.runFolderOrder(context),
                )
            }
        }.onSuccess { (loadedRuns, loadedWorkflows, folderData) ->
            val (loadedFolders, savedOrder) = folderData
            runs = loadedRuns
            workflows = loadedWorkflows
            folders = loadedFolders
            pageState.loaded.value = true
            val availableKeys = listOf(RUNS_ALL_VIEW, RUNS_BY_WORKFLOW_VIEW, RUNS_UNFILED_VIEW) + loadedFolders.map { it.viewKey() }
            folderOrder = reconcileFolderOrder(savedOrder, availableKeys)
            selectedIds = terminalRunIds(selectedIds.toSet(), loadedRuns).toList()
            if (selectedRunId != null && loadedRuns.none { it.record.runId == selectedRunId }) selectedRunId = null
            if (selectedView.isFolderView() && loadedFolders.none { it.folderId == selectedView.folderId() }) {
                selectedView = RUNS_ALL_VIEW
            }
            if (workflowFilter != null && loadedWorkflows.none { it.workflowId == workflowFilter }) workflowFilter = null
        }.onFailure {
            if (it is CancellationException) throw it
            message = "Runs could not be loaded."
        }
        isBusy = false
    }

    LaunchedEffect(repository, selectedRunId) {
        selectedRunDetails = null
        selectedRunDetails = selectedRunId?.let { runId ->
            runCatching { withContext(Dispatchers.IO) { repository.loadRunDetails(runId) } }
                .onFailure { failure ->
                    if (failure is kotlinx.coroutines.CancellationException) throw failure
                    message = "Run details could not be loaded."
                    selectedRunId = null
                }
                .getOrNull()
        }
    }

    fun mutate(success: String, operation: suspend () -> Unit) {
        scope.launch {
            isBusy = true
            message = null
            runCatching { withContext(Dispatchers.IO) { operation() } }
                .onSuccess {
                    message = success
                    selectedIds = emptyList()
                    localRefresh += 1
                }
                .onFailure { message = it.message ?: "The change could not be saved." }
            isBusy = false
        }
    }

    LaunchedEffect(selectionMode, selectedIds, isBusy) {
        onSelectionBarChange(
            if (!selectionMode) {
                null
            } else {
                ManagerSelectionBarState(
                    selectedCount = selectedIds.size,
                    onCopy = null,
                    onRename = { renameSelection = true }.takeIf { !isBusy && selectedIds.size == 1 },
                    onMove = { moveSelection = true },
                    onDelete = { pendingRunDelete = runs.filter { it.record.runId in selectedIds }.map { it.record } },
                    onDetails = { detailsSelection = true }.takeIf { selectedIds.isNotEmpty() },
                )
            },
        )
    }
    DisposableEffect(Unit) {
        onDispose { onSelectionBarChange(null) }
    }

    selectedRunDetails?.let { run ->
        val managedRun = runs.firstOrNull { it.record.runId == run.runId } ?: return@let
        StoredRunDetailScreen(
            record = run,
            workflowName = managedRun.workflowName,
            onBack = {
                selectedRunId = null
                selectedRunDetails = null
            },
            onOpenWorkflowRevision = managedRun.takeIf(ManagedRun::revisionAvailable)?.let {
                { onOpenWorkflowRevision(run) }
            },
        )
        return
    }
    if (selectedRunId != null) {
        androidx.activity.compose.BackHandler { selectedRunId = null }
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                androidx.compose.material3.CircularProgressIndicator()
                Text("Loading run…")
            }
        }
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
    val folderItems = remember(folders, folderOrder) {
        val custom = folders.associateBy { it.viewKey() }
        reconcileFolderOrder(
            folderOrder,
            listOf(RUNS_ALL_VIEW, RUNS_BY_WORKFLOW_VIEW, RUNS_UNFILED_VIEW) + custom.keys,
        ).mapNotNull { key ->
            when (key) {
                RUNS_ALL_VIEW -> ManagerFolderItem(key, "All runs")
                RUNS_BY_WORKFLOW_VIEW -> ManagerFolderItem(key, "By workflow")
                RUNS_UNFILED_VIEW -> ManagerFolderItem(key, "Unfiled")
                else -> custom[key]?.let { ManagerFolderItem(key, it.name, it.colorArgb, it) }
            }
        }
    }

    fun persistFolderOrder(order: List<String>) {
        folderOrder = order
        val customByKey = folders.associateBy { it.viewKey() }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    AppPreferences.setRunFolderOrder(context, order)
                    repository.reorderRunFolders(order.mapNotNull { customByKey[it]?.folderId })
                }
            }.onFailure { message = it.message ?: "Folder order could not be saved." }
        }
    }

    val pinHeader = LocalConfiguration.current.orientation != Configuration.ORIENTATION_LANDSCAPE
    val header: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().testTag("run-manager-header"), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            CompactManagerHeader(
                title = "Runs Manager",
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

            ReorderableFolderRow(
                folders = folderItems,
                selectedKey = selectedView,
                onSelect = { selectedView = it },
                onManage = { folderManager = it },
                onOrderChanged = ::persistFolderOrder,
                onCreate = { creatingFolder = true },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FilterMenuButton(
                    modifier = Modifier.weight(1f),
                    text = "Sort: ${sort.label}",
                    title = "Sort runs",
                    options = RunSortOption.entries.map { VeyarcChoice(it.name, it.label) },
                    selectedId = sort.name,
                    expanded = sortMenuOpen,
                    onOpen = { sortMenuOpen = true },
                    onDismiss = { sortMenuOpen = false },
                    onSelect = { choice ->
                        sort = RunSortOption.entries.first { it.name == choice.id }
                        sortMenuOpen = false
                    },
                )
                OutlinedButton(
                    onClick = { filtersOpen = true },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (hasActiveRunFilters(statusFilter, workflowFilter, dateFilter, revisionFilter)) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
                    ),
                ) {
                    Icon(Icons.Filled.FilterList, contentDescription = null)
                    Text("Filter")
                }
                AnimatedVisibility(
                    visible = searchActive,
                    enter = fadeIn(tween(180)) + expandHorizontally(tween(180)),
                    exit = fadeOut(tween(180)) + shrinkHorizontally(tween(180)),
                ) {
                    androidx.compose.material3.IconButton(onClick = {
                        selectionMode = !selectionMode
                        if (!selectionMode) selectedIds = emptyList()
                    }) {
                        androidx.compose.animation.AnimatedContent(selectionMode, label = "run selection toggle") { selecting ->
                            Icon(
                                if (selecting) Icons.Filled.Close else Icons.Outlined.Checklist,
                                contentDescription = if (selecting) "Leave selection mode" else "Select items",
                            )
                        }
                    }
                }
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        if (pinHeader) Box(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp)) { header() }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().testTag("run-manager-list"),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = if (pinHeader) 14.dp else 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (!pinHeader) item(key = "manager-header") { header() }
            item(key = "load-progress") {
                Box(Modifier.fillMaxWidth().height(4.dp)) {
                    if (isBusy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            message?.let { item { Text(it, color = MaterialTheme.colorScheme.primary) } }
            if (pageState.loaded.value && !isBusy && visible.isEmpty()) item { Card { Text("No runs match these filters.", modifier = Modifier.padding(18.dp)) } }

            if (selectedView == RUNS_BY_WORKFLOW_VIEW) {
                visible.groupBy { it.record.workflowId }.entries.forEachIndexed { index, (workflowId, workflowRuns) ->
                    item(key = "workflow-heading-$workflowId") {
                        Text(workflowRuns.first().workflowName, Modifier.padding(top = if (index == 0) 0.dp else 16.dp), style = MaterialTheme.typography.titleMedium)
                    }
                    items(workflowRuns, key = { it.record.runId }) { managed ->
                        RunCard(
                            run = managed,
                            selectionMode = selectionMode,
                            selected = managed.record.runId in selectedIds,
                            isBusy = isBusy,
                            onOpen = { selectedRunId = managed.record.runId },
                            onDelete = { pendingRunDelete = listOf(managed.record) },
                            onSelectedChange = { checked ->
                                selectedIds = if (checked) (selectedIds + managed.record.runId).distinct() else selectedIds - managed.record.runId
                            },
                        )
                    }
                }
            } else {
                items(visible, key = { it.record.runId }) { managed ->
                    RunCard(
                        run = managed,
                        selectionMode = selectionMode,
                        selected = managed.record.runId in selectedIds,
                        isBusy = isBusy,
                        onOpen = { selectedRunId = managed.record.runId },
                        onDelete = { pendingRunDelete = listOf(managed.record) },
                        onSelectedChange = { checked ->
                            selectedIds = if (checked) (selectedIds + managed.record.runId).distinct() else selectedIds - managed.record.runId
                        },
                    )
                }
            }
        }
    }

    if (filtersOpen) {
        AssistantPanel("Filter runs", onDismiss = { filtersOpen = false }, headerActions = {
            TextButton(onClick = {
                statusFilter = null
                workflowFilter = null
                dateFilter = RunDateFilter.ANY
                revisionFilter = ""
            }) { Text("Reset") }
        }) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FilterMenuButton(
                    text = "Status: ${statusFilter?.label() ?: "Any"}",
                    title = "Run status",
                    options = listOf(VeyarcChoice("any", "Any status")) + StoredRunStatus.entries.map { VeyarcChoice(it.name, it.label()) },
                    selectedId = statusFilter?.name ?: "any",
                    expanded = statusMenuOpen,
                    onOpen = { statusMenuOpen = true },
                    onDismiss = { statusMenuOpen = false },
                    onSelect = { choice ->
                        statusFilter = StoredRunStatus.entries.firstOrNull { it.name == choice.id }
                        statusMenuOpen = false
                    },
                )
                FilterMenuButton(
                    text = "Workflow: ${workflows.firstOrNull { it.workflowId == workflowFilter }?.name ?: "Any"}",
                    title = "Workflow",
                    options = listOf(VeyarcChoice("any", "Any workflow")) + workflows.map { VeyarcChoice(it.workflowId, it.name) },
                    selectedId = workflowFilter ?: "any",
                    expanded = workflowMenuOpen,
                    onOpen = { workflowMenuOpen = true },
                    onDismiss = { workflowMenuOpen = false },
                    onSelect = { choice ->
                        workflowFilter = choice.id.takeUnless { it == "any" }
                        workflowMenuOpen = false
                    },
                )
                FilterMenuButton(
                    text = "Date: ${dateFilter.label}",
                    title = "Run date",
                    options = RunDateFilter.entries.map { VeyarcChoice(it.name, it.label) },
                    selectedId = dateFilter.name,
                    expanded = dateMenuOpen,
                    onOpen = { dateMenuOpen = true },
                    onDismiss = { dateMenuOpen = false },
                    onSelect = { choice ->
                        dateFilter = RunDateFilter.entries.first { it.name == choice.id }
                        dateMenuOpen = false
                    },
                )
                OutlinedTextField(
                    value = revisionFilter,
                    onValueChange = { revisionFilter = it.filter(Char::isDigit) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Revision") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        }
    }

    if (creatingFolder) {
        RunFolderNameDialog(
            title = "New run folder",
            initialName = "",
            onDismiss = {
                creatingFolder = false
            },
            onConfirm = { name ->
                creatingFolder = false
                mutate("Folder created.") {
                    repository.createRunFolder(name)
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
                    if (name.trim() != folder.name) repository.renameRunFolder(folder.folderId, name)
                    if (colorArgb != folder.colorArgb) repository.setRunFolderColor(folder.folderId, colorArgb)
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

    if (renameSelection) {
        val target = runs.firstOrNull { it.record.runId == selectedIds.singleOrNull() }
        if (target != null) {
            RunFolderNameDialog(
                title = "Rename run",
                initialName = target.record.name ?: target.workflowName,
                fieldLabel = "Run name",
                onDismiss = { renameSelection = false },
                onConfirm = { name ->
                    renameSelection = false
                    mutate("Run renamed.") { repository.renameRun(target.record.runId, name) }
                },
            )
        } else {
            renameSelection = false
        }
    }

    if (detailsSelection) {
        val selected = runs.filter { it.record.runId in selectedIds }
        ManagerSelectionDetailsDialog(
            title = if (selected.size == 1) {
                selected.single().record.name ?: selected.single().workflowName
            } else {
                "${selected.size} runs"
            },
            lines = if (selected.size == 1) {
                val run = selected.single()
                listOf(
                    run.record.status.label(),
                    "Workflow: ${run.workflowName}",
                    "Revision ${run.record.revision}",
                    "Duration ${run.record.durationText()}",
                    formatRunTimestamp(run.record.startedAtMs),
                )
            } else {
                StoredRunStatus.entries.mapNotNull { status ->
                    selected.count { it.record.status == status }
                        .takeIf { it > 0 }
                        ?.let { "$it ${status.label().lowercase()}" }
                }
            },
            onDismiss = { detailsSelection = false },
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
    title: String,
    options: List<VeyarcChoice>,
    selectedId: String?,
    expanded: Boolean,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    onSelect: (VeyarcChoice) -> Unit,
) {
    Column(modifier = modifier) {
        OutlinedButton(
            onClick = onOpen,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
        ) {
            Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (expanded) VeyarcChoicePicker(title, options, selectedId, onDismiss, onSelect)
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun RunCard(
    run: ManagedRun,
    selectionMode: Boolean,
    selected: Boolean,
    isBusy: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onSelectedChange: (Boolean) -> Unit,
) {
    val active = run.record.status == StoredRunStatus.RUNNING
    var menuOpen by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().testTag("run-card-${run.record.runId}")) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            modifier = Modifier.fillMaxWidth().combinedClickable(
                enabled = !isBusy,
                onClick = { if (selectionMode && !active) onSelectedChange(!selected) else onOpen() },
                onLongClick = { if (!selectionMode) menuOpen = true },
            ),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ManagerSelectionCheckbox(selectionMode, selected, !active && !isBusy, onSelectedChange)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(run.record.name ?: run.workflowName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${run.record.status.label()} · revision ${run.record.revision} · ${run.record.durationText()}",
                        color = if (run.record.status == StoredRunStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        formatRunTimestamp(run.record.startedAtMs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Box(Modifier.align(Alignment.TopEnd)) {
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, shape = RoundedCornerShape(16.dp), containerColor = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 0.dp) {
                DropdownMenuItem(text = { Text("Delete") }, leadingIcon = { Icon(Icons.Filled.Delete, null) }, colors = MenuDefaults.itemColors(textColor = MaterialTheme.colorScheme.error, leadingIconColor = MaterialTheme.colorScheme.error), enabled = !active && !isBusy, onClick = {
                    menuOpen = false
                    onDelete()
                })
            }
        }
    }
}

@Composable
private fun RunFolderNameDialog(
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
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(fieldLabel) }) },
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
        needle.isEmpty() ||
            managed.record.name?.contains(needle, ignoreCase = true) == true ||
            managed.workflowName.contains(needle, ignoreCase = true) ||
            managed.record.runId.contains(needle, ignoreCase = true)
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

internal fun hasActiveRunFilters(
    status: StoredRunStatus?,
    workflowId: String?,
    date: RunDateFilter,
    revision: String,
): Boolean = status != null || workflowId != null || date != RunDateFilter.ANY || revision.isNotBlank()

private fun StoredRunRecord.durationMs(): Long? = finishedAtMs?.minus(startedAtMs)?.coerceAtLeast(0L)

private fun StoredRunRecord.durationText(): String = durationMs()?.let { duration ->
    val seconds = duration / 1_000L
    "%02d:%02d".format(seconds / 60L, seconds % 60L)
} ?: "In progress"

private fun StoredRunStatus.label(): String = name.lowercase().replaceFirstChar(Char::uppercase)
private fun FolderSummary.viewKey(): String = RUNS_FOLDER_PREFIX + folderId
private fun String.isFolderView(): Boolean = startsWith(RUNS_FOLDER_PREFIX)
private fun String.folderId(): String? = takeIf(String::isFolderView)?.removePrefix(RUNS_FOLDER_PREFIX)
private fun formatRunTimestamp(timestampMs: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timestampMs))

internal const val RUNS_ALL_VIEW = "system:all"
internal const val RUNS_BY_WORKFLOW_VIEW = "system:workflow"
internal const val RUNS_UNFILED_VIEW = "system:unfiled"
private const val RUNS_STATUS_PREFIX = "status:"
private const val RUNS_FOLDER_PREFIX = "folder:"
