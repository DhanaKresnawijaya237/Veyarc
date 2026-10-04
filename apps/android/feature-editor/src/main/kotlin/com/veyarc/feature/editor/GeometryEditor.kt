@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.veyarc.feature.editor

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.veyarc.data.storage.WorkflowAssetDescriptor
import com.veyarc.model.AssetId
import com.veyarc.model.DisplayOrientation
import com.veyarc.model.LongPress
import com.veyarc.model.NormalizedCoordinate
import com.veyarc.model.NormalizedRect
import com.veyarc.model.RegionAnchor
import com.veyarc.model.SwipePath
import com.veyarc.model.TapPoint
import com.veyarc.model.TapRegion
import com.veyarc.model.ViewportSpec
import com.veyarc.model.WorkflowStep
import com.veyarc.model.bundle.BundleAsset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.hypot

@Composable
internal fun GeometryEditorDialog(
    step: WorkflowStep,
    viewport: ViewportSpec,
    maxPathPoints: Int,
    assets: List<WorkflowAssetDescriptor>,
    assetNames: Map<String, String>,
    loadAsset: suspend (AssetId) -> BundleAsset?,
    loadPreview: suspend (AssetId, Int) -> TemplatePreviewData?,
    onDismiss: () -> Unit,
    onApply: (WorkflowStep) -> Unit,
) {
    var pickerOpen by rememberSaveable { mutableStateOf(false) }
    var selectedAssetId by rememberSaveable { mutableStateOf<String?>(null) }
    var referenceImage by remember { mutableStateOf<ImageBitmap?>(null) }
    val configuration = LocalConfiguration.current
    val compactLandscape = configuration.screenWidthDp < configuration.screenHeightDp &&
        viewport.recordedOrientation == DisplayOrientation.LANDSCAPE
    LaunchedEffect(selectedAssetId) {
        referenceImage = selectedAssetId
            ?.let(::AssetId)
            ?.let { assetId ->
                withContext(Dispatchers.Default) {
                    loadAsset(assetId)?.let { asset ->
                        decodeTemplatePreview(asset, maxOf(viewport.recordedWidthPx, viewport.recordedHeightPx))?.image
                    }
                }
            }
    }
    EditorSheetDialog(
        compact = compactLandscape,
        onDismiss = onDismiss,
        compactMaxHeightFraction = 0.86f,
        largeHeightFraction = 1f,
        contentScrollable = false,
    ) {
        Box(if (compactLandscape) Modifier.fillMaxWidth() else Modifier.fillMaxSize()) {
            when (step) {
                is TapPoint -> PointGeometryEditor(
                    title = "Edit tap point",
                    point = step.point,
                    viewport = viewport,
                    compact = compactLandscape,
                    background = referenceImage,
                    onToggleReference = { pickerOpen = !pickerOpen },
                    onApply = { point -> onApply(step.copy(point = point)) },
                )

                is LongPress -> LongPressGeometryEditor(
                    step = step,
                    viewport = viewport,
                    compact = compactLandscape,
                    background = referenceImage,
                    onToggleReference = { pickerOpen = !pickerOpen },
                    onApply = onApply,
                )

                is TapRegion -> RegionGeometryEditor(
                    step = step,
                    viewport = viewport,
                    compact = compactLandscape,
                    background = referenceImage,
                    onToggleReference = { pickerOpen = !pickerOpen },
                    onApply = onApply,
                )

                is SwipePath -> PathGeometryEditor(
                    step = step,
                    viewport = viewport,
                    maxPathPoints = maxPathPoints,
                    compact = compactLandscape,
                    background = referenceImage,
                    onToggleReference = { pickerOpen = !pickerOpen },
                    onApply = onApply,
                )

                else -> onDismiss()
            }
            TemplatePickerSheet(
                visible = pickerOpen,
                title = "Background",
                assets = assets,
                assetNames = assetNames,
                displayWidthPx = viewport.recordedWidthPx,
                displayHeightPx = viewport.recordedHeightPx,
                fullScreenOnly = true,
                selectedAssetId = selectedAssetId,
                allowNone = true,
                onDismiss = { pickerOpen = false },
                onSelect = { assetId ->
                    selectedAssetId = assetId?.value
                    pickerOpen = false
                },
                loadPreview = loadPreview,
            )
        }
    }
}

