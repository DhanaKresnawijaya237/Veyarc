package com.veyarc.automation.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuTouchProbeParsingTest {
    @Test
    fun discoversTheNamedMultiTouchDeviceAndCoordinateRanges() {
        val descriptor = TouchscreenDeviceDiscoveryParser.select(
            """
            add device 1: /dev/input/event2
              name:     "gpio-keys"
                ABS (0003): ABS_X : value 0, min 0, max 1, fuzz 0, flat 0, resolution 0
            add device 2: /dev/input/event5
              name:     "touchpanel"
                ABS (0003): ABS_MT_POSITION_X : value 0, min 0, max 1079, fuzz 0, flat 0, resolution 0
                ABS (0003): ABS_MT_POSITION_Y : value 0, min 0, max 2391, fuzz 0, flat 0, resolution 0
              input props:
                INPUT_PROP_DIRECT
            """.trimIndent(),
        )

        assertNotNull(descriptor)
        assertEquals("/dev/input/event5", descriptor?.path)
        assertEquals("touchpanel", descriptor?.name)
        assertEquals(0L, descriptor?.minX)
        assertEquals(1079L, descriptor?.maxX)
        assertEquals(0L, descriptor?.minY)
        assertEquals(2391L, descriptor?.maxY)
        assertTrue(descriptor?.hasMultiTouchAxes == true)
        assertTrue(descriptor?.hasDirectInputProperty == true)
    }

    @Test
    fun parsesDownMoveAndUpFramesWithKernelTimestamps() {
        val descriptor = descriptor()
        val parser = EvdevTouchFrameParser(descriptor)
        val observations = listOf(
            "[  1.000000] /dev/input/event5: EV_ABS ABS_MT_TRACKING_ID 00000001",
            "[  1.000000] /dev/input/event5: EV_ABS ABS_MT_POSITION_X 00000100",
            "[  1.000000] /dev/input/event5: EV_ABS ABS_MT_POSITION_Y 00000200",
            "[  1.000000] /dev/input/event5: EV_KEY BTN_TOUCH DOWN",
            "[  1.000000] /dev/input/event5: EV_SYN SYN_REPORT 00000000",
            "[  1.010000] /dev/input/event5: EV_ABS ABS_MT_POSITION_X 00000110",
            "[  1.010000] /dev/input/event5: EV_ABS ABS_MT_POSITION_Y 00000210",
            "[  1.010000] /dev/input/event5: EV_SYN SYN_REPORT 00000000",
            "[  1.020000] /dev/input/event5: EV_ABS ABS_MT_TRACKING_ID ffffffff",
            "[  1.020000] /dev/input/event5: EV_KEY BTN_TOUCH UP",
            "[  1.020000] /dev/input/event5: EV_SYN SYN_REPORT 00000000",
        ).mapNotNull { EvdevLineParser.parse(it) }

        val frames = observations.mapNotNull(parser::accept)
        val snapshot = parser.snapshot()

        assertEquals(listOf(TouchFrameKind.DOWN, TouchFrameKind.MOVE, TouchFrameKind.UP), frames.map { it.kind })
        assertEquals(1_000_000L, frames.first().timestampMicros)
        assertEquals(3L, snapshot.frameCount)
        assertEquals(1L, snapshot.completeGestureCount)
        assertEquals(0L, snapshot.malformedFrameCount)
        assertEquals(0L, snapshot.outOfRangeSampleCount)
    }

    @Test
    fun acceptsRapidConsecutiveSinglePointerGestures() {
        val parser = EvdevTouchFrameParser(descriptor())
        val lines = listOf(
            "[  1.000000] /dev/input/event5: EV_ABS ABS_MT_TRACKING_ID 00000001",
            "[  1.000000] /dev/input/event5: EV_ABS ABS_MT_POSITION_X 00000100",
            "[  1.000000] /dev/input/event5: EV_ABS ABS_MT_POSITION_Y 00000200",
            "[  1.000000] /dev/input/event5: EV_SYN SYN_REPORT 00000000",
            "[  1.001000] /dev/input/event5: EV_ABS ABS_MT_TRACKING_ID ffffffff",
            "[  1.001000] /dev/input/event5: EV_SYN SYN_REPORT 00000000",
            "[  1.002000] /dev/input/event5: EV_ABS ABS_MT_TRACKING_ID 00000002",
            "[  1.002000] /dev/input/event5: EV_ABS ABS_MT_POSITION_X 00000110",
            "[  1.002000] /dev/input/event5: EV_ABS ABS_MT_POSITION_Y 00000210",
            "[  1.002000] /dev/input/event5: EV_SYN SYN_REPORT 00000000",
            "[  1.003000] /dev/input/event5: EV_ABS ABS_MT_TRACKING_ID ffffffff",
            "[  1.003000] /dev/input/event5: EV_SYN SYN_REPORT 00000000",
        )

        lines.mapNotNull(EvdevLineParser::parse).forEach(parser::accept)

        assertEquals(4L, parser.snapshot().frameCount)
        assertEquals(2L, parser.snapshot().completeGestureCount)
        assertEquals(0L, parser.snapshot().malformedFrameCount)
    }

    @Test
    fun reportsIncompleteAndOutOfRangeInputWithoutReturningAFrame() {
        val descriptor = descriptor().copy(maxX = 100L)
        val parser = EvdevTouchFrameParser(descriptor)
        val lines = listOf(
            "[  1.000000] /dev/input/event5: EV_ABS ABS_MT_TRACKING_ID 00000001",
            "[  1.000000] /dev/input/event5: EV_ABS ABS_MT_POSITION_X 00000101",
            "[  1.000000] /dev/input/event5: EV_SYN SYN_REPORT 00000000",
        )
        lines.mapNotNull(EvdevLineParser::parse).forEach(parser::accept)
        parser.finish()

        assertEquals(1L, parser.snapshot().outOfRangeSampleCount)
        assertEquals(1L, parser.snapshot().malformedFrameCount)
        assertEquals(0L, parser.snapshot().frameCount)
        assertEquals(1L, parser.snapshot().incompleteGestureCount)
    }

    @Test
    fun rejectsUnlabelledOrMalformedEventLines() {
        assertNull(EvdevLineParser.parse("permission denied"))
        assertNull(EvdevLineParser.parse("[ 1.0] /dev/input/event5: EV_ABS ABS_X"))
    }

    private fun descriptor() = TouchscreenDeviceDescriptor(
        path = "/dev/input/event5",
        name = "touchpanel",
        minX = 0L,
        maxX = 1079L,
        minY = 0L,
        maxY = 2391L,
        hasMultiTouchAxes = true,
        hasDirectInputProperty = true,
    )
}
