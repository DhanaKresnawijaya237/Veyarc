package com.veyarc.feature.recorder

import android.content.Context

/** Process-owned access point for the future service-owned recording session. */
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
}