@Composable
internal fun TemplatePickerSheet(
    visible: Boolean,
    title: String,
    assets: List<WorkflowAssetDescriptor>,
    assetNames: Map<String, String>,
    displayWidthPx: Int,
    displayHeightPx: Int,
    fullScreenOnly: Boolean,
    selectedAssetId: String?,
    allowNone: Boolean,
    onDismiss: () -> Unit,
    onSelect: (AssetId?) -> Unit,
    loadPreview: suspend (AssetId, Int) -> TemplatePreviewData?,
) {
    if (!visible) return
    val options = assets.filter { asset ->
        val widthPx = asset.widthPx
        val heightPx = asset.heightPx
        widthPx == null || heightPx == null || templateCanAppearInPicker(
            widthPx = widthPx,
            heightPx = heightPx,
            displayWidthPx = displayWidthPx,
            displayHeightPx = displayHeightPx,
            fullScreenOnly = fullScreenOnly,
        )
    }
    val gridState = rememberLazyGridState()
    val sheetGesturesEnabled = rememberEditorSheetGesturesEnabled(gridState)
    val configuration = LocalConfiguration.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetGesturesEnabled = sheetGesturesEnabled,
        dragHandle = { CompactEditorSheetDragHandle() },
        shape = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 0.dp,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .height((configuration.screenHeightDp * 0.76f).dp),
        ) {
            Text(
                title,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
                style = MaterialTheme.typography.titleLarge,
            )
            if (allowNone) {
                Surface(
                    onClick = { onSelect(null) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                    color = if (selectedAssetId == null) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        Color.Transparent
                    },
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text(
                        "None",
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        textAlign = TextAlign.Center,
                    )
                }
            }
            if (options.isEmpty()) {
                Text(
                    if (fullScreenOnly) "No full-screen images." else "No cropped images.",
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyVerticalGrid(
                    state = gridState,
                    overscrollEffect = null,
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(start = 12.dp, top = 14.dp, end = 12.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(options, key = { it.assetId.value }) { asset ->
                        TemplatePickerItem(
                            asset = asset,
                            name = assetNames[asset.assetId.value] ?: "Image",
                            displayWidthPx = displayWidthPx,
                            displayHeightPx = displayHeightPx,
                            fullScreenOnly = fullScreenOnly,
                            selected = selectedAssetId == asset.assetId.value,
                            onSelect = { onSelect(asset.assetId) },
                            loadPreview = loadPreview,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TemplatePickerItem(
    asset: WorkflowAssetDescriptor,
    name: String,
    displayWidthPx: Int,
    displayHeightPx: Int,
    fullScreenOnly: Boolean,
    selected: Boolean,
    onSelect: () -> Unit,
    loadPreview: suspend (AssetId, Int) -> TemplatePreviewData?,
) {
    val previewState by produceState<TemplatePickerPreviewState>(TemplatePickerPreviewState.Loading, asset.assetId) {
        value = withContext(Dispatchers.Default) {
            loadPreview(asset.assetId, 720)?.let(TemplatePickerPreviewState::Ready)
                ?: TemplatePickerPreviewState.Unavailable
        }
    }
    val preview = (previewState as? TemplatePickerPreviewState.Ready)?.preview
    val correctSize = templateFitsPicker(
        descriptorWidthPx = asset.widthPx,
        descriptorHeightPx = asset.heightPx,
        previewWidthPx = preview?.widthPx,
        previewHeightPx = preview?.heightPx,
        displayWidthPx = displayWidthPx,
        displayHeightPx = displayHeightPx,
        fullScreenOnly = fullScreenOnly,
    )
    Surface(
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = correctSize == true, onClick = onSelect)
                    .aspectRatio(
                        if (fullScreenOnly) {
                            displayWidthPx.toFloat() / displayHeightPx
                        } else {
                            preview?.let { it.widthPx.toFloat() / it.heightPx } ?: 1f
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                when (val state = previewState) {
                    TemplatePickerPreviewState.Loading -> CircularProgressIndicator(Modifier.size(28.dp))

                    TemplatePickerPreviewState.Unavailable -> Text("Unreadable", style = MaterialTheme.typography.bodySmall)

                    is TemplatePickerPreviewState.Ready -> Image(
                        bitmap = state.preview.image,
                        contentDescription = name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.FillBounds,
                    )
                }
            }
            Text(
                name,
                modifier = Modifier.fillMaxWidth().padding(6.dp),
                maxLines = 1,
                textAlign = TextAlign.Center,
            )
        }
    }
}

internal fun templateFitsPicker(
    widthPx: Int,
    heightPx: Int,
    displayWidthPx: Int,
    displayHeightPx: Int,
    fullScreenOnly: Boolean,
): Boolean {
    val currentOrientation = widthPx == displayWidthPx && heightPx == displayHeightPx
    if (fullScreenOnly) return currentOrientation
    val otherOrientation = widthPx == displayHeightPx && heightPx == displayWidthPx
    return widthPx <= displayWidthPx && heightPx <= displayHeightPx && !currentOrientation && !otherOrientation
}

internal fun templateFitsPicker(
    descriptorWidthPx: Int?,
    descriptorHeightPx: Int?,
    previewWidthPx: Int?,
    previewHeightPx: Int?,
    displayWidthPx: Int,
    displayHeightPx: Int,
    fullScreenOnly: Boolean,
): Boolean? {
    val widthPx = descriptorWidthPx ?: previewWidthPx ?: return null
    val heightPx = descriptorHeightPx ?: previewHeightPx ?: return null
    return templateFitsPicker(widthPx, heightPx, displayWidthPx, displayHeightPx, fullScreenOnly)
}

internal fun templateCanAppearInPicker(
    widthPx: Int,
    heightPx: Int,
    displayWidthPx: Int,
    displayHeightPx: Int,
    fullScreenOnly: Boolean,
): Boolean {
    if (fullScreenOnly) {
        return templateFitsPicker(widthPx, heightPx, displayWidthPx, displayHeightPx, fullScreenOnly = true)
    }
    return !(widthPx == displayWidthPx && heightPx == displayHeightPx) &&
        !(widthPx == displayHeightPx && heightPx == displayWidthPx)
}

private sealed interface TemplatePickerPreviewState {
    data object Loading : TemplatePickerPreviewState
    data object Unavailable : TemplatePickerPreviewState
    data class Ready(val preview: TemplatePreviewData) : TemplatePickerPreviewState
}

@Composable
private fun PointGeometryEditor(
    title: String,
    point: NormalizedCoordinate,
    viewport: ViewportSpec,
    compact: Boolean,
    background: ImageBitmap?,
    onToggleReference: () -> Unit,
    onApply: (NormalizedCoordinate) -> Unit,
) {
    var selectedPoint by remember(point) { mutableStateOf(point) }
    GeometryEditorLayout(
        title = title,
        compact = compact,
        onToggleReference = onToggleReference,
        onApply = { onApply(selectedPoint) },
    ) {
        Text("Tap the display preview to place the contact point.")
        GeometryPreview(
            viewport = viewport,
            background = background,
            modifier = geometryPreviewModifier(viewport, compact),
            gestureModifier = Modifier.pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    selectedPoint = down.position.toNormalized(size)
                    var pressed = true
                    while (pressed) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        change.consume()
                        pressed = change.pressed
                    }
                }
            },
        ) {
            drawTargetMarker(selectedPoint)
        }
        Text(selectedPoint.displayText())
    }
}

@Composable
private fun LongPressGeometryEditor(
    step: LongPress,
    viewport: ViewportSpec,
    compact: Boolean,
    background: ImageBitmap?,
    onToggleReference: () -> Unit,
    onApply: (WorkflowStep) -> Unit,
) {
    var selectedPoint by remember(step) { mutableStateOf(step.point) }
    var durationText by remember(step) { mutableStateOf(step.durationMs.value.toString()) }
    val duration = durationText.toLongOrNull()
    val validDuration = duration != null && duration > 0
    GeometryEditorLayout(
        title = "Edit long press",
        compact = compact,
        onToggleReference = onToggleReference,
        applyEnabled = validDuration,
        onApply = {
            if (duration != null && duration > 0) {
                onApply(step.copy(point = selectedPoint, durationMs = com.veyarc.model.DurationMillis(duration)))
            }
        },
    ) {
        Text("Tap the display preview to place the press point.")
        GeometryPreview(
            viewport = viewport,
            background = background,
            modifier = geometryPreviewModifier(viewport, compact),
            gestureModifier = Modifier.pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    selectedPoint = down.position.toNormalized(size)
                    var pressed = true
                    while (pressed) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        change.consume()
                        pressed = change.pressed
                    }
                }
            },
        ) {
            drawTargetMarker(selectedPoint)
        }
        LongEditorField(
            label = "Hold duration (ms)",
            value = durationText.toLongOrNull() ?: 0L,
            onValueChange = { durationText = it.toString() },
        )
        if (!validDuration) {
            Text(
                "Hold duration must be greater than zero.",
                color = MaterialTheme.colorScheme.error,
            )
        }
        Text(selectedPoint.displayText())
    }
}

