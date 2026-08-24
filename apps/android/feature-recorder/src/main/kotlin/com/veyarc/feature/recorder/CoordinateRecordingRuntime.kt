package com.veyarc.feature.recorder

/** Process-owned access point for the future service-owned recording session. */
object CoordinateRecordingRuntime {
    @Volatile
    private var sharedController: CoordinateRecordingController? = null

    @Synchronized
    fun controller(): CoordinateRecordingController = sharedController
        ?: CoordinateRecordingController().also { sharedController = it }
}
