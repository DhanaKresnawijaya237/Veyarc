package com.veyarc.automation.android

import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Runtime API checks only. These tests never dispatch input or alter Accessibility settings. */
@RunWith(AndroidJUnit4::class)
class AndroidPlaybackCompatibilityTest {
    @Test
    fun api24GestureDescriptionCanRepresentTheSupportedShortPath() {
        val path = Path().apply {
            moveTo(10f, 10f)
            lineTo(20f, 20f)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 100L))
            .build()

        assertTrue(gesture.strokeCount == 1)
    }

    @Test
    @SdkSuppress(maxSdkVersion = Build.VERSION_CODES.N_MR1)
    fun api24And25KeepLongGesturesCapabilityGated() {
        assertFalse(AccessibilityGesturePolicy.supportsLongGestures(Build.VERSION.SDK_INT))
        assertTrue(
            AccessibilityGesturePolicy.durationRejectionReason(
                apiLevel = Build.VERSION.SDK_INT,
                durationMillis = 101L,
            ) != null,
        )
    }

    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.O)
    fun api26AndNewerAllowInterruptibleLongGestures() {
        assertTrue(AccessibilityGesturePolicy.supportsLongGestures(Build.VERSION.SDK_INT))
    }
}
