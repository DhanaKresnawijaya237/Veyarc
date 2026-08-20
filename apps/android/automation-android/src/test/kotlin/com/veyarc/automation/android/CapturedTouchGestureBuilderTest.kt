package com.veyarc.automation.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CapturedTouchGestureBuilderTest {
    @Test
    fun buildsACompleteGestureWithRelativeTiming() {
        val builder = CapturedTouchGestureBuilder()

        assertTrue(builder.append(CapturedTouchAction.Down, 1_000, ScreenPoint(10f, 20f)))
        assertTrue(builder.append(CapturedTouchAction.Move, 1_025, ScreenPoint(15f, 25f)))
        assertTrue(builder.append(CapturedTouchAction.Up, 1_050, ScreenPoint(20f, 30f)))

        val gesture = builder.build()
        assertNotNull(gesture)
        assertEquals(50L, gesture?.durationMillis)
        assertEquals(listOf(0L, 25L, 50L), gesture?.samples?.map { it.elapsedMillis })
    }

    @Test
    fun rejectsASequenceThatDoesNotStartWithDown() {
        val builder = CapturedTouchGestureBuilder()

        assertFalse(builder.append(CapturedTouchAction.Move, 1_000, ScreenPoint(10f, 20f)))
        assertNull(builder.build())
    }

    @Test
    fun doesNotBuildAnIncompleteGesture() {
        val builder = CapturedTouchGestureBuilder()

        assertFalse(builder.hasStarted)
        assertTrue(builder.append(CapturedTouchAction.Down, 1_000, ScreenPoint(10f, 20f)))
        assertTrue(builder.hasStarted)

        assertNull(builder.build())
    }
}
