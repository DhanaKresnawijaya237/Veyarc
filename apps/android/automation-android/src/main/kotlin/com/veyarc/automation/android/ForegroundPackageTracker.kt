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

        // Vivo and other OEMs report their screenshot/edge-panel windows as state
        // changes. Keep the last application package while these short-lived overlays
        // are visible, so a normal workflow is not aborted by the overlay itself.
        if (isTransientSystemPackage(packageName)) return

        // A persistent system window, keyboard, or accessibility overlay is not a safe
        // target for workflow input. Clear the value so the runner fails closed until an
        // application window becomes foreground again. If the event window is not
        // available on an OEM build, retain the package event as a compatibility fallback.
        val windowType = runCatching {
            service.windows.firstOrNull { it.id == event.windowId }?.type
        }.getOrNull()
        if (windowType != null && windowType != AccessibilityWindowInfo.TYPE_APPLICATION) {
            currentPackage = null
            return
        }

        currentPackage = packageName
    }

    internal fun isTransientSystemPackage(packageName: PackageName): Boolean = transientSystemPackagePrefixes.any(packageName.value::startsWith)

    fun detach(service: AccessibilityService) {
        if (attachedService === service) {
            attachedService = null
            currentPackage = null
        }
    }

    private val transientSystemPackagePrefixes = listOf(
        "com.vivo.upslide",
        "com.vivo.smartshot",
        "com.coloros.screenshot",
        "com.oplus.screenshot",
        "com.oppo.screenshot",
        "com.miui.screenshot",
        "com.samsung.android.app.smartcapture",
    )
}
