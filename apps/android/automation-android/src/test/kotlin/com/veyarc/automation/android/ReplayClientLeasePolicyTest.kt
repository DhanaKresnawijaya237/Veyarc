package com.veyarc.automation.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayClientLeasePolicyTest {
    @Test
    fun renewedLeaseExpiresAtItsBoundedDeadline() {
        val deadline = ReplayClientLeasePolicy.renewedDeadline(1_000L, 2_000L)

        assertEquals(3_000L, deadline)
        assertFalse(ReplayClientLeasePolicy.isExpired(2_999L, deadline))
        assertTrue(ReplayClientLeasePolicy.isExpired(3_000L, deadline))
    }

    @Test
    fun missingLeaseIsAlwaysExpired() {
        assertTrue(ReplayClientLeasePolicy.isExpired(nowMillis = 1L, deadlineMillis = 0L))
    }

    @Test
    fun renewalSaturatesInsteadOfOverflowing() {
        assertEquals(
            Long.MAX_VALUE,
            ReplayClientLeasePolicy.renewedDeadline(Long.MAX_VALUE - 1L, 2_000L),
        )
    }
}
