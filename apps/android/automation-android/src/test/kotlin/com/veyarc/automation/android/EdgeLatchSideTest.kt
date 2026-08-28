package com.veyarc.automation.android

import org.junit.Assert.assertEquals
import org.junit.Test

class EdgeLatchSideTest {
    @Test
    fun releaseSelectsOnlyLeftOrRightEvenNearGestureEdges() {
        assertEquals(
            EdgeLatchSide.LEFT,
            nearestEdgeLatchSide(4f, 500f, 1080, 2000, EdgeLatchSide.RIGHT),
        )
        assertEquals(
            EdgeLatchSide.RIGHT,
            nearestEdgeLatchSide(1075f, 500f, 1080, 2000, EdgeLatchSide.LEFT),
        )
        assertEquals(
            EdgeLatchSide.LEFT,
            nearestEdgeLatchSide(500f, 4f, 1080, 2000, EdgeLatchSide.RIGHT),
        )
        assertEquals(
            EdgeLatchSide.LEFT,
            nearestEdgeLatchSide(500f, 1996f, 1080, 2000, EdgeLatchSide.RIGHT),
        )
    }

    @Test
    fun invalidDisplayDimensionsKeepExistingSafePlacement() {
        assertEquals(
            EdgeLatchSide.RIGHT,
            nearestEdgeLatchSide(0f, 0f, 0, 0, EdgeLatchSide.TOP),
        )
    }

    @Test
    fun dragReleaseStoresAContinuousPositionAlongTheSelectedEdge() {
        assertEquals(
            0.5f,
            edgeLatchOffsetForRelease(
                side = EdgeLatchSide.LEFT,
                rawX = 0f,
                rawY = 500f,
                displayWidthPx = 1080,
                displayHeightPx = 1000,
                latchLengthPx = 100,
                fallback = 0f,
            ),
            0.001f,
        )
        assertEquals(
            1f,
            edgeLatchOffsetForRelease(
                side = EdgeLatchSide.RIGHT,
                rawX = 1000f,
                rawY = 1080f,
                displayWidthPx = 1000,
                displayHeightPx = 1080,
                latchLengthPx = 100,
                fallback = 0f,
            ),
            0.001f,
        )
    }

    @Test
    fun latchRoundsOnlyTheContentFacingSide() {
        assertEquals(listOf(0f, 0f, 4f, 4f, 4f, 4f, 0f, 0f).map(Float::toDouble), edgeLatchCornerRadii(EdgeLatchSide.LEFT, 4f).map { it.toDouble() })
        assertEquals(listOf(4f, 4f, 0f, 0f, 0f, 0f, 4f, 4f).map(Float::toDouble), edgeLatchCornerRadii(EdgeLatchSide.RIGHT, 4f).map { it.toDouble() })
    }
}
