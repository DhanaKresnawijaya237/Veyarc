package com.veyarc.automation.android

import android.content.Context
import com.veyarc.engine.EngineCapabilities
import com.veyarc.engine.EngineCapability

/** Runtime capability information used to compile only workflows Android can execute safely. */
data class AndroidCapabilitySnapshot(
    val accessibilityEnabled: Boolean,
    val serviceConnected: Boolean,
    val engineCapabilities: EngineCapabilities,
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
    )

    internal fun snapshot(
        accessibilityEnabled: Boolean,
        serviceConnected: Boolean,
    ): AndroidCapabilitySnapshot {
        val capabilities = buildSet {
            addAll(CORE_CAPABILITIES)
            add(EngineCapability.ORIENTATION_CONDITION)
            if (serviceConnected) add(EngineCapability.FOREGROUND_PACKAGE_CONDITION)
            if (accessibilityEnabled && serviceConnected) addAll(GESTURE_CAPABILITIES)
        }
        return AndroidCapabilitySnapshot(
            accessibilityEnabled = accessibilityEnabled,
            serviceConnected = serviceConnected,
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
}
