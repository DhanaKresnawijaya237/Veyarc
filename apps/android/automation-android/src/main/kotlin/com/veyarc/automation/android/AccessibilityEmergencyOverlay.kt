package com.veyarc.automation.android

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button

/** Best-effort always-available stop control owned by the accessibility service. */
object AccessibilityEmergencyOverlay {
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var service: AccessibilityService? = null
    private var overlayView: View? = null
    private var overlayWindowManager: WindowManager? = null

    internal fun attach(accessibilityService: AccessibilityService) {
        service = accessibilityService
    }

    internal fun detach(accessibilityService: AccessibilityService) {
        if (service === accessibilityService) {
            service = null
            hide()
        }
    }

    /** Returns false when the accessibility service is not connected yet. */
    fun show(onStop: () -> Unit): Boolean {
        val activeService = service ?: return false
        mainHandler.post {
            if (service !== activeService) return@post
            removeOverlay()
            val button = Button(activeService).apply {
                text = "STOP"
                contentDescription = "Stop Veyarc workflow"
                setOnClickListener {
                    onStop()
                    removeOverlay()
                }
            }
            val windowManager = activeService.getSystemService(WindowManager::class.java)
            val layoutParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = 16
                y = 96
            }
            runCatching {
                windowManager.addView(button, layoutParams)
                overlayView = button
                overlayWindowManager = windowManager
            }
        }
        return true
    }

    fun hide() {
        mainHandler.post { removeOverlay() }
    }

    private fun removeOverlay() {
        val view = overlayView ?: return
        runCatching { overlayWindowManager?.removeView(view) }
        overlayView = null
        overlayWindowManager = null
    }
}
