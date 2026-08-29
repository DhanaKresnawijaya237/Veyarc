@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.veyarc.feature.editor

import android.content.Context
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.veyarc.automation.android.ReplayFloatingController
import com.veyarc.data.storage.StoredRunRecord
import com.veyarc.data.storage.StoredRunStatus
import com.veyarc.data.storage.WorkflowRepository
import com.veyarc.data.storage.WorkflowStorageException
import com.veyarc.data.storage.WorkflowSummary
import com.veyarc.model.Condition
import com.veyarc.model.DisplayOrientation
import com.veyarc.model.IfElse
import com.veyarc.model.LaunchApp
import com.veyarc.model.LongPress
import com.veyarc.model.Repeat
import com.veyarc.model.Stop
import com.veyarc.model.SwipePath
import com.veyarc.model.TapPoint
import com.veyarc.model.TapRegion
import com.veyarc.model.Wait
import com.veyarc.model.WaitUntil
import com.veyarc.model.WhileStep
import com.veyarc.model.WorkflowStep
import com.veyarc.model.bundle.WorkflowBundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun WorkflowEditorScreen(
    repository: WorkflowRepository,
    onOpenAutomationHarness: () -> Unit,
    onRunWorkflow: (WorkflowBundle) -> Unit,
    initialBundle: WorkflowBundle? = null,
    initialRecordingJournalFileName: String? = null,
    onInitialBundleConsumed: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var summaries by remember { mutableStateOf<List<WorkflowSummary>>(emptyList()) }
    var runHistory by remember { mutableStateOf<List<StoredRunRecord>>(emptyList()) }
    var editingDraft by remember { mutableStateOf<WorkflowDraft?>(null) }
    var isBusy by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var refreshGeneration by remember { mutableIntStateOf(0) }
    var geometryTarget by remember { mutableStateOf<GeometryTarget?>(null) }
    var conditionTarget by remember { mutableStateOf<ConditionTarget?>(null) }

    val templatePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val draftAtImport = editingDraft ?: return@rememberLauncherForActivityResult
        scope.launch {
            isBusy = true
            statusMessage = null
            runCatching {
                withContext(Dispatchers.Default) {
                    importTemplateAsset(context.contentResolver, uri)
                }
            }.onSuccess { asset ->
                editingDraft = draftAtImport.addAsset(asset)
                statusMessage = "Encrypted image template added. Select it from an image condition."
            }.onFailure { error ->
                statusMessage = "Could not add image template: ${error.userMessage()}"
            }
            isBusy = false
        }
    }

    LaunchedEffect(initialBundle, initialRecordingJournalFileName) {
        val bundle = initialBundle ?: return@LaunchedEffect
        editingDraft = WorkflowDraft.fromBundle(bundle, initialRecordingJournalFileName)
        statusMessage = "Recording workflow opened. Saving edits creates the next revision."
        onInitialBundleConsumed()
    }

    LaunchedEffect(refreshGeneration) {
        isBusy = true
        statusMessage = null
        summaries = runCatching {
            withContext(Dispatchers.IO) { repository.listWorkflows() }
        }.onFailure { error ->
            statusMessage = "Could not load workflows: ${error.userMessage()}"
        }.getOrElse { emptyList() }
        runHistory = runCatching {
            withContext(Dispatchers.IO) { repository.listRunHistory(limit = 20) }
        }.getOrElse { emptyList() }
        isBusy = false
    }

    fun openWorkflow(summary: WorkflowSummary) {
        scope.launch {
            isBusy = true
            statusMessage = null
            runCatching {
                withContext(Dispatchers.IO) {
                    repository.loadRevision(summary.workflowId, summary.latestRevision)
                }
            }.onSuccess { bundle ->
                editingDraft = WorkflowDraft.fromBundle(bundle)
            }.onFailure { error ->
                statusMessage = "Could not open workflow: ${error.userMessage()}"
            }
            isBusy = false
        }
    }

    fun saveWorkflow(bundle: WorkflowBundle, afterSave: (() -> Unit)? = null) {
        val sourceJournalFileName = editingDraft?.recordingJournalFileName
        scope.launch {
            isBusy = true
            statusMessage = null
            runCatching {
                withContext(Dispatchers.IO) { repository.saveRevision(bundle) }
            }.onSuccess {
                editingDraft = WorkflowDraft.fromBundle(bundle, sourceJournalFileName)
                refreshGeneration += 1
                statusMessage = "Saved revision ${bundle.document.revision}."
                afterSave?.invoke()
            }.onFailure { error ->
                statusMessage = "Could not save revision: ${error.userMessage()}"
            }
            isBusy = false
        }
    }

    fun saveAndRun(bundle: WorkflowBundle) {
        saveWorkflow(bundle) { onRunWorkflow(bundle) }
    }

    fun deleteWorkflow(summary: WorkflowSummary) {
        scope.launch {
            isBusy = true
            statusMessage = null
            runCatching {
                withContext(Dispatchers.IO) { repository.deleteWorkflow(summary.workflowId) }
            }.onSuccess {
                refreshGeneration += 1
                statusMessage = "Deleted workflow \"${summary.name}\"."
            }.onFailure { error ->
                statusMessage = "Could not delete workflow: ${error.userMessage()}"
            }
            isBusy = false
        }
    }

    fun deleteRun(record: StoredRunRecord) {
        scope.launch {
            isBusy = true
            statusMessage = null
            runCatching {
                withContext(Dispatchers.IO) { repository.deleteRun(record.runId) }
            }.onSuccess {
                refreshGeneration += 1
                statusMessage = "Deleted run ${record.runId}."
            }.onFailure { error ->
                statusMessage = "Could not delete run: ${error.userMessage()}"
            }
            isBusy = false
        }
    }

    fun closeDraft() {
        geometryTarget = null
        editingDraft = null
        refreshGeneration += 1
    }

    val draft = editingDraft
    if (draft == null) {
        WorkflowLibraryScreen(
            summaries = summaries,
            runHistory = runHistory,
            isBusy = isBusy,
            statusMessage = statusMessage,
            onCreate = {
                editingDraft = WorkflowDraft.newDefault(viewportFor(context))
                statusMessage = null
            },
            onOpen = ::openWorkflow,
            onOpenAutomationHarness = onOpenAutomationHarness,
            onDeleteWorkflow = ::deleteWorkflow,
            onDeleteRun = ::deleteRun,
            modifier = modifier,
        )
    } else {
        BackHandler { closeDraft() }
        val validation = remember(draft) { draft.buildBundle() }
        WorkflowDraftScreen(
            draft = draft,
            validation = validation,
            isBusy = isBusy,
            statusMessage = statusMessage,
            onDraftChange = { editingDraft = it },
            onBack = ::closeDraft,
            onSave = { bundle -> saveWorkflow(bundle) },
            onRun = ::saveAndRun,
            onEditGeometry = { path, index -> geometryTarget = GeometryTarget(path, index) },
            onEditCondition = { path, index -> conditionTarget = ConditionTarget(path, index) },
            onAddTemplate = { templatePicker.launch("image/*") },
            modifier = modifier,
        )
        geometryTarget?.let { target ->
            val targetStep = draft.stepsAt(target.path).getOrNull(target.index)
            if (targetStep != null) {
                GeometryEditorDialog(
                    step = targetStep,
                    viewport = draft.viewport,
                    maxPathPoints = draft.runLimits.maxPathPoints,
                    onDismiss = { geometryTarget = null },
                    onApply = { updatedStep ->
                        editingDraft = draft.updateStep(target.path, target.index) { updatedStep }
                        geometryTarget = null
                    },
                )
            }
        }
        conditionTarget?.let { target ->
            val targetStep = draft.stepsAt(target.path).getOrNull(target.index)
            val condition = targetStep?.conditionOrNull()
            if (condition != null) {
                ConditionEditorDialog(
                    initialCondition = condition,
                    assets = draft.assets,
                    defaultPackage = draft.allowedPackagesText.firstPackageOrDefault(),
                    onAddTemplate = { templatePicker.launch("image/*") },
                    onDismiss = { conditionTarget = null },
                    onApply = { updatedCondition ->
                        editingDraft = draft.updateCondition(target.path, target.index) { updatedCondition }
                        conditionTarget = null
                    },
                )
            }
        }
    }
}

