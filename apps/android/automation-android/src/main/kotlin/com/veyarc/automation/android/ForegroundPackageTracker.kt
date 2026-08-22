package com.veyarc.automation.android

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.veyarc.model.PackageName

/** Keeps the last application package reported by Android's window-state events. */
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
        val service = attachedService ?: return
        if (event == null) return
        // TYPE_WINDOWS_CHANGED is emitted for transient overlays and system-window
        // bookkeeping; it is not a reliable foreground-app signal. Only a window-state
        // transition should replace the package used by the fail-closed runner.
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return
        }

        val packageName = event.packageName
            ?.toString()
            ?.takeIf { it.isNotBlank() }
            ?.let { packageValue -> runCatching { PackageName(packageValue) }.getOrNull() }
            ?: run {
                currentPackage = null
                return
            }

        // Only a full-screen application window is a foreground target. Screenshot,
        // edge-panel, keyboard, system, and accessibility-overlay windows are not safe
        // targets for workflow input. Keep the last app for a short-lived application
        // dialog, but clear it for a known non-application window so the runner fails
        // closed until an application window becomes foreground again.
        // The service opts into interactive-window metadata so it can distinguish an
        // application window from an overlay. It never requests a root node or reads
        // the view hierarchy.
        val window = runCatching {
            service.windows.firstOrNull { it.id == event.windowId }
        }.getOrNull()
        if (window != null && window.type != AccessibilityWindowInfo.TYPE_APPLICATION) {
            currentPackage = null
            return
        }
        if (window != null && !isFullScreenApplicationWindow(window.type, event.isFullScreen)) return
        if (window == null && !event.isFullScreen) {
            currentPackage = null
            return
        }

        currentPackage = packageName
    }

    internal fun isFullScreenApplicationWindow(windowType: Int?, isFullScreen: Boolean): Boolean = windowType == AccessibilityWindowInfo.TYPE_APPLICATION && isFullScreen

    fun detach(service: AccessibilityService) {
        if (attachedService === service) {
            attachedService = null
            currentPackage = null
        }
    }
}
