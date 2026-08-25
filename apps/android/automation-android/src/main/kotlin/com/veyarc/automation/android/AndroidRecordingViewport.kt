package com.veyarc.automation.android

import android.content.Context
import com.veyarc.engine.ActiveWindowMode
import com.veyarc.model.ViewportSpec

/** Returns a full-display viewport suitable for one recording journal, or null if unsupported. */
fun currentRecordingViewport(context: Context): ViewportSpec? {
    val active = AndroidViewportSource(context.applicationContext).currentViewport() ?: return null
    if (active.windowMode != ActiveWindowMode.FULL_DISPLAY) return null
    val densityDpi = context.resources.displayMetrics.densityDpi
    if (densityDpi <= 0) return null
    return ViewportSpec(
        recordedWidthPx = active.widthPx,
        recordedHeightPx = active.heightPx,
        recordedDensityDpi = densityDpi,
        recordedOrientation = active.orientation,
    )
}

/** Returns the physical display rotation used to interpret native touchscreen coordinates. */
fun currentDisplayRotation(context: Context): Int? = runCatching {
    val windowManager = context.getSystemService(android.view.WindowManager::class.java) ?: return null
    @Suppress("DEPRECATION")
    windowManager.defaultDisplay.rotation
}.getOrNull()
