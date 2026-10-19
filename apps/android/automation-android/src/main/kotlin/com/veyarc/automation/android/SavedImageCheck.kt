package com.veyarc.automation.android

import com.veyarc.engine.ConditionObservation
import com.veyarc.model.Condition
import com.veyarc.model.ImageAbsentCondition
import com.veyarc.model.ImageMatchMode
import com.veyarc.model.ImagePresentCondition
import com.veyarc.model.PixelColorCondition

/** Compares user-selected pixels only. Takes ownership of backgroundRgba and clears it on every exit. */
object SavedImageCheck {
    /** Tests the configured pixel or area in an original, full-size background. */
    fun searchColor(condition: PixelColorCondition, width: Int, height: Int, rgba: ByteArray, viewportWidth: Int, viewportHeight: Int): ConditionObservation {
        try {
            require(width == viewportWidth && height == viewportHeight) {
                "Use an original screenshot at $viewportWidth × $viewportHeight. This image is $width × $height; it won't be resized."
            }
            EphemeralScreenFrame(width, height, 0L, rgba).use { frame ->
                return VisualConditionEvaluator({ frame }, { null }).observe(condition)
            }
        } finally {
            rgba.fill(0)
        }
    }

    fun compare(condition: Condition, width: Int, height: Int, backgroundRgba: ByteArray, target: RgbaTemplate, viewportWidth: Int, viewportHeight: Int): ConditionObservation {
        try {
            require(width == viewportWidth && height == viewportHeight) {
                "Use an original screenshot at $viewportWidth × $viewportHeight. This image is $width × $height; it won't be resized."
            }
            val assetId = when (condition) {
                is ImagePresentCondition -> condition.assetId
                is ImageAbsentCondition -> condition.assetId
                else -> error("Select an image condition to test.")
            }
            val screenState = condition is ImagePresentCondition && condition.matchMode == ImageMatchMode.SCREEN_STATE
            if (screenState) {
                require(target.widthPx == width && target.heightPx == height) { "The comparison image must match the full screen size." }
            } else {
                val region = when (condition) {
                    is ImagePresentCondition -> condition.region
                    is ImageAbsentCondition -> condition.region
                    else -> error("Select an image condition.")
                }
                val bounds = FramePixelBounds.from(region, width, height)
                require(target.widthPx <= bounds.width && target.heightPx <= bounds.height) { "The target image is larger than this block's search area." }
            }
            EphemeralScreenFrame(width, height, 0L, backgroundRgba).use { frame ->
                return VisualConditionEvaluator({ frame }, { if (it == assetId) target else null }).observe(condition)
            }
        } finally {
            backgroundRgba.fill(0)
        }
    }
}
