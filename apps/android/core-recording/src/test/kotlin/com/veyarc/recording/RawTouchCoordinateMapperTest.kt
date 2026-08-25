package com.veyarc.recording

import org.junit.Assert.assertEquals
import org.junit.Test

class RawTouchCoordinateMapperTest {
    @Test
    fun rotationZeroPreservesNativeAxes() {
        assertCoordinate(TouchDisplayRotation.ROTATION_0, 0.0, 0.0, 0L, 0L)
        assertCoordinate(TouchDisplayRotation.ROTATION_0, 1.0, 1.0, 100L, 200L)
    }

    @Test
    fun rotationNinetyMovesPortraitTopLeftToLandscapeTopRight() {
        assertCoordinate(TouchDisplayRotation.ROTATION_90, 1.0, 0.0, 0L, 0L)
        assertCoordinate(TouchDisplayRotation.ROTATION_90, 0.0, 1.0, 100L, 200L)
    }

    @Test
    fun rotationOneEightyInvertsBothAxes() {
        assertCoordinate(TouchDisplayRotation.ROTATION_180, 1.0, 1.0, 0L, 0L)
        assertCoordinate(TouchDisplayRotation.ROTATION_180, 0.0, 0.0, 100L, 200L)
    }

    @Test
    fun rotationTwoSeventyMovesPortraitTopLeftToLandscapeBottomLeft() {
        assertCoordinate(TouchDisplayRotation.ROTATION_270, 0.0, 1.0, 0L, 0L)
        assertCoordinate(TouchDisplayRotation.ROTATION_270, 1.0, 0.0, 100L, 200L)
    }

    private fun assertCoordinate(
        rotation: TouchDisplayRotation,
        expectedX: Double,
        expectedY: Double,
        rawX: Long,
        rawY: Long,
    ) {
        val coordinate = RawTouchCoordinateMapper.toLogicalNormalized(
            rawX = rawX,
            rawY = rawY,
            minX = 0L,
            maxX = 100L,
            minY = 0L,
            maxY = 200L,
            rotation = rotation,
        )
        assertEquals(expectedX, coordinate.x, 0.000001)
        assertEquals(expectedY, coordinate.y, 0.000001)
    }
}