@Composable
private fun RegionGeometryEditor(
    step: TapRegion,
    viewport: ViewportSpec,
    compact: Boolean,
    background: ImageBitmap?,
    onToggleReference: () -> Unit,
    onApply: (WorkflowStep) -> Unit,
) {
    var region by remember(step) { mutableStateOf(step.region) }
    var anchorPreset by remember(step) { mutableStateOf(AnchorPreset.from(step.anchor)) }
    var customAnchorX by remember(step) { mutableStateOf(step.anchor.customXText()) }
    var customAnchorY by remember(step) { mutableStateOf(step.anchor.customYText()) }
    var anchorMenuExpanded by remember { mutableStateOf(false) }
    var activeHandle by remember { mutableStateOf<RegionDragHandle?>(null) }
    val anchor = anchorPreset.toAnchor(customAnchorX, customAnchorY)
    val validAnchor = anchor != null

    GeometryEditorLayout(
        title = "Edit touch region",
        compact = compact,
        onToggleReference = onToggleReference,
        applyEnabled = validAnchor,
        onApply = { anchor?.let { onApply(step.copy(region = region, anchor = it)) } },
    ) {
        Text("Drag a corner to resize the region, or drag inside it to move it.")
        GeometryPreview(
            viewport = viewport,
            background = background,
            modifier = geometryPreviewModifier(viewport, compact),
            gestureModifier = Modifier.pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    val start = down.position.toNormalized(size)
                    activeHandle = selectRegionHandle(region, start, threshold = 0.08)
                    var previous = down.position
                    var pressed = true
                    while (pressed) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        val current = change.position
                        val handle = activeHandle
                        if (handle != null) {
                            val deltaX = (current.x - previous.x) / size.width
                            val deltaY = (current.y - previous.y) / size.height
                            region = updateRegion(region, handle, deltaX.toDouble(), deltaY.toDouble())
                        }
                        previous = current
                        change.consume()
                        pressed = change.pressed
                    }
                    activeHandle = null
                }
            },
        ) {
            drawRegion(region, anchor)
        }
        Text(region.displayText())
        Box {
            OutlinedButton(onClick = { anchorMenuExpanded = true }) {
                Text("Anchor: ${anchorPreset.label}")
            }
            if (anchorMenuExpanded) {
                VeyarcChoicePicker(
                    title = "Tap anchor",
                    options = AnchorPreset.entries.map { VeyarcChoice(it.name, it.label) },
                    selectedId = anchorPreset.name,
                    onDismiss = { anchorMenuExpanded = false },
                    onSelect = { choice ->
                        anchorPreset = AnchorPreset.entries.first { it.name == choice.id }
                        anchorMenuExpanded = false
                    },
                )
            }
        }
        if (anchorPreset == AnchorPreset.CUSTOM) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DecimalEditorField(
                    label = "Anchor X",
                    value = customAnchorX,
                    onValueChange = { customAnchorX = it },
                    modifier = Modifier.weight(1f),
                )
                DecimalEditorField(
                    label = "Anchor Y",
                    value = customAnchorY,
                    onValueChange = { customAnchorY = it },
                    modifier = Modifier.weight(1f),
                )
            }
            if (!validAnchor) {
                Text(
                    "Custom anchor coordinates must be between 0 and 1.",
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun PathGeometryEditor(
    step: SwipePath,
    viewport: ViewportSpec,
    maxPathPoints: Int,
    compact: Boolean,
    background: ImageBitmap?,
    onToggleReference: () -> Unit,
    onApply: (WorkflowStep) -> Unit,
) {
    var points by remember(step) { mutableStateOf(step.points.map { it.point }) }
    var durationText by remember(step) { mutableStateOf(step.durationMs.value.toString()) }
    val duration = durationText.toLongOrNull()
    val timedPoints = duration?.let { timedPath(points, it, maxPathPoints) }
    val validPath = timedPoints != null
    val previewProgress = remember { Animatable(1f) }
    val previewScope = rememberCoroutineScope()
    var previewing by remember { mutableStateOf(false) }
    val previewPoint = timedPoints?.let { interpolatePath(it, previewProgress.value) }

    GeometryEditorLayout(
        title = "Edit swipe path",
        compact = compact,
        onToggleReference = onToggleReference,
        applyEnabled = validPath,
        headerAction = {
            IconButton(
                enabled = validPath && !previewing,
                onClick = {
                    previewScope.launch {
                        previewing = true
                        previewProgress.snapTo(0f)
                        previewProgress.animateTo(
                            targetValue = 1f,
                            animationSpec = tween(durationMillis = (duration ?: 1L).coerceIn(1L, 10_000L).toInt()),
                        )
                        previewing = false
                    }
                },
            ) {
                Icon(
                    Icons.Outlined.PlayArrow,
                    contentDescription = if (previewing) "Previewing swipe path" else "Preview swipe path",
                )
            }
        },
        onApply = {
            if (duration != null && timedPoints != null) {
                onApply(step.copy(durationMs = com.veyarc.model.DurationMillis(duration), points = timedPoints))
            }
        },
    ) {
        Text("Draw a single-pointer path on the display preview. Drawing replaces the saved path.")
        GeometryPreview(
            viewport = viewport,
            background = background,
            modifier = geometryPreviewModifier(viewport, compact),
            gestureModifier = Modifier.pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    val collected = mutableListOf(down.position.toNormalized(size))
                    points = collected.toList()
                    var previous = down.position
                    var pressed = true
                    while (pressed) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        val current = change.position
                        if (hypot(current.x - previous.x, current.y - previous.y) >= 2f) {
                            collected += current.toNormalized(size)
                            points = collected.toList()
                            previous = current
                        }
                        change.consume()
                        pressed = change.pressed
                    }
                    points = downsamplePath(collected, maxPathPoints)
                }
            },
        ) {
            drawSwipePath(points, previewPoint)
        }
        LongEditorField(
            label = "Path duration (ms)",
            value = duration ?: 0L,
            onValueChange = { durationText = it.toString() },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            when {
                points.size < 2 -> "Draw at least two points."
                duration == null || duration <= 0 -> "Path duration must be greater than zero."
                duration < points.lastIndex -> "Duration must allow each sampled point to have a unique timestamp."
                points.size > maxPathPoints -> "The path will be reduced to $maxPathPoints points when applied."
                else -> "${points.size} points · $duration ms"
            },
            color = if (validPath) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun GeometryEditorLayout(
    title: String,
    compact: Boolean,
    applyEnabled: Boolean = true,
    headerAction: (@Composable () -> Unit)? = null,
    onToggleReference: (() -> Unit)? = null,
    onApply: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val layoutModifier = if (compact) {
        Modifier.fillMaxWidth()
    } else {
        Modifier.fillMaxSize()
    }
    Column(
        modifier = layoutModifier.padding(
            top = if (compact) 12.dp else 8.dp,
            start = 12.dp,
            end = 12.dp,
            bottom = 12.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            onToggleReference?.let { toggle ->
                IconButton(onClick = toggle) {
                    Icon(Icons.Outlined.Image, contentDescription = "Choose background template")
                }
            }
            headerAction?.invoke()
            Button(enabled = applyEnabled, onClick = onApply) { Text("Apply") }
        }
        content()
    }
}

private fun ColumnScope.geometryPreviewModifier(viewport: ViewportSpec, compact: Boolean): Modifier = if (compact) {
    Modifier.fillMaxWidth().aspectRatio(viewport.recordedWidthPx.toFloat() / viewport.recordedHeightPx)
} else {
    Modifier.fillMaxWidth().weight(1f)
}

@Composable
private fun GeometryPreview(
    viewport: ViewportSpec,
    background: ImageBitmap?,
    modifier: Modifier,
    gestureModifier: Modifier,
    content: DrawScope.() -> Unit,
) {
    val surface = MaterialTheme.colorScheme.surfaceVariant
    val grid = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        val aspect = viewport.recordedWidthPx.toFloat() / viewport.recordedHeightPx.toFloat()
        val width = minOf(maxWidth, maxHeight * aspect)
        val height = width / aspect
        Canvas(
            modifier = Modifier
                .size(DpSize(width, height))
                .then(gestureModifier),
        ) {
            drawRect(surface)
            background?.let { image ->
                drawImage(
                    image = image,
                    dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                    alpha = 0.72f,
                    filterQuality = FilterQuality.High,
                )
            }
            for (step in 1..3) {
                val x = size.width * step / 4f
                val y = size.height * step / 4f
                drawLine(grid, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
                drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
            }
            content()
        }
    }
}

private fun DrawScope.drawTargetMarker(point: NormalizedCoordinate) {
    val center = point.toPx(size)
    drawCircle(Color(0xFF6750A4), radius = 16.dp.toPx(), center = center)
    drawCircle(Color.White, radius = 16.dp.toPx(), center = center, style = Stroke(width = 2.dp.toPx()))
    drawLine(Color.White, Offset(center.x - 22.dp.toPx(), center.y), Offset(center.x + 22.dp.toPx(), center.y), strokeWidth = 1.dp.toPx())
    drawLine(Color.White, Offset(center.x, center.y - 22.dp.toPx()), Offset(center.x, center.y + 22.dp.toPx()), strokeWidth = 1.dp.toPx())
}

private fun DrawScope.drawRegion(region: NormalizedRect, anchor: RegionAnchor?) {
    val rect = region.toRect(size)
    drawRect(Color(0xFF6750A4).copy(alpha = 0.2f), topLeft = rect.topLeft, size = rect.size)
    drawRect(Color(0xFF6750A4), topLeft = rect.topLeft, size = rect.size, style = Stroke(width = 3.dp.toPx()))
    val handleRadius = 10.dp.toPx()
    listOf(rect.topLeft, rect.topRight, rect.bottomLeft, rect.bottomRight).forEach { handle ->
        drawCircle(Color.White, radius = handleRadius + 2.dp.toPx(), center = handle)
        drawCircle(Color(0xFF6750A4), radius = handleRadius, center = handle)
    }
    anchor?.let {
        drawTargetMarker(region.anchorPoint(it))
    }
}

private fun DrawScope.drawSwipePath(
    points: List<NormalizedCoordinate>,
    previewPoint: NormalizedCoordinate?,
) {
    if (points.isEmpty()) return
    if (points.size > 1) {
        val path = Path().apply {
            moveTo(points.first().toPx(size).x, points.first().toPx(size).y)
            points.drop(1).forEach { point ->
                val pixel = point.toPx(size)
                lineTo(pixel.x, pixel.y)
            }
        }
        drawPath(
            path,
            Color(0xFF6750A4),
            style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round),
        )
        points.forEach { point ->
            drawCircle(Color(0xFF6750A4), radius = 4.dp.toPx(), center = point.toPx(size))
        }
    }
    drawTargetMarker(points.first())
    if (points.size > 1) drawTargetMarker(points.last())
    previewPoint?.let { drawCircle(Color(0xFFB3261E), radius = 10.dp.toPx(), center = it.toPx(size)) }
}

@Composable
private fun DecimalEditorField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { next ->
            if (next.isEmpty() || next.matches(Regex("0?(\\.\\d{0,4})?|1(\\.0{0,4})?"))) {
                onValueChange(next)
            }
        },
        modifier = modifier,
        label = { Text(label) },
        singleLine = true,
    )
}

