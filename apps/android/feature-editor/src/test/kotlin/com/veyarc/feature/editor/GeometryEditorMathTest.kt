package com.veyarc.feature.editor

import com.veyarc.model.NormalizedCoordinate
import com.veyarc.model.NormalizedRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeometryEditorMathTest {
    @Test
    fun resizingRegionKeepsItInsideTheDisplay() {
        val region = NormalizedRect(x = 0.2, y = 0.2, width = 0.4, height = 0.4)

        val resized = updateRegion(
            region = region,
            handle = RegionDragHandle.BOTTOM_RIGHT,
            deltaX = 0.9,
            deltaY = 0.9,
        )

        assertEquals(1.0, resized.x + resized.width, 0.000001)
        assertEquals(1.0, resized.y + resized.height, 0.000001)
        assertTrue(resized.width >= 0.04)
        assertTrue(resized.height >= 0.04)
    }

    @Test
    fun timedPathPreservesEndpointsAndStrictOffsets() {
        val points = listOf(
            NormalizedCoordinate(0.1, 0.2),
            NormalizedCoordinate(0.5, 0.4),
            NormalizedCoordinate(0.9, 0.8),
        )

        val timed = timedPath(points, durationMs = 900, maximumPoints = 128)!!

        assertEquals(points.first(), timed.first().point)
        assertEquals(points.last(), timed.last().point)
        assertEquals(0L, timed.first().offsetMs.value)
        assertEquals(900L, timed.last().offsetMs.value)
        assertTrue(timed.zipWithNext().all { (first, second) -> first.offsetMs.value < second.offsetMs.value })
    }

    @Test
    fun pathInterpolationFollowsTimedPoints() {
        val points = timedPath(
            points = listOf(
                NormalizedCoordinate(0.0, 0.0),
                NormalizedCoordinate(1.0, 1.0),
            ),
            durationMs = 100,
            maximumPoints = 128,
        )!!

        assertEquals(NormalizedCoordinate(0.5, 0.5), interpolatePath(points, 0.5f))
    }
}
