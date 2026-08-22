package com.veyarc.automation.android

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.veyarc.model.PackageName

/** Keeps the last package reported by Android's window-change accessibility events. */
internal object ForegroundPackageTracker {
    @Volatile
    private var attachedService: AccessibilityService? = null

    @Volatile
    private var currentPackage: PackageName? = null

    fun current(): PackageName? = currentPackage

    fun attach(service: AccessibilityService) {
        attachedService = service
        currentPackage = null
    }

    fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || attachedService == null) return
        // TYPE_WINDOWS_CHANGED is emitted for transient overlays and system-window
        // bookkeeping; it is not a reliable foreground-app signal. Only a window-state
        // transition should replace the package used by the fail-closed runner.
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return
        }

        currentPackage = event.packageName
            ?.toString()
            ?.takeIf { it.isNotBlank() }
            ?.let { packageValue -> runCatching { PackageName(packageValue) }.getOrNull() }
    }

    fun detach(service: AccessibilityService) {
        if (attachedService === service) {
            attachedService = null
            currentPackage = null
        }
    }
}
