package com.veyarc.feature.editor

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.veyarc.data.storage.AndroidKeystoreKeySource
import com.veyarc.data.storage.DecodedTemplate
import com.veyarc.data.storage.EncryptedTemplateAssetCodec
import com.veyarc.model.AssetId
import com.veyarc.model.AssetKind
import com.veyarc.model.WorkflowIds
import com.veyarc.model.bundle.BundleAsset
import java.io.IOException
import kotlin.math.ceil

private const val TEMPLATE_MIME_TYPE = "image/x-veyarc-rgba"

class TemplateAssetImportException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Imports one user-selected image, downsizing it before decoding and encrypting its RGBA pixels. */
internal fun importTemplateAsset(contentResolver: ContentResolver, uri: Uri): BundleAsset {
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

    val sampleSize = safeSampleSize(bounds.outWidth, bounds.outHeight)
    val bitmap = try {
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

    return try {
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
}

private fun safeSampleSize(width: Int, height: Int): Int {
    var sample = 1
    while (ceil(width.toDouble() / sample) * ceil(height.toDouble() / sample) > DecodedTemplate.MAX_TEMPLATE_PIXELS) {
        if (sample > 16_384) throw TemplateAssetImportException("The selected image dimensions are unsupported")
        sample *= 2
    }
    return sample
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
