package com.veyarc.feature.editor

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.veyarc.data.storage.AndroidKeystoreKeySource
import com.veyarc.data.storage.EncryptedTemplateAssetCodec
import com.veyarc.model.NormalizedCoordinate
import com.veyarc.model.NormalizedRect
import com.veyarc.model.bundle.BundleAsset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import android.graphics.Color as AndroidColor

private const val TEMPLATE_KEY_ALIAS = "veyarc.template-assets.v1"
private const val PREVIEW_MAX_DIMENSION = 320
private const val DEVICE_PREVIEW_HEIGHT_DP = 360

@Composable
internal fun ImageConditionPreview(
    region: NormalizedRect,
    displayWidthPx: Int,
    displayHeightPx: Int,
    asset: BundleAsset?,
    screenStateMode: Boolean = false,
    onRegionChange: ((NormalizedRect) -> Unit)?,
) {
    val safeDisplayWidth = displayWidthPx.coerceAtLeast(1)
    val safeDisplayHeight = displayHeightPx.coerceAtLeast(1)
    val preview by produceState<TemplatePreviewState>(
        initialValue = if (asset == null) TemplatePreviewState.Unavailable else TemplatePreviewState.Loading,
        key1 = asset?.assetId,
    ) {
        value = asset?.let { selected ->
            withContext(Dispatchers.Default) { decodeTemplatePreview(selected) }
                ?.let(TemplatePreviewState::Ready)
                ?: TemplatePreviewState.Unavailable
        } ?: TemplatePreviewState.Unavailable
    }
    val searchBounds = PreviewPixelBounds.from(region, safeDisplayWidth, safeDisplayHeight)

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Current-device comparison", style = MaterialTheme.typography.titleSmall)
            Text(
                if (screenStateMode) {
                    "Veyarc compares this image once against the complete current screen. The image must have the same pixel dimensions and orientation as the screen."
                } else {
                    "The device outline uses the current screen dimensions. Drag the purple area or its corners to choose where Veyarc searches. The image is drawn at the same pixel scale used by the matcher."
                },
                modifier = Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            DeviceComparisonPreview(
                region = region,
                displayWidthPx = safeDisplayWidth,
                displayHeightPx = safeDisplayHeight,
                template = (preview as? TemplatePreviewState.Ready)?.data,
                onRegionChange = onRegionChange,
                screenStateMode = screenStateMode,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(DEVICE_PREVIEW_HEIGHT_DP.dp)
                    .padding(top = 10.dp),
            )
            Text(
                "Current screen: $safeDisplayWidth × $safeDisplayHeight px",
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.labelMedium,
            )
            if (!screenStateMode) {
                Text(
                    "Search area: ${searchBounds.width} × ${searchBounds.height} px at " +
                        "X ${region.x.toEditorText()}, Y ${region.y.toEditorText()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TemplateScaleDetails(
                preview = preview,
                displayWidthPx = safeDisplayWidth,
                displayHeightPx = safeDisplayHeight,
                searchBounds = searchBounds,
                region = region,
                screenStateMode = screenStateMode,
            )
        }
    }
}

