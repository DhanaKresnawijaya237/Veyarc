package com.veyarc.automation.android

/**
 * Capability gate for the strict "freeze human input while replaying" mode.
 *
 * A normal Android accessibility overlay cannot both consume physical touches and let a
 * synthetic dispatchGesture pass through to a third-party application. Keeping that mode
 * unavailable until a privileged input-filter backend is installed is intentional: a failed
 * capability check must send no input and must not install a global input intercept.
 */
data class ReplayFreezeAvailability(
    val available: Boolean,
    val reason: String? = null,
)

object ReplayFreezeCapability {
    private const val UNAVAILABLE_REASON =
        "Strict screen freeze is unavailable safely on this Android build. Android does not " +
            "provide a normal-app input filter that can block human touches while routing the " +
            "same replay gesture to a third-party app. Replay was not started."

    /**
     * The privileged input-filter backend is not implemented yet. Keep this explicit and
     * fail-closed instead of falling back to a touch-exploration or full-screen overlay hack.
     */
    fun availability(): ReplayFreezeAvailability = ReplayFreezeAvailability(available = false, reason = UNAVAILABLE_REASON)
}
