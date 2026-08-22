package com.veyarc.automation.android

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import com.veyarc.engine.ActiveViewport
import com.veyarc.engine.ActiveWindowMode
import com.veyarc.engine.ViewportSource
import com.veyarc.model.DisplayOrientation

/** Reads the physical display bounds and classifies the active Android window mode. */
internal class AndroidViewportSource(
    private val context: Context,
) : ViewportSource {
    override fun currentViewport(): ActiveViewport? = runCatching {
        val windowManager = context.getSystemService(WindowManager::class.java) ?: return null
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        if (metrics.widthPixels <= 0 || metrics.heightPixels <= 0) return null

        val bounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds
        } else {
            Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
        }
        ActiveViewport(
            widthPx = metrics.widthPixels,
            heightPx = metrics.heightPixels,
            orientation = when (context.resources.configuration.orientation) {
                android.content.res.Configuration.ORIENTATION_LANDSCAPE -> DisplayOrientation.LANDSCAPE
                else -> DisplayOrientation.PORTRAIT
            },
            windowMode = classifyWindowMode(bounds, metrics.widthPixels, metrics.heightPixels),
        )
    }.getOrNull()

    companion object {
        /** Pure classifier kept separate so window-mode behavior can be unit tested without a device. */
        internal fun classifyWindowMode(
            bounds: Rect,
            displayWidthPx: Int,
            displayHeightPx: Int,
        ): ActiveWindowMode {
            require(displayWidthPx > 0) { "Display width must be positive" }
            require(displayHeightPx > 0) { "Display height must be positive" }

            val width = bounds.width()
            val height = bounds.height()
            if (bounds.left <= 0 && bounds.top <= 0 &&
                width == displayWidthPx && height == displayHeightPx
            ) {
                return ActiveWindowMode.FULL_DISPLAY
            }
            return if (width == displayWidthPx || height == displayHeightPx) {
                ActiveWindowMode.SPLIT_SCREEN
            } else {
                ActiveWindowMode.FREEFORM
            }
        }
    }
}
