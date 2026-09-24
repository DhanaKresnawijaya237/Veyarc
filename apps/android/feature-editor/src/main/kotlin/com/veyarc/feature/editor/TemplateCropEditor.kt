package com.veyarc.feature.editor

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.RotateRight
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.veyarc.data.storage.DecodedTemplate
import com.veyarc.model.NormalizedCoordinate
import com.veyarc.model.NormalizedRect
import kotlin.math.ceil
import kotlin.math.floor

private val DEFAULT_TEMPLATE_CROP = NormalizedRect(
    x = 0.25,
    y = 0.4,
    width = 0.5,
    height = 0.2,
)

internal val FULL_TEMPLATE_CROP = NormalizedRect(0.0, 0.0, 1.0, 1.0)

internal data class TemplateCropDraft(
    val source: TemplateCropSource,
    val crop: NormalizedRect = DEFAULT_TEMPLATE_CROP,
    val rotationQuarterTurns: Int = 0,
    val name: String = "",
)

@Composable
internal fun TemplateCropEditorDialog(
    draft: TemplateCropDraft,
    displayWidthPx: Int,
    displayHeightPx: Int,
    existingNames: Set<String>,
    onDraftChange: (TemplateCropDraft) -> Unit,
    onDismiss: () -> Unit,
    onApply: (NormalizedRect, Int, String) -> Unit,
) {
    val source = draft.source
    val crop = draft.crop
    val rotationQuarterTurns = draft.rotationQuarterTurns
    val name = draft.name
    val nameIsValid = name.isBlank() || (
        name.trim().length <= 64 && existingNames.none { it.equals(name.trim(), ignoreCase = true) }
        )
    val rotatedSource = remember(source, rotationQuarterTurns) { source.rotated(rotationQuarterTurns) }
    DisposableEffect(rotatedSource.preview) {
        onDispose {
            if (rotatedSource.preview !== source.preview) rotatedSource.preview.asAndroidBitmap().recycle()
        }
    }
    val cropWidthPx = crop.pixelWidth(rotatedSource.widthPx)
    val cropHeightPx = crop.pixelHeight(rotatedSource.heightPx)
    val cropPixels = cropWidthPx.toLong() * cropHeightPx
    val cropFitsDisplay = cropWidthPx <= displayWidthPx && cropHeightPx <= displayHeightPx
    val cropIsSupported = cropPixels <= DecodedTemplate.MAX_TEMPLATE_PIXELS && cropFitsDisplay
    val configuration = LocalConfiguration.current
    val compactLandscape = configuration.screenWidthDp < configuration.screenHeightDp && rotatedSource.widthPx > rotatedSource.heightPx

    EditorSheetDialog(
        compact = compactLandscape,
        onDismiss = onDismiss,
        compactMaxHeightFraction = 0.88f,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(
                    start = 12.dp,
                    top = if (compactLandscape) 20.dp else 0.dp,
                    end = 12.dp,
                    bottom = 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Select the template area", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Drag the crop or its corners to frame the area you want.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TemplateCropWorkspace(
                    source = rotatedSource,
                    crop = crop,
                    onCropChange = { onDraftChange(draft.copy(crop = it)) },
                    onFullScreen = { onDraftChange(draft.copy(crop = FULL_TEMPLATE_CROP)) },
                    onReset = { onDraftChange(draft.copy(crop = DEFAULT_TEMPLATE_CROP)) },
                    onRotate = {
                        onDraftChange(
                            draft.copy(
                                crop = crop.rotatedClockwise(),
                                rotationQuarterTurns = (rotationQuarterTurns + 1).mod(4),
                            ),
                        )
                    },
                    onApply = { onApply(crop, rotationQuarterTurns, name.trim()) },
                    applyEnabled = cropIsSupported && nameIsValid,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { onDraftChange(draft.copy(name = it)) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Template name (optional)") },
                    singleLine = true,
                    isError = !nameIsValid,
                    supportingText = if (nameIsValid) null else ({ Text("Use a unique name up to 64 characters.") }),
                )
                Text(
                    "Selected crop: $cropWidthPx × $cropHeightPx px, $cropPixels pixels",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (cropIsSupported) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
                if (!cropIsSupported) {
                    Text(
                        if (!cropFitsDisplay) {
                            "This crop does not fit the workflow's $displayWidthPx × $displayHeightPx display. Resize or rotate it."
                        } else {
                            "This crop is too large. Reduce it to ${DecodedTemplate.MAX_TEMPLATE_PIXELS} pixels or fewer."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun TemplateCropWorkspace(
    source: TemplateCropSource,
    crop: NormalizedRect,
    onCropChange: (NormalizedRect) -> Unit,
    onFullScreen: () -> Unit,
    onReset: () -> Unit,
    onRotate: () -> Unit,
    onApply: () -> Unit,
    applyEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val configuration = LocalConfiguration.current
    val phonePortrait = configuration.screenWidthDp < configuration.screenHeightDp
    val sourceAspect = source.widthPx.toFloat() / source.heightPx
    val actionsBelow = phonePortrait && sourceAspect > 1f
    val workspaceHeight = when {
        actionsBelow ->
            ((configuration.screenWidthDp - 24f) / sourceAspect + 56f).coerceAtLeast(160f).dp

        !phonePortrait && sourceAspect < 1f -> (configuration.screenHeightDp * 0.86f).dp

        else -> (configuration.screenHeightDp * 0.68f).dp
    }
    BoxWithConstraints(
        modifier = modifier.height(workspaceHeight),
        contentAlignment = Alignment.Center,
    ) {
        val actionRailWidth = 48.dp
        val actionRowHeight = 52.dp
        val previewMaxHeight = if (actionsBelow) maxHeight - actionRowHeight else maxHeight
        val previewWidth = minOf(
            if (actionsBelow) maxWidth else maxWidth - actionRailWidth * 2,
            previewMaxHeight * sourceAspect,
        )
        val previewHeight = previewWidth / sourceAspect
        val rightSpace = (maxWidth - previewWidth) / 2

        TemplateCropCanvas(
            source = source,
            crop = crop,
            onCropChange = onCropChange,
            modifier = Modifier.align(if (actionsBelow) Alignment.TopCenter else Alignment.Center).size(previewWidth, previewHeight),
        )
        if (actionsBelow) {
            Row(
                modifier = Modifier.align(Alignment.BottomCenter).width(previewWidth).height(actionRowHeight),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onFullScreen) {
                    Icon(Icons.Outlined.Fullscreen, contentDescription = "Select full image")
                }
                IconButton(onClick = onReset) {
                    Icon(Icons.Outlined.RestartAlt, contentDescription = "Reset crop")
                }
                IconButton(onClick = onRotate) {
                    Icon(Icons.AutoMirrored.Outlined.RotateRight, contentDescription = "Rotate image clockwise")
                }
                IconButton(onClick = onApply, enabled = applyEnabled) {
                    Icon(Icons.Outlined.Check, contentDescription = "Use crop")
                }
            }
        } else {
            Box(
                modifier = Modifier.align(Alignment.CenterEnd).width(rightSpace).height(previewHeight),
            ) {
                IconButton(
                    onClick = onApply,
                    enabled = applyEnabled,
                    modifier = Modifier.align(Alignment.TopCenter),
                ) {
                    Icon(Icons.Outlined.Check, contentDescription = "Use crop")
                }
                Column(modifier = Modifier.align(Alignment.BottomCenter)) {
                    IconButton(onClick = onFullScreen) {
                        Icon(Icons.Outlined.Fullscreen, contentDescription = "Select full image")
                    }
                    IconButton(onClick = onReset) {
                        Icon(Icons.Outlined.RestartAlt, contentDescription = "Reset crop")
                    }
                    IconButton(onClick = onRotate) {
                        Icon(Icons.AutoMirrored.Outlined.RotateRight, contentDescription = "Rotate image clockwise")
                    }
                }
            }
        }
    }
}

private fun TemplateCropSource.rotated(quarterTurns: Int): TemplateCropSource {
    val turns = quarterTurns.mod(4)
    if (turns == 0) return this
    val sourceBitmap = preview.asAndroidBitmap()
    val rotatedBitmap = Bitmap.createBitmap(
        sourceBitmap,
        0,
        0,
        sourceBitmap.width,
        sourceBitmap.height,
        Matrix().apply { postRotate(turns * 90f) },
        true,
    )
    return copy(
        widthPx = if (turns % 2 == 0) widthPx else heightPx,
        heightPx = if (turns % 2 == 0) heightPx else widthPx,
        preview = rotatedBitmap.asImageBitmap(),
    )
}

@Composable
private fun TemplateCropCanvas(
    source: TemplateCropSource,
    crop: NormalizedRect,
    onCropChange: (NormalizedRect) -> Unit,
    modifier: Modifier = Modifier,
) {
    val borderColor = MaterialTheme.colorScheme.primary
    val handleColor = MaterialTheme.colorScheme.primary
    var activeHandle by remember(source.uri) { mutableStateOf<RegionDragHandle?>(null) }
    val currentCrop by rememberUpdatedState(crop)
    val currentOnCropChange by rememberUpdatedState(onCropChange)

    Box(
        modifier = modifier
            .background(Color.Black)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp)),
    ) {
        Image(
            bitmap = source.preview,
            contentDescription = "Image selected for template cropping",
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.fillMaxSize(),
        )
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(source.uri) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            activeHandle = selectRegionHandle(
                                region = currentCrop,
                                point = NormalizedCoordinate(
                                    x = (offset.x / size.width).toDouble().coerceIn(0.0, 1.0),
                                    y = (offset.y / size.height).toDouble().coerceIn(0.0, 1.0),
                                ),
                                threshold = 0.075,
                            )
                        },
                        onDragEnd = { activeHandle = null },
                        onDragCancel = { activeHandle = null },
                    ) { change, dragAmount ->
                        val handle = activeHandle ?: return@detectDragGestures
                        change.consume()
                        currentOnCropChange(
                            updateRegion(
                                region = currentCrop,
                                handle = handle,
                                deltaX = dragAmount.x.toDouble() / size.width,
                                deltaY = dragAmount.y.toDouble() / size.height,
                                minimumSize = 0.025,
                            ),
                        )
                    }
                },
        ) {
            val cropRect = Rect(
                left = (crop.x * size.width).toFloat(),
                top = (crop.y * size.height).toFloat(),
                right = ((crop.x + crop.width) * size.width).toFloat(),
                bottom = ((crop.y + crop.height) * size.height).toFloat(),
            )
            val shade = Color.Black.copy(alpha = 0.62f)
            drawRect(shade, size = androidx.compose.ui.geometry.Size(size.width, cropRect.top))
            drawRect(
                shade,
                topLeft = Offset(0f, cropRect.bottom),
                size = androidx.compose.ui.geometry.Size(size.width, size.height - cropRect.bottom),
            )
            drawRect(
                shade,
                topLeft = Offset(0f, cropRect.top),
                size = androidx.compose.ui.geometry.Size(cropRect.left, cropRect.height),
            )
            drawRect(
                shade,
                topLeft = Offset(cropRect.right, cropRect.top),
                size = androidx.compose.ui.geometry.Size(size.width - cropRect.right, cropRect.height),
            )
            val strokeWidth = 2.dp.toPx()
            val visibleRect = Rect(
                left = cropRect.left.coerceIn(strokeWidth / 2f, size.width - strokeWidth / 2f),
                top = cropRect.top.coerceIn(strokeWidth / 2f, size.height - strokeWidth / 2f),
                right = cropRect.right.coerceIn(strokeWidth / 2f, size.width - strokeWidth / 2f),
                bottom = cropRect.bottom.coerceIn(strokeWidth / 2f, size.height - strokeWidth / 2f),
            )
            drawRect(
                color = borderColor,
                topLeft = visibleRect.topLeft,
                size = visibleRect.size,
                style = Stroke(width = strokeWidth),
            )
            val handleRadius = 7.dp.toPx()
            val handleCenterRadius = 2.5.dp.toPx()
            listOf(cropRect.topLeft, cropRect.topRight, cropRect.bottomLeft, cropRect.bottomRight).forEach { point ->
                val visibleCenter = Offset(
                    x = point.x.coerceIn(handleRadius, size.width - handleRadius),
                    y = point.y.coerceIn(handleRadius, size.height - handleRadius),
                )
                drawCircle(color = handleColor, radius = handleRadius, center = visibleCenter)
                drawCircle(color = Color.White, radius = handleCenterRadius, center = visibleCenter)
            }
        }
    }
}

private fun NormalizedRect.pixelWidth(sourceWidthPx: Int): Int {
    val left = floor(x * sourceWidthPx).toInt()
    val right = ceil((x + width) * sourceWidthPx).toInt()
    return (right - left).coerceAtLeast(1)
}

private fun NormalizedRect.pixelHeight(sourceHeightPx: Int): Int {
    val top = floor(y * sourceHeightPx).toInt()
    val bottom = ceil((y + height) * sourceHeightPx).toInt()
    return (bottom - top).coerceAtLeast(1)
}
