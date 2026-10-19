package com.veyarc.feature.editor

import android.graphics.Bitmap
import com.veyarc.automation.android.RgbaTemplate
import com.veyarc.automation.android.SavedImageCheck
import com.veyarc.data.storage.AndroidKeystoreKeySource
import com.veyarc.data.storage.EncryptedTemplateAssetCodec
import com.veyarc.data.storage.TEMPLATE_ASSET_KEY_ALIAS
import com.veyarc.engine.ConditionObservation
import com.veyarc.model.Condition
import com.veyarc.model.PixelColorCondition
import com.veyarc.model.bundle.BundleAsset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

internal suspend fun testColorCondition(condition: PixelColorCondition, image: BundleAsset, viewportWidth: Int, viewportHeight: Int): ConditionObservation = runInterruptible(Dispatchers.Default) {
    val decoded = imageTestCodec().decrypt(image.assetId, image.bytes)
    try {
        SavedImageCheck.searchColor(condition, decoded.widthPx, decoded.heightPx, decoded.takeRgbaBytes(), viewportWidth, viewportHeight)
    } finally {
        decoded.clear()
    }
}

suspend fun testImageCondition(condition: Condition, target: BundleAsset, background: BundleAsset, viewportWidth: Int, viewportHeight: Int): ConditionObservation = runInterruptible(Dispatchers.Default) {
    val decoded = imageTestCodec().decrypt(background.assetId, background.bytes)
    try {
        compareImagePixels(condition, target, decoded.widthPx, decoded.heightPx, decoded.takeRgbaBytes(), viewportWidth, viewportHeight)
    } finally {
        decoded.clear()
    }
}

suspend fun testImageCondition(condition: Condition, target: BundleAsset, background: Bitmap, viewportWidth: Int, viewportHeight: Int): ConditionObservation = runInterruptible(Dispatchers.Default) {
    require(background.width == viewportWidth && background.height == viewportHeight) { "Use an original screenshot at $viewportWidth × $viewportHeight. This image is ${background.width} × ${background.height}; it won't be resized." }
    compareImagePixels(condition, target, background.width, background.height, background.toRgbaBytes(), viewportWidth, viewportHeight)
}

private fun compareImagePixels(condition: Condition, target: BundleAsset, width: Int, height: Int, pixels: ByteArray, viewportWidth: Int, viewportHeight: Int): ConditionObservation {
    try {
        val decoded = imageTestCodec().decrypt(target.assetId, target.bytes)
        try {
            val templatePixels = decoded.takeRgbaBytes()
            try {
                val template = RgbaTemplate.fromOwnedRgba(decoded.widthPx, decoded.heightPx, templatePixels)
                try {
                    return SavedImageCheck.compare(condition, width, height, pixels, template, viewportWidth, viewportHeight)
                } finally {
                    template.clear()
                }
            } finally {
                templatePixels.fill(0)
            }
        } finally {
            decoded.clear()
        }
    } finally {
        pixels.fill(0)
    }
}

private fun imageTestCodec() = EncryptedTemplateAssetCodec(AndroidKeystoreKeySource(TEMPLATE_ASSET_KEY_ALIAS))
