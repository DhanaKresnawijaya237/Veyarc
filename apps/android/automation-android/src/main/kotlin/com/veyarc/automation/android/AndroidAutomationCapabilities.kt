package com.veyarc.automation.android

import android.content.Context
import android.media.projection.MediaProjectionManager
import com.veyarc.engine.EngineCapabilities
import com.veyarc.engine.EngineCapability

/** Runtime capability information used to compile only workflows Android can execute safely. */
data class AndroidCapabilitySnapshot(
    val accessibilityEnabled: Boolean,
    val serviceConnected: Boolean,
    val engineCapabilities: EngineCapabilities,
    /** True when Android exposes MediaProjection for a consented per-run capture. */
    val mediaProjectionAvailable: Boolean = false,
) {
    val gestureDispatchReady: Boolean
        get() = accessibilityEnabled && serviceConnected

    val foregroundPackageReady: Boolean
        get() = serviceConnected

    fun supports(capability: EngineCapability): Boolean = engineCapabilities.supports(capability)
}

object AndroidAutomationCapabilities {
    fun snapshot(context: Context): AndroidCapabilitySnapshot = snapshot(
        accessibilityEnabled = AccessibilityGestureDispatcher.isEnabled(context),
        serviceConnected = AccessibilityGestureDispatcher.isConnected,
        mediaProjectionAvailable = context.getSystemService(MediaProjectionManager::class.java) != null,
    )

    internal fun snapshot(
        accessibilityEnabled: Boolean,
        serviceConnected: Boolean,
        mediaProjectionAvailable: Boolean = false,
    ): AndroidCapabilitySnapshot {
        val capabilities = buildSet {
            addAll(CORE_CAPABILITIES)
            add(EngineCapability.ORIENTATION_CONDITION)
            if (mediaProjectionAvailable) addAll(VISUAL_CONDITION_CAPABILITIES)
            if (serviceConnected) add(EngineCapability.FOREGROUND_PACKAGE_CONDITION)
            if (accessibilityEnabled && serviceConnected) addAll(GESTURE_CAPABILITIES)
        }
        return AndroidCapabilitySnapshot(
            accessibilityEnabled = accessibilityEnabled,
            serviceConnected = serviceConnected,
            mediaProjectionAvailable = mediaProjectionAvailable,
            engineCapabilities = EngineCapabilities.of(capabilities),
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

    private val GESTURE_CAPABILITIES = setOf(
        EngineCapability.TAP_POINT,
        EngineCapability.TAP_REGION,
        EngineCapability.LONG_PRESS,
        EngineCapability.SWIPE_PATH,
    )

    private val VISUAL_CONDITION_CAPABILITIES = setOf(
        EngineCapability.PIXEL_COLOR_CONDITION,
        EngineCapability.IMAGE_TEMPLATE_CONDITION,
    )
}
