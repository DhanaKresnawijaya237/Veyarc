package com.veyarc.feature.editor

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.veyarc.data.storage.AndroidKeystoreKeySource
import com.veyarc.data.storage.DecodedTemplate
import com.veyarc.data.storage.EncryptedTemplateAssetCodec
import com.veyarc.data.storage.TEMPLATE_ASSET_KEY_ALIAS
import com.veyarc.model.AssetId
import com.veyarc.model.AssetKind
import com.veyarc.model.NormalizedCoordinate
import com.veyarc.model.NormalizedRect
import com.veyarc.model.WorkflowIds
import com.veyarc.model.bundle.BundleAsset
import com.veyarc.ui.AnnotationStroke
import com.veyarc.ui.annotateCroppedImage
import com.veyarc.ui.renderEditedImage
import com.veyarc.ui.rotatedForEditing
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

private const val CROP_PREVIEW_MAX_PIXELS = DecodedTemplate.MAX_TEMPLATE_PIXELS
private const val MAX_SOURCE_IMAGE_BYTES = 64L * 1024L * 1024L

private const val TEMPLATE_MIME_TYPE = "image/x-veyarc-rgba"

class TemplateAssetImportException(message: String, cause: Throwable? = null) : Exception(message, cause)

internal data class TemplateCropSource(
    val uri: Uri,
    val widthPx: Int,
    val heightPx: Int,
    val preview: ImageBitmap,
    val existingAsset: BundleAsset? = null,
)

internal fun loadTemplateCropSource(asset: BundleAsset): TemplateCropSource {
    val preview = requireNotNull(decodeTemplatePreview(asset)) { "Image could not be opened" }
    return TemplateCropSource(Uri.parse("veyarc-template:${asset.assetId.value}"), preview.widthPx, preview.heightPx, preview.image, asset)
}

internal fun importEditedTemplate(source: TemplateCropSource, crop: NormalizedRect, turns: Int, strokes: List<AnnotationStroke>, replace: Boolean): BundleAsset {
    val asset = requireNotNull(source.existingAsset)
    val image = requireNotNull(decodeTemplatePreview(asset, maxOf(source.widthPx, source.heightPx))).image.asAndroidBitmap()
    val rotated = image.rotatedForEditing(turns)
    val result = try {
        renderEditedImage(rotated, crop, strokes)
    } finally {
        if (rotated !== image) rotated.recycle()
    }
    return encryptTemplateBitmap(result, if (replace) asset.assetId else AssetId("template-${WorkflowIds.newUuid()}"))
}

/** Loads a bounded display copy while retaining the original dimensions used for the final crop. */
internal fun loadTemplateCropSource(contentResolver: ContentResolver, uri: Uri): TemplateCropSource {
    val bounds = readImageBounds(contentResolver, uri)
    val sampleSize = calculatePreviewSampleSize(bounds.first, bounds.second)
    val preview = decodeBitmap(contentResolver, uri, sampleSize)
    return TemplateCropSource(
        uri = uri,
        widthPx = bounds.first,
        heightPx = bounds.second,
        preview = preview.asImageBitmap(),
    )
}

internal fun sampleImagePixel(resolver: ContentResolver, source: TemplateCropSource, point: NormalizedCoordinate): Long {
    source.existingAsset?.let { asset ->
        val decoded = EncryptedTemplateAssetCodec(AndroidKeystoreKeySource(TEMPLATE_ASSET_KEY_ALIAS)).decrypt(asset.assetId, asset.bytes)
        val rgba = decoded.takeRgbaBytes()
        return try {
            val x = (point.x * (decoded.widthPx - 1)).roundToInt().coerceIn(0, decoded.widthPx - 1)
            val y = (point.y * (decoded.heightPx - 1)).roundToInt().coerceIn(0, decoded.heightPx - 1)
            val offset = (y * decoded.widthPx + x) * 4
            ((rgba[offset + 3].toLong() and 255) shl 24) or
                ((rgba[offset].toLong() and 255) shl 16) or
                ((rgba[offset + 1].toLong() and 255) shl 8) or
                (rgba[offset + 2].toLong() and 255)
        } finally {
            rgba.fill(0)
        }
    }
    val x = (point.x * (source.widthPx - 1)).roundToInt().coerceIn(0, source.widthPx - 1)
    val y = (point.y * (source.heightPx - 1)).roundToInt().coerceIn(0, source.heightPx - 1)
    if (source.uri == Uri.EMPTY) return source.preview.asAndroidBitmap().getPixel(x, y).toLong() and 0xFFFFFFFFL
    val pixel = decodeRegion(resolver, source.uri, Rect(x, y, x + 1, y + 1))
    return try {
        pixel.getPixel(0, 0).toLong() and 0xFFFFFFFFL
    } finally {
        pixel.recycle()
    }
}

