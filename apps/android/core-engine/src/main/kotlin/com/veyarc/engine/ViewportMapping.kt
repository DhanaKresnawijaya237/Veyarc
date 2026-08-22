package com.veyarc.engine

import com.veyarc.model.DisplayOrientation
import com.veyarc.model.NormalizedCoordinate
import com.veyarc.model.TimedPoint

/** The windowing modes that an active Android display can report to the runner. */
enum class ActiveWindowMode {
    FULL_DISPLAY,
    SPLIT_SCREEN,
    FREEFORM,
}

/** The current logical display geometry used for one workflow step. */
data class ActiveViewport(
    val widthPx: Int,
    val heightPx: Int,
    val orientation: DisplayOrientation,
    val windowMode: ActiveWindowMode = ActiveWindowMode.FULL_DISPLAY,
) {
    init {
        require(widthPx > 0) { "Active viewport width must be positive" }
        require(heightPx > 0) { "Active viewport height must be positive" }
    }
}

/** A display-relative coordinate in logical pixels. */
data class DisplayCoordinate(
    val x: Double,
    val y: Double,
) {
    init {
        require(x.isFinite()) { "Display coordinate x must be finite" }
        require(y.isFinite()) { "Display coordinate y must be finite" }
        require(x >= 0.0) { "Display coordinate x must be nonnegative" }
        require(y >= 0.0) { "Display coordinate y must be nonnegative" }
    }
}

/** A timed display coordinate used by a swipe path. */
data class DisplayTimedPoint(
    val point: DisplayCoordinate,
    val offsetMs: Long,
) {
    init {
        require(offsetMs >= 0L) { "Display point offset must be nonnegative" }
    }
}

/** Raised when the active window cannot safely receive full-display coordinates. */
class UnsupportedViewportException(
    val windowMode: ActiveWindowMode,
) : IllegalArgumentException(
    "Workflow playback requires full-display mode, but active mode is $windowMode",
)

/** Converts normalized workflow coordinates to the current logical display. */
object ViewportMapper {
    fun map(point: NormalizedCoordinate, viewport: ActiveViewport): DisplayCoordinate {
        requireSupported(viewport)
        return DisplayCoordinate(
            x = mapAxis(point.x, viewport.widthPx),
            y = mapAxis(point.y, viewport.heightPx),
        )
    }

    fun map(points: List<TimedPoint>, viewport: ActiveViewport): List<DisplayTimedPoint> {
        requireSupported(viewport)
        return points.map { point ->
            DisplayTimedPoint(
                point = DisplayCoordinate(
                    x = mapAxis(point.point.x, viewport.widthPx),
                    y = mapAxis(point.point.y, viewport.heightPx),
                ),
                offsetMs = point.offsetMs.value,
            )
        }
    }

    private fun requireSupported(viewport: ActiveViewport) {
        if (viewport.windowMode != ActiveWindowMode.FULL_DISPLAY) {
            throw UnsupportedViewportException(viewport.windowMode)
        }
    }

    /** Maps [0, 1] to the valid pixel-index range [0, size - 1]. */
    private fun mapAxis(value: Double, size: Int): Double = value * (size - 1).toDouble()
}
