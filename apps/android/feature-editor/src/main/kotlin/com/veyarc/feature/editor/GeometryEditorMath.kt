package com.veyarc.feature.editor

import com.veyarc.model.DurationMillis
import com.veyarc.model.NormalizedCoordinate
import com.veyarc.model.NormalizedRect
import com.veyarc.model.TimedPoint
import kotlin.math.hypot

internal enum class RegionDragHandle {
    MOVE,
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT,
}

internal fun NormalizedCoordinate.distanceTo(other: NormalizedCoordinate): Double = hypot(x - other.x, y - other.y)

internal fun selectRegionHandle(
    region: NormalizedRect,
    point: NormalizedCoordinate,
    threshold: Double,
): RegionDragHandle? {
    val handles = listOf(
        RegionDragHandle.TOP_LEFT to NormalizedCoordinate(region.x, region.y),
        RegionDragHandle.TOP_RIGHT to NormalizedCoordinate(region.x + region.width, region.y),
        RegionDragHandle.BOTTOM_LEFT to NormalizedCoordinate(region.x, region.y + region.height),
        RegionDragHandle.BOTTOM_RIGHT to NormalizedCoordinate(region.x + region.width, region.y + region.height),
    )
    handles
        .minByOrNull { (_, handlePoint) -> handlePoint.distanceTo(point) }
        ?.takeIf { (_, handlePoint) -> handlePoint.distanceTo(point) <= threshold }
        ?.let { (handle, _) -> return handle }
    return region.contains(point).takeIf { it }?.let { RegionDragHandle.MOVE }
}

internal fun updateRegion(
    region: NormalizedRect,
    handle: RegionDragHandle,
    deltaX: Double,
    deltaY: Double,
    minimumSize: Double = 0.04,
): NormalizedRect {
    val minimum = minimumSize.coerceIn(0.001, 0.5)
    val right = region.x + region.width
    val bottom = region.y + region.height
    var left = region.x
    var top = region.y
    var newRight = right
    var newBottom = bottom

    when (handle) {
        RegionDragHandle.MOVE -> {
            left = (region.x + deltaX).coerceIn(0.0, 1.0 - region.width)
            top = (region.y + deltaY).coerceIn(0.0, 1.0 - region.height)
            newRight = left + region.width
            newBottom = top + region.height
        }

        RegionDragHandle.TOP_LEFT -> {
            left = (region.x + deltaX).coerceIn(0.0, right - minimum)
            top = (region.y + deltaY).coerceIn(0.0, bottom - minimum)
        }

        RegionDragHandle.TOP_RIGHT -> {
            newRight = (right + deltaX).coerceIn(left + minimum, 1.0)
            top = (region.y + deltaY).coerceIn(0.0, bottom - minimum)
        }

        RegionDragHandle.BOTTOM_LEFT -> {
            left = (region.x + deltaX).coerceIn(0.0, right - minimum)
            newBottom = (bottom + deltaY).coerceIn(top + minimum, 1.0)
        }

        RegionDragHandle.BOTTOM_RIGHT -> {
            newRight = (right + deltaX).coerceIn(left + minimum, 1.0)
            newBottom = (bottom + deltaY).coerceIn(top + minimum, 1.0)
        }
    }

    return NormalizedRect(
        x = left,
        y = top,
        width = newRight - left,
        height = newBottom - top,
    )
}

internal fun downsamplePath(
    points: List<NormalizedCoordinate>,
    maximumPoints: Int,
): List<NormalizedCoordinate> {
    val maximum = maximumPoints.coerceAtLeast(2)
    if (points.size <= maximum) return points
    val lastIndex = points.lastIndex
    return List(maximum) { index ->
        points[(index * lastIndex) / (maximum - 1)]
    }
}

internal fun timedPath(
    points: List<NormalizedCoordinate>,
    durationMs: Long,
    maximumPoints: Int,
): List<TimedPoint>? {
    if (durationMs <= 0) return null
    val sampled = downsamplePath(points, maximumPoints)
    if (sampled.size < 2 || durationMs < sampled.lastIndex) return null
    return sampled.mapIndexed { index, point ->
        val offset = if (index == sampled.lastIndex) {
            durationMs
        } else {
            (durationMs * index) / sampled.lastIndex
        }
        TimedPoint(point = point, offsetMs = DurationMillis(offset))
    }
}

internal fun interpolatePath(
    points: List<TimedPoint>,
    progress: Float,
): NormalizedCoordinate? {
    if (points.isEmpty()) return null
    if (points.size == 1) return points.single().point
    val clamped = progress.coerceIn(0f, 1f)
    val targetOffset = points.last().offsetMs.value.toDouble() * clamped.toDouble()
    val nextIndex = points.indexOfFirst { it.offsetMs.value.toDouble() >= targetOffset }
    if (nextIndex <= 0) return points.first().point
    if (nextIndex == -1) return points.last().point
    val previous = points[nextIndex - 1]
    val next = points[nextIndex]
    val span = (next.offsetMs.value - previous.offsetMs.value).toDouble()
    val local = ((targetOffset - previous.offsetMs.value.toDouble()) / span).coerceIn(0.0, 1.0)
    return NormalizedCoordinate(
        x = previous.point.x + (next.point.x - previous.point.x) * local,
        y = previous.point.y + (next.point.y - previous.point.y) * local,
    )
}
