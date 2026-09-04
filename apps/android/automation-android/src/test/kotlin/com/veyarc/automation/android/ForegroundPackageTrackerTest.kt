package com.veyarc.automation.android

import android.view.accessibility.AccessibilityWindowInfo
import com.veyarc.model.PackageName
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

    @Test
    fun onlyActiveOrFocusedSystemWindowsBlockAutomation() {
        assertTrue(
            ForegroundPackageTracker.isBlockingSystemWindow(
                AccessibilityWindowInfo.TYPE_SYSTEM,
                isActive = true,
                isFocused = false,
            ),
        )
        assertTrue(
            ForegroundPackageTracker.isBlockingSystemWindow(
                AccessibilityWindowInfo.TYPE_SYSTEM,
                isActive = false,
                isFocused = true,
            ),
        )
        assertFalse(
            ForegroundPackageTracker.isBlockingSystemWindow(
                AccessibilityWindowInfo.TYPE_SYSTEM,
                isActive = false,
                isFocused = false,
            ),
        )
        assertFalse(
            ForegroundPackageTracker.isBlockingSystemWindow(
                AccessibilityWindowInfo.TYPE_INPUT_METHOD,
                isActive = true,
                isFocused = true,
            ),
        )
    }

    @Test
    fun recognizesOnlyVeyarcsOwnAccessibilityOverlay() {
        assertTrue(
            ForegroundPackageTracker.isOwnAccessibilityOverlay(
                AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY,
                PackageName("com.veyarc.app"),
                "com.veyarc.app",
            ),
        )
        assertFalse(
            ForegroundPackageTracker.isOwnAccessibilityOverlay(
                AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY,
                PackageName("com.vivo.upslide"),
                "com.veyarc.app",
            ),
        )
        assertFalse(
            ForegroundPackageTracker.isOwnAccessibilityOverlay(
                AccessibilityWindowInfo.TYPE_SYSTEM,
                PackageName("com.veyarc.app"),
                "com.veyarc.app",
            ),
        )
    }
}
