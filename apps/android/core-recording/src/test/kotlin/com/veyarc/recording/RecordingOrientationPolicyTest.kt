package com.veyarc.recording

import com.veyarc.model.DisplayOrientation
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingOrientationPolicyTest {
    @Test
    fun replayRequiresTheRecordedOrientationFamily() {
        assertTrue(
            RecordingOrientationPolicy.canReplay(
                DisplayOrientation.PORTRAIT,
                DisplayOrientation.PORTRAIT,
            ),
        )
        assertTrue(
            RecordingOrientationPolicy.canReplay(
                DisplayOrientation.LANDSCAPE,
                DisplayOrientation.LANDSCAPE,
            ),
        )
        assertFalse(
            RecordingOrientationPolicy.canReplay(
                DisplayOrientation.PORTRAIT,
                DisplayOrientation.LANDSCAPE,
            ),
        )
    }

    @Test
    fun captureRejectsEveryPhysicalRotationChange() {
        assertTrue(
            RecordingOrientationPolicy.captureRotationUnchanged(
                TouchDisplayRotation.ROTATION_0,
                TouchDisplayRotation.ROTATION_0,
            ),
        )
        assertFalse(
            RecordingOrientationPolicy.captureRotationUnchanged(
                TouchDisplayRotation.ROTATION_0,
                TouchDisplayRotation.ROTATION_180,
            ),
        )
        assertFalse(
            RecordingOrientationPolicy.captureRotationUnchanged(
                TouchDisplayRotation.ROTATION_90,
                TouchDisplayRotation.ROTATION_270,
            ),
        )
    }
}
