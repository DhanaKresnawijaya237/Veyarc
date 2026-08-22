package com.veyarc.automation.android

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.veyarc.model.PackageName

/** Tracks the active application package without inspecting child view content. */
internal object ForegroundPackageTracker {
    @Volatile
    private var attachedService: AccessibilityService? = null

    @Volatile
    private var currentPackage: PackageName? = null

    fun current(): PackageName? = currentPackage

    fun attach(service: AccessibilityService) {
        attachedService = service
        currentPackage = readActiveApplicationPackage(service)
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

        // A matching application window is a foreground target. Screenshot, edge-panel,
        // keyboard, system, and accessibility-overlay windows are not safe targets for
        // workflow input, so clear the value for a known non-application window and let
        // the runner fail closed until an application window becomes foreground again.
        // Do not require event.isFullScreen here: OEM builds commonly report normal
        // edge-to-edge application windows as not full-screen even though they are the
        // active app window.
        // The service opts into interactive-window metadata so it can distinguish an
        // application window from an overlay. When an event is unavailable at service
        // startup, attach() reads only the active root's package and window id. It never
        // traverses child nodes or inspects the screen's view hierarchy.
        val window = runCatching {
            service.windows.firstOrNull { it.id == event.windowId }
        }.getOrNull()
        if (window != null && !isApplicationWindow(window.type)) {
            // Veyarc's emergency stop control is a non-focusable accessibility overlay
            // owned by this service. It must not replace the application underneath it.
            if (!isOwnAccessibilityOverlay(window.type, packageName, service.packageName)) {
                currentPackage = null
            }
            return
        }

        // Some OEM builds do not expose a matching AccessibilityWindowInfo even when
        // the event is a normal application transition. In that case the event package
        // is the only available signal. The workflow allowlist is still checked by the
        // runner before any input is dispatched, so an undeclared overlay package is
        // rejected rather than acted on.
        currentPackage = packageName
    }

    internal fun isApplicationWindow(windowType: Int?): Boolean = windowType == AccessibilityWindowInfo.TYPE_APPLICATION

    internal fun isOwnAccessibilityOverlay(windowType: Int?, eventPackage: PackageName, servicePackage: String): Boolean = windowType == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY && eventPackage.value == servicePackage

    fun detach(service: AccessibilityService) {
        if (attachedService === service) {
            attachedService = null
            currentPackage = null
        }
    }

    private fun readActiveApplicationPackage(service: AccessibilityService): PackageName? {
        val root = runCatching { service.rootInActiveWindow }.getOrNull() ?: return null
        val packageName = packageNameOf(root.packageName) ?: return null
        val window = runCatching {
            service.windows.firstOrNull { it.id == root.windowId }
        }.getOrNull()
        return packageName.takeIf { window == null || isApplicationWindow(window.type) }
    }

    private fun packageNameOf(value: CharSequence?): PackageName? = value
        ?.toString()
        ?.takeIf { it.isNotBlank() }
        ?.let { packageValue -> runCatching { PackageName(packageValue) }.getOrNull() }
}
