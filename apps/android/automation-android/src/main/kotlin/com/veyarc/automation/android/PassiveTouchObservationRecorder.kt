package com.veyarc.automation.android

/**
 * Capability gate for third-party passive touch observation.
 *
 * The previous accessibility touch-exploration experiment could disrupt normal Vivo input, so
 * this adapter is intentionally disabled until a transparent privileged source is verified. It
 * never changes accessibility flags, intercepts touch, or replays anything.
 */
object PassiveTouchObservationRecorder {
    val isSupported: Boolean = false
}
