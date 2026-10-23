package com.veyarc.feature.editor

import android.content.res.Configuration
import android.graphics.Bitmap
import android.util.LruCache
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.veyarc.data.storage.AndroidKeystoreKeySource
import com.veyarc.data.storage.DecodedTemplate
import com.veyarc.data.storage.EncryptedTemplateAssetCodec
import com.veyarc.model.AssetId
import com.veyarc.model.NormalizedCoordinate
import com.veyarc.model.NormalizedRect
import com.veyarc.model.bundle.BundleAsset
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import android.graphics.Color as AndroidColor

private const val TEMPLATE_KEY_ALIAS = "veyarc.template-assets.v1"
internal const val PREVIEW_MAX_DIMENSION = 720
private const val DEVICE_PREVIEW_HEIGHT_DP = 360

@Composable
internal fun ImageConditionPreview(
    region: NormalizedRect,
    displayWidthPx: Int,
    displayHeightPx: Int,
    assetId: AssetId?,
    backgroundAssetId: AssetId? = null,
    loadPreview: suspend (AssetId, Int) -> TemplatePreviewData?,
    screenStateMode: Boolean = false,
    colorSearch: Boolean = false,
    runtimeImage: Boolean = false,
    onRegionChange: ((NormalizedRect) -> Unit)?,
) {
    val safeDisplayWidth = displayWidthPx.coerceAtLeast(1)
    val safeDisplayHeight = displayHeightPx.coerceAtLeast(1)
    val preview by produceState<TemplatePreviewState>(
        initialValue = assetId?.let { cachedTemplatePreview(it, PREVIEW_MAX_DIMENSION) }?.let(TemplatePreviewState::Ready)
            ?: if (assetId == null) TemplatePreviewState.Unavailable else TemplatePreviewState.Loading,
        key1 = assetId,
    ) {
        value = assetId?.let { cachedTemplatePreview(it, PREVIEW_MAX_DIMENSION) }?.let(TemplatePreviewState::Ready)
            ?: if (assetId == null) TemplatePreviewState.Unavailable else TemplatePreviewState.Loading
        value = assetId?.let { selected ->
            (cachedTemplatePreview(selected, PREVIEW_MAX_DIMENSION) ?: loadPreview(selected, PREVIEW_MAX_DIMENSION))
                ?.let(TemplatePreviewState::Ready)
                ?: TemplatePreviewState.Unavailable
        } ?: TemplatePreviewState.Unavailable
    }
    val backgroundPreview by produceState(backgroundAssetId?.let { cachedTemplatePreview(it, PREVIEW_MAX_DIMENSION) }, backgroundAssetId) {
        value = backgroundAssetId?.let { cachedTemplatePreview(it, PREVIEW_MAX_DIMENSION) }
        value = backgroundAssetId?.let { selected ->
            cachedTemplatePreview(selected, PREVIEW_MAX_DIMENSION) ?: loadPreview(selected, PREVIEW_MAX_DIMENSION)
        }
    }
    val configuration = LocalConfiguration.current
    val portrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT
    val previewHeight = if (portrait && safeDisplayWidth > safeDisplayHeight) {
        max(180f, configuration.screenWidthDp * safeDisplayHeight.toFloat() / safeDisplayWidth + 28f)
    } else if (portrait) {
        460f
    } else {
        DEVICE_PREVIEW_HEIGHT_DP.toFloat()
    }
    val searchBounds = PreviewPixelBounds.from(region, safeDisplayWidth, safeDisplayHeight)

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                if (runtimeImage) {
                    "Captured image search area"
                } else if (colorSearch) {
                    "Colour search area"
                } else {
                    "Current-device comparison"
                },
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                if (screenStateMode) "Compare with the whole screen." else "Drag the area or its corners to choose where Veyarc searches.",
                modifier = Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            DeviceComparisonPreview(
                region = region,
                displayWidthPx = safeDisplayWidth,
                displayHeightPx = safeDisplayHeight,
                template = (preview as? TemplatePreviewState.Ready)?.data,
                backgroundTemplate = backgroundPreview,
                onRegionChange = onRegionChange,
                screenStateMode = screenStateMode,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(previewHeight.dp)
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
            if (!colorSearch && !runtimeImage) {
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
}

@Composable
private fun DeviceComparisonPreview(
    region: NormalizedRect,
    displayWidthPx: Int,
    displayHeightPx: Int,
    template: TemplatePreviewData?,
    backgroundTemplate: TemplatePreviewData?,
    onRegionChange: ((NormalizedRect) -> Unit)?,
    screenStateMode: Boolean,
    modifier: Modifier = Modifier,
) {
    val displayAspectRatio = displayWidthPx.toFloat() / displayHeightPx
    val searchColor = MaterialTheme.colorScheme.primary
    val templateBorderColor = MaterialTheme.colorScheme.tertiary
    var dragRegion by remember { mutableStateOf<NormalizedRect?>(null) }
    val displayedRegion = dragRegion ?: region
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val horizontalPadding = 12.dp
        val verticalPadding = 8.dp
        val availableWidth = (maxWidth - horizontalPadding * 2).coerceAtLeast(0.dp)
        val availableHeight = (maxHeight - verticalPadding * 2).coerceAtLeast(0.dp)
        val screenWidth = minOf(availableWidth, availableHeight * displayAspectRatio)
        val screenHeight = screenWidth / displayAspectRatio

        Box(
            modifier = Modifier
                .size(screenWidth, screenHeight)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(2.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp)),
        ) {
            backgroundTemplate?.let { data ->
                Image(
                    bitmap = data.image,
                    contentDescription = "Selected screen background",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds,
                )
            }
            template?.let { data ->
                val templateWidth = screenWidth * data.widthPx.toFloat() / displayWidthPx
                val templateHeight = screenHeight * data.heightPx.toFloat() / displayHeightPx
                val searchLeft = screenWidth * displayedRegion.x.toFloat()
                val searchTop = screenHeight * displayedRegion.y.toFloat()
                val searchWidth = screenWidth * displayedRegion.width.toFloat()
                val searchHeight = screenHeight * displayedRegion.height.toFloat()
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
                    try {
                        detectDragGestures(
                            onDragStart = dragStart@{ offset ->
                                if (size.width == 0 || size.height == 0) return@dragStart
                                activeHandle = selectRegionHandle(
                                    region = currentRegion,
                                    point = NormalizedCoordinate(
                                        x = (offset.x / size.width).toDouble().coerceIn(0.0, 1.0),
                                        y = (offset.y / size.height).toDouble().coerceIn(0.0, 1.0),
                                    ),
                                    threshold = 0.075,
                                )
                                if (activeHandle != null) dragRegion = currentRegion
                            },
                            onDragEnd = {
                                val selected = dragRegion
                                dragRegion = null
                                activeHandle = null
                                selected?.takeIf { it != currentRegion }?.let { currentOnRegionChange?.invoke(it) }
                            },
                            onDragCancel = {
                                dragRegion = null
                                activeHandle = null
                            },
                        ) { change, dragAmount ->
                            val handle = activeHandle?.takeIf { size.width > 0 && size.height > 0 } ?: return@detectDragGestures
                            change.consume()
                            // Keep layout-changing condition updates until release, while the crop follows the finger.
                            dragRegion = updateRegion(
                                region = dragRegion ?: currentRegion,
                                handle = handle,
                                deltaX = dragAmount.x.toDouble() / size.width,
                                deltaY = dragAmount.y.toDouble() / size.height,
                            )
                        }
                    } finally {
                        dragRegion = null
                        activeHandle = null
                    }
                }
            }
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("condition-search-area")
                    .then(interactionModifier),
            ) {
                val handleRadius = 7.dp.toPx()
                // A collapsing parent can temporarily measure this preview at zero size.
                if (size.minDimension < handleRadius * 2) return@Canvas
                val searchRect = Rect(
                    left = (displayedRegion.x * size.width).toFloat(),
                    top = (displayedRegion.y * size.height).toFloat(),
                    right = ((displayedRegion.x + displayedRegion.width) * size.width).toFloat(),
                    bottom = ((displayedRegion.y + displayedRegion.height) * size.height).toFloat(),
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
                        "Ready",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Text(
                        "Use a $displayWidthPx × $displayHeightPx full-screen image.",
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

                    else -> Unit
                }
                if (data.widthPx.toLong() * data.heightPx > 1_000_000L || heightPercent > 70.0) {
                    Text(
                        "Use a smaller crop for faster matching.",
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

internal data class TemplatePreviewData(
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

internal fun decodeTemplatePreview(
    asset: BundleAsset,
    maxDimension: Int = 1_200,
): TemplatePreviewData? = try {
    require(maxDimension > 0)
    val key = "${asset.assetId.value}:$maxDimension"
    sourcePreviewCache.get(key)?.let { return it }
    val decoded = EncryptedTemplateAssetCodec(
        AndroidKeystoreKeySource(TEMPLATE_KEY_ALIAS),
    ).decrypt(asset.assetId, asset.bytes)
    try {
        buildTemplatePreview(decoded, maxDimension, decoded.widthPx, decoded.heightPx).also { sourcePreviewCache.put(key, it) }
    } finally {
        decoded.clear()
    }
} catch (_: Exception) {
    null
}

internal fun cachedTemplatePreview(assetId: AssetId, maxDimension: Int): TemplatePreviewData? = templatePreviewCache.get("${assetId.value}:$maxDimension")

internal fun decodeDisplayTemplatePreview(asset: BundleAsset, maxDimension: Int): TemplatePreviewData? = cachedTemplatePreview(asset.assetId, maxDimension)
    ?: decodeTemplatePreview(asset, maxDimension)?.also { templatePreviewCache.put("${asset.assetId.value}:$maxDimension", it) }

internal fun clearTemplatePreviewCache() {
    templatePreviewCache.evictAll()
    sourcePreviewCache.evictAll()
}

internal fun invalidateTemplatePreview(assetId: AssetId) {
    listOf(templatePreviewCache, sourcePreviewCache).forEach { cache ->
        cache.snapshot().keys.filter { it.startsWith("${assetId.value}:") }.forEach(cache::remove)
    }
}

internal fun decodeTemplatePreview(
    assetId: AssetId,
    decoded: DecodedTemplate,
    maxDimension: Int,
    sourceWidthPx: Int = decoded.widthPx,
    sourceHeightPx: Int = decoded.heightPx,
): TemplatePreviewData? = try {
    require(maxDimension > 0)
    val cacheKey = "${assetId.value}:$maxDimension"
    templatePreviewCache.get(cacheKey)?.let { return it }
    buildTemplatePreview(decoded, maxDimension, sourceWidthPx, sourceHeightPx).also { templatePreviewCache.put(cacheKey, it) }
} catch (_: Exception) {
    null
}

private fun buildTemplatePreview(decoded: DecodedTemplate, maxDimension: Int, sourceWidthPx: Int, sourceHeightPx: Int): TemplatePreviewData {
    val rgbaBytes = decoded.copyRgbaBytes()
    try {
        val scale = min(
            1f,
            min(maxDimension.toFloat() / decoded.widthPx, maxDimension.toFloat() / decoded.heightPx),
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
        createdBitmap.prepareToDraw()
        return TemplatePreviewData(
            image = createdBitmap.asImageBitmap(),
            widthPx = sourceWidthPx,
            heightPx = sourceHeightPx,
        )
    } finally {
        rgbaBytes.fill(0)
    }
}

private val templatePreviewCache = object : LruCache<String, TemplatePreviewData>(16 * 1024 * 1024) {
    override fun sizeOf(key: String, value: TemplatePreviewData): Int = value.image.width * value.image.height * 4
}

// Full-resolution edits must neither reuse thumbnails nor evict frequently displayed previews.
private val sourcePreviewCache = object : LruCache<String, TemplatePreviewData>(16 * 1024 * 1024) {
    override fun sizeOf(key: String, value: TemplatePreviewData): Int = value.image.width * value.image.height * 4
}
