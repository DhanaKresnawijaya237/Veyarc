package com.veyarc.automation.android

/** Pure deadline policy used by the Shizuku helper's process-death safety lease. */
internal object ReplayClientLeasePolicy {
    fun renewedDeadline(nowMillis: Long, timeoutMillis: Long): Long {
        require(nowMillis >= 0L) { "Lease clock must be nonnegative" }
        require(timeoutMillis > 0L) { "Lease timeout must be positive" }
        return if (nowMillis > Long.MAX_VALUE - timeoutMillis) Long.MAX_VALUE else nowMillis + timeoutMillis
    }

    fun isExpired(nowMillis: Long, deadlineMillis: Long): Boolean = deadlineMillis <= 0L || nowMillis >= deadlineMillis
}