@Composable
private fun DeviceComparisonPreview(
    region: NormalizedRect,
    displayWidthPx: Int,
    displayHeightPx: Int,
    template: TemplatePreviewData?,
    onRegionChange: ((NormalizedRect) -> Unit)?,
    screenStateMode: Boolean,
    modifier: Modifier = Modifier,
) {
    val displayAspectRatio = displayWidthPx.toFloat() / displayHeightPx
    val searchColor = MaterialTheme.colorScheme.primary
    val templateBorderColor = MaterialTheme.colorScheme.tertiary
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val horizontalPadding = 12.dp
        val verticalPadding = 8.dp
        val availableWidth = maxWidth - horizontalPadding * 2
        val availableHeight = maxHeight - verticalPadding * 2
        val screenWidth = minOf(availableWidth, availableHeight * displayAspectRatio)
        val screenHeight = screenWidth / displayAspectRatio

        Box(
            modifier = Modifier
                .size(screenWidth, screenHeight)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(2.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp)),
        ) {
            template?.let { data ->
                val templateWidth = screenWidth * data.widthPx.toFloat() / displayWidthPx
                val templateHeight = screenHeight * data.heightPx.toFloat() / displayHeightPx
                val searchLeft = screenWidth * region.x.toFloat()
                val searchTop = screenHeight * region.y.toFloat()
                val searchWidth = screenWidth * region.width.toFloat()
                val searchHeight = screenHeight * region.height.toFloat()
                val templateLeft = searchLeft + (searchWidth - templateWidth) / 2f
                val templateTop = searchTop + (searchHeight - templateHeight) / 2f
                Image(
                    bitmap = data.image,
                    contentDescription = "Selected template at its current-device comparison scale",
                    modifier = Modifier
                        .offset(x = templateLeft, y = templateTop)
                        .size(width = templateWidth, height = templateHeight)
                        .border(2.dp, templateBorderColor, RoundedCornerShape(3.dp)),
                    contentScale = ContentScale.FillBounds,
                )
            }
            var activeHandle by remember { mutableStateOf<RegionDragHandle?>(null) }
            val currentRegion by rememberUpdatedState(region)
            val currentOnRegionChange by rememberUpdatedState(onRegionChange)
            val interactionModifier = if (onRegionChange == null) {
                Modifier
            } else {
                Modifier.pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            activeHandle = selectRegionHandle(
                                region = currentRegion,
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
                        currentOnRegionChange?.invoke(
                            updateRegion(
                                region = currentRegion,
                                handle = handle,
                                deltaX = dragAmount.x.toDouble() / size.width,
                                deltaY = dragAmount.y.toDouble() / size.height,
                            ),
                        )
                    }
                }
            }
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .then(interactionModifier),
            ) {
                val searchRect = Rect(
                    left = (region.x * size.width).toFloat(),
                    top = (region.y * size.height).toFloat(),
                    right = ((region.x + region.width) * size.width).toFloat(),
                    bottom = ((region.y + region.height) * size.height).toFloat(),
                )
                val strokeWidth = 2.dp.toPx()
                val visibleRect = Rect(
                    left = searchRect.left.coerceIn(strokeWidth / 2f, size.width - strokeWidth / 2f),
                    top = searchRect.top.coerceIn(strokeWidth / 2f, size.height - strokeWidth / 2f),
                    right = searchRect.right.coerceIn(strokeWidth / 2f, size.width - strokeWidth / 2f),
                    bottom = searchRect.bottom.coerceIn(strokeWidth / 2f, size.height - strokeWidth / 2f),
                )
                if (!screenStateMode) {
                    drawRect(
                        color = searchColor.copy(alpha = 0.18f),
                        topLeft = visibleRect.topLeft,
                        size = visibleRect.size,
                    )
                }
                drawRect(
                    color = searchColor,
                    topLeft = visibleRect.topLeft,
                    size = visibleRect.size,
                    style = Stroke(width = strokeWidth),
                )
                if (!screenStateMode) {
                    val handleRadius = 7.dp.toPx()
                    val handleCenterRadius = 2.5.dp.toPx()
                    listOf(searchRect.topLeft, searchRect.topRight, searchRect.bottomLeft, searchRect.bottomRight).forEach { point ->
                        val visibleCenter = Offset(
                            x = point.x.coerceIn(handleRadius, size.width - handleRadius),
                            y = point.y.coerceIn(handleRadius, size.height - handleRadius),
                        )
                        drawCircle(color = searchColor, radius = handleRadius, center = visibleCenter)
                        drawCircle(color = Color.White, radius = handleCenterRadius, center = visibleCenter)
                    }
                }
            }
        }
    }
}

