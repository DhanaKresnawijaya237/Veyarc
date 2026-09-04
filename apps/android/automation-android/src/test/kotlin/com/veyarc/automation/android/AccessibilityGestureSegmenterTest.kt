package com.veyarc.automation.android

import org.junit.Assert.assertEquals
import org.junit.Test

class AccessibilityGestureSegmenterTest {
    @Test
    fun splitsLongPathIntoBoundedConnectedSegmentsWithoutDroppingCorners() {
        val corner = ScreenPoint(100f, 0f)
        val segments = AccessibilityGestureSegmenter.split(
            points = listOf(ScreenPoint(0f, 0f), corner, ScreenPoint(100f, 100f)),
            durationMillis = 250L,
            maximumSegmentDurationMillis = 100L,
        )

        assertEquals(listOf(100L, 100L, 50L), segments.map { it.durationMillis })
        assertEquals(250L, segments.sumOf { it.durationMillis })
        segments.zipWithNext().forEach { (first, second) ->
            assertEquals(first.points.last(), second.points.first())
        }
        assertEquals(1, segments.flatMap { it.points }.count { it == corner })
    }

    @Test
    fun splitsStationaryHoldAtTheSameCoordinate() {
        val point = ScreenPoint(42f, 73f)
        val segments = AccessibilityGestureSegmenter.split(
            points = listOf(point),
            durationMillis = 201L,
            maximumSegmentDurationMillis = 100L,
        )

        assertEquals(listOf(100L, 100L, 1L), segments.map { it.durationMillis })
        assertEquals(listOf(point, point, point), segments.map { it.points.single() })
    }

    @Test
    fun leavesShortGestureUnchanged() {
        val points = listOf(ScreenPoint(1f, 2f), ScreenPoint(3f, 4f))

        assertEquals(
            listOf(AccessibilityGestureSegment(points, 75L)),
            AccessibilityGestureSegmenter.split(points, 75L, 100L),
        )
    }
}
