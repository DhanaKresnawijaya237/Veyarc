package com.veyarc.automation.android

import android.view.accessibility.AccessibilityWindowInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundPackageTrackerTest {
    @Test
    fun acceptsApplicationWindowsWithoutRequiringFullScreenMetadata() {
        assertTrue(ForegroundPackageTracker.isApplicationWindow(AccessibilityWindowInfo.TYPE_APPLICATION))
        assertFalse(ForegroundPackageTracker.isApplicationWindow(AccessibilityWindowInfo.TYPE_SYSTEM))
    }

    @Test
    fun doesNotAcceptAnUnknownWindowTypeAsAnApplication() {
        assertFalse(ForegroundPackageTracker.isApplicationWindow(null))
    }
}
