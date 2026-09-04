package com.veyarc.feature.editor

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
        assertTrue(sampledWidth * sampledHeight <= 1_000_000L)
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

    private fun ceilDivideForTest(value: Int, divisor: Int): Long = (value.toLong() + divisor - 1L) / divisor
}
