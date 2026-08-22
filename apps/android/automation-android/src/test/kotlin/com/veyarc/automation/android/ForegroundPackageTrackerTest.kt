package com.veyarc.automation.android

import android.view.accessibility.AccessibilityWindowInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundPackageTrackerTest {
    @Test
    fun acceptsOnlyFullScreenApplicationWindows() {
        assertTrue(ForegroundPackageTracker.isFullScreenApplicationWindow(AccessibilityWindowInfo.TYPE_APPLICATION, true))
        assertFalse(ForegroundPackageTracker.isFullScreenApplicationWindow(AccessibilityWindowInfo.TYPE_SYSTEM, true))
        assertFalse(ForegroundPackageTracker.isFullScreenApplicationWindow(AccessibilityWindowInfo.TYPE_APPLICATION, false))
    }

    @Test
    fun doesNotAcceptAnUnknownWindowTypeAsAnApplication() {
        assertFalse(ForegroundPackageTracker.isFullScreenApplicationWindow(null, true))
    }
}
