package com.veyarc.automation.android

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.res.Configuration
import android.view.accessibility.AccessibilityEvent

class VeyarcAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        clearUnexpectedTouchExplorationRequest()
        AccessibilityGestureDispatcher.attach(this)
        AccessibilityEmergencyOverlay.attach(this)
        ReplayFloatingController.attach(this)
        ForegroundPackageTracker.attach(this)
        AndroidDeviceSafety.notifyWindowChanges()
    }

    /**
     * A previously installed build could have requested touch exploration at runtime. The current
     * production service never uses that mode, so clear a stale request as soon as Android binds
     * the service. This is a defensive pass-through reset, not a mode switch used for replay.
     */
    private fun clearUnexpectedTouchExplorationRequest() {
        val info = serviceInfo ?: return
        val flag = AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
        if (info.flags and flag == 0) return
        info.flags = info.flags and flag.inv()
        runCatching { serviceInfo = info }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        ForegroundPackageTracker.onAccessibilityEvent(event)
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || event?.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            AndroidDeviceSafety.notifyWindowChanges()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Keep the user-chosen normalized latch position across rotation/density changes. This
        // only rebuilds the small overlay; it never changes input routing or accessibility flags.
        ReplayFloatingController.onDisplayChanged()
    }

    override fun onInterrupt() {
        AccessibilityGestureDispatcher.reset()
        AccessibilityEmergencyOverlay.hide()
        ReplayFloatingController.onServiceInterrupted(this)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        ForegroundPackageTracker.detach(this)
        AccessibilityEmergencyOverlay.detach(this)
        ReplayFloatingController.detach(this)
        AccessibilityGestureDispatcher.detach(this)
        AndroidDeviceSafety.notifyWindowChanges()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        ForegroundPackageTracker.detach(this)
        AccessibilityEmergencyOverlay.detach(this)
        ReplayFloatingController.detach(this)
        AccessibilityGestureDispatcher.detach(this)
        AndroidDeviceSafety.notifyWindowChanges()
        super.onDestroy()
    }
}