private enum class AnchorPreset(
    val label: String,
    val offset: Pair<Double, Double>?,
) {
    CENTER("Center", null),
    TOP_LEFT("Top left", 0.0 to 0.0),
    TOP_RIGHT("Top right", 1.0 to 0.0),
    BOTTOM_LEFT("Bottom left", 0.0 to 1.0),
    BOTTOM_RIGHT("Bottom right", 1.0 to 1.0),
    CUSTOM("Custom", null),
    ;

    fun toAnchor(xText: String, yText: String): RegionAnchor? = when {
        this == CENTER -> RegionAnchor.Center

        offset != null -> RegionAnchor.Offset(offset.first, offset.second)

        else -> {
            val x = xText.toDoubleOrNull()
            val y = yText.toDoubleOrNull()
            if (x == null || y == null || x !in 0.0..1.0 || y !in 0.0..1.0) {
                null
            } else {
                RegionAnchor.Offset(x, y)
            }
        }
    }

    companion object {
        fun from(anchor: RegionAnchor): AnchorPreset = when (anchor) {
            RegionAnchor.Center -> CENTER
            is RegionAnchor.Offset -> entries.firstOrNull { preset -> preset.offset == (anchor.x to anchor.y) } ?: CUSTOM
        }
    }
}

private fun RegionAnchor.customXText(): String = when (this) {
    RegionAnchor.Center -> "0.5"
    is RegionAnchor.Offset -> x.toString()
}

