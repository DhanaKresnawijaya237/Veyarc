package com.veyarc.automation.android

import android.os.Build
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AccessibilityGestureDispatcherTest {
    @Test
    fun acceptsAValidArbitraryPath() {
        val result =
            AccessibilityGestureDispatcher.validateRequest(
                points = listOf(ScreenPoint(10f, 20f), ScreenPoint(30f, 40f)),
                durationMillis = 500,
                minimumPoints = 2,
            )

        assertNull(result)
    }

    @Test
    fun rejectsAPathWithTooFewPoints() {
        val result =
            AccessibilityGestureDispatcher.validateRequest(
                points = listOf(ScreenPoint(10f, 20f)),
                durationMillis = 500,
                minimumPoints = 2,
            )

        assertNotNull(result)
    }

    @Test
    fun rejectsInvalidCoordinates() {
        val result =
            AccessibilityGestureDispatcher.validateRequest(
                points = listOf(ScreenPoint(Float.NaN, 20f)),
                durationMillis = 100,
                minimumPoints = 1,
            )

        assertNotNull(result)
    }

    @Test
    fun api24AllowsOnlyShortSingleSegmentDurations() {
        assertNull(AccessibilityGesturePolicy.durationRejectionReason(Build.VERSION_CODES.N, 100L))
        assertNotNull(AccessibilityGesturePolicy.durationRejectionReason(Build.VERSION_CODES.N, 101L))
    }

    @Test
    fun api26AllowsTheFullBoundedGestureDuration() {
        assertNull(AccessibilityGesturePolicy.durationRejectionReason(Build.VERSION_CODES.O, 60_000L))
    }
}
