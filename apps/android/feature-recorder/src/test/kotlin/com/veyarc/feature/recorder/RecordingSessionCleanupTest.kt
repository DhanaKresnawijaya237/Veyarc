package com.veyarc.feature.recorder

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingSessionCleanupTest {
    @Test
    fun duplicateStopRequestsAreIgnored() {
        val cleanup = RecordingSessionCleanup()

        assertTrue(cleanup.begin())
        assertTrue(cleanup.requestStop(RecordingStopCause.USER_STOP))
        assertFalse(cleanup.requestStop(RecordingStopCause.TIMEOUT))
        assertFalse(cleanup.requestStop(RecordingStopCause.SERVICE_DISCONNECT))
        assertTrue(cleanup.claimTerminal())

        cleanup.reset()
        assertTrue(cleanup.begin())
    }

    @Test
    fun onlyOneTerminalCallbackCanWin() {
        val cleanup = RecordingSessionCleanup()

        assertTrue(cleanup.begin())
        assertTrue(cleanup.claimTerminal())
        assertFalse(cleanup.claimTerminal())
        assertFalse(cleanup.requestStop(RecordingStopCause.PROCESS_CLOSE))
    }

    @Test
    fun unsafeStopsPreservePartialTouchesButUserStopDoesNot() {
        val cleanup = RecordingSessionCleanup()

        assertTrue(cleanup.begin())
        assertTrue(cleanup.requestStop(RecordingStopCause.USER_STOP))
        assertFalse(requireNotNull(cleanup.currentStopCause()).preservePartialTouch)
        assertFalse(requireNotNull(cleanup.currentStopCause()).isFailure)

        cleanup.claimTerminal()
        cleanup.reset()
        assertNull(cleanup.currentStopCause())

        assertTrue(cleanup.begin())
        assertTrue(cleanup.requestStop(RecordingStopCause.SERVICE_DISCONNECT))
        assertTrue(requireNotNull(cleanup.currentStopCause()).preservePartialTouch)
        assertTrue(requireNotNull(cleanup.currentStopCause()).isFailure)
    }
}
