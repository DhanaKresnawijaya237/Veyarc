package com.veyarc.automation.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaProjectionCaptureRuntimeTest {
    @Test
    fun terminationClearsAndClosesTheActiveCapture() {
        val capture = FakeMediaProjectionSession()
        var terminationReason: String? = null
        MediaProjectionCaptureRuntime.prepare()
        val registration = MediaProjectionCaptureRuntime.registerTerminationListener {
            terminationReason = it
        }
        try {
            MediaProjectionCaptureRuntime.attach(capture)

            assertTrue(MediaProjectionCaptureRuntime.awaitActive(0L))
            MediaProjectionCaptureRuntime.notifyTerminated("Consent revoked")

            assertFalse(MediaProjectionCaptureRuntime.isActive)
            assertTrue(capture.closed)
            assertEquals("Consent revoked", terminationReason)
            assertEquals("Consent revoked", MediaProjectionCaptureRuntime.lastError)
        } finally {
            registration.close()
            MediaProjectionCaptureRuntime.notifyTerminated("Test cleanup")
        }
    }

    @Test
    fun expectedDetachLeavesSessionClosureToItsServiceOwner() {
        val capture = FakeMediaProjectionSession()
        MediaProjectionCaptureRuntime.prepare()
        try {
            MediaProjectionCaptureRuntime.attach(capture)
            MediaProjectionCaptureRuntime.detach(capture)

            assertFalse(MediaProjectionCaptureRuntime.isActive)
            assertFalse(capture.closed)
            assertNull(MediaProjectionCaptureRuntime.lastError)
        } finally {
            capture.close()
            MediaProjectionCaptureRuntime.notifyTerminated("Test cleanup")
        }
    }

    @Test
    fun closedListenerDoesNotReceiveLaterTermination() {
        var calls = 0
        MediaProjectionCaptureRuntime.prepare()
        MediaProjectionCaptureRuntime.registerTerminationListener { calls++ }.close()

        MediaProjectionCaptureRuntime.notifyTerminated("Stopped")

        assertEquals(0, calls)
    }

    private class FakeMediaProjectionSession : MediaProjectionSession {
        override var isActive = true
        var closed = false

        override fun captureLatestFrame(): EphemeralScreenFrame? = null

        override fun close() {
            isActive = false
            closed = true
        }
    }
}
