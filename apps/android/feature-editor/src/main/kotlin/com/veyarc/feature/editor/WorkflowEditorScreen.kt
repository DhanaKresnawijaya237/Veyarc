@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.veyarc.feature.editor

import android.content.Context
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Loop
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.veyarc.data.storage.WorkflowRepository
import com.veyarc.data.storage.WorkflowStorageException
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
    session: WorkflowEditorSession,
    onRunWorkflow: (WorkflowBundle) -> Unit,
    onOpenWorkflowManager: () -> Unit,
    initialBundle: WorkflowBundle? = null,
    initialRecordingJournalFileName: String? = null,
    onInitialBundleConsumed: () -> Unit = {},
    newWorkflowRequestId: Int = 0,
    newWorkflowFolderId: String? = null,
    onNewWorkflowRequestConsumed: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isBusy by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var geometryTarget by remember { mutableStateOf<GeometryTarget?>(null) }
    var conditionTarget by remember { mutableStateOf<ConditionTarget?>(null) }
    var pendingTemplateCrop by remember { mutableStateOf<TemplateCropSource?>(null) }
    var pendingClose by remember { mutableStateOf(false) }
    var pendingReplacement by remember { mutableStateOf<DraftReplacement?>(null) }

    val templatePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            isBusy = true
            statusMessage = null
            runCatching {
                withContext(Dispatchers.Default) {
                    loadTemplateCropSource(context.contentResolver, uri)
                }
            }.onSuccess { source ->
                pendingTemplateCrop = source
                statusMessage = "Select the stable part of the image that Veyarc should match."
            }.onFailure { error ->
                statusMessage = "Could not open image: ${error.userMessage()}"
            }
            isBusy = false
        }
    }

    fun applyReplacement(request: DraftReplacement) {
        when (request) {
            is DraftReplacement.Existing -> {
                session.open(request.bundle, request.journalFileName)
                statusMessage = "Workflow opened. Saving edits creates the next revision."
                onInitialBundleConsumed()
            }

            is DraftReplacement.New -> {
                session.create(request.viewport, request.folderId)
                statusMessage = null
                onNewWorkflowRequestConsumed()
            }
        }
        pendingReplacement = null
    }

    fun cancelReplacement(request: DraftReplacement) {
        when (request) {
            is DraftReplacement.Existing -> onInitialBundleConsumed()
            is DraftReplacement.New -> onNewWorkflowRequestConsumed()
        }
        pendingReplacement = null
    }

    LaunchedEffect(initialBundle, initialRecordingJournalFileName) {
        val bundle = initialBundle ?: return@LaunchedEffect
        val current = session.draft
        if (current?.workflowId == bundle.document.id && current.lastSavedRevision == bundle.document.revision) {
            onInitialBundleConsumed()
            return@LaunchedEffect
        }
        val request = DraftReplacement.Existing(bundle, initialRecordingJournalFileName)
        if (session.isDirty) pendingReplacement = request else applyReplacement(request)
    }

    LaunchedEffect(newWorkflowRequestId) {
        if (newWorkflowRequestId == 0) return@LaunchedEffect
        val request = DraftReplacement.New(viewportFor(context), newWorkflowFolderId)
        if (session.isDirty) pendingReplacement = request else applyReplacement(request)
    }

    fun saveWorkflow(bundle: WorkflowBundle, afterSave: (() -> Unit)? = null) {
        val sourceJournalFileName = session.draft?.recordingJournalFileName
        scope.launch {
            isBusy = true
            statusMessage = null
            runCatching {
                withContext(Dispatchers.IO) {
                    repository.saveRevision(bundle, session.destinationFolderId)
                }
            }.onSuccess {
                session.markSaved(WorkflowDraft.fromBundle(bundle, sourceJournalFileName))
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

    fun discardDraft() {
        geometryTarget = null
        conditionTarget = null
        session.close()
        pendingClose = false
    }

    fun requestCloseDraft() {
        if (session.isDirty) pendingClose = true else discardDraft()
    }

    val draft = session.draft
    if (draft == null) {
        EmptyWorkflowEditorScreen(
            onCreate = {
                session.create(viewportFor(context))
                statusMessage = null
            },
            onOpenWorkflowManager = onOpenWorkflowManager,
            modifier = modifier,
        )
    } else {
        BackHandler { requestCloseDraft() }
        val validation = remember(draft) { draft.buildBundle() }
        WorkflowDraftScreen(
            draft = draft,
            validation = validation,
            isBusy = isBusy,
            statusMessage = statusMessage,
            session = session,
            onDraftChange = session::update,
            onBack = ::requestCloseDraft,
            onSave = { bundle -> saveWorkflow(bundle) },
            onRun = ::saveAndRun,
            onValidate = {
                statusMessage = validation.issues.firstOrNull()?.let { "${it.path}: ${it.message}" }
                    ?: "Workflow is valid."
            },
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
                        session.update(draft.updateStep(target.path, target.index) { updatedStep })
                        geometryTarget = null
                    },
                )
            }
        }
        conditionTarget?.let { target ->
            val targetStep = draft.stepsAt(target.path).getOrNull(target.index)
            val condition = targetStep?.conditionOrNull()
            if (condition != null) {
                val previewViewport = viewportFor(context)
                ConditionEditorDialog(
                    initialCondition = condition,
                    assets = draft.assets,
                    defaultPackage = draft.allowedPackagesText.firstPackageOrDefault(),
                    onAddTemplate = { templatePicker.launch("image/*") },
                    previewWidthPx = previewViewport.recordedWidthPx,
                    previewHeightPx = previewViewport.recordedHeightPx,
                    onDismiss = { conditionTarget = null },
                    onApply = { updatedCondition, removedAssetIds ->
                        session.update(
                            removedAssetIds.fold(
                                draft.updateCondition(target.path, target.index) { updatedCondition },
                            ) { currentDraft, assetId -> currentDraft.removeAsset(assetId) },
                        )
                        conditionTarget = null
                    },
                )
            }
        }
    }
    pendingTemplateCrop?.let { source ->
        TemplateCropEditorDialog(
            source = source,
            onDismiss = { pendingTemplateCrop = null },
            onApply = { crop ->
                scope.launch {
                    isBusy = true
                    statusMessage = null
                    runCatching {
                        withContext(Dispatchers.Default) {
                            importTemplateAsset(context.contentResolver, source.uri, crop)
                        }
                    }.onSuccess { asset ->
                        session.draft?.addAsset(asset)?.let(session::update)
                        pendingTemplateCrop = null
                        statusMessage = "Encrypted cropped image template added. Select it from the image condition."
                    }.onFailure { error ->
                        statusMessage = "Could not add image crop: ${error.userMessage()}"
                    }
                    isBusy = false
                }
            },
        )
    }

    val currentValidation = session.draft?.buildBundle()
    if (pendingClose) {
        UnsavedDraftDialog(
            title = "Close this workflow?",
            validation = currentValidation,
            isBusy = isBusy,
            onSave = { currentValidation?.bundle?.let { saveWorkflow(it, ::discardDraft) } },
            onDiscard = ::discardDraft,
            onCancel = { pendingClose = false },
        )
    }
    pendingReplacement?.let { request ->
        UnsavedDraftDialog(
            title = "Replace unsaved changes?",
            validation = currentValidation,
            isBusy = isBusy,
            onSave = { currentValidation?.bundle?.let { saveWorkflow(it) { applyReplacement(request) } } },
            onDiscard = { applyReplacement(request) },
            onCancel = { cancelReplacement(request) },
        )
    }
}