private fun RegionAnchor.customYText(): String = when (this) {
    RegionAnchor.Center -> "0.5"
    is RegionAnchor.Offset -> y.toString()
}

private fun NormalizedCoordinate.displayText(): String = "Point: ${(x * 100).formatPercent()}%, ${(y * 100).formatPercent()}%"

private fun NormalizedRect.displayText(): String = "Region: ${(x * 100).formatPercent()}%, ${(y * 100).formatPercent()}% · ${(width * 100).formatPercent()}% × ${(height * 100).formatPercent()}%"

private fun Double.formatPercent(): String = "%.1f".format(this)

private fun NormalizedCoordinate.toPx(size: Size): Offset = Offset(
    x = (x * size.width).toFloat(),
    y = (y * size.height).toFloat(),
)

private fun NormalizedRect.toRect(size: Size): Rect = Rect(
    left = (x * size.width).toFloat(),
    top = (y * size.height).toFloat(),
    right = ((x + width) * size.width).toFloat(),
    bottom = ((y + height) * size.height).toFloat(),
)

private fun NormalizedRect.anchorPoint(anchor: RegionAnchor): NormalizedCoordinate = when (anchor) {
    RegionAnchor.Center -> NormalizedCoordinate(x + width / 2.0, y + height / 2.0)
    is RegionAnchor.Offset -> NormalizedCoordinate(x + width * anchor.x, y + height * anchor.y)
}

private fun Offset.toNormalized(size: IntSize): NormalizedCoordinate = NormalizedCoordinate(
    x = (x / size.width.toFloat()).coerceIn(0f, 1f).toDouble(),
    y = (y / size.height.toFloat()).coerceIn(0f, 1f).toDouble(),
)