@Composable
private fun TemplateScaleDetails(
    preview: TemplatePreviewState,
    displayWidthPx: Int,
    displayHeightPx: Int,
    searchBounds: PreviewPixelBounds,
    region: NormalizedRect,
    screenStateMode: Boolean,
) {
    when (preview) {
        TemplatePreviewState.Loading -> Text(
            "Loading encrypted image preview...",
            modifier = Modifier.padding(top = 6.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        TemplatePreviewState.Unavailable -> Text(
            "No readable image template is selected.",
            modifier = Modifier.padding(top = 6.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )

        is TemplatePreviewState.Ready -> {
            val data = preview.data
            val widthPercent = data.widthPx * 100.0 / displayWidthPx
            val heightPercent = data.heightPx * 100.0 / displayHeightPx
            val originColumns = (searchBounds.width - data.widthPx + 1).coerceAtLeast(0)
            val originRows = (searchBounds.height - data.heightPx + 1).coerceAtLeast(0)
            val originCount = originColumns.toLong() * originRows
            Text(
                "Template: ${data.widthPx} × ${data.heightPx} px " +
                    "(${widthPercent.roundToInt()}% of screen width, ${heightPercent.roundToInt()}% of screen height)",
                modifier = Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.bodySmall,
            )
            if (screenStateMode) {
                if (data.widthPx == displayWidthPx && data.heightPx == displayHeightPx) {
                    Text(
                        "Ready for one fixed-position coarse check followed by full-resolution verification.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Text(
                        "This image does not match the current screen dimensions. Add an uncropped $displayWidthPx × $displayHeightPx screenshot for screen-state matching.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            } else {
                when {
                    originCount == 0L -> Text(
                        "The template is larger than the purple search area, so this condition cannot match.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )

                    else -> Text(
                        "Possible positions at this scale: $originCount. The centered placement above only shows size; Veyarc checks each valid position inside the purple area.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (data.widthPx.toLong() * data.heightPx > 1_000_000L || heightPercent > 70.0) {
                    Text(
                        "This template is too large for a reliable search. Remove it and add a small, stable crop instead.",
                        modifier = Modifier.padding(top = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (region.width >= 0.9 && data.widthPx * 2 <= displayWidthPx + 2) {
                    Text(
                        "If this image came from a full-screen screenshot, remove it and add it again. Older builds reduced large images and lost their screen scale.",
                        modifier = Modifier.padding(top = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

private sealed interface TemplatePreviewState {
    data object Loading : TemplatePreviewState

    data object Unavailable : TemplatePreviewState

    data class Ready(val data: TemplatePreviewData) : TemplatePreviewState
}

private data class TemplatePreviewData(
    val image: ImageBitmap,
    val widthPx: Int,
    val heightPx: Int,
)

private data class PreviewPixelBounds(
    val left: Int,
    val top: Int,
    val rightExclusive: Int,
    val bottomExclusive: Int,
) {
    val width: Int get() = rightExclusive - left
    val height: Int get() = bottomExclusive - top

    companion object {
        fun from(region: NormalizedRect, displayWidthPx: Int, displayHeightPx: Int): PreviewPixelBounds {
            val left = floor(region.x * displayWidthPx).toInt().coerceIn(0, displayWidthPx - 1)
            val top = floor(region.y * displayHeightPx).toInt().coerceIn(0, displayHeightPx - 1)
            val right = ceil((region.x + region.width) * displayWidthPx).toInt()
                .coerceIn(left + 1, displayWidthPx)
            val bottom = ceil((region.y + region.height) * displayHeightPx).toInt()
                .coerceIn(top + 1, displayHeightPx)
            return PreviewPixelBounds(left, top, right, bottom)
        }
    }
}

private fun decodeTemplatePreview(asset: BundleAsset): TemplatePreviewData? = try {
    val decoded = EncryptedTemplateAssetCodec(
        AndroidKeystoreKeySource(TEMPLATE_KEY_ALIAS),
    ).decrypt(asset.assetId, asset.bytes)
    try {
        val rgbaBytes = decoded.copyRgbaBytes()
        try {
            val scale = min(
                1f,
                min(
                    PREVIEW_MAX_DIMENSION.toFloat() / decoded.widthPx,
                    PREVIEW_MAX_DIMENSION.toFloat() / decoded.heightPx,
                ),
            )
            val previewWidth = max(1, (decoded.widthPx * scale).roundToInt())
            val previewHeight = max(1, (decoded.heightPx * scale).roundToInt())
            val createdBitmap = Bitmap.createBitmap(previewWidth, previewHeight, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(previewWidth * previewHeight)
            try {
                for (y in 0 until previewHeight) {
                    val sourceY = min(decoded.heightPx - 1, y * decoded.heightPx / previewHeight)
                    for (x in 0 until previewWidth) {
                        val sourceX = min(decoded.widthPx - 1, x * decoded.widthPx / previewWidth)
                        val sourceOffset = (sourceY * decoded.widthPx + sourceX) * 4
                        pixels[y * previewWidth + x] = AndroidColor.argb(
                            rgbaBytes[sourceOffset + 3].toInt() and 0xFF,
                            rgbaBytes[sourceOffset].toInt() and 0xFF,
                            rgbaBytes[sourceOffset + 1].toInt() and 0xFF,
                            rgbaBytes[sourceOffset + 2].toInt() and 0xFF,
                        )
                    }
                }
                createdBitmap.setPixels(pixels, 0, previewWidth, 0, 0, previewWidth, previewHeight)
            } finally {
                pixels.fill(0)
            }
            TemplatePreviewData(
                image = createdBitmap.asImageBitmap(),
                widthPx = decoded.widthPx,
                heightPx = decoded.heightPx,
            )
        } finally {
            rgbaBytes.fill(0)
        }
    } finally {
        decoded.clear()
    }
} catch (_: Exception) {
    null
}
