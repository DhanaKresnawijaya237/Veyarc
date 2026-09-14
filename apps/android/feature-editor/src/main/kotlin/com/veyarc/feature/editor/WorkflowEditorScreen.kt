@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package com.veyarc.feature.editor

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.widget.ImageView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Loop
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Tune
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import com.veyarc.data.storage.RevisionSummary
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
    reducedDensity: Boolean = false,
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
    var pendingOrientationSave by remember { mutableStateOf<PendingOrientationSave?>(null) }
    var revisions by remember { mutableStateOf<List<RevisionSummary>>(emptyList()) }

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
    val appPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val packageName = result.data?.component?.packageName ?: return@rememberLauncherForActivityResult
        val current = session.draft ?: return@rememberLauncherForActivityResult
        val packages = current.allowedPackagesText.packageNames()
        if (packageName !in packages) {
            session.update(current.copy(allowedPackagesText = (packages + packageName).joinToString(", ")))
        }
    }

    fun applyReplacement(request: DraftReplacement) {
        when (request) {
            is DraftReplacement.Existing -> {
                val currentRevision = session.draft
                    ?.takeIf { it.workflowId == request.bundle.document.id }
                    ?.latestKnownRevision
                session.open(
                    request.bundle,
                    request.journalFileName,
                    maxOf(request.latestKnownRevision, currentRevision ?: 0),
                )
                statusMessage = "Workflow opened. Saving edits creates the next revision."
                if (request.consumeInitialBundle) onInitialBundleConsumed()
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
            is DraftReplacement.Existing -> if (request.consumeInitialBundle) onInitialBundleConsumed()
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
        val latestRevision = withContext(Dispatchers.IO) {
            repository.listRevisions(bundle.document.id).maxOfOrNull(RevisionSummary::revision)
        } ?: bundle.document.revision
        val request = DraftReplacement.Existing(
            bundle,
            initialRecordingJournalFileName,
            latestRevision,
            consumeInitialBundle = true,
        )
        if (session.isDirty) pendingReplacement = request else applyReplacement(request)
    }

    LaunchedEffect(newWorkflowRequestId) {
        if (newWorkflowRequestId == 0) return@LaunchedEffect
        val request = DraftReplacement.New(viewportFor(context), newWorkflowFolderId)
        if (session.isDirty) pendingReplacement = request else applyReplacement(request)
    }

    LaunchedEffect(session.draft?.workflowId, session.draft?.lastSavedRevision) {
        val workflowId = session.draft?.workflowId ?: run {
            revisions = emptyList()
            return@LaunchedEffect
        }
        revisions = withContext(Dispatchers.IO) { repository.listRevisions(workflowId) }
    }

    fun openRevision(revision: Int) {
        val current = session.draft ?: return
        if (current.lastSavedRevision == revision) return
        scope.launch {
            isBusy = true
            statusMessage = null
            runCatching {
                withContext(Dispatchers.IO) { repository.loadRevision(current.workflowId, revision) }
            }.onSuccess { bundle ->
                val request = DraftReplacement.Existing(
                    bundle,
                    journalFileName = null,
                    latestKnownRevision = revisions.maxOfOrNull(RevisionSummary::revision)
                        ?: bundle.document.revision,
                    consumeInitialBundle = false,
                )
                if (session.isDirty) pendingReplacement = request else applyReplacement(request)
            }.onFailure { error ->
                statusMessage = "Could not open revision: ${error.userMessage()}"
            }
            isBusy = false
        }
    }

    fun saveWorkflow(
        bundle: WorkflowBundle,
        afterSave: (() -> Unit)? = null,
        orientationConfirmed: Boolean = false,
    ) {
        val savedOrientation = session.savedOrientation
        if (!orientationConfirmed && savedOrientation != null && savedOrientation != bundle.document.viewport.recordedOrientation) {
            pendingOrientationSave = PendingOrientationSave(bundle, afterSave)
            return
        }
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
        saveWorkflow(bundle, afterSave = { onRunWorkflow(bundle) })
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
            onSave = { bundle -> saveWorkflow(bundle) },
            onRun = ::saveAndRun,
            onValidate = {
                statusMessage = validation.issues.firstOrNull()?.let { "${it.path}: ${it.message}" }
                    ?: "Workflow is valid."
            },
            onEditGeometry = { path, index -> geometryTarget = GeometryTarget(path, index) },
            onEditCondition = { path, index -> conditionTarget = ConditionTarget(path, index) },
            onAddTemplate = { templatePicker.launch("image/*") },
            revisions = revisions,
            onOpenRevision = ::openRevision,
            onPickApplication = {
                appPicker.launch(
                    Intent(Intent.ACTION_PICK_ACTIVITY).putExtra(
                        Intent.EXTRA_INTENT,
                        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                    ),
                )
            },
            reducedDensity = reducedDensity,
            modifier = modifier,
        )
        geometryTarget?.let { target ->
            val targetStep = draft.stepsAt(target.path).getOrNull(target.index)
            if (targetStep != null) {
                GeometryEditorDialog(
                    step = targetStep,
                    viewport = draft.viewport,
                    maxPathPoints = draft.runLimits.maxPathPoints,
                    assets = draft.assets,
                    assetNames = draft.assetNames,
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
            onSave = { currentValidation?.bundle?.let { saveWorkflow(it, afterSave = { applyReplacement(request) }) } },
            onDiscard = { applyReplacement(request) },
            onCancel = { cancelReplacement(request) },
        )
    }
    pendingOrientationSave?.let { pending ->
        AlertDialog(
            onDismissRequest = { pendingOrientationSave = null },
            title = { Text("Save orientation change?") },
            text = {
                Text(
                    "This revision changes the workflow to ${pending.bundle.document.viewport.recordedOrientation.name.lowercase()}. Existing coordinates may need review.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingOrientationSave = null
                    saveWorkflow(pending.bundle, pending.afterSave, orientationConfirmed = true)
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { pendingOrientationSave = null }) { Text("Keep editing") }
            },
        )
    }
}

private data class PendingOrientationSave(
    val bundle: WorkflowBundle,
    val afterSave: (() -> Unit)?,
)

private sealed interface DraftReplacement {
    data class Existing(
        val bundle: WorkflowBundle,
        val journalFileName: String?,
        val latestKnownRevision: Int,
        val consumeInitialBundle: Boolean,
    ) : DraftReplacement

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
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, top = 20.dp, end = 20.dp)
                .heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Workflow Editor", style = MaterialTheme.typography.headlineSmall)
        }
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
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
    onSave: (WorkflowBundle) -> Unit,
    onRun: (WorkflowBundle) -> Unit,
    onValidate: () -> Unit,
    onEditGeometry: (StepListPath, Int) -> Unit,
    onEditCondition: (StepListPath, Int) -> Unit,
    onAddTemplate: () -> Unit,
    revisions: List<RevisionSummary>,
    onOpenRevision: (Int) -> Unit,
    onPickApplication: () -> Unit,
    reducedDensity: Boolean,
    modifier: Modifier = Modifier,
) {
    var selection by remember { mutableStateOf<BlockSelection?>(null) }
    Column(
        modifier = modifier.fillMaxSize().pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                selection = null
            }
        },
    ) {
        Row(
            modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, top = 20.dp, end = 20.dp)
                .heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = draft.name,
                onValueChange = { onDraftChange(draft.copy(name = it)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = MaterialTheme.typography.headlineSmall.copy(color = MaterialTheme.colorScheme.onSurface),
                decorationBox = { field ->
                    if (draft.name.isBlank()) {
                        Text("Workflow title", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    field()
                },
            )
        }
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize().weight(1f),
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
                        validationState = validation.issues.isEmpty(),
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
                        revisions = revisions,
                        onOpenRevision = onOpenRevision,
                        onPickApplication = onPickApplication,
                        reducedDensity = reducedDensity,
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
                    validationState = validation.issues.isEmpty(),
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
                    revisions = revisions,
                    onOpenRevision = onOpenRevision,
                    onPickApplication = onPickApplication,
                    reducedDensity = reducedDensity,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

private data class BlockSelection(val path: StepListPath, val stepId: String)

private data class StepBounds(val height: Int)

private fun stepDropIndex(
    steps: List<WorkflowStep>,
    bounds: Map<String, StepBounds>,
    spacingPx: Float,
    draggedStepId: String?,
    dragOffsetY: Float,
): Int {
    val key = draggedStepId ?: return -1
    val sourceIndex = steps.indexOfFirst { it.id == draggedStepId }
    if (sourceIndex < 0 || key !in bounds) return sourceIndex
    var top = 0f
    val centers = steps.associate { step ->
        val height = bounds[step.id]?.height ?: 0
        val center = top + height / 2f
        top += height + spacingPx
        step.id to center
    }
    val draggedCenter = centers.getValue(key) + dragOffsetY
    return steps.count { it.id != draggedStepId && (centers[it.id] ?: Float.MAX_VALUE) < draggedCenter }
        .coerceIn(0, steps.lastIndex)
}

@Composable
private fun WorkflowDraftCanvas(
    draft: WorkflowDraft,
    validation: DraftBuildResult,
    session: WorkflowEditorSession,
    isBusy: Boolean,
    statusMessage: String?,
    validationState: Boolean?,
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
    revisions: List<RevisionSummary>,
    onOpenRevision: (Int) -> Unit,
    onPickApplication: () -> Unit,
    reducedDensity: Boolean,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = modifier.verticalScroll(scrollState).padding(
            horizontal = if (reducedDensity) 12.dp else 16.dp,
            vertical = if (reducedDensity) 8.dp else 12.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(if (reducedDensity) 8.dp else 12.dp),
    ) {
        EditorToolbar(
            draft = draft,
            session = session,
            validation = validation,
            validationState = validationState,
            isBusy = isBusy,
            onDraftChange = onDraftChange,
            onSave = onSave,
            onRun = onRun,
            onValidate = onValidate,
            revisions = revisions,
            onOpenRevision = onOpenRevision,
        )
        AllowedApplications(
            packages = draft.allowedPackagesText,
            onPickApplication = onPickApplication,
            onRemoveApplication = { packageName ->
                onDraftChange(
                    draft.copy(
                        allowedPackagesText = draft.allowedPackagesText.packageNames()
                            .filterNot { it == packageName }
                            .joinToString(", "),
                    ),
                )
            },
        )
        draft.recordingJournalFileName?.let { journalFileName ->
            Text(
                "Imported from encrypted recording: $journalFileName",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TemplateAssetSection(
            assets = draft.assets,
            assetNames = draft.assetNames,
            onAddTemplate = onAddTemplate,
            onRemoveTemplate = { assetId -> onDraftChange(draft.removeAsset(assetId)) },
            onRenameTemplate = { assetId, name -> onDraftChange(draft.renameAsset(assetId, name)) },
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
            scrollState = scrollState,
        )
        statusMessage?.let { message -> Text(message, color = MaterialTheme.colorScheme.primary) }
    }
}

@Composable
private fun EditorToolbar(
    draft: WorkflowDraft,
    session: WorkflowEditorSession,
    validation: DraftBuildResult,
    validationState: Boolean?,
    isBusy: Boolean,
    onDraftChange: (WorkflowDraft) -> Unit,
    onSave: (WorkflowBundle) -> Unit,
    onRun: (WorkflowBundle) -> Unit,
    onValidate: () -> Unit,
    revisions: List<RevisionSummary>,
    onOpenRevision: (Int) -> Unit,
) {
    var validationDetailsVisible by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Surface(
                modifier = Modifier.height(48.dp),
                onClick = {
                    val viewport = draft.viewport
                    val orientation = if (viewport.recordedOrientation == DisplayOrientation.PORTRAIT) {
                        DisplayOrientation.LANDSCAPE
                    } else {
                        DisplayOrientation.PORTRAIT
                    }
                    onDraftChange(
                        draft.copy(
                            viewport = viewport.copy(
                                recordedWidthPx = viewport.recordedHeightPx,
                                recordedHeightPx = viewport.recordedWidthPx,
                                recordedOrientation = orientation,
                            ),
                        ),
                    )
                },
                enabled = !isBusy,
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        Icons.Outlined.ScreenRotation,
                        contentDescription = "Switch workflow orientation",
                        modifier = Modifier.size(24.dp),
                    )
                    Text(
                        if (draft.viewport.recordedOrientation == DisplayOrientation.PORTRAIT) "Portrait" else "Landscape",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
            RevisionSelector(
                currentRevision = draft.lastSavedRevision,
                dirty = session.isDirty,
                revisions = revisions,
                onOpenRevision = onOpenRevision,
                modifier = Modifier.weight(1f).height(48.dp),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = session::undo, enabled = session.canUndo && !isBusy) {
                Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = "Undo")
            }
            IconButton(onClick = session::redo, enabled = session.canRedo && !isBusy) {
                Icon(Icons.AutoMirrored.Outlined.Redo, contentDescription = "Redo")
            }
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .combinedClickable(
                        enabled = !isBusy,
                        onClick = onValidate,
                        onLongClick = {
                            if (validation.issues.isNotEmpty()) validationDetailsVisible = true
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.CheckCircle,
                    contentDescription = "Validate workflow. Long press for failure details",
                    tint = when (validationState) {
                        true -> MaterialTheme.colorScheme.primary
                        false -> MaterialTheme.colorScheme.error
                        null -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            IconButton(
                onClick = { validation.bundle?.let(onRun) },
                enabled = !isBusy && validation.bundle != null,
            ) { Icon(Icons.Outlined.PlayArrow, contentDescription = "Run workflow") }
            IconButton(
                onClick = { validation.bundle?.let(onSave) },
                enabled = !isBusy && validation.bundle != null && (session.isDirty || session.requiresRevisionSave),
            ) { Icon(Icons.Outlined.Save, contentDescription = "Save revision ${draft.nextRevision}") }
        }
    }
    if (validationDetailsVisible) {
        AlertDialog(
            onDismissRequest = { validationDetailsVisible = false },
            title = { Text("Validation issues") },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    validation.issues.forEach { issue ->
                        Text("${issue.path}: ${issue.message}")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { validationDetailsVisible = false }) { Text("Done") }
            },
        )
    }
}

@Composable
private fun RevisionSelector(
    currentRevision: Int?,
    dirty: Boolean,
    revisions: List<RevisionSummary>,
    onOpenRevision: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    currentRevision?.let { "Revision $it" + if (dirty) " •" else "" } ?: "Unsaved",
                    modifier = Modifier.weight(1f).padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
                )
                IconButton(
                    onClick = { expanded = !expanded },
                    enabled = revisions.isNotEmpty(),
                ) {
                    Icon(Icons.Outlined.ArrowDropDown, contentDescription = "Choose workflow revision")
                }
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            revisions.forEach { revision ->
                DropdownMenuItem(
                    text = { Text("Revision ${revision.revision}") },
                    onClick = {
                        expanded = false
                        onOpenRevision(revision.revision)
                    },
                )
            }
        }
    }
}

@Composable
private fun AllowedApplications(
    packages: String,
    onPickApplication: () -> Unit,
    onRemoveApplication: (String) -> Unit,
) {
    val context = LocalContext.current
    val packageNames = remember(packages) { packages.packageNames() }
    var expanded by remember { mutableStateOf(true) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Allowed apps", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Icon(
                Icons.Outlined.ArrowDropDown,
                contentDescription = if (expanded) "Collapse allowed apps" else "Expand allowed apps",
                modifier = Modifier.rotate(if (expanded) 180f else 0f),
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(120)) + expandVertically(tween(120)),
            exit = fadeOut(tween(100)) + shrinkVertically(tween(100)),
        ) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (packageNames.isNotEmpty()) {
                    items(packageNames, key = { it }) { packageName ->
                        val applicationInfo = remember(packageName) {
                            runCatching { context.packageManager.getApplicationInfo(packageName, 0) }.getOrNull()
                        }
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.width(72.dp).clickable { onRemoveApplication(packageName) },
                        ) {
                            if (applicationInfo != null) {
                                AndroidView(
                                    factory = { ImageView(it).apply { scaleType = ImageView.ScaleType.FIT_CENTER } },
                                    update = { it.setImageDrawable(context.packageManager.getApplicationIcon(applicationInfo)) },
                                    modifier = Modifier.size(44.dp),
                                )
                                Text(
                                    context.packageManager.getApplicationLabel(applicationInfo).toString(),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            } else {
                                Icon(Icons.Outlined.Apps, contentDescription = null, modifier = Modifier.size(44.dp))
                                Text(
                                    packageName.substringAfterLast('.'),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                }
                item {
                    IconButton(onClick = onPickApplication) {
                        Icon(Icons.Outlined.Add, contentDescription = "Add allowed app", modifier = Modifier.size(32.dp))
                    }
                }
            }
        }
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
            EditableStepSummary(draft, selection.path, index, step, onDraftChange)
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
    assetNames: Map<String, String>,
    onAddTemplate: () -> Unit,
    onRemoveTemplate: (com.veyarc.model.AssetId) -> Unit,
    onRenameTemplate: (com.veyarc.model.AssetId, String) -> Unit,
) {
    var expanded by remember { mutableStateOf(true) }
    var renameTarget by remember { mutableStateOf<com.veyarc.model.AssetId?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Image templates", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = onAddTemplate) {
                Icon(Icons.Outlined.Add, contentDescription = "Add image template")
            }
            Icon(
                Icons.Outlined.ArrowDropDown,
                contentDescription = if (expanded) "Collapse image templates" else "Expand image templates",
                modifier = Modifier.rotate(if (expanded) 180f else 0f),
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(120)) + expandVertically(tween(120)),
            exit = fadeOut(tween(100)) + shrinkVertically(tween(100)),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Templates are encrypted with Android Keystore before they enter the workflow bundle.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (assets.isEmpty()) {
                    Text("No templates added yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    assets.forEachIndexed { index, asset ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { renameTarget = asset.assetId },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(assetNames[asset.assetId.value] ?: "Template ${index + 1}")
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
    }
    renameTarget?.let { assetId ->
        var name by remember(assetId) { mutableStateOf(assetNames[assetId.value].orEmpty()) }
        val valid = name.trim().let { candidate ->
            candidate.isNotEmpty() && candidate.length <= 64 &&
                assetNames.none { (id, existing) -> id != assetId.value && existing.equals(candidate, ignoreCase = true) }
        }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename template") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Template name") },
                    supportingText = if (valid) null else ({ Text("Use a unique name with 1 to 64 characters.") }),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = valid,
                    onClick = {
                        onRenameTemplate(assetId, name.trim())
                        renameTarget = null
                    },
                ) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel") } },
        )
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
    scrollState: androidx.compose.foundation.ScrollState,
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val view = LocalView.current
    val bounds = remember(path) { mutableStateMapOf<String, StepBounds>() }
    var draggedStepId by remember(path) { mutableStateOf<String?>(null) }
    var dragOffsetY by remember(path) { mutableFloatStateOf(0f) }
    var pointerWindowY by remember(path) { mutableFloatStateOf(0f) }
    val steps = draft.stepsAt(path)
    val sourceIndex = steps.indexOfFirst { it.id == draggedStepId }
    val sourceBounds = draggedStepId?.let(bounds::get)
    val spacingPx = with(density) { 8.dp.toPx() }
    val targetIndex = stepDropIndex(steps, bounds, spacingPx, draggedStepId, dragOffsetY)
    val shiftDistance = (sourceBounds?.height ?: 0) + spacingPx

    LaunchedEffect(draggedStepId, pointerWindowY) {
        while (draggedStepId != null) {
            val scrollDelta = when {
                pointerWindowY < view.height * 0.16f -> -20f
                pointerWindowY > view.height * 0.84f -> 20f
                else -> break
            }
            withFrameNanos { }
            val consumed = scrollState.scrollBy(scrollDelta)
            if (consumed == 0f) break
            dragOffsetY += consumed
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(heading, style = MaterialTheme.typography.titleMedium)
        if (steps.isEmpty()) {
            Text(
                "No blocks in this branch yet.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        steps.forEachIndexed { index, step ->
            val issuePrefix = draft.modelPath(path, index)
            val neighborShift = when {
                sourceIndex < targetIndex && index in (sourceIndex + 1)..targetIndex -> -shiftDistance
                sourceIndex > targetIndex && index in targetIndex until sourceIndex -> shiftDistance
                else -> 0f
            }
            val animatedShift by animateFloatAsState(neighborShift, tween(100), label = "block insertion")
            key(step.id) {
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
                    scrollState = scrollState,
                    translationY = when {
                        step.id == draggedStepId -> dragOffsetY
                        draggedStepId != null -> animatedShift
                        else -> 0f
                    },
                    dragging = step.id == draggedStepId,
                    onMeasured = { bounds[step.id] = it },
                    onDragStart = { initialPointerY ->
                        onSelectionChange(BlockSelection(path, step.id))
                        draggedStepId = step.id
                        dragOffsetY = 0f
                        pointerWindowY = initialPointerY
                    },
                    onDrag = { deltaY, currentPointerY ->
                        dragOffsetY += deltaY
                        pointerWindowY = currentPointerY
                    },
                    onDragFinished = { commit ->
                        val from = steps.indexOfFirst { it.id == step.id }
                        val to = stepDropIndex(steps, bounds, spacingPx, step.id, dragOffsetY)
                        if (commit && from >= 0 && to >= 0 && from != to) {
                            onDraftChange(draft.moveStepTo(path, from, to))
                        }
                        draggedStepId = null
                        dragOffsetY = 0f
                    },
                    onInsertAfter = { kind ->
                        onDraftChange(
                            draft.insertStep(
                                path,
                                index + 1,
                                kind,
                                draft.allowedPackagesText.firstPackageOrDefault(),
                            ),
                        )
                    },
                )
            }
        }
        AddBlockMenu(
            compact = true,
            modifier = Modifier.align(Alignment.CenterHorizontally).offset(y = (-10).dp),
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
    scrollState: androidx.compose.foundation.ScrollState,
    translationY: Float,
    dragging: Boolean,
    onMeasured: (StepBounds) -> Unit,
    onDragStart: (Float) -> Unit,
    onDrag: (Float, Float) -> Unit,
    onDragFinished: (Boolean) -> Unit,
    onInsertAfter: (WorkflowStepKind) -> Unit,
) {
    var topInWindow by remember(step.id) { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val liftScale by animateFloatAsState(if (dragging) 1.025f else 1f, tween(100), label = "block lift")
    val currentDragStart by rememberUpdatedState(onDragStart)
    val currentDrag by rememberUpdatedState(onDrag)
    val currentDragFinished by rememberUpdatedState(onDragFinished)
    val expanded = step.id !in draft.collapsedStepIds
    val category = step.blockCategory()
    val accent = category.accent()
    val selected = selection?.stepId == step.id
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(if (dragging) 2f else 0f)
            .graphicsLayer {
                this.translationY = translationY
                scaleX = liftScale
                scaleY = liftScale
                shadowElevation = if (dragging) with(density) { 14.dp.toPx() } else 0f
            }
            .onGloballyPositioned {
                topInWindow = it.positionInWindow().y
                onMeasured(StepBounds(it.size.height))
            },
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 22.dp)
                .pointerInput(step.id) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { currentDragStart(topInWindow + it.y) },
                        onDragEnd = { currentDragFinished(true) },
                        onDragCancel = { currentDragFinished(false) },
                        onDrag = { change, amount ->
                            change.consume()
                            currentDrag(amount.y, topInWindow + change.position.y)
                        },
                    )
                }
                .clickable {
                    onSelectionChange(BlockSelection(path, step.id))
                    onDraftChange(draft.toggleCollapsed(step.id))
                },
            shape = category.shape(),
            colors = CardDefaults.cardColors(containerColor = category.containerColor(selected)),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(category.icon, contentDescription = null, tint = accent)
                    Column(modifier = Modifier.weight(1f)) {
                        Text("${index + 1}. ${step.displayName()}", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (issues.isNotEmpty()) Text("Needs attention", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                    }
                    if (step.hasEditableGeometry()) {
                        IconButton(onClick = { onEditGeometry(path, index) }) {
                            Icon(Icons.Outlined.Edit, contentDescription = "Edit touch geometry")
                        }
                    }
                    if (step.conditionOrNull() != null) {
                        IconButton(onClick = { onEditCondition(path, index) }) {
                            Icon(Icons.Outlined.Tune, contentDescription = "Edit condition")
                        }
                    }
                    IconButton(onClick = { onDraftChange(draft.removeStep(path, index)) }) {
                        Icon(Icons.Outlined.Delete, contentDescription = "Remove block")
                    }
                }
                if (expanded) {
                    EditableStepSummary(draft, path, index, step, onDraftChange)
                    issues.forEach { issue ->
                        Text(issue.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    if (showInlineInspector) {
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
                        scrollState = scrollState,
                    )
                }
            }
        }
        Box(modifier = Modifier.matchParentSize()) {
            ExecutionRail(
                accent = accent,
                terminal = step is Stop,
                modifier = Modifier.align(Alignment.TopStart).width(22.dp).fillMaxHeight(),
            )
            if (index < draft.stepsAt(path).lastIndex && !dragging) {
                AddBlockMenu(
                    compact = true,
                    subdued = true,
                    modifier = Modifier.align(Alignment.BottomStart).offset(x = 2.dp, y = 24.dp).zIndex(3f),
                    onAdd = onInsertAfter,
                )
            }
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
    scrollState: androidx.compose.foundation.ScrollState,
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
            scrollState = scrollState,
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
            scrollState = scrollState,
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
                scrollState = scrollState,
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
                scrollState = scrollState,
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
    scrollState: androidx.compose.foundation.ScrollState,
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
                scrollState = scrollState,
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
private fun BlockCategory.containerColor(selected: Boolean): Color = accent().copy(alpha = if (selected) 0.28f else 0.14f)

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
private fun EditableStepSummary(
    draft: WorkflowDraft,
    path: StepListPath,
    index: Int,
    step: WorkflowStep,
    onDraftChange: (WorkflowDraft) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        when (step) {
            is Wait -> {
                Text("Wait", style = MaterialTheme.typography.bodyMedium)
                InlineLongField(step.durationMs.value, 0) { value ->
                    onDraftChange(draft.updateStep(path, index) { step.copy(durationMs = com.veyarc.model.DurationMillis(value)) })
                }
                Text("ms", style = MaterialTheme.typography.bodyMedium)
            }

            is WaitUntil -> {
                Text("${step.condition.summary()} · timeout", style = MaterialTheme.typography.bodyMedium)
                InlineLongField(step.timeoutMs.value, 0) { value ->
                    onDraftChange(draft.updateStep(path, index) { step.copy(timeoutMs = com.veyarc.model.DurationMillis(value)) })
                }
                Text("ms, poll every", style = MaterialTheme.typography.bodyMedium)
                InlineLongField(step.pollIntervalMs.value, 1) { value ->
                    onDraftChange(draft.updateStep(path, index) { step.copy(pollIntervalMs = com.veyarc.model.DurationMillis(value)) })
                }
                Text("ms", style = MaterialTheme.typography.bodyMedium)
            }

            is Repeat -> {
                InlineLongField(step.count.toLong(), 0, Int.MAX_VALUE.toLong()) { value ->
                    onDraftChange(draft.updateStep(path, index) { step.copy(count = value.toInt()) })
                }
                Text("iterations, ${step.bodySteps.size} nested blocks", style = MaterialTheme.typography.bodyMedium)
            }

            is WhileStep -> {
                Text("${step.condition.summary()} · up to", style = MaterialTheme.typography.bodyMedium)
                InlineLongField(step.maxIterations.toLong(), 1, Int.MAX_VALUE.toLong()) { value ->
                    onDraftChange(draft.updateStep(path, index) { step.copy(maxIterations = value.toInt()) })
                }
                Text("iterations, ${step.bodySteps.size} nested blocks", style = MaterialTheme.typography.bodyMedium)
            }

            is LaunchApp -> {
                Text("${step.packageName.value} · timeout", style = MaterialTheme.typography.bodyMedium)
                InlineLongField(step.foregroundTimeoutMs.value, 1) { value ->
                    onDraftChange(
                        draft.updateStep(path, index) {
                            step.copy(foregroundTimeoutMs = com.veyarc.model.DurationMillis(value))
                        },
                    )
                }
                Text("ms", style = MaterialTheme.typography.bodyMedium)
            }

            else -> Text(step.summary(), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun InlineLongField(
    value: Long,
    minimum: Long,
    maximum: Long = Long.MAX_VALUE,
    onValueChange: (Long) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    Box(Modifier.widthIn(min = 16.dp, max = 96.dp)) {
        Text(
            text.ifEmpty { "0" },
            color = Color.Transparent,
            style = MaterialTheme.typography.bodyMedium,
        )
        BasicTextField(
            value = text,
            onValueChange = { candidate ->
                if (candidate.all(Char::isDigit)) {
                    text = candidate
                    candidate.toLongOrNull()?.takeIf { it in minimum..maximum }?.let(onValueChange)
                }
            },
            modifier = Modifier.matchParentSize(),
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.primary,
                textDecoration = TextDecoration.Underline,
            ),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
        )
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
        is Wait,
        is Repeat,
        is WhileStep,
        is IfElse,
        is TapPoint,
        is TapRegion,
        is LongPress,
        is SwipePath,
        is WaitUntil,
        is LaunchApp,
        is Stop,
        -> Unit

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
    onAdd: (WorkflowStepKind) -> Unit,
    compact: Boolean = false,
    subdued: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        if (compact) {
            IconButton(onClick = { expanded = true }, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Outlined.Add,
                    contentDescription = "Add block here",
                    modifier = Modifier.size(16.dp),
                    tint = if (subdued) {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
        } else {
            OutlinedButton(onClick = { expanded = true }) { Text("Add block") }
        }
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

private fun String.packageNames(): List<String> = split(',', ' ', '\n', '\t')
    .map(String::trim)
    .filter(String::isNotEmpty)
    .distinct()

private fun WorkflowStep.conditionOrNull(): Condition? = when (this) {
    is WaitUntil -> condition
    is IfElse -> condition
    is WhileStep -> condition
    else -> null
}

private fun WorkflowStep.hasEditableGeometry(): Boolean = this is TapPoint || this is TapRegion || this is LongPress || this is SwipePath

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
