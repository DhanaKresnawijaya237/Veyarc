package com.veyarc.automation.android

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

class VeyarcAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        AccessibilityGestureDispatcher.attach(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        AccessibilityGestureDispatcher.detach(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        AccessibilityGestureDispatcher.detach(this)
        super.onDestroy()
    }
}
