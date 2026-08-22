package com.veyarc.automation.android

import android.os.SystemClock
import com.veyarc.engine.ExecutionClock

/** Monotonic Android clock used by the synchronous workflow runner. */
object AndroidExecutionClock : ExecutionClock {
    override fun nowMs(): Long = SystemClock.elapsedRealtime()

    override fun sleep(durationMs: Long) {
        require(durationMs >= 0L) { "Sleep duration must be nonnegative" }
        if (durationMs == 0L) return
        try {
            Thread.sleep(durationMs)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw AndroidAutomationException("Workflow execution was interrupted", error)
        }
    }
}
