package com.veyarc.feature.recorder

import android.content.Context

/** Process-owned access point for the active service-owned recording session. */
object CoordinateRecordingRuntime {
    @Volatile
    private var sharedController: CoordinateRecordingController? = null

    @Synchronized
    fun controller(context: Context): CoordinateRecordingController = sharedController
        ?: CoordinateRecordingController(context.applicationContext).also { sharedController = it }

    @Synchronized
    fun close() {
        sharedController?.close()
        sharedController = null
    }

    /** Reports an unexpected foreground-service loss without creating a new controller. */
    @Synchronized
    fun notifyRecordingServiceLost(reason: String = "The recording foreground service stopped unexpectedly.") {
        sharedController?.onRecordingServiceLost(reason)
    }
}