/** Chooses a power-of-two preview sample without overflowing on hostile image dimensions. */
internal fun calculatePreviewSampleSize(widthPx: Int, heightPx: Int): Int {
    require(widthPx > 0 && heightPx > 0) { "Image dimensions must be positive" }
    var sampleSize = 1L
    while (ceilDivide(widthPx, sampleSize) * ceilDivide(heightPx, sampleSize) > CROP_PREVIEW_MAX_PIXELS) {
        sampleSize *= 2L
        if (sampleSize > Int.MAX_VALUE) {
            throw TemplateAssetImportException("The selected image dimensions are too large")
        }
    }
    return sampleSize.toInt()
}

private fun ceilDivide(value: Int, divisor: Long): Long = (value.toLong() + divisor - 1L) / divisor

/** Imports only the user-selected crop and preserves its original pixel scale before encryption. */
internal fun importTemplateAsset(
    contentResolver: ContentResolver,
    uri: Uri,
    crop: NormalizedRect,
    rotationQuarterTurns: Int = 0,
    strokes: List<AnnotationStroke> = emptyList(),
): BundleAsset {
    val (sourceWidth, sourceHeight) = readImageBounds(contentResolver, uri)
    val turns = rotationQuarterTurns.mod(4)
    val cropBounds = crop.inSourceOrientation(turns).toPixelBounds(sourceWidth, sourceHeight)
    val cropPixelCount = cropBounds.width().toLong() * cropBounds.height()
    if (cropPixelCount > DecodedTemplate.MAX_TEMPLATE_PIXELS) {
        throw TemplateAssetImportException(
            "The selected crop contains $cropPixelCount pixels. Select ${DecodedTemplate.MAX_TEMPLATE_PIXELS} pixels or fewer.",
        )
    }

    val bitmap = decodeRegion(contentResolver, uri, cropBounds)
    val rotated = try {
        bitmap.rotateClockwise(turns)
    } catch (error: Exception) {
        bitmap.recycle()
        throw error
    }
    if (rotated !== bitmap) bitmap.recycle()
    if (strokes.isEmpty()) return encryptTemplateBitmap(rotated)
    val annotated = try {
        annotateCroppedImage(rotated, crop, strokes)
    } finally {
        rotated.recycle()
    }
    return encryptTemplateBitmap(annotated)
}

/** Rotates an existing encrypted crop while preserving its asset id and references. */
internal fun rotateTemplateAssetClockwise(asset: BundleAsset): BundleAsset {
    require(asset.kind == AssetKind.IMAGE_TEMPLATE && asset.encrypted) { "Only encrypted image templates can be rotated" }
    val codec = EncryptedTemplateAssetCodec(AndroidKeystoreKeySource(TEMPLATE_ASSET_KEY_ALIAS))
    val decoded = codec.decrypt(asset.assetId, asset.bytes)
    return try {
        val sourceBytes = decoded.copyRgbaBytes()
        val rotatedBytes = try {
            rotateRgbaClockwise(sourceBytes, decoded.widthPx, decoded.heightPx)
        } finally {
            sourceBytes.fill(0)
        }
        val rotated = DecodedTemplate(decoded.heightPx, decoded.widthPx, rotatedBytes)
        try {
            BundleAsset(
                assetId = asset.assetId,
                kind = asset.kind,
                mimeType = asset.mimeType,
                bytes = codec.encrypt(asset.assetId, rotated),
                encrypted = true,
                widthPx = rotated.widthPx,
                heightPx = rotated.heightPx,
            )
        } finally {
            rotated.clear()
            rotatedBytes.fill(0)
        }
    } finally {
        decoded.clear()
    }
}

