package com.veyarc.feature.editor

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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

@Composable
internal fun TemplateCropEditorDialog(
    source: TemplateCropSource,
    onDismiss: () -> Unit,
    onApply: (NormalizedRect) -> Unit,
) {
    var crop by remember(source.uri) { mutableStateOf(DEFAULT_TEMPLATE_CROP) }
    val cropWidthPx = crop.pixelWidth(source.widthPx)
    val cropHeightPx = crop.pixelHeight(source.heightPx)
    val cropPixels = cropWidthPx.toLong() * cropHeightPx
    val cropIsSupported = cropPixels <= DecodedTemplate.MAX_TEMPLATE_PIXELS

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Select the template area", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "For 'Image appears somewhere,' choose a small stable detail such as a button, icon, or label. For 'Screen state matches,' use the complete screenshot. Drag inside the box to move it or drag a corner to resize it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TemplateCropCanvas(
                    source = source,
                    crop = crop,
                    onCropChange = { crop = it },
                    modifier = Modifier.fillMaxWidth(),
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
                        "This crop is too large. Reduce it to ${DecodedTemplate.MAX_TEMPLATE_PIXELS} pixels or fewer.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = { crop = DEFAULT_TEMPLATE_CROP },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Reset crop")
                    }
                    Button(
                        onClick = { onApply(crop) },
                        enabled = cropIsSupported,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Use crop")
                    }
                }
                OutlinedButton(
                    onClick = { crop = NormalizedRect(0.0, 0.0, 1.0, 1.0) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Select full image for screen-state matching")
                }
                OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text("Cancel")
                }
            }
        }
    }
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
    BoxWithConstraints(
        modifier = modifier.height(520.dp),
        contentAlignment = Alignment.Center,
    ) {
        val sourceAspect = source.widthPx.toFloat() / source.heightPx
        val previewWidth = minOf(maxWidth, maxHeight * sourceAspect)
        val previewHeight = previewWidth / sourceAspect
        var activeHandle by remember(source.uri) { mutableStateOf<RegionDragHandle?>(null) }
        val currentCrop by rememberUpdatedState(crop)
        val currentOnCropChange by rememberUpdatedState(onCropChange)

        Box(
            modifier = Modifier
                .size(previewWidth, previewHeight)
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
