@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package com.veyarc.feature.editor

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Loop
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LocalPinnableContainer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.veyarc.automation.android.ReplayControllerWorkflow
import com.veyarc.automation.android.ReplayWorkflowCatalog
import com.veyarc.data.storage.RevisionSummary
import com.veyarc.data.storage.WorkflowRepository
import com.veyarc.data.storage.WorkflowRevisionDocument
import com.veyarc.data.storage.WorkflowRevisionEditorData
import com.veyarc.data.storage.WorkflowStorageException
import com.veyarc.model.AssetId
import com.veyarc.model.Condition
import com.veyarc.model.DisplayOrientation
import com.veyarc.model.IfElse
import com.veyarc.model.Jump
import com.veyarc.model.LaunchApp
import com.veyarc.model.LongPress
import com.veyarc.model.Repeat
import com.veyarc.model.StageBoundary
import com.veyarc.model.Stop
import com.veyarc.model.SwipePath
import com.veyarc.model.TapPoint
import com.veyarc.model.TapRegion
import com.veyarc.model.Wait
import com.veyarc.model.WaitUntil
import com.veyarc.model.WhileStep
import com.veyarc.model.WorkflowIds
import com.veyarc.model.WorkflowStep
import com.veyarc.model.bundle.WorkflowBundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun WorkflowEditorScreen(
    repository: WorkflowRepository,
    session: WorkflowEditorSession,
    onRunWorkflow: (WorkflowRevisionDocument) -> Unit,
    onOpenWorkflowManager: () -> Unit,
    onAskAi: (() -> Unit)? = null,
    initialBundle: WorkflowBundle? = null,
    initialStoredWorkflowId: String? = null,
    initialStoredRevision: Int? = null,
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
    var isOpening by remember { mutableStateOf(false) }
    var initialLoadFinished by remember(initialBundle, initialStoredWorkflowId, initialStoredRevision) { mutableStateOf(false) }
    var geometryTarget by session::geometryTarget
    var conditionTarget by session::conditionTarget
    var pendingTemplateCrop by session::pendingTemplateCrop
    var pendingClose by remember { mutableStateOf(false) }
    var pendingReplacement by remember { mutableStateOf<DraftReplacement?>(null) }
    var pendingOrientationSave by remember { mutableStateOf<PendingOrientationSave?>(null) }
    val blockClipboard = remember(context) { EditorBlockClipboard(context) }
    var clipboardAvailable by remember { mutableStateOf(false) }
    var pendingBlockPaste by remember { mutableStateOf<PendingBlockPaste?>(null) }

    LaunchedEffect(Unit) {
        clipboardAvailable = withContext(Dispatchers.IO) { runCatching { blockClipboard.read() != null }.getOrDefault(false) }
    }

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
                pendingTemplateCrop = TemplateCropDraft(source)
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
        clearTemplatePreviewCache()
        when (request) {
            is DraftReplacement.Revision -> {
                val currentRevision = session.draft
                    ?.takeIf { it.workflowId == request.draft.workflowId }
                    ?.latestKnownRevision ?: 0
                val opened = if (currentRevision > (request.draft.latestKnownRevision ?: 0)) {
                    request.draft.copy(latestKnownRevision = currentRevision)
                } else {
                    request.draft
                }
                session.openPrepared(opened, request.validation.takeIf { opened === request.draft })
                session.cacheRevisionSummaries(opened.workflowId, request.revisions.takeIf { opened === request.draft }.orEmpty())
                statusMessage = "Workflow opened."
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
            is DraftReplacement.Revision -> if (request.consumeInitialBundle) onInitialBundleConsumed()
            is DraftReplacement.New -> onNewWorkflowRequestConsumed()
        }
        pendingReplacement = null
    }

    suspend fun prepareRevision(
        revisions: List<RevisionSummary>,
        consumeInitial: Boolean,
        createDraft: () -> WorkflowDraft,
    ): DraftReplacement.Revision = withContext(Dispatchers.Default) {
        val prepared = createDraft()
        DraftReplacement.Revision(prepared, prepared.buildBundle(), revisions, consumeInitial)
    }

    suspend fun loadStoredRevision(
        workflowId: String,
        revision: Int,
        journalFileName: String?,
        consumeInitial: Boolean,
    ): DraftReplacement.Revision {
        val (data, revisions) = withContext(Dispatchers.IO) {
            repository.loadRevisionForEditor(workflowId, revision) to repository.listRevisions(workflowId)
        }
        val latest = maxOf(revision, revisions.maxOfOrNull(RevisionSummary::revision) ?: revision)
        return prepareRevision(revisions, consumeInitial) { WorkflowDraft.fromRevision(data, journalFileName, latest) }
    }

    LaunchedEffect(initialBundle, initialRecordingJournalFileName) {
        val bundle = initialBundle ?: return@LaunchedEffect
        val current = session.draft
        if (current?.workflowId == bundle.document.id && current.lastSavedRevision == bundle.document.revision) {
            initialLoadFinished = true
            onInitialBundleConsumed()
            return@LaunchedEffect
        }
        isBusy = true
        isOpening = true
        try {
            val revisions = withContext(Dispatchers.IO) { repository.listRevisions(bundle.document.id) }
            val latest = maxOf(bundle.document.revision, revisions.maxOfOrNull(RevisionSummary::revision) ?: 0)
            val request = prepareRevision(revisions, true) { WorkflowDraft.fromBundle(bundle, initialRecordingJournalFileName, latest) }
            if (session.isDirty) pendingReplacement = request else applyReplacement(request)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            statusMessage = "Could not open workflow: ${error.userMessage()}"
            onInitialBundleConsumed()
        } finally {
            isBusy = false
            isOpening = false
            initialLoadFinished = true
        }
    }

    LaunchedEffect(initialStoredWorkflowId, initialStoredRevision) {
        val workflowId = initialStoredWorkflowId ?: return@LaunchedEffect
        val revision = initialStoredRevision?.takeIf { it > 0 } ?: return@LaunchedEffect
        val current = session.draft
        if (current?.workflowId == workflowId && current.lastSavedRevision == revision) {
            initialLoadFinished = true
            onInitialBundleConsumed()
            return@LaunchedEffect
        }
        isBusy = true
        isOpening = true
        try {
            val request = loadStoredRevision(workflowId, revision, initialRecordingJournalFileName, true)
            if (session.isDirty) pendingReplacement = request else applyReplacement(request)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            statusMessage = "Could not open workflow: ${error.userMessage()}"
            onInitialBundleConsumed()
        } finally {
            isBusy = false
            isOpening = false
            initialLoadFinished = true
        }
    }

    LaunchedEffect(newWorkflowRequestId) {
        if (newWorkflowRequestId == 0) return@LaunchedEffect
        val request = DraftReplacement.New(viewportFor(context), newWorkflowFolderId)
        if (session.isDirty) pendingReplacement = request else applyReplacement(request)
    }

    LaunchedEffect(session.draft?.workflowId, session.draft?.lastSavedRevision) {
        val current = session.draft ?: return@LaunchedEffect
        if (session.cachedRevisionSummaries(current.workflowId, current.lastSavedRevision) != null) {
            return@LaunchedEffect
        }
        val summaries = if (current.lastSavedRevision == null) {
            emptyList()
        } else {
            withContext(Dispatchers.IO) { repository.listRevisions(current.workflowId) }
        }
        session.cacheRevisionSummaries(current.workflowId, summaries)
    }

    fun openRevision(revision: Int) {
        val current = session.draft ?: return
        if (isBusy || current.lastSavedRevision == revision) return
        scope.launch {
            isBusy = true
            isOpening = true
            statusMessage = null
            try {
                val request = loadStoredRevision(current.workflowId, revision, null, false)
                if (session.isDirty) pendingReplacement = request else applyReplacement(request)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                statusMessage = "Could not open revision: ${error.userMessage()}"
            } finally {
                isBusy = false
                isOpening = false
            }
        }
    }

    fun saveWorkflow(
        draft: WorkflowDraft,
        afterSave: ((WorkflowRevisionDocument) -> Unit)? = null,
        orientationConfirmed: Boolean = false,
    ) {
        if (session.draft !== draft) {
            statusMessage = "The draft changed. Review it before saving."
            return
        }
        val build = session.validationFor(draft)
        val document = build.document
        if (document == null) {
            statusMessage = build.issues.firstOrNull()?.message ?: "Workflow is not valid."
            return
        }
        val savedOrientation = session.savedOrientation
        if (!orientationConfirmed && savedOrientation != null && savedOrientation != document.viewport.recordedOrientation) {
            pendingOrientationSave = PendingOrientationSave(draft, afterSave)
            return
        }
        val sourceJournalFileName = session.draft?.recordingJournalFileName
        if (isBusy || !session.beginSave()) return
        isBusy = true
        scope.launch {
            try {
                statusMessage = null
                runCatching {
                    withContext(Dispatchers.IO) {
                        val latest = repository.listRevisions(draft.workflowId).maxOfOrNull { it.revision }
                        if (latest != null && latest > (draft.latestKnownRevision ?: 0)) {
                            throw WorkflowStorageException("A newer saved revision exists. Open it and review your changes before saving again.")
                        }
                        val sourceRevision = draft.lastSavedRevision
                        if (sourceRevision == null) {
                            val bundle = build.bundle
                                ?: throw WorkflowStorageException("Unsaved workflow assets are incomplete")
                            repository.saveRevision(bundle, session.destinationFolderId)
                        } else {
                            repository.saveRevisionFromAssetChanges(
                                sourceWorkflowId = draft.workflowId,
                                sourceRevision = sourceRevision,
                                document = document,
                                changedAssets = draft.assets,
                                destinationFolderId = session.destinationFolderId,
                            )
                        }
                        repository.loadRevisionForEditor(document.id, document.revision)
                    }
                }.onSuccess { stored ->
                    session.markSaved(WorkflowDraft.fromRevision(stored, sourceJournalFileName))
                    ReplayWorkflowCatalog.addOrReplace(
                        context,
                        ReplayControllerWorkflow(
                            workflowId = stored.revision.document.id,
                            revision = stored.revision.document.revision,
                            name = stored.revision.document.name,
                        ),
                    )
                    statusMessage = "Saved revision ${stored.revision.document.revision}."
                    afterSave?.invoke(stored.revision)
                }.onFailure { error ->
                    statusMessage = "Could not save revision: ${error.userMessage()}"
                }
            } finally {
                isBusy = false
            }
        }.invokeOnCompletion { session.finishSave() }
    }

    fun saveAndRun(draft: WorkflowDraft) {
        val revision = draft.lastSavedRevision
        if (!session.isDirty && revision != null) {
            scope.launch {
                isBusy = true
                statusMessage = null
                runCatching {
                    withContext(Dispatchers.IO) {
                        repository.loadRevisionDocument(draft.workflowId, revision)
                    }
                }.onSuccess(onRunWorkflow).onFailure { error ->
                    statusMessage = "Could not open workflow: ${error.userMessage()}"
                }
                isBusy = false
            }
        } else {
            saveWorkflow(draft, afterSave = onRunWorkflow)
        }
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

    suspend fun loadDraftAsset(assetId: com.veyarc.model.AssetId): com.veyarc.model.bundle.BundleAsset? {
        val current = session.draft ?: return null
        current.assets.firstOrNull { it.assetId == assetId }?.let { return it }
        val sourceRevision = current.lastSavedRevision ?: return null
        return runCatching {
            withContext(Dispatchers.IO) {
                repository.loadRevisionAsset(current.workflowId, sourceRevision, assetId)
            }
        }.getOrNull()
    }

    fun copyBlocks(path: StepListPath, ids: List<String>) {
        val current = session.draft ?: return
        val selected = current.stepsAt(path).filter { it.id in ids }
        if (selected.isEmpty()) return
        scope.launch {
            isBusy = true
            statusMessage = null
            runCatching {
                val images = selected.clipboardImageIds().map { id ->
                    val assetId = AssetId(id)
                    val asset = loadDraftAsset(assetId) ?: error("A referenced image is unavailable: ${current.assetNames[id] ?: id}")
                    asset to (current.assetNames[id] ?: "Image")
                }
                withContext(Dispatchers.IO) { blockClipboard.replace(current.viewport.recordedOrientation, selected, images) }
            }.onSuccess {
                clipboardAvailable = true
                statusMessage = "Copied ${selected.size} block${if (selected.size == 1) "" else "s"}."
            }.onFailure { error -> statusMessage = "Could not copy blocks: ${error.userMessage()}" }
            isBusy = false
        }
    }

    fun requestBlockPaste(path: StepListPath, index: Int) {
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { blockClipboard.read() } }
                .onSuccess { entry ->
                    if (entry == null) {
                        clipboardAvailable = false
                        statusMessage = "The block clipboard is empty."
                    } else {
                        pendingBlockPaste = PendingBlockPaste(path, index, entry)
                    }
                }.onFailure { error -> statusMessage = "Could not open block clipboard: ${error.userMessage()}" }
        }
    }

    fun confirmBlockPaste(importImages: Boolean) {
        val pending = pendingBlockPaste ?: return
        pendingBlockPaste = null
        scope.launch {
            isBusy = true
            statusMessage = null
            runCatching {
                val current = session.draft ?: error("No workflow is open")
                val existingIds = current.assetDescriptors.mapTo(mutableSetOf()) { it.assetId.value }
                val required = pending.entry.steps.clipboardImageIds()
                val imagesToImport = if (importImages) pending.entry.images.filter { it.id.value in required && it.id.value !in existingIds } else emptyList()
                val ids = imagesToImport.associate { it.id.value to AssetId(WorkflowIds.newUuid()) }
                val loaded = withContext(Dispatchers.IO) { imagesToImport.map { it to blockClipboard.importImage(it, requireNotNull(ids[it.id.value])) } }
                var updated = current.insertSteps(pending.path, pending.index, remapClipboardSteps(pending.entry.steps, ids))
                loaded.forEach { (image, asset) ->
                    updated = updated.addAsset(asset).renameAsset(asset.assetId, uniqueClipboardImageName(updated, image.name))
                }
                check(session.draft === current) { "The workflow changed while pasting. Try again." }
                session.update(updated)
                val omitted = required - existingIds - ids.keys
                statusMessage = if (omitted.isEmpty()) "Pasted ${pending.entry.steps.size} blocks." else "Pasted blocks. Add the missing images before saving."
            }.onFailure { error -> statusMessage = "Could not paste blocks: ${error.userMessage()}" }
            isBusy = false
        }
    }

    suspend fun loadDraftPreview(assetId: com.veyarc.model.AssetId, maxDimension: Int): TemplatePreviewData? {
        cachedTemplatePreview(assetId, maxDimension)?.let { return it }
        val current = session.draft ?: return null
        current.assets.firstOrNull { it.assetId == assetId }?.let { asset ->
            return withContext(Dispatchers.Default) { decodeTemplatePreview(asset, maxDimension) }
        }
        val sourceRevision = current.lastSavedRevision ?: return null
        val decoded = runCatching {
            withContext(Dispatchers.IO) {
                repository.loadRevisionAssetPreviewDecoded(current.workflowId, sourceRevision, assetId)
            }
        }.getOrNull() ?: return null
        return try {
            withContext(Dispatchers.Default) { decodeTemplatePreview(assetId, decoded, maxDimension) }
        } finally {
            decoded.clear()
        }
    }

    val draft = session.draft
    val awaitingInitial = !initialLoadFinished && (
        (
            initialStoredWorkflowId != null && initialStoredRevision != null &&
                (draft?.workflowId != initialStoredWorkflowId || draft.lastSavedRevision != initialStoredRevision)
            ) ||
            (initialBundle != null && (draft?.workflowId != initialBundle.document.id || draft.lastSavedRevision != initialBundle.document.revision))
        )
    val loading = isOpening || awaitingInitial
    val validation = draft?.takeUnless { loading }?.let(session::validationFor)
    if (loading) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator()
                Text("Opening workflow…")
            }
        }
    } else if (draft == null) {
        EmptyWorkflowEditorScreen(
            onCreate = {
                session.create(viewportFor(context))
                statusMessage = null
            },
            onOpenWorkflowManager = onOpenWorkflowManager,
            statusMessage = statusMessage,
            onAskAi = onAskAi?.let { openChat ->
                {
                    session.createForAssistant(viewportFor(context))
                    statusMessage = null
                    openChat()
                }
            },
            modifier = modifier,
        )
    } else {
        BackHandler { requestCloseDraft() }
        WorkflowDraftScreen(
            draft = draft,
            validation = requireNotNull(validation),
            isBusy = isBusy,
            statusMessage = statusMessage,
            session = session,
            onDraftChange = session::update,
            onAskAi = onAskAi?.takeUnless { isBusy },
            onSave = { saveWorkflow(draft) },
            onRun = { saveAndRun(draft) },
            onValidate = {
                statusMessage = validation.issues.firstOrNull()?.displayMessage()
                    ?: "Workflow is valid."
            },
            onEditGeometry = { path, index -> geometryTarget = GeometryTarget(path, index) },
            onEditCondition = { path, index -> conditionTarget = ConditionTarget(path, index) },
            onAddTemplate = { templatePicker.launch("image/*") },
            revisions = session.revisionSummaries,
            onOpenRevision = ::openRevision,
            onPickApplication = {
                appPicker.launch(
                    Intent(Intent.ACTION_PICK_ACTIVITY).putExtra(
                        Intent.EXTRA_INTENT,
                        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                    ),
                )
            },
            clipboardAvailable = clipboardAvailable,
            onCopyBlocks = ::copyBlocks,
            onPasteBlocks = ::requestBlockPaste,
            modifier = modifier,
        )
        pendingBlockPaste?.let { pending ->
            val missing = pending.entry.images.filter { image ->
                image.id.value in pending.entry.steps.clipboardImageIds() && draft.assetDescriptors.none { it.assetId == image.id }
            }
            AlertDialog(
                onDismissRequest = { pendingBlockPaste = null },
                title = { Text("Paste ${pending.entry.steps.size} blocks?") },
                text = {
                    Column {
                        if (pending.entry.orientation != draft.viewport.recordedOrientation) Text("These blocks were copied from a ${pending.entry.orientation.name.lowercase()} workflow. Check touch coordinates and images after pasting.")
                        if (missing.isNotEmpty()) Text("Add ${missing.size} referenced image${if (missing.size == 1) "" else "s"} to this workflow?")
                        Text("Packages, variables, and links to blocks outside the copied group may need review after pasting.")
                    }
                },
                confirmButton = { TextButton(onClick = { confirmBlockPaste(true) }) { Text(if (missing.isEmpty()) "Paste" else "Paste with images") } },
                dismissButton = {
                    if (missing.isEmpty()) {
                        TextButton(onClick = { pendingBlockPaste = null }) { Text("Cancel") }
                    } else {
                        TextButton(onClick = { confirmBlockPaste(false) }) { Text("Without images") }
                    }
                },
            )
        }
        geometryTarget?.let { target ->
            val targetStep = draft.stepsAt(target.path).getOrNull(target.index)
            if (targetStep != null) {
                GeometryEditorDialog(
                    step = targetStep,
                    viewport = draft.viewport,
                    maxPathPoints = draft.runLimits.maxPathPoints,
                    assets = draft.assetDescriptors,
                    assetNames = draft.assetNames,
                    loadAsset = ::loadDraftAsset,
                    loadPreview = ::loadDraftPreview,
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
                ConditionEditorDialog(
                    initialCondition = condition,
                    assets = draft.assetDescriptors,
                    assetNames = draft.assetNames,
                    defaultPackage = draft.allowedPackagesText.firstPackageOrDefault(),
                    onAddTemplate = { templatePicker.launch("image/*") },
                    loadAsset = ::loadDraftAsset,
                    loadPreview = ::loadDraftPreview,
                    previewWidthPx = draft.viewport.recordedWidthPx,
                    previewHeightPx = draft.viewport.recordedHeightPx,
                    onDismiss = { conditionTarget = null },
                    onApply = { updatedCondition ->
                        session.update(draft.updateCondition(target.path, target.index) { updatedCondition })
                        conditionTarget = null
                    },
                )
            }
        }
    }
    pendingTemplateCrop?.let { cropDraft ->
        val display = session.draft?.viewport ?: viewportFor(context)
        TemplateCropEditorDialog(
            draft = cropDraft,
            displayWidthPx = display.recordedWidthPx,
            displayHeightPx = display.recordedHeightPx,
            existingNames = session.draft?.activeAssetNames().orEmpty(),
            onDraftChange = { pendingTemplateCrop = it },
            onDismiss = { pendingTemplateCrop = null },
            onApply = { crop, rotationQuarterTurns, requestedName ->
                scope.launch {
                    isBusy = true
                    statusMessage = null
                    runCatching {
                        withContext(Dispatchers.Default) {
                            importTemplateAsset(context.contentResolver, cropDraft.source.uri, crop, rotationQuarterTurns)
                        }
                    }.onSuccess { asset ->
                        val prefix = if (crop == FULL_TEMPLATE_CROP) "Full screen" else "Crop"
                        clearTemplatePreviewCache()
                        session.draft?.addAsset(asset, prefix)?.let { withAsset ->
                            session.update(
                                if (requestedName.isBlank()) withAsset else withAsset.renameAsset(asset.assetId, requestedName),
                            )
                        }
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

    if (pendingClose) {
        UnsavedDraftDialog(
            title = "Close this workflow?",
            validation = validation,
            isBusy = isBusy,
            onSave = { session.draft?.let { saveWorkflow(it, afterSave = { discardDraft() }) } },
            onDiscard = ::discardDraft,
            onCancel = { pendingClose = false },
        )
    }
    pendingReplacement?.let { request ->
        UnsavedDraftDialog(
            title = "Replace unsaved changes?",
            validation = validation,
            isBusy = isBusy,
            onSave = { session.draft?.let { saveWorkflow(it, afterSave = { applyReplacement(request) }) } },
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
                    "This revision changes the workflow to ${pending.draft.viewport.recordedOrientation.name.lowercase()}. Existing coordinates may need review.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingOrientationSave = null
                    saveWorkflow(pending.draft, pending.afterSave, orientationConfirmed = true)
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { pendingOrientationSave = null }) { Text("Keep editing") }
            },
        )
    }
}

private data class PendingOrientationSave(
    val draft: WorkflowDraft,
    val afterSave: ((WorkflowRevisionDocument) -> Unit)?,
)

private sealed interface DraftReplacement {
    data class Revision(
        val draft: WorkflowDraft,
        val validation: DraftBuildResult,
        val revisions: List<RevisionSummary>,
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
            TextButton(onClick = onSave, enabled = !isBusy && validation?.document != null) {
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

@Composable
private fun EmptyWorkflowEditorScreen(
    onCreate: () -> Unit,
    statusMessage: String? = null,
    onOpenWorkflowManager: () -> Unit,
    onAskAi: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val compact = maxHeight < 420.dp
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, top = if (compact) 8.dp else 20.dp, end = 20.dp)
                    .heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Workflow Editor", style = MaterialTheme.typography.headlineSmall)
            }
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Spacer(Modifier.height(12.dp))
                Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().padding(if (compact) 16.dp else 24.dp), verticalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 16.dp)) {
                        if (!compact) Icon(Icons.Outlined.Tune, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                        Text("Build your next workflow", style = MaterialTheme.typography.titleLarge)
                        Text("Start fresh or open a saved workflow.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FlowRow(Modifier.fillMaxWidth(), maxItemsInEachRow = if (compact) 2 else 1, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = onCreate, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                                Icon(Icons.Outlined.Add, null, Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("New workflow")
                            }
                            OutlinedButton(onClick = onOpenWorkflowManager, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                                Icon(Icons.Outlined.FolderOpen, null, Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Choose workflow")
                            }
                        }
                    }
                }
                if (onAskAi != null) {
                    Surface(onClick = onAskAi, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Icon(Icons.Outlined.ChatBubbleOutline, null, tint = MaterialTheme.colorScheme.primary)
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Create with AI", style = MaterialTheme.typography.titleMedium)
                                Text("Describe what you want to automate.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                statusMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
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
    onAskAi: (() -> Unit)?,
    onSave: () -> Unit,
    onRun: () -> Unit,
    onValidate: () -> Unit,
    onEditGeometry: (StepListPath, Int) -> Unit,
    onEditCondition: (StepListPath, Int) -> Unit,
    onAddTemplate: () -> Unit,
    revisions: List<RevisionSummary>,
    onOpenRevision: (Int) -> Unit,
    onPickApplication: () -> Unit,
    clipboardAvailable: Boolean,
    onCopyBlocks: (StepListPath, List<String>) -> Unit,
    onPasteBlocks: (StepListPath, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selection by remember { mutableStateOf<BlockSelection?>(null) }
    var selectionMode by remember(draft.workflowId) { mutableStateOf(false) }
    var selectedPath by remember(draft.workflowId) { mutableStateOf<StepListPath?>(null) }
    var selectedIds by remember(draft.workflowId) { mutableStateOf<Set<String>>(emptySet()) }
    val clipboardActions = BlockClipboardActions(
        clipboardAvailable = clipboardAvailable,
        selectionMode = selectionMode,
        selectedIds = selectedIds,
        canCopy = if (selectionMode) selectedIds.isNotEmpty() else selection != null,
        onToggleSelectionMode = {
            selectionMode = !selectionMode
            selectedIds = emptySet()
            selectedPath = null
        },
        onCopy = {
            val path = if (selectionMode) selectedPath else selection?.path
            val ids = if (selectionMode) selectedIds.toList() else listOfNotNull(selection?.stepId)
            if (path != null && ids.isNotEmpty()) {
                onCopyBlocks(path, ids)
                selectionMode = false
                selectedIds = emptySet()
            }
        },
        onSelect = { chosen ->
            if (selectionMode) {
                if (selectedPath != chosen.path) {
                    selectedPath = chosen.path
                    selectedIds = setOf(chosen.stepId)
                } else {
                    selectedIds = if (chosen.stepId in selectedIds) selectedIds - chosen.stepId else selectedIds + chosen.stepId
                }
            } else {
                selection = chosen
            }
        },
        onPaste = onPasteBlocks,
    )
    val scrollHeader = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    CompositionLocalProvider(LocalBlockClipboardActions provides clipboardActions) {
        Column(modifier = modifier.fillMaxSize()) {
            if (!scrollHeader) {
                WorkflowTitleEditor(
                    draft = draft,
                    onDraftChange = onDraftChange,
                    onAskAi = onAskAi,
                    modifier = Modifier.padding(start = 20.dp, top = 20.dp, end = 20.dp),
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
                            onAskAi = onAskAi,
                            onSelectionChange = clipboardActions.onSelect,
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
                            scrollHeader = scrollHeader,
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
                        onAskAi = onAskAi,
                        onSelectionChange = clipboardActions.onSelect,
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
                        scrollHeader = scrollHeader,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

@Composable
private fun WorkflowTitleEditor(
    draft: WorkflowDraft,
    onDraftChange: (WorkflowDraft) -> Unit,
    onAskAi: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = draft.name,
            onValueChange = { onDraftChange(draft.copy(name = it)) },
            modifier = Modifier.weight(1f).testTag("workflow-title"),
            singleLine = true,
            textStyle = MaterialTheme.typography.headlineSmall.copy(color = MaterialTheme.colorScheme.onSurface),
            decorationBox = { field ->
                if (draft.name.isBlank()) {
                    Text("Workflow title", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                field()
            },
        )
        if (onAskAi != null) {
            Surface(onClick = onAskAi, shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = "Ask AI", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

private data class BlockSelection(val path: StepListPath, val stepId: String)

private data class PendingBlockPaste(val path: StepListPath, val index: Int, val entry: BlockClipboardEntry)

private data class BlockClipboardActions(
    val clipboardAvailable: Boolean,
    val selectionMode: Boolean,
    val selectedIds: Set<String>,
    val canCopy: Boolean,
    val onToggleSelectionMode: () -> Unit,
    val onCopy: () -> Unit,
    val onSelect: (BlockSelection) -> Unit,
    val onPaste: (StepListPath, Int) -> Unit,
)

private val LocalBlockClipboardActions = compositionLocalOf<BlockClipboardActions?> { null }

private fun uniqueClipboardImageName(draft: WorkflowDraft, requested: String): String {
    val base = requested.trim().take(48).ifEmpty { "Image" }
    val used = draft.activeAssetNames().mapTo(mutableSetOf()) { it.lowercase() }
    if (base.lowercase() !in used) return base
    var suffix = 2
    while ("$base $suffix".lowercase() in used) suffix++
    return "$base $suffix"
}

internal data class StepBounds(val topInWindow: Float, val height: Int) {
    val centerInWindow: Float
        get() = topInWindow + height / 2f
}

internal data class StepLocation(val path: StepListPath, val stepId: String)

internal data class StepDragSource(
    val path: StepListPath,
    val stepId: String,
    val height: Int,
)

internal data class StepDropTarget(val path: StepListPath, val index: Int)

internal class StepDragCoordinator {
    val pipelineBounds = mutableMapOf<StepListPath, Rect>()
    val stepBounds = mutableMapOf<StepLocation, StepBounds>()
    var source by mutableStateOf<StepDragSource?>(null)
    var target by mutableStateOf<StepDropTarget?>(null)
    private var heldTopInWindow by mutableFloatStateOf(0f)
    private var sourceLayoutTopInWindow by mutableFloatStateOf(0f)
    val dragOffsetY: Float
        get() = heldTopInWindow - sourceLayoutTopInWindow
    var pointerWindowX by mutableFloatStateOf(0f)
    var pointerWindowY by mutableFloatStateOf(0f)

    fun start(path: StepListPath, stepId: String, pointer: Offset, draft: WorkflowDraft) {
        val bounds = stepBounds[StepLocation(path, stepId)] ?: return
        source = StepDragSource(path, stepId, bounds.height)
        heldTopInWindow = bounds.topInWindow
        sourceLayoutTopInWindow = bounds.topInWindow
        pointerWindowX = pointer.x
        pointerWindowY = pointer.y
        updateTarget(draft)
    }

    fun drag(delta: Offset, draft: WorkflowDraft) {
        heldTopInWindow += delta.y
        pointerWindowX += delta.x
        pointerWindowY += delta.y
        updateTarget(draft)
    }

    fun updatePipeline(path: StepListPath, bounds: Rect, draft: WorkflowDraft) {
        pipelineBounds[path] = bounds
        if (source != null) updateTarget(draft)
    }

    fun updateStep(path: StepListPath, stepId: String, bounds: StepBounds, draft: WorkflowDraft) {
        stepBounds[StepLocation(path, stepId)] = bounds
        if (source?.let { it.path == path && it.stepId == stepId } == true) {
            sourceLayoutTopInWindow = bounds.topInWindow
        }
        if (source != null) updateTarget(draft)
    }

    fun removeStep(path: StepListPath, stepId: String) {
        stepBounds.remove(StepLocation(path, stepId))
    }

    fun removePipeline(path: StepListPath) {
        pipelineBounds.remove(path)
        stepBounds.keys.filter { it.path == path }.forEach(stepBounds::remove)
        if (target?.path == path) target = null
    }

    fun end() {
        source = null
        target = null
        heldTopInWindow = 0f
        sourceLayoutTopInWindow = 0f
        pointerWindowX = 0f
        pointerWindowY = 0f
    }

    private fun updateTarget(draft: WorkflowDraft) {
        val active = source ?: return
        val pointer = Offset(pointerWindowX, pointerWindowY)
        val destinationPath = pipelineBounds.entries
            .asSequence()
            .filter { (_, bounds) -> bounds.contains(pointer) }
            .filterNot { (path, _) -> path.segments.any { it.parentId == active.stepId } }
            .sortedWith(
                compareByDescending<Map.Entry<StepListPath, Rect>> { it.key.segments.size }
                    .thenBy { it.value.height },
            )
            .firstOrNull()
            ?.key
            ?: active.path
        val candidates = draft.stepsAt(destinationPath).filterNot { step ->
            destinationPath == active.path && step.id == active.stepId
        }
        // Offscreen lazy items have no bounds. Keep their actual indices when locating an insertion.
        val measured = candidates.mapIndexedNotNull { index, step ->
            stepBounds[StepLocation(destinationPath, step.id)]?.let { index to it.centerInWindow }
        }
        val index = measured.firstOrNull { (_, center) -> center >= pointerWindowY }?.first
            ?: measured.lastOrNull()?.let { it.first + 1 }
            ?: if (destinationPath == active.path) {
                draft.stepsAt(active.path).indexOfFirst { it.id == active.stepId }
            } else {
                candidates.size
            }
        target = StepDropTarget(destinationPath, index.coerceIn(0, candidates.size))
    }
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
    onAskAi: (() -> Unit)?,
    onSelectionChange: (BlockSelection) -> Unit,
    onDraftChange: (WorkflowDraft) -> Unit,
    onSave: () -> Unit,
    onRun: () -> Unit,
    onValidate: () -> Unit,
    onEditGeometry: (StepListPath, Int) -> Unit,
    onEditCondition: (StepListPath, Int) -> Unit,
    onAddTemplate: () -> Unit,
    revisions: List<RevisionSummary>,
    onOpenRevision: (Int) -> Unit,
    onPickApplication: () -> Unit,
    scrollHeader: Boolean,
    modifier: Modifier = Modifier,
) {
    val scrollState = key(draft.workflowId) { rememberLazyListState() }
    val stepDragCoordinator = remember(draft.workflowId) { StepDragCoordinator() }
    val root = remember { StepListPath() }
    DisposableEffect(stepDragCoordinator) {
        onDispose {
            stepDragCoordinator.end()
            stepDragCoordinator.removePipeline(root)
        }
    }
    LaunchedEffect(stepDragCoordinator.source?.stepId, stepDragCoordinator.pointerWindowY) {
        while (stepDragCoordinator.source != null) {
            val bounds = stepDragCoordinator.pipelineBounds[root] ?: break
            val margin = bounds.height * 0.16f
            val scrollDelta = when {
                stepDragCoordinator.pointerWindowY < bounds.top + margin -> -20f
                stepDragCoordinator.pointerWindowY > bounds.bottom - margin -> 20f
                else -> break
            }
            withFrameNanos { }
            if (scrollState.scrollBy(scrollDelta) == 0f) break
        }
    }
    val horizontalPadding = 16.dp
    val verticalPadding = 12.dp
    val itemSpacing = 12.dp
    val density = LocalDensity.current
    var toolbarHeight by remember(density) { mutableIntStateOf(with(density) { 140.dp.roundToPx() }) }
    Box(modifier = modifier) {
        LazyColumn(
            state = scrollState,
            modifier = Modifier.fillMaxSize().onGloballyPositioned {
                val bounds = it.boundsInWindow()
                // The floating toolbar covers the top of the list; drag scrolling uses the visible area.
                val visible = if (scrollHeader) bounds else bounds.copy(top = minOf(bounds.bottom, bounds.top + toolbarHeight))
                stepDragCoordinator.updatePipeline(root, visible, draft)
            }.testTag("workflow-block-list"),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = horizontalPadding,
                end = horizontalPadding,
                top = if (scrollHeader) 0.dp else with(density) { toolbarHeight.toDp() },
                bottom = verticalPadding,
            ),
            verticalArrangement = Arrangement.spacedBy(itemSpacing),
        ) {
            item(key = "editor-header", contentType = "header") {
                Column(verticalArrangement = Arrangement.spacedBy(itemSpacing)) {
                    if (scrollHeader) {
                        WorkflowTitleEditor(
                            draft = draft,
                            onDraftChange = onDraftChange,
                            onAskAi = onAskAi,
                            modifier = Modifier.padding(top = verticalPadding),
                        )
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
                    }
                    AllowedApplications(
                        packages = draft.allowedPackagesText,
                        onPickApplication = onPickApplication,
                        onReorderApplications = { onDraftChange(draft.copy(allowedPackagesText = it.joinToString(", "))) },
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
                        assets = draft.assetDescriptors,
                        assetNames = draft.assetNames,
                        referencedAssetIds = draft.referencedAssetIds(),
                        onAddTemplate = onAddTemplate,
                        onRemoveTemplate = { assetId ->
                            clearTemplatePreviewCache()
                            onDraftChange(draft.removeAsset(assetId))
                        },
                        onRenameTemplate = { assetId, name -> onDraftChange(draft.renameAsset(assetId, name)) },
                    )
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Execution", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                        Text("${draft.steps.size} blocks", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (draft.steps.isEmpty()) {
                item(key = "empty-blocks") { Text("No blocks yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            itemsIndexed(draft.steps, key = { _, step -> step.id }, contentType = { _, step -> step::class }) { index, step ->
                StepEditorItem(
                    draft = draft,
                    path = root,
                    index = index,
                    step = step,
                    validation = validation,
                    selection = selection,
                    showInlineInspector = showInlineInspector,
                    onSelectionChange = onSelectionChange,
                    onDraftChange = onDraftChange,
                    onEditGeometry = onEditGeometry,
                    onEditCondition = onEditCondition,
                    dragCoordinator = stepDragCoordinator,
                )
            }
            item(key = "add-block", contentType = "footer") {
                StepListFooter(draft, root, onDraftChange, stepDragCoordinator)
            }
            item(key = "editor-status", contentType = "footer") {
                statusMessage?.let { message -> Text(message, color = MaterialTheme.colorScheme.primary) }
            }
        }
        if (!scrollHeader) {
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
                modifier = Modifier.align(Alignment.TopCenter).onSizeChanged { toolbarHeight = it.height }
                    .padding(horizontal = horizontalPadding, vertical = verticalPadding),
            )
        }
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
    onSave: () -> Unit,
    onRun: () -> Unit,
    onValidate: () -> Unit,
    revisions: List<RevisionSummary>,
    onOpenRevision: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var validationDetailsVisible by remember { mutableStateOf(false) }
    val clipboard = LocalBlockClipboardActions.current
    Surface(modifier = modifier.testTag("workflow-toolbar"), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.92f), tonalElevation = 0.dp) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                    IconButton(onClick = session::undo, enabled = session.canUndo && !isBusy) {
                        Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = "Undo")
                    }
                    IconButton(onClick = session::redo, enabled = session.canRedo && !isBusy) {
                        Icon(Icons.AutoMirrored.Outlined.Redo, contentDescription = "Redo")
                    }
                    IconButton(onClick = { clipboard?.onToggleSelectionMode?.invoke() }, enabled = !isBusy) {
                        Icon(
                            Icons.Outlined.SelectAll,
                            contentDescription = if (clipboard?.selectionMode == true) "Leave block selection" else "Select blocks to copy",
                            tint = if (clipboard?.selectionMode == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    IconButton(
                        onClick = { clipboard?.onCopy?.invoke() },
                        enabled = !isBusy && clipboard?.canCopy == true,
                    ) { Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy selected blocks") }
                }
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
                    onClick = onRun,
                    enabled = !isBusy && validation.document != null,
                ) { Icon(Icons.Outlined.PlayArrow, contentDescription = "Run workflow") }
                IconButton(
                    onClick = onSave,
                    enabled = !isBusy && validation.document != null && (session.isDirty || session.requiresRevisionSave),
                ) {
                    if (isBusy) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Outlined.Save, contentDescription = "Save revision ${draft.nextRevision}")
                    }
                }
            }
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
                        Text(issue.displayMessage())
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { validationDetailsVisible = false }) { Text("Done") }
            },
        )
    }
}

private fun DraftIssue.displayMessage(): String = if (path == "/assets" && message.endsWith(" is still used")) {
    message
} else {
    "$path: $message"
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
            onClick = { expanded = !expanded },
            enabled = revisions.isNotEmpty(),
            modifier = Modifier.fillMaxSize().semantics { contentDescription = "Choose workflow revision" },
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Box(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                Text(
                    currentRevision?.let { "Revision $it" + if (dirty) " •" else "" } ?: "Unsaved",
                )
            }
        }
        if (expanded) {
            VeyarcChoicePicker(
                title = "Choose revision",
                options = revisions.map { VeyarcChoice(it.revision.toString(), "Revision ${it.revision}") },
                selectedId = currentRevision?.toString(),
                onDismiss = { expanded = false },
                onSelect = { choice ->
                    expanded = false
                    onOpenRevision(choice.id.toInt())
                },
            )
        }
    }
}

@Composable
private fun AllowedApplications(
    packages: String,
    onPickApplication: () -> Unit,
    onRemoveApplication: (String) -> Unit,
    onReorderApplications: (List<String>) -> Unit,
) {
    val packageNames = remember(packages) { packages.packageNames() }
    var expanded by rememberSaveable { mutableStateOf(true) }
    EditorSetupSection("Allowed apps", Icons.Outlined.Apps, expanded, { expanded = !expanded }, onPickApplication, "Add allowed app") {
        if (packageNames.isEmpty()) {
            Text("No apps added.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            AllowedApplicationRow(packageNames, onRemoveApplication, onReorderApplications)
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
    assets: List<com.veyarc.data.storage.WorkflowAssetDescriptor>,
    assetNames: Map<String, String>,
    referencedAssetIds: Set<String>,
    onAddTemplate: () -> Unit,
    onRemoveTemplate: (com.veyarc.model.AssetId) -> Unit,
    onRenameTemplate: (com.veyarc.model.AssetId, String) -> Unit,
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(true) }
    var renameTarget by remember { mutableStateOf<com.veyarc.model.AssetId?>(null) }
    EditorSetupSection("Image templates", Icons.Outlined.Image, expanded, { expanded = !expanded }, onAddTemplate, "Add image template") {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (assets.isEmpty()) {
                Text("No templates added.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                assets.forEachIndexed { index, asset ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { renameTarget = asset.assetId },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                assetNames[asset.assetId.value] ?: "Template ${index + 1}",
                                color = if (asset.assetId.value in referencedAssetIds) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.82f)
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                            Text(
                                listOfNotNull(
                                    asset.addedAtMs?.let { "Added ${java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(it))}" },
                                    asset.byteCount?.let { android.text.format.Formatter.formatShortFileSize(context, it) },
                                ).joinToString(" · "),
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
private fun EditorSetupSection(
    title: String,
    icon: ImageVector,
    expanded: Boolean,
    onToggle: () -> Unit,
    onAdd: () -> Unit,
    addDescription: String,
    content: @Composable () -> Unit,
) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).heightIn(min = 48.dp).clickable(interactionSource = null, indication = null, onClick = onToggle), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                }
                IconButton(onClick = onAdd) { Icon(Icons.Outlined.Add, addDescription, tint = MaterialTheme.colorScheme.primary) }
            }
            AnimatedVisibility(expanded, enter = fadeIn(tween(120)) + expandVertically(tween(120)), exit = fadeOut(tween(100)) + shrinkVertically(tween(100))) {
                Box(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 12.dp)) { content() }
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
    dragCoordinator: StepDragCoordinator,
) {
    DisposableEffect(path) {
        onDispose { dragCoordinator.removePipeline(path) }
    }
    Column(
        modifier = Modifier.onGloballyPositioned { coordinates ->
            dragCoordinator.updatePipeline(path, coordinates.boundsInWindow(), draft)
        },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(heading, style = MaterialTheme.typography.titleMedium)
        val steps = draft.stepsAt(path)
        if (steps.isEmpty()) Text("No blocks in this branch yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        steps.forEachIndexed { index, step ->
            key(step.id) {
                StepEditorItem(
                    draft, path, index, step, validation, selection, showInlineInspector,
                    onSelectionChange, onDraftChange, onEditGeometry, onEditCondition, dragCoordinator,
                )
            }
        }
        StepListFooter(draft, path, onDraftChange, dragCoordinator)
    }
}

@Composable
private fun StepEditorItem(
    draft: WorkflowDraft,
    path: StepListPath,
    index: Int,
    step: WorkflowStep,
    validation: DraftBuildResult,
    selection: BlockSelection?,
    showInlineInspector: Boolean,
    onSelectionChange: (BlockSelection) -> Unit,
    onDraftChange: (WorkflowDraft) -> Unit,
    onEditGeometry: (StepListPath, Int) -> Unit,
    onEditCondition: (StepListPath, Int) -> Unit,
    dragCoordinator: StepDragCoordinator,
) {
    val steps = draft.stepsAt(path)
    val activeDrag = dragCoordinator.source
    val dropTarget = dragCoordinator.target
    val sourceIndex = if (activeDrag?.path == path) steps.indexOfFirst { it.id == activeDrag.stepId } else -1
    val targetIndex = if (dropTarget?.path == path) dropTarget.index else -1
    val density = androidx.compose.ui.platform.LocalDensity.current
    val shiftDistance = (activeDrag?.height ?: 0) + with(density) { (if (path.segments.isEmpty()) 12.dp else 8.dp).toPx() }
    val container = LocalPinnableContainer.current
    val holdsDrag = activeDrag?.stepId == step.id || activeDrag?.path?.segments?.any { it.parentId == step.id } == true
    DisposableEffect(container, holdsDrag) {
        val handle = if (holdsDrag) container?.pin() else null
        onDispose { handle?.release() }
    }
    DisposableEffect(path, step.id) {
        onDispose { dragCoordinator.removeStep(path, step.id) }
    }
    val issuePrefix = draft.modelPath(path, index)
    val neighborShift = when {
        activeDrag == null -> 0f

        activeDrag.path == path && dropTarget?.path == path &&
            sourceIndex < targetIndex && index in (sourceIndex + 1)..targetIndex -> -shiftDistance

        activeDrag.path == path && dropTarget?.path == path &&
            sourceIndex > targetIndex && index in targetIndex until sourceIndex -> shiftDistance

        activeDrag.path == path && dropTarget?.path != path && index > sourceIndex -> -shiftDistance

        activeDrag.path != path && dropTarget?.path == path && index >= targetIndex -> shiftDistance

        else -> 0f
    }
    val animatedShift by animateFloatAsState(neighborShift, tween(100), label = "block insertion")
    CompositionLocalProvider(LocalRippleConfiguration provides null) {
        Box(Modifier.testTag("workflow-block-${step.id}")) {
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
                dragCoordinator = dragCoordinator,
                translationY = when {
                    step.id == activeDrag?.stepId -> dragCoordinator.dragOffsetY
                    activeDrag != null -> animatedShift
                    else -> 0f
                },
                dragging = step.id == activeDrag?.stepId,
                onMeasured = { dragCoordinator.updateStep(path, step.id, it, draft) },
                onDragStart = { initialPointerY ->
                    onSelectionChange(BlockSelection(path, step.id))
                    dragCoordinator.start(path, step.id, initialPointerY, draft)
                },
                onDrag = { delta ->
                    dragCoordinator.drag(delta, draft)
                },
                onDragFinished = { commit ->
                    val source = dragCoordinator.source
                    val target = dragCoordinator.target
                    if (commit && source != null && target != null) {
                        val from = draft.stepsAt(source.path).indexOfFirst { it.id == source.stepId }
                        if (from >= 0) {
                            val moved = draft.moveStepTo(source.path, from, target.path, target.index)
                            if (moved != draft) {
                                onDraftChange(moved)
                                onSelectionChange(BlockSelection(target.path, source.stepId))
                            }
                        }
                    }
                    dragCoordinator.end()
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
}

@Composable
private fun StepListFooter(
    draft: WorkflowDraft,
    path: StepListPath,
    onDraftChange: (WorkflowDraft) -> Unit,
    dragCoordinator: StepDragCoordinator,
) {
    val clipboard = LocalBlockClipboardActions.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val steps = draft.stepsAt(path)
    val activeDrag = dragCoordinator.source
    val dropTarget = dragCoordinator.target
    val targetIndex = if (dropTarget?.path == path) dropTarget.index else -1
    val shiftDistance = (activeDrag?.height ?: 0) + with(density) { (if (path.segments.isEmpty()) 12.dp else 8.dp).toPx() }
    val bottomGap by animateDpAsState(
        targetValue = if (
            activeDrag != null &&
            steps.isNotEmpty() &&
            activeDrag.path != path &&
            dropTarget?.path == path &&
            targetIndex == steps.size
        ) {
            with(density) { shiftDistance.toDp() }
        } else {
            0.dp
        },
        animationSpec = tween(100),
        label = "nested block insertion gap",
    )
    Column(Modifier.fillMaxWidth()) {
        if (bottomGap > 0.dp) Spacer(Modifier.height(bottomGap))
        AddBlockMenu(
            compact = true,
            canAddJump = steps.isNotEmpty(),
            modifier = Modifier.align(Alignment.CenterHorizontally).offset(y = (-10).dp),
            onAdd = { kind -> onDraftChange(draft.addStep(path, kind, draft.allowedPackagesText.firstPackageOrDefault())) },
            onPaste = { clipboard?.onPaste?.invoke(path, steps.size) },
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
    dragCoordinator: StepDragCoordinator,
    translationY: Float,
    dragging: Boolean,
    onMeasured: (StepBounds) -> Unit,
    onDragStart: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragFinished: (Boolean) -> Unit,
    onInsertAfter: (WorkflowStepKind) -> Unit,
) {
    val clipboard = LocalBlockClipboardActions.current
    var positionInWindow by remember(step.id) { mutableStateOf(Offset.Zero) }
    var dragHandlePositionInWindow by remember(step.id) { mutableStateOf(Offset.Zero) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val liftScale by animateFloatAsState(if (dragging) 1.025f else 1f, tween(100), label = "block lift")
    val currentDragStart by rememberUpdatedState(onDragStart)
    val currentDrag by rememberUpdatedState(onDrag)
    val currentDragFinished by rememberUpdatedState(onDragFinished)
    val deleteZoneWidthPx = with(density) { 48.dp.toPx() }
    var overDelete by remember(step.id) { mutableStateOf(false) }
    val currentOverDelete by rememberUpdatedState(overDelete)
    val currentDelete by rememberUpdatedState { onDraftChange(draft.removeStep(path, index)) }
    val expanded = step.id !in draft.collapsedStepIds
    val category = step.blockCategory()
    val accent = category.accent()
    val selected = if (clipboard?.selectionMode == true) step.id in clipboard.selectedIds else selection?.stepId == step.id
    val containsDraggedBlock = dragCoordinator.source?.path?.segments?.any { it.parentId == step.id } == true
    if (step is StageBoundary) {
        StageBoundaryRow(
            draft = draft,
            path = path,
            index = index,
            translationY = translationY,
            dragging = dragging,
            onSelectionChange = onSelectionChange,
            onDraftChange = onDraftChange,
            onMeasured = onMeasured,
            onDragStart = onDragStart,
            onDrag = onDrag,
            onDragFinished = onDragFinished,
            onInsertAfter = onInsertAfter,
        )
        return
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(if (dragging || containsDraggedBlock) 2f else 0f)
            .onGloballyPositioned {
                positionInWindow = it.positionInWindow()
                onMeasured(StepBounds(positionInWindow.y, it.size.height))
            }
            .graphicsLayer {
                this.translationY = translationY
                scaleX = liftScale
                scaleY = liftScale
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 22.dp)
                .background(category.containerColor(selected), category.shape())
                .clickable {
                    onSelectionChange(BlockSelection(path, step.id))
                    if (clipboard?.selectionMode != true) onDraftChange(draft.toggleCollapsed(step.id))
                },
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { dragHandlePositionInWindow = it.positionInWindow() }
                        .pointerInput(step.id) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    overDelete = false
                                    currentDragStart(dragHandlePositionInWindow + it)
                                },
                                onDragEnd = {
                                    if (currentOverDelete) {
                                        currentDragFinished(false)
                                        currentDelete()
                                    } else {
                                        currentDragFinished(true)
                                    }
                                    overDelete = false
                                },
                                onDragCancel = {
                                    overDelete = false
                                    currentDragFinished(false)
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    overDelete = isOverDeleteTarget(change.position.x, size.width, deleteZoneWidthPx)
                                    currentDrag(amount)
                                },
                            )
                        },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(category.icon, contentDescription = null, tint = accent)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "${executionNumberAt(draft.stepsAt(path), index)}. ${step.displayName()}",
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
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
                    Box(
                        modifier = Modifier.background(
                            if (dragging && overDelete) MaterialTheme.colorScheme.errorContainer else Color.Transparent,
                            CircleShape,
                        ),
                    ) {
                        IconButton(onClick = { onDraftChange(draft.removeStep(path, index)) }) {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = "Remove block",
                                tint = if (dragging && overDelete) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
                AnimatedVisibility(expanded, enter = expandVertically(tween(140)) + fadeIn(tween(90)), exit = shrinkVertically(tween(120)) + fadeOut(tween(70))) {
                    Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                            dragCoordinator = dragCoordinator,
                        )
                    }
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
                    canAddJump = draft.stepsAt(path).size > 1 || step !is Jump,
                    modifier = Modifier.align(Alignment.BottomStart).offset(x = 2.dp, y = 24.dp).zIndex(3f),
                    onAdd = onInsertAfter,
                    onPaste = { clipboard?.onPaste?.invoke(path, index + 1) },
                )
            }
        }
    }
}

@Composable
private fun StageBoundaryRow(
    draft: WorkflowDraft,
    path: StepListPath,
    index: Int,
    translationY: Float,
    dragging: Boolean,
    onSelectionChange: (BlockSelection) -> Unit,
    onDraftChange: (WorkflowDraft) -> Unit,
    onMeasured: (StepBounds) -> Unit,
    onDragStart: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragFinished: (Boolean) -> Unit,
    onInsertAfter: (WorkflowStepKind) -> Unit,
) {
    val clipboard = LocalBlockClipboardActions.current
    val step = draft.stepsAt(path)[index] as StageBoundary
    val density = androidx.compose.ui.platform.LocalDensity.current
    var positionInWindow by remember(step.id) { mutableStateOf(Offset.Zero) }
    val stageNumber = draft.stepsAt(path).take(index + 1).count { it is StageBoundary }
    val liftScale by animateFloatAsState(if (dragging) 1.025f else 1f, tween(100), label = "boundary lift")
    val currentDragStart by rememberUpdatedState(onDragStart)
    val currentDrag by rememberUpdatedState(onDrag)
    val currentDragFinished by rememberUpdatedState(onDragFinished)
    val deleteZoneWidthPx = with(density) { 48.dp.toPx() }
    var overDelete by remember(step.id) { mutableStateOf(false) }
    val currentOverDelete by rememberUpdatedState(overDelete)
    val currentDelete by rememberUpdatedState { onDraftChange(draft.removeStep(path, index)) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(if (dragging) 2f else 0f)
            .onGloballyPositioned {
                positionInWindow = it.positionInWindow()
                onMeasured(StepBounds(positionInWindow.y, it.size.height))
            }
            .graphicsLayer {
                this.translationY = translationY
                scaleX = liftScale
                scaleY = liftScale
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    if (clipboard?.selectionMode == true && step.id in clipboard.selectedIds) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else Color.Transparent,
                    MaterialTheme.shapes.small,
                )
                .clickable { onSelectionChange(BlockSelection(path, step.id)) }
                .pointerInput(step.id) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = {
                            overDelete = false
                            currentDragStart(positionInWindow + it)
                        },
                        onDragEnd = {
                            if (currentOverDelete) {
                                currentDragFinished(false)
                                currentDelete()
                            } else {
                                currentDragFinished(true)
                            }
                            overDelete = false
                        },
                        onDragCancel = {
                            overDelete = false
                            currentDragFinished(false)
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            overDelete = isOverDeleteTarget(change.position.x, size.width, deleteZoneWidthPx)
                            currentDrag(amount)
                        },
                    )
                }
                .padding(start = 12.dp, end = 2.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HorizontalDivider(Modifier.weight(1f))
            Text("Stage $stageNumber", style = MaterialTheme.typography.labelLarge)
            HorizontalDivider(Modifier.weight(1f))
            if (dragging) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(
                            if (overDelete) MaterialTheme.colorScheme.errorContainer else Color.Transparent,
                            CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = "Drop here to remove stage boundary",
                        tint = if (overDelete) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (index < draft.stepsAt(path).lastIndex && !dragging) {
            AddBlockMenu(
                compact = true,
                subdued = true,
                canAddJump = true,
                modifier = Modifier.align(Alignment.BottomStart).offset(x = 2.dp, y = 24.dp).zIndex(3f),
                onAdd = onInsertAfter,
                onPaste = { clipboard?.onPaste?.invoke(path, index + 1) },
            )
        }
    }
}

internal fun executionNumberAt(steps: List<WorkflowStep>, index: Int): Int = steps.take(index + 1).count { it !is StageBoundary }

internal fun isOverDeleteTarget(pointerX: Float, widthPx: Int, targetWidthPx: Float): Boolean = pointerX >= widthPx - targetWidthPx

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
    dragCoordinator: StepDragCoordinator,
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
            dragCoordinator = dragCoordinator,
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
            dragCoordinator = dragCoordinator,
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
                dragCoordinator = dragCoordinator,
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
                dragCoordinator = dragCoordinator,
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
    dragCoordinator: StepDragCoordinator,
) {
    Box(
        modifier = Modifier.background(
            MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
            MaterialTheme.shapes.medium,
        ),
    ) {
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
                dragCoordinator = dragCoordinator,
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
    JUMP("Go to", Icons.AutoMirrored.Outlined.Redo),
    APP("App", Icons.Outlined.Apps),
    STOP("Stop", Icons.Outlined.StopCircle),
}

private fun WorkflowStep.blockCategory(): BlockCategory = when (this) {
    is TapPoint, is TapRegion, is LongPress, is SwipePath -> BlockCategory.ACTION
    is Wait -> BlockCategory.TIMING
    is WaitUntil -> BlockCategory.CONDITION
    is IfElse -> BlockCategory.BRANCH
    is Repeat, is WhileStep -> BlockCategory.LOOP
    is Jump -> BlockCategory.JUMP
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
    BlockCategory.JUMP -> MaterialTheme.colorScheme.primary
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
                Text("Wait until ${step.condition.summary(draft.assetNames)} · timeout", style = MaterialTheme.typography.bodyMedium)
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
                Text("While ${step.condition.summary(draft.assetNames)} · up to", style = MaterialTheme.typography.bodyMedium)
                InlineLongField(step.maxIterations.toLong(), 1, Int.MAX_VALUE.toLong()) { value ->
                    onDraftChange(draft.updateStep(path, index) { step.copy(maxIterations = value.toInt()) })
                }
                Text("iterations, ${step.bodySteps.size} nested blocks", style = MaterialTheme.typography.bodyMedium)
            }

            is LaunchApp -> {
                Text("Open ${step.packageName.value} · wait up to", style = MaterialTheme.typography.bodyMedium)
                InlineLongField(step.foregroundTimeoutMs.value, 1) { value ->
                    onDraftChange(
                        draft.updateStep(path, index) {
                            step.copy(foregroundTimeoutMs = com.veyarc.model.DurationMillis(value))
                        },
                    )
                }
                Text("ms", style = MaterialTheme.typography.bodyMedium)
            }

            is Jump -> {
                Text("Continue from", style = MaterialTheme.typography.bodyMedium)
                JumpTargetPicker(
                    steps = draft.stepsAt(path),
                    selectedId = step.targetStepId,
                    excludedId = step.id,
                    onSelect = { targetId ->
                        onDraftChange(draft.updateStep(path, index) { step.copy(targetStepId = targetId) })
                    },
                )
                Text("until this section has run", style = MaterialTheme.typography.bodyMedium)
                InlineLongField(step.maxVisits.toLong(), 1, draft.runLimits.maxLoopIterations.toLong()) { value ->
                    onDraftChange(draft.updateStep(path, index) { step.copy(maxVisits = value.toInt()) })
                }
                Text("times", style = MaterialTheme.typography.bodyMedium)
            }

            else -> Text(step.summary(draft.assetNames), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun JumpTargetPicker(
    steps: List<WorkflowStep>,
    selectedId: String,
    excludedId: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val options = steps.filterNot { it.id == excludedId }
    val selected = options.firstOrNull { it.id == selectedId }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text(steps.jumpTargetLabel(selected), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (expanded) {
            VeyarcChoicePicker(
                title = "Go to",
                options = options.map { VeyarcChoice(it.id, steps.jumpTargetLabel(it)) },
                selectedId = selectedId,
                onDismiss = { expanded = false },
                onSelect = { choice ->
                    expanded = false
                    onSelect(choice.id)
                },
            )
        }
    }
}

private fun List<WorkflowStep>.jumpTargetLabel(step: WorkflowStep?): String {
    if (step == null) return "Choose a destination"
    if (step is StageBoundary) {
        val stageNumber = take(indexOf(step) + 1).count { it is StageBoundary }
        return "Stage $stageNumber"
    }
    val blockNumber = take(indexOf(step) + 1).count { it !is StageBoundary }
    return "$blockNumber. ${step.displayName()}"
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
        is StageBoundary,
        is Jump,
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
    onPaste: () -> Unit,
    compact: Boolean = false,
    subdued: Boolean = false,
    canAddJump: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val clipboard = LocalBlockClipboardActions.current
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
        if (expanded) {
            VeyarcChoicePicker(
                title = "Add block",
                options = buildList {
                    if (clipboard?.clipboardAvailable == true) add(VeyarcChoice("paste", "Paste blocks"))
                    WorkflowStepKind.entries.filter { it != WorkflowStepKind.JUMP || canAddJump }.forEach { add(VeyarcChoice(it.name, it.displayName())) }
                },
                onDismiss = { expanded = false },
                onSelect = { choice ->
                    expanded = false
                    if (choice.id == "paste") onPaste() else onAdd(WorkflowStepKind.entries.first { it.name == choice.id })
                },
            )
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
