package com.veyarc.automation.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidDeviceSafetyTest {
    @Test
    fun lockScreenTakesPriorityOverOtherUnsafeState() {
        assertEquals(
            AndroidUnsafeDeviceState.LOCK_SCREEN_ACTIVE,
            AndroidDeviceSafety.evaluate(true, false, false, true),
        )
    }

    @Test
    fun refusesUnknownLockState() {
        assertEquals(
            AndroidUnsafeDeviceState.LOCK_STATE_UNAVAILABLE,
            AndroidDeviceSafety.evaluate(null, true, true, false),
        )
    }

    @Test
    fun reportsAccessibilityAndSystemWindowFailures() {
        assertEquals(
            AndroidUnsafeDeviceState.ACCESSIBILITY_DISABLED,
            AndroidDeviceSafety.evaluate(false, false, true, false),
        )
        assertEquals(
            AndroidUnsafeDeviceState.ACCESSIBILITY_DISCONNECTED,
            AndroidDeviceSafety.evaluate(false, true, false, false),
        )
        assertEquals(
            AndroidUnsafeDeviceState.SYSTEM_WINDOW_ACTIVE,
            AndroidDeviceSafety.evaluate(false, true, true, true),
        )
    }

    @Test
    fun acceptsAnUnlockedConnectedApplicationSurface() {
        assertNull(AndroidDeviceSafety.evaluate(false, true, true, false))
    }
}
