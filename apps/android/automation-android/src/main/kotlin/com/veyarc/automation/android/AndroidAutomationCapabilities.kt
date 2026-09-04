package com.veyarc.automation.android

import android.content.Context
import android.media.projection.MediaProjectionManager
import android.os.Build
import com.veyarc.engine.EngineCapabilities
import com.veyarc.engine.EngineCapability

/** Runtime capability information used to compile only workflows Android can execute safely. */
data class AndroidCapabilitySnapshot(
    val accessibilityEnabled: Boolean,
    val serviceConnected: Boolean,
    val engineCapabilities: EngineCapabilities,
    /** True when Android exposes MediaProjection for a consented per-run capture. */
    val mediaProjectionAvailable: Boolean = false,
    val unsafeDeviceState: AndroidUnsafeDeviceState? = null,
) {
    val gestureDispatchReady: Boolean
        get() = accessibilityEnabled && serviceConnected

    val foregroundPackageReady: Boolean
        get() = serviceConnected

    val runReady: Boolean
        get() = accessibilityEnabled && serviceConnected && unsafeDeviceState == null

    val runBlockReason: String?
        get() = unsafeDeviceState?.let { "Run unavailable because ${it.reason}." }
            ?: when {
                !accessibilityEnabled -> "Run unavailable because Veyarc Accessibility is disabled."
                !serviceConnected -> "Run unavailable because Veyarc Accessibility is disconnected."
                else -> null
            }

    fun supports(capability: EngineCapability): Boolean = engineCapabilities.supports(capability)
}

object AndroidAutomationCapabilities {
    fun snapshot(context: Context): AndroidCapabilitySnapshot {
        val appContext = context.applicationContext
        val accessibilityEnabled = AccessibilityGestureDispatcher.isEnabled(appContext)
        val serviceConnected = AccessibilityGestureDispatcher.isConnected
        return snapshot(
            accessibilityEnabled = accessibilityEnabled,
            serviceConnected = serviceConnected,
            apiLevel = Build.VERSION.SDK_INT,
            mediaProjectionAvailable = appContext.getSystemService(MediaProjectionManager::class.java) != null,
            unsafeDeviceState = AndroidDeviceSafety.current(appContext),
        )
    }

    internal fun snapshot(
        accessibilityEnabled: Boolean,
        serviceConnected: Boolean,
        apiLevel: Int = Build.VERSION_CODES.O,
        mediaProjectionAvailable: Boolean = false,
        unsafeDeviceState: AndroidUnsafeDeviceState? = null,
    ): AndroidCapabilitySnapshot {
        val capabilities = buildSet {
            addAll(CORE_CAPABILITIES)
            add(EngineCapability.ORIENTATION_CONDITION)
            if (mediaProjectionAvailable) addAll(VISUAL_CONDITION_CAPABILITIES)
            if (serviceConnected) add(EngineCapability.FOREGROUND_PACKAGE_CONDITION)
            if (accessibilityEnabled && serviceConnected) {
                addAll(BASE_GESTURE_CAPABILITIES)
                if (AccessibilityGesturePolicy.supportsLongGestures(apiLevel)) {
                    addAll(LONG_GESTURE_CAPABILITIES)
                }
            }
        }
        return AndroidCapabilitySnapshot(
            accessibilityEnabled = accessibilityEnabled,
            serviceConnected = serviceConnected,
            mediaProjectionAvailable = mediaProjectionAvailable,
            engineCapabilities = EngineCapabilities.of(capabilities),
            unsafeDeviceState = unsafeDeviceState,
        )
    }

    private val CORE_CAPABILITIES = setOf(
        EngineCapability.WAIT,
        EngineCapability.WAIT_UNTIL,
        EngineCapability.IF_ELSE,
        EngineCapability.REPEAT,
        EngineCapability.WHILE,
        EngineCapability.LAUNCH_APP,
        EngineCapability.STOP,
        EngineCapability.SET_VARIABLE,
        EngineCapability.VARIABLE_COMPARISON_CONDITION,
        EngineCapability.STEP_RESULT_CONDITION,
        EngineCapability.LOGICAL_CONDITION,
    )

    private val BASE_GESTURE_CAPABILITIES = setOf(
        EngineCapability.TAP_POINT,
        EngineCapability.TAP_REGION,
    )

    private val LONG_GESTURE_CAPABILITIES = setOf(
        EngineCapability.LONG_PRESS,
        EngineCapability.SWIPE_PATH,
    )

    private val VISUAL_CONDITION_CAPABILITIES = setOf(
        EngineCapability.PIXEL_COLOR_CONDITION,
        EngineCapability.IMAGE_TEMPLATE_CONDITION,
    )
}