private sealed interface DraftReplacement {
    data class Existing(val bundle: WorkflowBundle, val journalFileName: String?) : DraftReplacement

    data class New(val viewport: com.veyarc.model.ViewportSpec, val folderId: String?) : DraftReplacement
}

@Composable
private fun UnsavedDraftDialog(
    title: String,
    validation: DraftBuildResult?,
    isBusy: Boolean,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = { Text("Save this revision, discard the changes, or keep editing.") },
        confirmButton = {
            TextButton(onClick = onSave, enabled = !isBusy && validation?.bundle != null) {
                Text("Save")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDiscard, enabled = !isBusy) { Text("Discard") }
                TextButton(onClick = onCancel, enabled = !isBusy) { Text("Keep editing") }
            }
        },
    )
}

private data class GeometryTarget(
    val path: StepListPath,
    val index: Int,
)

private data class ConditionTarget(
    val path: StepListPath,
    val index: Int,
)

@Composable
private fun EmptyWorkflowEditorScreen(
    onCreate: () -> Unit,
    onOpenWorkflowManager: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(title = { Text("Workflow Editor") })
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(padding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Create a workflow or choose one from Workflow Manager.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onCreate) { Text("New workflow") }
            OutlinedButton(onClick = onOpenWorkflowManager) {
                Text("Choose workflow")
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
    session: WorkflowEditorSession,
    onDraftChange: (WorkflowDraft) -> Unit,
    onBack: () -> Unit,
    onSave: (WorkflowBundle) -> Unit,
    onRun: (WorkflowBundle) -> Unit,
    onValidate: () -> Unit,
    onEditGeometry: (StepListPath, Int) -> Unit,
    onEditCondition: (StepListPath, Int) -> Unit,
    onAddTemplate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selection by remember { mutableStateOf<BlockSelection?>(null) }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        buildString {
                            append(draft.name.ifBlank { "Workflow editor" })
                            if (session.isDirty) append(" •")
                        },
                    )
                },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
    ) { padding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(padding),
        ) {
            val wide = maxWidth >= 840.dp
            if (wide) {
                Row(modifier = Modifier.fillMaxSize()) {
                    WorkflowDraftCanvas(
                        draft = draft,
                        validation = validation,
                        session = session,
                        isBusy = isBusy,
                        statusMessage = statusMessage,
                        selection = selection,
                        showInlineInspector = false,
                        onSelectionChange = { selection = it },
                        onDraftChange = onDraftChange,
                        onSave = onSave,
                        onRun = onRun,
                        onValidate = onValidate,
                        onEditGeometry = onEditGeometry,
                        onEditCondition = onEditCondition,
                        onAddTemplate = onAddTemplate,
                        modifier = Modifier.weight(0.62f),
                    )
                    VerticalDivider()
                    BlockInspector(
                        draft = draft,
                        selection = selection,
                        onDraftChange = onDraftChange,
                        onEditGeometry = onEditGeometry,
                        onEditCondition = onEditCondition,
                        modifier = Modifier.weight(0.38f),
                    )
                }
            } else {
                WorkflowDraftCanvas(
                    draft = draft,
                    validation = validation,
                    session = session,
                    isBusy = isBusy,
                    statusMessage = statusMessage,
                    selection = selection,
                    showInlineInspector = true,
                    onSelectionChange = { selection = it },
                    onDraftChange = onDraftChange,
                    onSave = onSave,
                    onRun = onRun,
                    onValidate = onValidate,
                    onEditGeometry = onEditGeometry,
                    onEditCondition = onEditCondition,
                    onAddTemplate = onAddTemplate,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

private data class BlockSelection(val path: StepListPath, val stepId: String)

@Composable
private fun WorkflowDraftCanvas(
    draft: WorkflowDraft,
    validation: DraftBuildResult,
    session: WorkflowEditorSession,
    isBusy: Boolean,
    statusMessage: String?,
    selection: BlockSelection?,
    showInlineInspector: Boolean,
    onSelectionChange: (BlockSelection) -> Unit,
    onDraftChange: (WorkflowDraft) -> Unit,
    onSave: (WorkflowBundle) -> Unit,
    onRun: (WorkflowBundle) -> Unit,
    onValidate: () -> Unit,
    onEditGeometry: (StepListPath, Int) -> Unit,
    onEditCondition: (StepListPath, Int) -> Unit,
    onAddTemplate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        EditorToolbar(
            draft = draft,
            session = session,
            validation = validation,
            isBusy = isBusy,
            onSave = onSave,
            onRun = onRun,
            onValidate = onValidate,
        )
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
            supportingText = { Text("Separate packages with commas or spaces. Use Launch app first when the workflow opens another app.") },
            singleLine = true,
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
        Text("Execution", style = MaterialTheme.typography.titleLarge)
        StepListEditor(
            draft = draft,
            path = StepListPath(),
            heading = "Start",
            validation = validation,
            selection = selection,
            showInlineInspector = showInlineInspector,
            onSelectionChange = onSelectionChange,
            onDraftChange = onDraftChange,
            onEditGeometry = onEditGeometry,
            onEditCondition = onEditCondition,
        )
        statusMessage?.let { message -> Text(message, color = MaterialTheme.colorScheme.primary) }
    }
}

@Composable
private fun EditorToolbar(
    draft: WorkflowDraft,
    session: WorkflowEditorSession,
    validation: DraftBuildResult,
    isBusy: Boolean,
    onSave: (WorkflowBundle) -> Unit,
    onRun: (WorkflowBundle) -> Unit,
    onValidate: () -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            EditorBadge(draft.viewport.recordedOrientation.name.lowercase().replaceFirstChar(Char::uppercase))
        }
        item {
            EditorBadge(
                draft.lastSavedRevision?.let { revision ->
                    "Revision $revision" + if (session.isDirty) " • edited" else ""
                } ?: "Unsaved workflow",
            )
        }
        item {
            OutlinedButton(onClick = session::undo, enabled = session.canUndo && !isBusy) {
                Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = null)
                Text("Undo")
            }
        }
        item {
            OutlinedButton(onClick = session::redo, enabled = session.canRedo && !isBusy) {
                Icon(Icons.AutoMirrored.Outlined.Redo, contentDescription = null)
                Text("Redo")
            }
        }
        item {
            OutlinedButton(onClick = onValidate, enabled = !isBusy) {
                Icon(Icons.Outlined.CheckCircle, contentDescription = null)
                Text("Validate")
            }
        }
        item {
            Button(
                onClick = { validation.bundle?.let(onSave) },
                enabled = !isBusy && validation.bundle != null && session.isDirty,
            ) { Text("Save v${draft.nextRevision}") }
        }
        item {
            OutlinedButton(
                onClick = { validation.bundle?.let(onRun) },
                enabled = !isBusy && validation.bundle != null,
            ) { Text("Run") }
        }
    }
}

