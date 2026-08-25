package com.veyarc.automation.android

import android.content.Context
import com.veyarc.engine.ActiveWindowMode
import com.veyarc.model.ViewportSpec

/** Full-display geometry plus the physical rotation observed at the same instant. */
data class AndroidRecordingViewportSnapshot(
    val viewport: ViewportSpec,
    val displayRotation: Int,
)

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

/**
 * Reads the viewport and display rotation as one replay-readiness sample.
 *
 * A target app can become foreground before Android finishes rotating the display. Replay must
 * compare both values from repeated samples instead of mapping against a transient geometry.
 */
fun currentRecordingViewportSnapshot(context: Context): AndroidRecordingViewportSnapshot? {
    val viewport = currentRecordingViewport(context) ?: return null
    val rotation = currentDisplayRotation(context) ?: return null
    return AndroidRecordingViewportSnapshot(viewport = viewport, displayRotation = rotation)
}

/** Returns the physical display rotation used to interpret native touchscreen coordinates. */
fun currentDisplayRotation(context: Context): Int? = runCatching {
    val windowManager = context.getSystemService(android.view.WindowManager::class.java) ?: return null
    @Suppress("DEPRECATION")
    windowManager.defaultDisplay.rotation
}.getOrNull()
