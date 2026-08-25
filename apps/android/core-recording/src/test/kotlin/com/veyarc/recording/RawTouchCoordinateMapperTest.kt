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
    fun rotationNinetyMovesPortraitTopLeftToLandscapeBottomLeft() {
        assertCoordinate(TouchDisplayRotation.ROTATION_90, 0.0, 1.0, 0L, 0L)
        assertCoordinate(TouchDisplayRotation.ROTATION_90, 1.0, 0.0, 100L, 200L)
    }

    @Test
    fun rotationOneEightyInvertsBothAxes() {
        assertCoordinate(TouchDisplayRotation.ROTATION_180, 1.0, 1.0, 0L, 0L)
        assertCoordinate(TouchDisplayRotation.ROTATION_180, 0.0, 0.0, 100L, 200L)
    }

    @Test
    fun rotationTwoSeventyMovesPortraitTopLeftToLandscapeTopRight() {
        assertCoordinate(TouchDisplayRotation.ROTATION_270, 1.0, 0.0, 0L, 0L)
        assertCoordinate(TouchDisplayRotation.ROTATION_270, 0.0, 1.0, 100L, 200L)
    }

    @Test
    fun rotationNinetyPreservesAllFourLogicalCorners() {
        assertCorners(
            rotation = TouchDisplayRotation.ROTATION_90,
            expected = listOf(
                0.0 to 1.0,
                0.0 to 0.0,
                1.0 to 0.0,
                1.0 to 1.0,
            ),
        )
    }

    @Test
    fun rotationTwoSeventyPreservesAllFourLogicalCorners() {
        assertCorners(
            rotation = TouchDisplayRotation.ROTATION_270,
            expected = listOf(
                1.0 to 0.0,
                1.0 to 1.0,
                0.0 to 1.0,
                0.0 to 0.0,
            ),
        )
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

    private fun assertCorners(
        rotation: TouchDisplayRotation,
        expected: List<Pair<Double, Double>>,
    ) {
        val rawCorners = listOf(
            0L to 0L,
            100L to 0L,
            100L to 200L,
            0L to 200L,
        )
        rawCorners.map { (rawX, rawY) ->
            RawTouchCoordinateMapper.toLogicalNormalized(
                rawX = rawX,
                rawY = rawY,
                minX = 0L,
                maxX = 100L,
                minY = 0L,
                maxY = 200L,
                rotation = rotation,
            )
        }.zip(expected).forEach { pair ->
            val actual = pair.first
            val expectedX = pair.second.first
            val expectedY = pair.second.second
            assertEquals(expectedX, actual.x, 0.000001)
            assertEquals(expectedY, actual.y, 0.000001)
        }
    }
}
