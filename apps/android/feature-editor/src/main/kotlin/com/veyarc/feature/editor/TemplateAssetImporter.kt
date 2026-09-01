package com.veyarc.feature.editor

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.veyarc.data.storage.AndroidKeystoreKeySource
import com.veyarc.data.storage.DecodedTemplate
import com.veyarc.data.storage.EncryptedTemplateAssetCodec
import com.veyarc.model.AssetId
import com.veyarc.model.AssetKind
import com.veyarc.model.NormalizedRect
import com.veyarc.model.WorkflowIds
import com.veyarc.model.bundle.BundleAsset
import java.io.IOException
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

private const val CROP_PREVIEW_MAX_PIXELS = 1_000_000L

private const val TEMPLATE_MIME_TYPE = "image/x-veyarc-rgba"

class TemplateAssetImportException(message: String, cause: Throwable? = null) : Exception(message, cause)

internal data class TemplateCropSource(
    val uri: Uri,
    val widthPx: Int,
    val heightPx: Int,
    val preview: ImageBitmap,
)

/** Loads a bounded display copy while retaining the original dimensions used for the final crop. */
internal fun loadTemplateCropSource(contentResolver: ContentResolver, uri: Uri): TemplateCropSource {
    val bounds = readImageBounds(contentResolver, uri)
    var sampleSize = 1
    while (ceil(bounds.first.toDouble() / sampleSize) * ceil(bounds.second.toDouble() / sampleSize) > CROP_PREVIEW_MAX_PIXELS) {
        sampleSize *= 2
    }
    val preview = decodeBitmap(contentResolver, uri, sampleSize)
    return TemplateCropSource(
        uri = uri,
        widthPx = bounds.first,
        heightPx = bounds.second,
        preview = preview.asImageBitmap(),
    )
}

/** Imports only the user-selected crop and preserves its original pixel scale before encryption. */
internal fun importTemplateAsset(
    contentResolver: ContentResolver,
    uri: Uri,
    crop: NormalizedRect,
): BundleAsset {
    val (sourceWidth, sourceHeight) = readImageBounds(contentResolver, uri)
    val cropBounds = crop.toPixelBounds(sourceWidth, sourceHeight)
    val cropPixelCount = cropBounds.width().toLong() * cropBounds.height()
    if (cropPixelCount > DecodedTemplate.MAX_TEMPLATE_PIXELS) {
        throw TemplateAssetImportException(
            "The selected crop contains $cropPixelCount pixels. Select ${DecodedTemplate.MAX_TEMPLATE_PIXELS} pixels or fewer.",
        )
    }

    val bitmap = decodeRegion(contentResolver, uri, cropBounds)
    return encryptTemplateBitmap(bitmap)
}

private fun readImageBounds(contentResolver: ContentResolver, uri: Uri): Pair<Int, Int> {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    try {
        val input = contentResolver.openInputStream(uri)
            ?: throw TemplateAssetImportException("The selected file could not be opened")
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
    val input = contentResolver.openInputStream(uri)
        ?: throw TemplateAssetImportException("The selected file could not be opened")
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
        val input = contentResolver.openInputStream(uri)
            ?: throw TemplateAssetImportException("The selected file could not be opened")
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

private fun encryptTemplateBitmap(bitmap: Bitmap): BundleAsset = try {
    val pixelCount = bitmap.width.toLong() * bitmap.height
    if (pixelCount > DecodedTemplate.MAX_TEMPLATE_PIXELS) {
        throw TemplateAssetImportException("The selected image is too large to use as a template")
    }
    val rgba = bitmap.toRgbaBytes()
    val assetId = AssetId("template-${WorkflowIds.newUuid()}")
    val decoded = DecodedTemplate(bitmap.width, bitmap.height, rgba)
    val encrypted = try {
        EncryptedTemplateAssetCodec(
            keySource = AndroidKeystoreKeySource("veyarc.template-assets.v1"),
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
    )
} finally {
    bitmap.recycle()
}

private fun NormalizedRect.toPixelBounds(widthPx: Int, heightPx: Int): Rect {
    val left = floor(x * widthPx).toInt().coerceIn(0, widthPx - 1)
    val top = floor(y * heightPx).toInt().coerceIn(0, heightPx - 1)
    val right = ceil((x + width) * widthPx).toInt().coerceIn(left + 1, widthPx)
    val bottom = ceil((y + height) * heightPx).toInt().coerceIn(top + 1, heightPx)
    return Rect(left, top, max(left + 1, right), max(top + 1, bottom))
}

private fun Bitmap.toRgbaBytes(): ByteArray {
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
