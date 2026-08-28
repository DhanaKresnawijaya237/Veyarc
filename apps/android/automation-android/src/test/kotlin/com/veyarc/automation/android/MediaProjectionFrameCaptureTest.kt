package com.veyarc.automation.android

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
}
