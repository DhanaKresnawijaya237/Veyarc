package com.veyarc.automation.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

class ReplayFreezeCapabilityTest {
    @Test
    fun strictFreezeFailsClosedUntilPrivilegedBackendExists() {
        val availability = ReplayFreezeCapability.availability()

        assertFalse(availability.available)
        assertNotNull(availability.reason)
    }
}