private data class GeometryTarget(
    val path: StepListPath,
    val index: Int,
)

private data class ConditionTarget(
    val path: StepListPath,
    val index: Int,
)

private sealed interface DeleteTarget {
    data class Workflow(val summary: WorkflowSummary) : DeleteTarget
    data class Run(val record: StoredRunRecord) : DeleteTarget
}

@Composable
private fun WorkflowLibraryScreen(
    summaries: List<WorkflowSummary>,
    runHistory: List<StoredRunRecord>,
    isBusy: Boolean,
    statusMessage: String?,
    onCreate: () -> Unit,
    onOpen: (WorkflowSummary) -> Unit,
    onOpenAutomationHarness: () -> Unit,
    onDeleteWorkflow: (WorkflowSummary) -> Unit,
    onDeleteRun: (StoredRunRecord) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var replayControllerEnabled by remember {
        mutableStateOf(ReplayFloatingController.isEnabled(context))
    }
    var pendingDelete by remember { mutableStateOf<DeleteTarget?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Workflow library") },
                actions = {
                    TextButton(onClick = onOpenAutomationHarness) { Text("Accessibility test") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Create, review, and save versioned automation workflows locally.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        "Coordinates are normalized to the recorded display. Point, region, and path geometry editing is the next editor increment.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text("Veyarc edge-latch control center", style = MaterialTheme.typography.titleMedium)
                                Switch(
                                    checked = replayControllerEnabled,
                                    onCheckedChange = { enabled ->
                                        replayControllerEnabled = enabled
                                        ReplayFloatingController.setEnabled(context, enabled)
                                    },
                                )
                            }
                            Text(
                                "When enabled, a small latch stays attached to the left or right screen edge during recording and replay. Drag it continuously along an edge; release near the other side to switch between left and right, and it remembers the position along that edge without moving through app content or gesture regions. Press it to open the adjacent control center. Opening it during active replay stops the replay first. The panel provides recording controls, one square button for the latest saved workflow, and a separate library button; the persistent notification remains an independent emergency fallback. Strict screen freeze is unavailable, so replay remains touch pass-through.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedButton(onClick = { ReplayFloatingController.resetPosition(context) }) {
                                Text("Reset latch position")
                            }
                        }
                    }
                    Button(onClick = onCreate, enabled = !isBusy) { Text("New workflow") }
                }
            }
            if (isBusy) item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
            statusMessage?.let { message ->
                item { Text(message, color = MaterialTheme.colorScheme.primary) }
            }
            if (!isBusy && summaries.isEmpty()) {
                item {
                    Text(
                        "No saved workflows yet. Create and save one before using Replay.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(summaries, key = { it.workflowId }) { summary ->
                WorkflowSummaryCard(
                    summary = summary,
                    onClick = { onOpen(summary) },
                    onDelete = { pendingDelete = DeleteTarget.Workflow(summary) },
                    deleteEnabled = !isBusy,
                )
            }
            if (runHistory.isNotEmpty()) {
                item {
                    Text("Recent runs", style = MaterialTheme.typography.titleLarge)
                }
                items(runHistory, key = { it.runId }) { record ->
                    RunHistoryCard(
                        record = record,
                        onDelete = { pendingDelete = DeleteTarget.Run(record) },
                        deleteEnabled = !isBusy && record.status != StoredRunStatus.RUNNING,
                    )
                }
            }
        }
    }

    pendingDelete?.let { target ->
        val isWorkflow = target is DeleteTarget.Workflow
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(if (isWorkflow) "Delete workflow?" else "Delete run?") },
            text = {
                Text(
                    if (isWorkflow) {
                        "This removes the workflow, every saved revision, and its run history from this phone."
                    } else {
                        "This permanently removes the selected run record from this phone."
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        when (target) {
                            is DeleteTarget.Workflow -> onDeleteWorkflow(target.summary)
                            is DeleteTarget.Run -> onDeleteRun(target.record)
                        }
                        pendingDelete = null
                    },
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun RunHistoryCard(
    record: StoredRunRecord,
    onDelete: () -> Unit,
    deleteEnabled: Boolean,
) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    "${record.status.name.lowercase()} · revision ${record.revision}",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "Started ${record.startedAtMs} · finished ${record.finishedAtMs ?: "—"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                record.interruptionReason?.let { reason ->
                    Text(reason, style = MaterialTheme.typography.bodySmall)
                }
            }
            IconButton(onClick = onDelete, enabled = deleteEnabled) {
                Icon(Icons.Outlined.Delete, contentDescription = "Delete run")
            }
        }
    }
}

