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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.veyarc.data.storage.WorkflowAssetDescriptor
import com.veyarc.model.AssetId
import com.veyarc.model.CaptureImage
import com.veyarc.model.DisplayOrientation
import com.veyarc.model.FindImage
import com.veyarc.model.ImagePositionBinding
import com.veyarc.model.LongPress
import com.veyarc.model.NormalizedCoordinate
import com.veyarc.model.NormalizedRect
import com.veyarc.model.RegionAnchor
import com.veyarc.model.SwipePath
import com.veyarc.model.TapPoint
import com.veyarc.model.TapRegion
import com.veyarc.model.VariableDeclaration
import com.veyarc.model.VariableName
import com.veyarc.model.ViewportSpec
import com.veyarc.model.WorkflowStep
import com.veyarc.model.backgroundAssetId
import com.veyarc.model.withBackgroundAsset
import kotlinx.coroutines.launch
import kotlin.math.hypot

@Composable
internal fun GeometryEditorDialog(
    step: WorkflowStep,
    viewport: ViewportSpec,
    maxPathPoints: Int,
    assets: List<WorkflowAssetDescriptor>,
    assetNames: Map<String, String>,
    loadPreview: suspend (AssetId, Int) -> TemplatePreviewData?,
    onDismiss: () -> Unit,
    onAddImage: () -> Unit,
    onApply: (WorkflowStep) -> Unit,
    imageSearches: Map<String, List<FindImage>> = emptyMap(),
    variables: Map<String, VariableDeclaration> = emptyMap(),
) {
    var pickerOpen by rememberSaveable { mutableStateOf(false) }
    var selectedAssetId by rememberSaveable(step.id) { mutableStateOf(step.backgroundAssetId()?.value) }
    val originalBinding = when (step) {
        is TapPoint -> step.imagePosition
        is LongPress -> step.imagePosition
        else -> null
    }
    var resultName by rememberSaveable(step.id) { mutableStateOf(originalBinding?.name?.value) }
    var offsetX by rememberSaveable(step.id) { mutableStateOf(originalBinding?.offsetXPx ?: 0) }
    var offsetY by rememberSaveable(step.id) { mutableStateOf(originalBinding?.offsetYPx ?: 0) }
    var offsetXVariable by rememberSaveable(step.id) { mutableStateOf(originalBinding?.offsetXVariable?.value) }
    var offsetYVariable by rememberSaveable(step.id) { mutableStateOf(originalBinding?.offsetYVariable?.value) }
    var positionValid by rememberSaveable(step.id) { mutableStateOf(true) }
    val binding = resultName?.let { ImagePositionBinding(VariableName(it), offsetX, offsetY, offsetXVariable?.let(::VariableName), offsetYVariable?.let(::VariableName)) }
    val positionControls: @Composable () -> Unit = {
        ImagePositionFields(binding, imageSearches, variables, onValidityChange = { positionValid = it }) {
            resultName = it?.name?.value
            offsetX = it?.offsetXPx ?: 0
            offsetY = it?.offsetYPx ?: 0
            offsetXVariable = it?.offsetXVariable?.value
            offsetYVariable = it?.offsetYVariable?.value
        }
    }
    fun apply(value: WorkflowStep) {
        val positioned = when (value) {
            is TapPoint -> value.copy(imagePosition = binding)
            is LongPress -> value.copy(imagePosition = binding)
            else -> value
        }
        onApply(positioned.withBackgroundAsset(selectedAssetId?.let(::AssetId)))
    }
    val referenceImage by produceState(selectedAssetId?.let { cachedTemplatePreview(AssetId(it), PREVIEW_MAX_DIMENSION)?.image }, selectedAssetId) {
        value = selectedAssetId?.let { cachedTemplatePreview(AssetId(it), PREVIEW_MAX_DIMENSION)?.image }
        value = selectedAssetId?.let { (cachedTemplatePreview(AssetId(it), PREVIEW_MAX_DIMENSION) ?: loadPreview(AssetId(it), PREVIEW_MAX_DIMENSION))?.image }
    }
    val configuration = LocalConfiguration.current
    val compactLandscape = configuration.screenWidthDp < configuration.screenHeightDp &&
        viewport.recordedOrientation == DisplayOrientation.LANDSCAPE
    val compact = compactLandscape || binding != null
    EditorSheetDialog(
        compact = compact,
        onDismiss = onDismiss,
        compactMaxHeightFraction = 0.86f,
        largeHeightFraction = 1f,
        contentScrollable = false,
    ) {
        Box(if (compact) Modifier.fillMaxWidth() else Modifier.fillMaxSize()) {
            when (step) {
                is TapPoint -> PointGeometryEditor(
                    title = "Edit tap point",
                    point = step.point,
                    positionControls = positionControls,
                    dynamicPosition = binding != null,
                    positionValid = positionValid,
                    viewport = viewport,
                    compact = compact,
                    background = referenceImage,
                    onToggleReference = { pickerOpen = !pickerOpen },
                    onAddImage = onAddImage,
                    onApply = { point -> apply(step.copy(point = point)) },
                )

                is LongPress -> LongPressGeometryEditor(
                    step = step,
                    positionControls = positionControls,
                    dynamicPosition = binding != null,
                    positionValid = positionValid,
                    variables = variables,
                    viewport = viewport,
                    compact = compact,
                    background = referenceImage,
                    onToggleReference = { pickerOpen = !pickerOpen },
                    onAddImage = onAddImage,
                    onApply = ::apply,
                )

                is CaptureImage -> RegionGeometryEditor(
                    step = TapRegion(step.id, step.region, RegionAnchor.Center),
                    viewport = viewport,
                    compact = compact,
                    background = referenceImage,
                    onToggleReference = { pickerOpen = !pickerOpen },
                    onAddImage = onAddImage,
                    onApply = { updated -> apply(step.copy(region = (updated as TapRegion).region)) },
                    capture = true,
                )

                is TapRegion -> RegionGeometryEditor(
                    step = step,
                    viewport = viewport,
                    compact = compact,
                    background = referenceImage,
                    onToggleReference = { pickerOpen = !pickerOpen },
                    onAddImage = onAddImage,
                    onApply = ::apply,
                )

                is SwipePath -> PathGeometryEditor(
                    step = step,
                    viewport = viewport,
                    maxPathPoints = maxPathPoints,
                    compact = compact,
                    background = referenceImage,
                    onToggleReference = { pickerOpen = !pickerOpen },
                    onAddImage = onAddImage,
                    onApply = ::apply,
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
    allowAnySize: Boolean = false,
    onAddImage: (() -> Unit)? = null,
    capturedImages: Set<String> = emptySet(),
    selectedCapture: String? = null,
    onSelectCapture: ((String) -> Unit)? = null,
) {
    if (!visible) return
    val options = assets.filter { asset ->
        val widthPx = asset.widthPx
        val heightPx = asset.heightPx
        allowAnySize || widthPx == null || heightPx == null || templateCanAppearInPicker(
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
        modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)),
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetGesturesEnabled = sheetGesturesEnabled,
        dragHandle = { CompactEditorSheetDragHandle() },
        shape = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 0.dp,
        contentWindowInsets = { WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom) },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .height((configuration.screenHeightDp * 0.76f).dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                onAddImage?.let { add ->
                    IconButton(onClick = add) { Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = "Add image") }
                }
            }
            if (capturedImages.isNotEmpty() && onSelectCapture != null) {
                androidx.compose.foundation.lazy.LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(capturedImages.size) { index ->
                        val name = capturedImages.elementAt(index)
                        Surface(onClick = { onSelectCapture(name) }, shape = RoundedCornerShape(12.dp), color = if (name == selectedCapture) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh) {
                            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                Text(name, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
            if (allowNone) {
                if (capturedImages.isNotEmpty()) Spacer(Modifier.height(14.dp))
                Surface(
                    onClick = { onSelect(null) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                    color = if (selectedAssetId == null && selectedCapture == null) {
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
                    if (allowAnySize) {
                        "No images yet."
                    } else if (fullScreenOnly) {
                        "No full-screen images."
                    } else {
                        "No cropped images."
                    },
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyVerticalGrid(
                    modifier = Modifier.weight(1f).testTag("template-picker-list"),
                    state = gridState,
                    overscrollEffect = null,
                    columns = if (allowAnySize) GridCells.Adaptive(140.dp) else GridCells.Fixed(2),
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
                            allowAnySize = allowAnySize,
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
    allowAnySize: Boolean = false,
) {
    val previewState by produceState<TemplatePickerPreviewState>(cachedTemplatePreview(asset.assetId, PREVIEW_MAX_DIMENSION)?.let(TemplatePickerPreviewState::Ready) ?: TemplatePickerPreviewState.Loading, asset.assetId) {
        value = (cachedTemplatePreview(asset.assetId, PREVIEW_MAX_DIMENSION) ?: loadPreview(asset.assetId, PREVIEW_MAX_DIMENSION))
            ?.let(TemplatePickerPreviewState::Ready)
            ?: TemplatePickerPreviewState.Unavailable
    }
    val preview = (previewState as? TemplatePickerPreviewState.Ready)?.preview
    val imageWidth = asset.widthPx ?: preview?.widthPx
    val imageHeight = asset.heightPx ?: preview?.heightPx
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
        onClick = onSelect,
        enabled = if (allowAnySize) preview != null else correctSize == true,
        modifier = Modifier.testTag("template-picker-${asset.assetId.value}"),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(
                        if (allowAnySize) {
                            1f
                        } else if (fullScreenOnly) {
                            displayWidthPx.toFloat() / displayHeightPx
                        } else {
                            if (imageWidth != null && imageHeight != null) imageWidth.toFloat() / imageHeight else 1f
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
                        contentScale = if (allowAnySize) ContentScale.Fit else ContentScale.FillBounds,
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
    positionControls: @Composable () -> Unit,
    dynamicPosition: Boolean,
    positionValid: Boolean,
    viewport: ViewportSpec,
    compact: Boolean,
    background: ImageBitmap?,
    onToggleReference: () -> Unit,
    onAddImage: () -> Unit,
    onApply: (NormalizedCoordinate) -> Unit,
) {
    var selectedPoint by remember(point) { mutableStateOf(point) }
    GeometryEditorLayout(
        title = title,
        compact = compact,
        onToggleReference = onToggleReference,
        onAddImage = onAddImage,
        applyEnabled = positionValid,
        onApply = { onApply(selectedPoint) },
    ) {
        positionControls()
        if (!dynamicPosition) {
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
}

@Composable
private fun LongPressGeometryEditor(
    step: LongPress,
    positionControls: @Composable () -> Unit,
    dynamicPosition: Boolean,
    positionValid: Boolean,
    variables: Map<String, VariableDeclaration>,
    viewport: ViewportSpec,
    compact: Boolean,
    background: ImageBitmap?,
    onToggleReference: () -> Unit,
    onAddImage: () -> Unit,
    onApply: (WorkflowStep) -> Unit,
) {
    var selectedPoint by remember(step) { mutableStateOf(step.point) }
    var duration by rememberSaveable(step.id) { mutableStateOf(step.durationMs.value) }
    var durationVariable by rememberSaveable(step.id) { mutableStateOf(step.durationVariable?.value) }
    var validDuration by rememberSaveable(step.id) { mutableStateOf(true) }
    GeometryEditorLayout(
        title = "Edit long press",
        compact = compact,
        onToggleReference = onToggleReference,
        onAddImage = onAddImage,
        applyEnabled = validDuration && positionValid,
        onApply = {
            onApply(step.copy(point = selectedPoint, durationMs = com.veyarc.model.DurationMillis(duration), durationVariable = durationVariable?.let(::VariableName)))
        },
    ) {
        positionControls()
        if (!dynamicPosition) {
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
            Text(selectedPoint.displayText())
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Hold duration", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            NumberValueControl("Hold duration", durationVariable?.let(::VariableName), variables, {
                durationVariable = it?.value
                validDuration = true
            }) {
                InlineLongField(duration, 1, resetKey = null, label = "Hold duration", onValidityChange = { validDuration = it }) { duration = it }
            }
            Text("ms", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!validDuration) {
            Text(
                "Hold duration must be greater than zero.",
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun RegionGeometryEditor(
    step: TapRegion,
    viewport: ViewportSpec,
    compact: Boolean,
    background: ImageBitmap?,
    onToggleReference: () -> Unit,
    onAddImage: () -> Unit,
    onApply: (WorkflowStep) -> Unit,
    capture: Boolean = false,
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
        title = if (capture) "Capture area" else "Edit touch region",
        compact = compact,
        onToggleReference = onToggleReference,
        onAddImage = onAddImage,
        applyEnabled = validAnchor,
        onApply = { anchor?.let { onApply(step.copy(region = region, anchor = it)) } },
    ) {
        val fullCapture = capture && region == NormalizedRect(0.0, 0.0, 1.0, 1.0)
        if (capture) {
            InlineVariableOptions(listOf("crop" to "Crop", "screen" to "Full screen"), if (fullCapture) "screen" else "crop") {
                region = if (it == "screen") NormalizedRect(0.0, 0.0, 1.0, 1.0) else NormalizedRect(0.25, 0.25, 0.5, 0.5)
            }
        }
        if (!fullCapture) Text("Drag a corner to resize the region, or drag inside it to move it.")
        GeometryPreview(
            viewport = viewport,
            background = background,
            modifier = geometryPreviewModifier(viewport, compact),
            gestureModifier = if (fullCapture) {
                Modifier
            } else {
                Modifier.pointerInput(Unit) {
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
                }
            },
        ) {
            drawRegion(region, if (capture) null else anchor)
        }
        Text(region.displayText())
        if (!capture) {
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
}

@Composable
private fun PathGeometryEditor(
    step: SwipePath,
    viewport: ViewportSpec,
    maxPathPoints: Int,
    compact: Boolean,
    background: ImageBitmap?,
    onToggleReference: () -> Unit,
    onAddImage: () -> Unit,
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
        onAddImage = onAddImage,
        applyEnabled = validPath,
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
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LongEditorField(label = "Path duration (ms)", value = durationText, onValueChange = { durationText = it }, modifier = Modifier.weight(1f))
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
        }
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
    onToggleReference: (() -> Unit)? = null,
    onAddImage: (() -> Unit)? = null,
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
            onAddImage?.let { add -> IconButton(onClick = add) { Icon(Icons.Outlined.AddPhotoAlternate, "Add image") } }
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
