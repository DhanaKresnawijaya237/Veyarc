package com.veyarc.automation.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayServiceBindingPolicyTest {
    @Test
    fun closedClientDisposesALateHelperBinding() {
        assertTrue(
            ReplayServiceBindingPolicy.shouldDisposeConnectedService(
                closed = true,
                activeRequest = true,
                hasPendingCommands = true,
            ),
        )
    }

    @Test
    fun cancelledRequestDisposesALateHelperBinding() {
        assertTrue(
            ReplayServiceBindingPolicy.shouldDisposeConnectedService(
                closed = false,
                activeRequest = false,
                hasPendingCommands = false,
            ),
        )
    }

    @Test
    fun activeRequestKeepsBindingWhileCommandsArePending() {
        assertFalse(
            ReplayServiceBindingPolicy.shouldDisposeConnectedService(
                closed = false,
                activeRequest = true,
                hasPendingCommands = true,
            ),
        )
    }

    @Test
    fun activeRequestKeepsBindingAfterSubmission() {
        assertFalse(
            ReplayServiceBindingPolicy.shouldDisposeConnectedService(
                closed = false,
                activeRequest = true,
                hasPendingCommands = false,
            ),
        )
    }
}
