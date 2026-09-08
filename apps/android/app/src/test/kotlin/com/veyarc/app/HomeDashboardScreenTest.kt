package com.veyarc.app

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeDashboardScreenTest {
    @Test
    fun durationUsesCompactHumanReadableUnits() {
        assertEquals("—", formatDuration(null))
        assertEquals("12s", formatDuration(12_999))
        assertEquals("2m 5s", formatDuration(125_000))
    }
}
