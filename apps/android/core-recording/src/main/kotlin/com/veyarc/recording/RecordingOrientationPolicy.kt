package com.veyarc.recording

import com.veyarc.model.DisplayOrientation

/** Orientation rules shared by coordinate recording and raw replay. */
object RecordingOrientationPolicy {
    /** Portrait and landscape recordings are separate replay variants. */
    fun canReplay(
        recorded: DisplayOrientation,
        current: DisplayOrientation,
    ): Boolean = recorded == current

    /** Any physical rotation during one capture makes that capture non-reproducible. */
    fun captureRotationUnchanged(
        initial: TouchDisplayRotation,
        current: TouchDisplayRotation,
    ): Boolean = initial == current
}