internal fun rotateRgbaClockwise(bytes: ByteArray, widthPx: Int, heightPx: Int): ByteArray {
    require(widthPx > 0 && heightPx > 0 && bytes.size.toLong() == widthPx.toLong() * heightPx * 4L)
    val rotated = ByteArray(bytes.size)
    for (y in 0 until heightPx) {
        for (x in 0 until widthPx) {
            val source = (y * widthPx + x) * 4
            val targetX = heightPx - 1 - y
            val targetY = x
            val target = (targetY * heightPx + targetX) * 4
            bytes.copyInto(rotated, target, source, source + 4)
        }
    }
    return rotated
}

internal fun NormalizedRect.rotatedClockwise(): NormalizedRect = NormalizedRect(
    x = 1.0 - y - height,
    y = x,
    width = height,
    height = width,
)

internal fun NormalizedRect.inSourceOrientation(rotationQuarterTurns: Int): NormalizedRect = when (rotationQuarterTurns.mod(4)) {
    0 -> this
    1 -> NormalizedRect(y, 1.0 - x - width, height, width)
    2 -> NormalizedRect(1.0 - x - width, 1.0 - y - height, width, height)
    else -> NormalizedRect(1.0 - y - height, x, height, width)
}

private fun readImageBounds(contentResolver: ContentResolver, uri: Uri): Pair<Int, Int> {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    try {
        val input = openBoundedImageStream(contentResolver, uri)
        input.use {
            BitmapFactory.decodeStream(input, null, bounds)
        }
    } catch (error: TemplateAssetImportException) {
        throw error
    } catch (error: IOException) {
        throw TemplateAssetImportException("Could not read the selected image", error)
    }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        throw TemplateAssetImportException("The selected file is not a readable image")
    }
    return bounds.outWidth to bounds.outHeight
}

private fun decodeBitmap(contentResolver: ContentResolver, uri: Uri, sampleSize: Int): Bitmap = try {
    val input = openBoundedImageStream(contentResolver, uri)
    input.use {
        BitmapFactory.decodeStream(
            input,
            null,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inScaled = false
            },
        )
    }
} catch (error: TemplateAssetImportException) {
    throw error
} catch (error: IOException) {
    throw TemplateAssetImportException("Could not decode the selected image", error)
} ?: throw TemplateAssetImportException("The selected file is not a readable image")

private fun decodeRegion(contentResolver: ContentResolver, uri: Uri, bounds: Rect): Bitmap {
    val decoder = try {
        val input = openBoundedImageStream(contentResolver, uri)
        input.use {
            BitmapRegionDecoder.newInstance(input, false)
        } ?: throw TemplateAssetImportException("The selected file does not support region decoding")
    } catch (error: TemplateAssetImportException) {
        throw error
    } catch (error: IOException) {
        throw TemplateAssetImportException("Could not prepare the selected image crop", error)
    }
    return try {
        decoder.decodeRegion(
            bounds,
            BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inScaled = false
            },
        ) ?: throw TemplateAssetImportException("The selected crop could not be decoded")
    } finally {
        decoder.recycle()
    }
}

private fun Bitmap.rotateClockwise(quarterTurns: Int): Bitmap {
    val turns = quarterTurns.mod(4)
    if (turns == 0) return this
    return Bitmap.createBitmap(
        this,
        0,
        0,
        width,
        height,
        Matrix().apply { postRotate(turns * 90f) },
        true,
    )
}

private fun openBoundedImageStream(contentResolver: ContentResolver, uri: Uri): InputStream {
    val input = contentResolver.openInputStream(uri)
        ?: throw TemplateAssetImportException("The selected file could not be opened")
    return ByteLimitInputStream(input, MAX_SOURCE_IMAGE_BYTES)
}

