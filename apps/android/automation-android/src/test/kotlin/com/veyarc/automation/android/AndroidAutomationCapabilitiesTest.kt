package com.veyarc.automation.android

import com.veyarc.engine.EngineCapability
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAutomationCapabilitiesTest {
    @Test
    fun reportsGestureAndForegroundCapabilitiesWhenAccessibilityIsReady() {
        val snapshot = AndroidAutomationCapabilities.snapshot(
            accessibilityEnabled = true,
            serviceConnected = true,
        )

        assertTrue(snapshot.gestureDispatchReady)
        assertTrue(snapshot.foregroundPackageReady)
        assertTrue(snapshot.supports(EngineCapability.TAP_POINT))
        assertTrue(snapshot.supports(EngineCapability.LONG_PRESS))
        assertTrue(snapshot.supports(EngineCapability.SWIPE_PATH))
        assertTrue(snapshot.supports(EngineCapability.FOREGROUND_PACKAGE_CONDITION))
    }

    @Test
    fun doesNotReportGesturesUntilTheServiceIsConnected() {
        val snapshot = AndroidAutomationCapabilities.snapshot(
            accessibilityEnabled = true,
            serviceConnected = false,
        )

        assertFalse(snapshot.gestureDispatchReady)
        assertFalse(snapshot.foregroundPackageReady)
        assertFalse(snapshot.supports(EngineCapability.TAP_POINT))
        assertFalse(snapshot.supports(EngineCapability.FOREGROUND_PACKAGE_CONDITION))
    }

    @Test
    fun doesNotReportScreenCaptureConditionsBeforeMediaProjectionPhase() {
        val snapshot = AndroidAutomationCapabilities.snapshot(
            accessibilityEnabled = true,
            serviceConnected = true,
        )

        assertFalse(snapshot.supports(EngineCapability.PIXEL_COLOR_CONDITION))
        assertFalse(snapshot.supports(EngineCapability.IMAGE_TEMPLATE_CONDITION))
        assertTrue(snapshot.supports(EngineCapability.ORIENTATION_CONDITION))
    }
}
