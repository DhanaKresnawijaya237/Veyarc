package com.veyarc.automation.android

import com.veyarc.model.NormalizedRect
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaProjectionFrameCaptureTest {
    @Test
    fun ephemeralFrameExposesPixelsAndClearsThemOnClose() {
        val frame = EphemeralScreenFrame(
            widthPx = 2,
            heightPx = 1,
            timestampNanos = 42L,
            rgbaBytes = byteArrayOf(
                10,
                20,
                30,
                255.toByte(),
                40,
                50,
                60,
                128.toByte(),
            ),
        )

        assertEquals(RgbaColor(10, 20, 30, 255), frame.pixelAt(0, 0))
        assertEquals(RgbaColor(40, 50, 60, 128), frame.pixelAt(1, 0))
        assertTrue(!frame.isClosed)

        frame.close()

        assertTrue(frame.isClosed)
    }

    @Test
    fun displaySpecRejectsOversizedFrames() {
        try {
            MediaProjectionDisplaySpec(widthPx = 16_384, heightPx = 16_384, densityDpi = 420)
            throw AssertionError("Expected oversized projection to be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("too large"))
        }
    }

    @Test
    fun regionCropIsIndependentFromFrameAndUsesNormalizedBounds() {
        val frame = EphemeralScreenFrame(
            widthPx = 2,
            heightPx = 2,
            timestampNanos = 7L,
            rgbaBytes = byteArrayOf(
                1, 2, 3, 4,
                5, 6, 7, 8,
                9, 10, 11, 12,
                13, 14, 15, 16,
            ),
        )

        val crop = frame.copyRegion(NormalizedRect(0.5, 0.0, 0.5, 1.0))

        assertEquals(1, crop.widthPx)
        assertEquals(2, crop.heightPx)
        assertArrayEquals(byteArrayOf(5, 6, 7, 8, 13, 14, 15, 16), crop.copyRgbaBytes())
        frame.close()
    }
}
