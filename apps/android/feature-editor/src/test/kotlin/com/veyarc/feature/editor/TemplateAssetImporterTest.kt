package com.veyarc.feature.editor

import com.veyarc.model.NormalizedRect
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class TemplateAssetImporterTest {
    @Test
    fun previewSamplingStaysBoundedForPathologicalDimensions() {
        val sample = calculatePreviewSampleSize(Int.MAX_VALUE, Int.MAX_VALUE)
        val sampledWidth = ceilDivideForTest(Int.MAX_VALUE, sample)
        val sampledHeight = ceilDivideForTest(Int.MAX_VALUE, sample)

        assertTrue(sample > 0)
        assertTrue(sampledWidth * sampledHeight <= 3_000_000L)
    }

    @Test
    fun normalPhoneScreenshotKeepsFullResolutionPreviewWhenWithinBudget() {
        assertTrue(calculatePreviewSampleSize(540, 1_048) == 1)
    }

    @Test
    fun sourceStreamRejectsReadsBeyondItsByteBudget() {
        val stream = ByteLimitInputStream(ByteArrayInputStream(ByteArray(9)), maximumBytes = 8)

        assertTrue(runCatching { stream.readBytes() }.isFailure)
    }

    @Test
    fun clockwiseRgbaRotationMovesBottomPixelToTheLeft() {
        val top = byteArrayOf(1, 2, 3, 4)
        val bottom = byteArrayOf(5, 6, 7, 8)

        assertArrayEquals(bottom + top, rotateRgbaClockwise(top + bottom, widthPx = 1, heightPx = 2))
    }

    @Test
    fun rotatedCropMapsBackToTheSameSourceArea() {
        val sourceCrop = NormalizedRect(0.1, 0.2, 0.3, 0.4)
        val rotated = sourceCrop.rotatedClockwise()

        val restored = rotated.inSourceOrientation(1)
        assertEquals(sourceCrop.x, restored.x, 0.000_001)
        assertEquals(sourceCrop.y, restored.y, 0.000_001)
        assertEquals(sourceCrop.width, restored.width, 0.000_001)
        assertEquals(sourceCrop.height, restored.height, 0.000_001)
    }

    private fun ceilDivideForTest(value: Int, divisor: Int): Long = (value.toLong() + divisor - 1L) / divisor
}