internal class ByteLimitInputStream(
    input: InputStream,
    private val maximumBytes: Long,
) : FilterInputStream(input) {
    private var consumedBytes = 0L

    init {
        require(maximumBytes > 0L) { "maximumBytes must be positive" }
    }

    override fun read(): Int = super.read().also { value ->
        if (value >= 0) recordRead(1L)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = super.read(buffer, offset, length).also { count ->
        if (count > 0) recordRead(count.toLong())
    }

    override fun skip(byteCount: Long): Long = super.skip(byteCount).also(::recordRead)

    private fun recordRead(count: Long) {
        consumedBytes += count
        if (consumedBytes > maximumBytes) {
            throw IOException("Image source exceeds its read safety limit")
        }
    }
}

fun encryptTemplateBitmap(bitmap: Bitmap, assetId: AssetId = AssetId("template-${WorkflowIds.newUuid()}")): BundleAsset = try {
    val pixelCount = bitmap.width.toLong() * bitmap.height
    if (pixelCount > DecodedTemplate.MAX_TEMPLATE_PIXELS) {
        throw TemplateAssetImportException("The selected image is too large to use as a template")
    }
    val rgba = bitmap.toRgbaBytes()
    val decoded = DecodedTemplate(bitmap.width, bitmap.height, rgba)
    val encrypted = try {
        EncryptedTemplateAssetCodec(
            keySource = AndroidKeystoreKeySource(TEMPLATE_ASSET_KEY_ALIAS),
        ).encrypt(
            assetId = assetId,
            template = decoded,
        )
    } finally {
        decoded.clear()
        rgba.fill(0)
    }
    BundleAsset(
        assetId = assetId,
        kind = AssetKind.IMAGE_TEMPLATE,
        mimeType = TEMPLATE_MIME_TYPE,
        bytes = encrypted,
        encrypted = true,
        widthPx = bitmap.width,
        heightPx = bitmap.height,
    )
} finally {
    bitmap.recycle()
}

/** App bridge only: imports a reviewed crop from an immutable, locally submitted PNG. */
fun importAssistantTemplate(bytes: ByteArray, assetId: AssetId, crop: NormalizedRect, background: Boolean): BundleAsset {
    require(bytes.size in 1..32 * 1024 * 1024)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 4_194_304)
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("Image could not be read")
    val region = crop.toPixelBounds(bitmap.width, bitmap.height)
    val cropped = try {
        Bitmap.createBitmap(bitmap, region.left, region.top, region.width(), region.height())
    } catch (error: Exception) {
        bitmap.recycle()
        throw error
    }
    if (cropped !== bitmap) bitmap.recycle()
    if (background && cropped.width.toLong() * cropped.height > DecodedTemplate.MAX_TEMPLATE_PIXELS) {
        val scale = kotlin.math.sqrt(DecodedTemplate.MAX_TEMPLATE_PIXELS.toDouble() / (cropped.width.toDouble() * cropped.height))
        val scaled = Bitmap.createScaledBitmap(cropped, (cropped.width * scale).toInt().coerceAtLeast(1), (cropped.height * scale).toInt().coerceAtLeast(1), true)
        if (scaled !== cropped) cropped.recycle()
        return encryptTemplateBitmap(scaled, assetId)
    }
    return encryptTemplateBitmap(cropped, assetId)
}

private fun NormalizedRect.toPixelBounds(widthPx: Int, heightPx: Int): Rect {
    val left = floor(x * widthPx).toInt().coerceIn(0, widthPx - 1)
    val top = floor(y * heightPx).toInt().coerceIn(0, heightPx - 1)
    val right = ceil((x + width) * widthPx).toInt().coerceIn(left + 1, widthPx)
    val bottom = ceil((y + height) * heightPx).toInt().coerceIn(top + 1, heightPx)
    return Rect(left, top, max(left + 1, right), max(top + 1, bottom))
}

internal fun Bitmap.toRgbaBytes(): ByteArray {
    val output = ByteArray(width * height * 4)
    val row = IntArray(width)
    for (y in 0 until height) {
        getPixels(row, 0, width, 0, y, width, 1)
        for (x in 0 until width) {
            val argb = row[x]
            val offset = (y * width + x) * 4
            output[offset] = (argb shr 16).toByte()
            output[offset + 1] = (argb shr 8).toByte()
            output[offset + 2] = argb.toByte()
            output[offset + 3] = (argb ushr 24).toByte()
        }
    }
    row.fill(0)
    return output
}
