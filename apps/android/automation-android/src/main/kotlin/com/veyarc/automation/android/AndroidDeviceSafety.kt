package com.veyarc.automation.android

import android.app.KeyguardManager
import android.content.Context
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArraySet

/** Transient Android states in which Veyarc must not dispatch or continue automation input. */
enum class AndroidUnsafeDeviceState(
    val reason: String,
) {
    LOCK_SCREEN_ACTIVE("the lock screen is showing"),
    LOCK_STATE_UNAVAILABLE("Android's lock-screen state is unavailable"),
    ACCESSIBILITY_DISABLED("Veyarc Accessibility is disabled"),
    ACCESSIBILITY_DISCONNECTED("Veyarc Accessibility is disconnected"),
    SYSTEM_WINDOW_ACTIVE("a system dialog or system surface is active"),
}

/** Reads only device state needed to refuse unsafe automation. It never changes Android settings. */
object AndroidDeviceSafety {
    private val windowListeners = CopyOnWriteArraySet<() -> Unit>()

    /** Rechecks readiness when Android updates window metadata, without polling or changing input. */
    fun observeWindowChanges(onChanged: () -> Unit): Closeable {
        windowListeners.add(onChanged)
        return Closeable { windowListeners.remove(onChanged) }
    }

    internal fun notifyWindowChanges() {
        windowListeners.forEach { runCatching(it) }
    }

    fun current(context: Context): AndroidUnsafeDeviceState? {
        val appContext = context.applicationContext
        val keyguardLocked = appContext
            .getSystemService(KeyguardManager::class.java)
            ?.isKeyguardLocked
        return evaluate(
            keyguardLocked = keyguardLocked,
            accessibilityEnabled = AccessibilityGestureDispatcher.isEnabled(appContext),
            accessibilityConnected = AccessibilityGestureDispatcher.isConnected,
            systemWindowActive = ForegroundPackageTracker.hasActiveSystemWindow(),
        )
    }

    internal fun evaluate(
        keyguardLocked: Boolean?,
        accessibilityEnabled: Boolean,
        accessibilityConnected: Boolean,
        systemWindowActive: Boolean,
    ): AndroidUnsafeDeviceState? = when {
        keyguardLocked == true -> AndroidUnsafeDeviceState.LOCK_SCREEN_ACTIVE
        keyguardLocked == null -> AndroidUnsafeDeviceState.LOCK_STATE_UNAVAILABLE
        !accessibilityEnabled -> AndroidUnsafeDeviceState.ACCESSIBILITY_DISABLED
        !accessibilityConnected -> AndroidUnsafeDeviceState.ACCESSIBILITY_DISCONNECTED
        systemWindowActive -> AndroidUnsafeDeviceState.SYSTEM_WINDOW_ACTIVE
        else -> null
    }
}
