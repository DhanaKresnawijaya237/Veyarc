package com.veyarc.automation.android

import android.os.Build

/** API-level limits for gesture dispatch that must stay aligned with advertised capabilities. */
internal object AccessibilityGesturePolicy {
    const val MAX_LEGACY_GESTURE_DURATION_MILLIS = 100L

    fun supportsLongGestures(apiLevel: Int): Boolean = apiLevel >= Build.VERSION_CODES.O

    fun durationRejectionReason(apiLevel: Int, durationMillis: Long): String? = if (!supportsLongGestures(apiLevel) && durationMillis > MAX_LEGACY_GESTURE_DURATION_MILLIS) {
        "Gestures longer than $MAX_LEGACY_GESTURE_DURATION_MILLIS ms require Android 8.0 or later for safe interruption."
    } else {
        null
    }
}
