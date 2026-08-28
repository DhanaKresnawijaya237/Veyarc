package com.veyarc.automation.android

import org.junit.Assert.assertEquals
import org.junit.Test

class EdgeLatchSideTest {
    @Test
    fun releaseSelectsOnlyLeftOrRightEvenNearGestureEdges() {
        assertEquals(
            EdgeLatchSide.LEFT,
            nearestEdgeLatchSide(4f, 1080, EdgeLatchSide.RIGHT),
        )
        assertEquals(
            EdgeLatchSide.RIGHT,
            nearestEdgeLatchSide(1075f, 1080, EdgeLatchSide.LEFT),
        )
        assertEquals(
            EdgeLatchSide.LEFT,
            nearestEdgeLatchSide(500f, 1080, EdgeLatchSide.RIGHT),
        )
        assertEquals(
            EdgeLatchSide.LEFT,
            nearestEdgeLatchSide(500f, 1080, EdgeLatchSide.RIGHT),
        )
    }

    @Test
    fun invalidDisplayDimensionsKeepExistingSafePlacement() {
        assertEquals(
            EdgeLatchSide.RIGHT,
            nearestEdgeLatchSide(0f, 0, EdgeLatchSide.RIGHT),
        )
    }

    @Test
    fun dragReleaseStoresAContinuousPositionAlongTheSelectedEdge() {
        assertEquals(
            0.5f,
            edgeLatchOffsetForRelease(
                rawY = 500f,
                displayHeightPx = 1000,
                latchLengthPx = 100,
                fallback = 0f,
            ),
            0.001f,
        )
        assertEquals(
            1f,
            edgeLatchOffsetForRelease(
                rawY = 1080f,
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