@Composable
private fun EditorBadge(text: String) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(text, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
    }
}

@Composable
private fun BlockInspector(
    draft: WorkflowDraft,
    selection: BlockSelection?,
    onDraftChange: (WorkflowDraft) -> Unit,
    onEditGeometry: (StepListPath, Int) -> Unit,
    onEditCondition: (StepListPath, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val index = selection?.let { selected -> draft.stepsAt(selected.path).indexOfFirst { it.id == selected.stepId } } ?: -1
    val step = selection?.let { draft.stepsAt(it.path).getOrNull(index) }
    Column(
        modifier = modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Block inspector", style = MaterialTheme.typography.titleLarge)
        if (selection == null || step == null) {
            Text("Select a block to edit its fields.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text(step.displayName(), style = MaterialTheme.typography.titleMedium)
            Text(step.summary(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            StepEditorFields(
                draft = draft,
                path = selection.path,
                index = index,
                step = step,
                onDraftChange = onDraftChange,
                onEditGeometry = onEditGeometry,
                onEditCondition = onEditCondition,
            )
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
                            "${asset.mimeType} · ${asset.byteLength} encrypted bytes",
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
    validation: DraftBuildResult,
    selection: BlockSelection?,
    showInlineInspector: Boolean,
    onSelectionChange: (BlockSelection) -> Unit,
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
            val issuePrefix = draft.modelPath(path, index)
            StepCard(
                draft = draft,
                path = path,
                index = index,
                step = step,
                validation = validation,
                issues = validation.issues.filter { issue ->
                    issue.path == issuePrefix || issue.path.startsWith("$issuePrefix/")
                },
                selection = selection,
                showInlineInspector = showInlineInspector,
                onSelectionChange = onSelectionChange,
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
    validation: DraftBuildResult,
    issues: List<DraftIssue>,
    selection: BlockSelection?,
    showInlineInspector: Boolean,
    onSelectionChange: (BlockSelection) -> Unit,
    onDraftChange: (WorkflowDraft) -> Unit,
    onEditGeometry: (StepListPath, Int) -> Unit,
    onEditCondition: (StepListPath, Int) -> Unit,
) {
    val expanded = step.id !in draft.collapsedStepIds
    val category = step.blockCategory()
    val accent = category.accent()
    val selected = selection?.stepId == step.id
    Box(modifier = Modifier.fillMaxWidth()) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(start = 30.dp).clickable { onSelectionChange(BlockSelection(path, step.id)) },
            shape = category.shape(),
            colors = CardDefaults.cardColors(containerColor = category.containerColor()),
            border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
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
                    Icon(category.icon, contentDescription = null, tint = accent)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(category.label.uppercase(), color = accent, style = MaterialTheme.typography.labelSmall)
                        Text("${index + 1}. ${step.displayName()}", style = MaterialTheme.typography.titleSmall)
                    }
                    IconButton(onClick = { onDraftChange(draft.toggleCollapsed(step.id)) }) {
                        Icon(
                            if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                            contentDescription = if (expanded) "Collapse block" else "Expand block",
                        )
                    }
                }
                Text(step.summary(), style = MaterialTheme.typography.bodyMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    item {
                        TextButton(
                            enabled = index > 0,
                            onClick = { onDraftChange(draft.moveStep(path, index, -1)) },
                        ) {
                            Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = null)
                            Text("Move up")
                        }
                    }
                    item {
                        TextButton(
                            enabled = index < draft.stepsAt(path).lastIndex,
                            onClick = { onDraftChange(draft.moveStep(path, index, 1)) },
                        ) {
                            Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = null)
                            Text("Move down")
                        }
                    }
                    item {
                        TextButton(onClick = { onDraftChange(draft.removeStep(path, index)) }) {
                            Icon(Icons.Outlined.Delete, contentDescription = null)
                            Text("Remove")
                        }
                    }
                }
                issues.forEach { issue ->
                    Text(issue.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (expanded && showInlineInspector) {
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
                if (expanded) {
                    NestedStepLists(
                        draft = draft,
                        path = path,
                        step = step,
                        validation = validation,
                        selection = selection,
                        showInlineInspector = showInlineInspector,
                        onSelectionChange = onSelectionChange,
                        onDraftChange = onDraftChange,
                        onEditGeometry = onEditGeometry,
                        onEditCondition = onEditCondition,
                    )
                }
            }
        }
        Box(modifier = Modifier.matchParentSize()) {
            ExecutionRail(
                accent = accent,
                terminal = step is Stop,
                modifier = Modifier.align(Alignment.TopStart).width(30.dp).fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun ExecutionRail(accent: Color, terminal: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(11.dp).background(accent, CircleShape))
        Box(Modifier.width(2.dp).weight(1f).background(accent))
        if (!terminal) Box(Modifier.size(11.dp).background(accent, CircleShape))
    }
}

@Composable
private fun NestedStepLists(
    draft: WorkflowDraft,
    path: StepListPath,
    step: WorkflowStep,
    validation: DraftBuildResult,
    selection: BlockSelection?,
    showInlineInspector: Boolean,
    onSelectionChange: (BlockSelection) -> Unit,
    onDraftChange: (WorkflowDraft) -> Unit,
    onEditGeometry: (StepListPath, Int) -> Unit,
    onEditCondition: (StepListPath, Int) -> Unit,
) {
    when (step) {
        is Repeat -> BranchTray(
            draft = draft,
            path = path.child(step.id, NestedBranch.REPEAT_BODY),
            heading = "Repeat body",
            validation = validation,
            selection = selection,
            showInlineInspector = showInlineInspector,
            onSelectionChange = onSelectionChange,
            onDraftChange = onDraftChange,
            onEditGeometry = onEditGeometry,
            onEditCondition = onEditCondition,
        )

        is WhileStep -> BranchTray(
            draft = draft,
            path = path.child(step.id, NestedBranch.WHILE_BODY),
            heading = "While body",
            validation = validation,
            selection = selection,
            showInlineInspector = showInlineInspector,
            onSelectionChange = onSelectionChange,
            onDraftChange = onDraftChange,
            onEditGeometry = onEditGeometry,
            onEditCondition = onEditCondition,
        )

        is IfElse -> {
            BranchTray(
                draft = draft,
                path = path.child(step.id, NestedBranch.IF_THEN),
                heading = "Then",
                validation = validation,
                selection = selection,
                showInlineInspector = showInlineInspector,
                onSelectionChange = onSelectionChange,
                onDraftChange = onDraftChange,
                onEditGeometry = onEditGeometry,
                onEditCondition = onEditCondition,
            )
            BranchTray(
                draft = draft,
                path = path.child(step.id, NestedBranch.IF_ELSE),
                heading = "Else",
                validation = validation,
                selection = selection,
                showInlineInspector = showInlineInspector,
                onSelectionChange = onSelectionChange,
                onDraftChange = onDraftChange,
                onEditGeometry = onEditGeometry,
                onEditCondition = onEditCondition,
            )
        }

        else -> Unit
    }
}

@Composable
private fun BranchTray(
    draft: WorkflowDraft,
    path: StepListPath,
    heading: String,
    validation: DraftBuildResult,
    selection: BlockSelection?,
    showInlineInspector: Boolean,
    onSelectionChange: (BlockSelection) -> Unit,
    onDraftChange: (WorkflowDraft) -> Unit,
    onEditGeometry: (StepListPath, Int) -> Unit,
    onEditCondition: (StepListPath, Int) -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))) {
        Box(modifier = Modifier.padding(10.dp)) {
            StepListEditor(
                draft = draft,
                path = path,
                heading = heading,
                validation = validation,
                selection = selection,
                showInlineInspector = showInlineInspector,
                onSelectionChange = onSelectionChange,
                onDraftChange = onDraftChange,
                onEditGeometry = onEditGeometry,
                onEditCondition = onEditCondition,
            )
        }
    }
}

private enum class BlockCategory(val label: String, val icon: ImageVector) {
    ACTION("Action", Icons.Outlined.TouchApp),
    TIMING("Timing", Icons.Outlined.Schedule),
    CONDITION("Condition", Icons.AutoMirrored.Outlined.HelpOutline),
    BRANCH("Branch", Icons.AutoMirrored.Outlined.CallSplit),
    LOOP("Loop", Icons.Outlined.Loop),
    APP("App", Icons.Outlined.Apps),
    STOP("Stop", Icons.Outlined.StopCircle),
}

private fun WorkflowStep.blockCategory(): BlockCategory = when (this) {
    is TapPoint, is TapRegion, is LongPress, is SwipePath -> BlockCategory.ACTION
    is Wait -> BlockCategory.TIMING
    is WaitUntil -> BlockCategory.CONDITION
    is IfElse -> BlockCategory.BRANCH
    is Repeat, is WhileStep -> BlockCategory.LOOP
    is LaunchApp -> BlockCategory.APP
    is Stop -> BlockCategory.STOP
    else -> BlockCategory.ACTION
}

@Composable
private fun BlockCategory.accent(): Color = when (this) {
    BlockCategory.ACTION -> MaterialTheme.colorScheme.primary
    BlockCategory.TIMING -> MaterialTheme.colorScheme.tertiary
    BlockCategory.CONDITION -> MaterialTheme.colorScheme.secondary
    BlockCategory.BRANCH -> MaterialTheme.colorScheme.primary
    BlockCategory.LOOP -> MaterialTheme.colorScheme.tertiary
    BlockCategory.APP -> MaterialTheme.colorScheme.secondary
    BlockCategory.STOP -> MaterialTheme.colorScheme.error
}

@Composable
private fun BlockCategory.containerColor(): Color = accent().copy(alpha = 0.14f)

private fun BlockCategory.shape(): Shape = when (this) {
    BlockCategory.CONDITION, BlockCategory.BRANCH -> CutCornerShape(topStart = 18.dp, bottomStart = 18.dp)
    BlockCategory.LOOP -> RoundedCornerShape(22.dp)
    BlockCategory.STOP -> RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 4.dp, bottomEnd = 4.dp)
    else -> RoundedCornerShape(14.dp)
}

private fun WorkflowDraft.modelPath(path: StepListPath, index: Int): String {
    var current = steps
    var prefix = "/steps"
    path.segments.forEach { segment ->
        val parentIndex = current.indexOfFirst { it.id == segment.parentId }
        if (parentIndex < 0) return "$prefix/$index"
        val parent = current[parentIndex]
        val childName = when (segment.branch) {
            NestedBranch.REPEAT_BODY, NestedBranch.WHILE_BODY -> "bodySteps"
            NestedBranch.IF_THEN -> "thenSteps"
            NestedBranch.IF_ELSE -> "elseSteps"
        }
        prefix = "$prefix/$parentIndex/$childName"
        current = parent.childrenForEditor(segment.branch)
    }
    return "$prefix/$index"
}

private fun WorkflowStep.childrenForEditor(branch: NestedBranch): List<WorkflowStep> = when (branch) {
    NestedBranch.REPEAT_BODY -> (this as? Repeat)?.bodySteps.orEmpty()
    NestedBranch.WHILE_BODY -> (this as? WhileStep)?.bodySteps.orEmpty()
    NestedBranch.IF_THEN -> (this as? IfElse)?.thenSteps.orEmpty()
    NestedBranch.IF_ELSE -> (this as? IfElse)?.elseSteps.orEmpty()
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
        }

        is IfElse -> {
            OutlinedButton(onClick = { onEditCondition(path, index) }) { Text("Edit condition") }
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
