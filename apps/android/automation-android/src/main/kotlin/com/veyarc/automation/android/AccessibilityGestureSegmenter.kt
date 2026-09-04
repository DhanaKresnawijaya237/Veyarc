package com.veyarc.automation.android

import kotlin.math.hypot

internal data class AccessibilityGestureSegment(
    val points: List<ScreenPoint>,
    val durationMillis: Long,
)

/** Splits a gesture into bounded, exactly connected pieces without dropping path corners. */
internal object AccessibilityGestureSegmenter {
    fun split(
        points: List<ScreenPoint>,
        durationMillis: Long,
        maximumSegmentDurationMillis: Long,
    ): List<AccessibilityGestureSegment> {
        require(points.isNotEmpty()) { "A gesture needs at least one point" }
        require(durationMillis > 0L) { "A gesture duration must be positive" }
        require(maximumSegmentDurationMillis > 0L) { "A segment duration must be positive" }
        if (durationMillis <= maximumSegmentDurationMillis) {
            return listOf(AccessibilityGestureSegment(points, durationMillis))
        }

        val segmentDurations = buildList {
            var remainingMillis = durationMillis
            while (remainingMillis > 0L) {
                val segmentDuration = minOf(maximumSegmentDurationMillis, remainingMillis)
                add(segmentDuration)
                remainingMillis -= segmentDuration
            }
        }
        val cumulativeDistances = cumulativeDistances(points)
        val totalDistance = cumulativeDistances.last()
        val boundaryTimes = buildList {
            add(0L)
            segmentDurations.runningFold(0L, Long::plus).drop(1).forEach(::add)
        }
        val boundaryDistances = boundaryTimes.map { elapsedMillis ->
            if (totalDistance == 0f) 0f else totalDistance * elapsedMillis.toFloat() / durationMillis.toFloat()
        }
        val boundaryPoints = boundaryDistances.map { distance ->
            pointAtDistance(points, cumulativeDistances, distance)
        }

        return segmentDurations.indices.map { index ->
            val startDistance = boundaryDistances[index]
            val endDistance = boundaryDistances[index + 1]
            val segmentPoints = buildList {
                add(boundaryPoints[index])
                points.indices.drop(1).dropLast(1).forEach { pointIndex ->
                    val distance = cumulativeDistances[pointIndex]
                    if (distance > startDistance && distance < endDistance) add(points[pointIndex])
                }
                val endPoint = boundaryPoints[index + 1]
                if (last() != endPoint) add(endPoint)
            }
            AccessibilityGestureSegment(segmentPoints, segmentDurations[index])
        }
    }

    private fun cumulativeDistances(points: List<ScreenPoint>): List<Float> = buildList {
        var distance = 0f
        add(distance)
        points.zipWithNext().forEach { (start, end) ->
            distance += hypot(end.x - start.x, end.y - start.y)
            add(distance)
        }
    }

    private fun pointAtDistance(
        points: List<ScreenPoint>,
        cumulativeDistances: List<Float>,
        requestedDistance: Float,
    ): ScreenPoint {
        if (requestedDistance <= 0f || points.size == 1) return points.first()
        val totalDistance = cumulativeDistances.last()
        if (requestedDistance >= totalDistance) return points.last()
        val endIndex = cumulativeDistances.indexOfFirst { it >= requestedDistance }.coerceAtLeast(1)
        val startDistance = cumulativeDistances[endIndex - 1]
        val endDistance = cumulativeDistances[endIndex]
        if (endDistance <= startDistance) return points[endIndex]
        val fraction = (requestedDistance - startDistance) / (endDistance - startDistance)
        val start = points[endIndex - 1]
        val end = points[endIndex]
        return ScreenPoint(
            x = start.x + (end.x - start.x) * fraction,
            y = start.y + (end.y - start.y) * fraction,
        )
    }
}
