package com.veyarc.feature.runner

import com.veyarc.model.DisplayOrientation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkflowOrientationPreflightTest {
    @Test
    fun acceptsTheRecordedOrientation() {
        assertNull(
            workflowOrientationBlockReason(
                required = DisplayOrientation.PORTRAIT,
                current = DisplayOrientation.PORTRAIT,
            ),
        )
    }

    @Test
    fun explainsHowToCorrectAnOrientationMismatch() {
        assertEquals(
            "Rotate the device to landscape before starting this workflow.",
            workflowOrientationBlockReason(
                required = DisplayOrientation.LANDSCAPE,
                current = DisplayOrientation.PORTRAIT,
            ),
        )
    }

    @Test
    fun blocksAnUnknownViewport() {
        assertEquals(
            "Run unavailable because the current full-display orientation could not be determined.",
            workflowOrientationBlockReason(
                required = DisplayOrientation.PORTRAIT,
                current = null,
            ),
        )
    }
}