@Composable
private fun WorkflowSummaryCard(
    summary: WorkflowSummary,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    deleteEnabled: Boolean,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(summary.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "Revision ${summary.latestRevision} · Updated ${summary.updatedAtMs}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    summary.workflowId,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete, enabled = deleteEnabled) {
                Icon(Icons.Outlined.Delete, contentDescription = "Delete workflow")
            }
        }
    }
}

@Composable
private fun WorkflowDraftScreen(
    draft: WorkflowDraft,
    validation: DraftBuildResult,
    isBusy: Boolean,
    statusMessage: String?,
    onDraftChange: (WorkflowDraft) -> Unit,
    onBack: () -> Unit,
    onSave: (WorkflowBundle) -> Unit,
    onRun: (WorkflowBundle) -> Unit,
    onEditGeometry: (StepListPath, Int) -> Unit,
    onEditCondition: (StepListPath, Int) -> Unit,
    onAddTemplate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(draft.name.ifBlank { "Workflow editor" }) },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                actions = {
                    TextButton(
                        enabled = !isBusy && validation.bundle != null,
                        onClick = { validation.bundle?.let(onSave) },
                    ) {
                        Text("Save v${draft.nextRevision}")
                    }
                    TextButton(
                        enabled = !isBusy && validation.bundle != null,
                        onClick = { validation.bundle?.let(onRun) },
                    ) {
                        Text("Review & run")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = draft.name,
                onValueChange = { onDraftChange(draft.copy(name = it)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Workflow name") },
                singleLine = true,
            )
            OutlinedTextField(
                value = draft.allowedPackagesText,
                onValueChange = { onDraftChange(draft.copy(allowedPackagesText = it)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Allowed Android packages") },
                supportingText = { Text("Separate packages with commas or spaces. If opening another app, make Launch app the first block.") },
                singleLine = true,
            )
            Text(
                "${draft.lastSavedRevision?.let { "Saved revision $it" } ?: "New workflow"} · next save is revision ${draft.nextRevision}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            draft.recordingJournalFileName?.let { journalFileName ->
                Text(
                    "Imported from encrypted recording: $journalFileName",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ValidationCard(validation = validation)
            TemplateAssetSection(
                assets = draft.assets,
                onAddTemplate = onAddTemplate,
                onRemoveTemplate = { assetId -> onDraftChange(draft.removeAsset(assetId)) },
            )
            HorizontalDivider()
            Text("Blocks", style = MaterialTheme.typography.titleLarge)
            StepListEditor(
                draft = draft,
                path = StepListPath(),
                heading = "Main sequence",
                onDraftChange = onDraftChange,
                onEditGeometry = onEditGeometry,
                onEditCondition = onEditCondition,
            )
            statusMessage?.let { message ->
                Text(message, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun TemplateAssetSection(
    assets: List<com.veyarc.model.bundle.BundleAsset>,
    onAddTemplate: () -> Unit,
    onRemoveTemplate: (com.veyarc.model.AssetId) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Image templates", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = onAddTemplate) { Text("Add image") }
        }
        Text(
            "Templates are encrypted with Android Keystore before they enter the workflow bundle. Remove unused templates to keep validation clean.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (assets.isEmpty()) {
            Text("No templates added yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            assets.forEach { asset ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(asset.assetId.value, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${asset.mimeType} · ${asset.bytes.size} encrypted bytes",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { onRemoveTemplate(asset.assetId) }) {
                        Icon(Icons.Outlined.Delete, contentDescription = "Remove image template")
                    }
                }
            }
        }
    }
}

@Composable
private fun ValidationCard(validation: DraftBuildResult) {
    val valid = validation.issues.isEmpty()
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (valid) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.errorContainer
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                if (valid) "Workflow is valid and ready to save." else "Fix these issues before saving:",
                style = MaterialTheme.typography.titleSmall,
            )
            validation.issues.forEach { issue ->
                Text("${issue.path}: ${issue.message}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun StepListEditor(
    draft: WorkflowDraft,
    path: StepListPath,
    heading: String,
    onDraftChange: (WorkflowDraft) -> Unit,
    onEditGeometry: (StepListPath, Int) -> Unit,
    onEditCondition: (StepListPath, Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(heading, style = MaterialTheme.typography.titleMedium)
        val steps = draft.stepsAt(path)
        if (steps.isEmpty()) {
            Text(
                "No blocks in this branch yet.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        steps.forEachIndexed { index, step ->
            StepCard(
                draft = draft,
                path = path,
                index = index,
                step = step,
                onDraftChange = onDraftChange,
                onEditGeometry = onEditGeometry,
                onEditCondition = onEditCondition,
            )
        }
        AddBlockMenu(
            defaultPackage = draft.allowedPackagesText.firstPackageOrDefault(),
            onAdd = { kind -> onDraftChange(draft.addStep(path, kind, draft.allowedPackagesText.firstPackageOrDefault())) },
        )
    }
}

@Composable
private fun StepCard(
    draft: WorkflowDraft,
    path: StepListPath,
    index: Int,
    step: WorkflowStep,
    onDraftChange: (WorkflowDraft) -> Unit,
    onEditGeometry: (StepListPath, Int) -> Unit,
    onEditCondition: (StepListPath, Int) -> Unit,
) {
    val expanded = step.id !in draft.collapsedStepIds
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    "${index + 1}. ${step.displayName()}",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                )
                IconButton(
                    enabled = index > 0,
                    onClick = { onDraftChange(draft.moveStep(path, index, -1)) },
                ) {
                    Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = "Move block up")
                }
                IconButton(
                    enabled = index < draft.stepsAt(path).lastIndex,
                    onClick = { onDraftChange(draft.moveStep(path, index, 1)) },
                ) {
                    Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = "Move block down")
                }
                IconButton(
                    onClick = { onDraftChange(draft.removeStep(path, index)) },
                ) {
                    Icon(Icons.Outlined.Delete, contentDescription = "Remove block")
                }
                TextButton(onClick = { onDraftChange(draft.toggleCollapsed(step.id)) }) {
                    Text(if (expanded) "Collapse" else "Expand")
                }
            }
            Text(step.summary(), style = MaterialTheme.typography.bodyMedium)
            if (expanded) {
                StepEditorFields(
                    draft = draft,
                    path = path,
                    index = index,
                    step = step,
                    onDraftChange = onDraftChange,
                    onEditGeometry = onEditGeometry,
                    onEditCondition = onEditCondition,
                )
            }
        }
    }
}

@Composable
private fun StepEditorFields(
    draft: WorkflowDraft,
    path: StepListPath,
    index: Int,
    step: WorkflowStep,
    onDraftChange: (WorkflowDraft) -> Unit,
    onEditGeometry: (StepListPath, Int) -> Unit,
    onEditCondition: (StepListPath, Int) -> Unit,
) {
    when (step) {
        is Wait -> {
            LongEditorField(
                label = "Duration (ms)",
                value = step.durationMs.value,
                onValueChange = { value ->
                    if (value >= 0) {
                        onDraftChange(
                            draft.updateStep(path, index) {
                                step.copy(durationMs = com.veyarc.model.DurationMillis(value))
                            },
                        )
                    }
                },
            )
        }

        is Repeat -> {
            LongEditorField(
                label = "Repeat count",
                value = step.count.toLong(),
                onValueChange = { value ->
                    if (value in 0..Int.MAX_VALUE) {
                        onDraftChange(draft.updateStep(path, index) { step.copy(count = value.toInt()) })
                    }
                },
            )
            StepListEditor(
                draft = draft,
                path = path.child(step.id, NestedBranch.REPEAT_BODY),
                heading = "Repeat body",
                onDraftChange = onDraftChange,
                onEditGeometry = onEditGeometry,
                onEditCondition = onEditCondition,
            )
        }

        is WhileStep -> {
            OutlinedButton(onClick = { onEditCondition(path, index) }) { Text("Edit condition") }
            LongEditorField(
                label = "Maximum iterations",
                value = step.maxIterations.toLong(),
                onValueChange = { value ->
                    if (value in 1..Int.MAX_VALUE) {
                        onDraftChange(draft.updateStep(path, index) { step.copy(maxIterations = value.toInt()) })
                    }
                },
            )
            StepListEditor(
                draft = draft,
                path = path.child(step.id, NestedBranch.WHILE_BODY),
                heading = "While body",
                onDraftChange = onDraftChange,
                onEditGeometry = onEditGeometry,
                onEditCondition = onEditCondition,
            )
        }

        is IfElse -> {
            OutlinedButton(onClick = { onEditCondition(path, index) }) { Text("Edit condition") }
            StepListEditor(
                draft = draft,
                path = path.child(step.id, NestedBranch.IF_THEN),
                heading = "Then branch",
                onDraftChange = onDraftChange,
                onEditGeometry = onEditGeometry,
                onEditCondition = onEditCondition,
            )
            StepListEditor(
                draft = draft,
                path = path.child(step.id, NestedBranch.IF_ELSE),
                heading = "Else branch",
                onDraftChange = onDraftChange,
                onEditGeometry = onEditGeometry,
                onEditCondition = onEditCondition,
            )
        }

        is TapPoint,
        is TapRegion,
        is LongPress,
        is SwipePath,
        -> {
            OutlinedButton(onClick = { onEditGeometry(path, index) }) {
                Text("Edit geometry")
            }
        }

        is WaitUntil,
        -> {
            OutlinedButton(onClick = { onEditCondition(path, index) }) { Text("Edit condition") }
            LongEditorField(
                label = "Timeout (ms)",
                value = step.timeoutMs.value,
                onValueChange = { value ->
                    if (value >= 0) onDraftChange(draft.updateStep(path, index) { step.copy(timeoutMs = com.veyarc.model.DurationMillis(value)) })
                },
            )
            LongEditorField(
                label = "Poll interval (ms)",
                value = step.pollIntervalMs.value,
                onValueChange = { value ->
                    if (value > 0) onDraftChange(draft.updateStep(path, index) { step.copy(pollIntervalMs = com.veyarc.model.DurationMillis(value)) })
                },
            )
        }

        is LaunchApp,
        is Stop,
        -> {
            Text(
                "This block has no additional editor fields yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        else -> {
            Text(
                "This block is available in the workflow contract but does not have a dedicated editor yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun LongEditorField(
    label: String,
    value: Long,
    onValueChange: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { candidate ->
            text = candidate
            candidate.toLongOrNull()?.let(onValueChange)
        },
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
    )
}

@Composable
private fun AddBlockMenu(
    defaultPackage: String,
    onAdd: (WorkflowStepKind) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text("Add block") }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            WorkflowStepKind.entries.forEach { kind ->
                DropdownMenuItem(
                    text = { Text(kind.displayName()) },
                    onClick = {
                        expanded = false
                        onAdd(kind)
                    },
                )
            }
        }
    }
}

private fun String.firstPackageOrDefault(): String = split(',', ' ', '\n', '\t')
    .map(String::trim)
    .firstOrNull { it.isNotEmpty() }
    ?: "com.veyarc.app"

private fun WorkflowStep.conditionOrNull(): Condition? = when (this) {
    is WaitUntil -> condition
    is IfElse -> condition
    is WhileStep -> condition
    else -> null
}

private fun viewportFor(context: Context): com.veyarc.model.ViewportSpec {
    val metrics = context.resources.displayMetrics
    val orientation = if (context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
        DisplayOrientation.LANDSCAPE
    } else {
        DisplayOrientation.PORTRAIT
    }
    return com.veyarc.model.ViewportSpec(
        recordedWidthPx = metrics.widthPixels.coerceAtLeast(1),
        recordedHeightPx = metrics.heightPixels.coerceAtLeast(1),
        recordedDensityDpi = metrics.densityDpi.coerceAtLeast(1),
        recordedOrientation = orientation,
    )
}

private fun Throwable.userMessage(): String = when (this) {
    is WorkflowStorageException -> message ?: "storage operation failed"
    else -> message ?: "unexpected error"
}
