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

    @Volatile
    private var lastEvidenceWasApplication: Boolean = false

    fun current(): PackageName? {
        val service = attachedService ?: return currentPackage
        val activePackage = readActiveApplicationPackage(service)
        if (activePackage != null) {
            currentPackage = activePackage
            lastEvidenceWasApplication = true
        } else if (!lastEvidenceWasApplication) {
            currentPackage = null
        }
        return currentPackage
    }

    /** Recording must never reuse a cached app underneath a system screen or missing service. */
    fun currentForRecording(): PackageName? {
        val service = attachedService ?: return null
        return runCatching {
            if (service.windows.any { isBlockingSystemWindow(it.type, it.isActive, it.isFocused) }) null else readActiveApplicationPackage(service, requireWindow = true)
        }.getOrNull()
    }

    /** True only for a focused or active TYPE_SYSTEM window, not keyboards or Veyarc overlays. */
    internal fun hasActiveSystemWindow(): Boolean {
        val service = attachedService ?: return false
        return runCatching {
            service.windows.any { window ->
                isBlockingSystemWindow(
                    windowType = window.type,
                    isActive = window.isActive,
                    isFocused = window.isFocused,
                )
            }
        }.getOrDefault(false)
    }

    fun attach(service: AccessibilityService) {
        attachedService = service
        currentPackage = readActiveApplicationPackage(service).also {
            lastEvidenceWasApplication = it != null
        }
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
                lastEvidenceWasApplication = false
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
                lastEvidenceWasApplication = false
            }
            return
        }

        if (window != null) {
            currentPackage = packageName
            lastEvidenceWasApplication = true
            return
        }

        // Do not trust an unmatched event package. Vivo can emit a window-state event
        // for an edge panel without exposing its window record. Re-read the active root
        // instead, and fail closed when Android cannot identify an application window.
        currentPackage = readActiveApplicationPackage(service).also {
            lastEvidenceWasApplication = it != null
        }
    }

    internal fun isApplicationWindow(windowType: Int?): Boolean = windowType == AccessibilityWindowInfo.TYPE_APPLICATION

    internal fun isBlockingSystemWindow(
        windowType: Int?,
        isActive: Boolean,
        isFocused: Boolean,
    ): Boolean = windowType == AccessibilityWindowInfo.TYPE_SYSTEM && (isActive || isFocused)

    internal fun isOwnAccessibilityOverlay(windowType: Int?, eventPackage: PackageName, servicePackage: String): Boolean = windowType == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY && eventPackage.value == servicePackage

    fun detach(service: AccessibilityService) {
        if (attachedService === service) {
            attachedService = null
            currentPackage = null
            lastEvidenceWasApplication = false
        }
    }

    private fun readActiveApplicationPackage(service: AccessibilityService, requireWindow: Boolean = false): PackageName? {
        val root = runCatching { service.rootInActiveWindow }.getOrNull() ?: return null
        val packageName = packageNameOf(root.packageName) ?: return null
        val window = runCatching { root.window }.getOrNull()
            ?: runCatching { service.windows.firstOrNull { it.id == root.windowId } }.getOrNull()
        return packageName.takeIf { (!requireWindow && window == null) || isApplicationWindow(window?.type) }
    }

    private fun packageNameOf(value: CharSequence?): PackageName? = value
        ?.toString()
        ?.takeIf { it.isNotBlank() }
        ?.let { packageValue -> runCatching { PackageName(packageValue) }.getOrNull() }
}
