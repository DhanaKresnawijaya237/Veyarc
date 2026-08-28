package com.veyarc.automation.android

/**
 * Result types retained for the Phase 0 diagnostic screen and old callers.
 *
 * The old accessibility touch-interception experiment is intentionally disabled. It changed
 * global touch-exploration state and could leave a physical device unable to receive ordinary
 * touches after a service interruption. Third-party recording uses the privileged read-only
 * Shizuku source instead; this class must never register a TouchInteractionController callback.
 */
sealed interface TouchCaptureUpdate {
    data object UnsupportedApi : TouchCaptureUpdate

    data object ServiceUnavailable : TouchCaptureUpdate

    data object AlreadyArmed : TouchCaptureUpdate

    data object DisabledBySafety : TouchCaptureUpdate

    data object Arming : TouchCaptureUpdate

    data object Armed : TouchCaptureUpdate

    data object Capturing : TouchCaptureUpdate

    data class Replaying(val gesture: CapturedTouchGesture) : TouchCaptureUpdate

    data class Completed(val gesture: CapturedTouchGesture) : TouchCaptureUpdate

    data object Cancelled : TouchCaptureUpdate

    data class Failed(val reason: String) : TouchCaptureUpdate
}

/**
 * Compatibility shell for the removed accessibility interception experiment.
 *
 * It deliberately has no service reference, no mutable accessibility flags, no overlay, and no
 * input callback. Keeping the entry point lets the legacy screen explain why the diagnostic is
 * unavailable without leaving a second input-control implementation in the app.
 */
object TouchInterceptionRecorder {
    val isSupported: Boolean = false
    val isArmed: Boolean = false

    fun arm(onUpdate: (TouchCaptureUpdate) -> Unit) {
        onUpdate(TouchCaptureUpdate.DisabledBySafety)
    }

    fun cancel() = Unit

    internal fun reset() = Unit

    internal fun attach(accessibilityService: android.accessibilityservice.AccessibilityService) = Unit

    internal fun detach(accessibilityService: android.accessibilityservice.AccessibilityService) = Unit
}
