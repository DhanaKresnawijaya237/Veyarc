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
    private var overlayLayoutParams: WindowManager.LayoutParams? = null

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
            if (!removeOverlay()) {
                makeOverlayPassThroughAndRetry()
                return@post
            }
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
                overlayLayoutParams = layoutParams
            }
        }
        return true
    }

    fun hide() {
        mainHandler.post {
            if (!removeOverlay()) makeOverlayPassThroughAndRetry()
        }
    }

    private fun removeOverlay(): Boolean {
        val view = overlayView ?: run {
            overlayWindowManager = null
            overlayLayoutParams = null
            return true
        }
        val manager = overlayWindowManager
        val removed = if (manager == null) {
            view.parent == null
        } else {
            runCatching {
                manager.removeViewImmediate(view)
                true
            }.getOrElse { view.parent == null }
        }
        if (!removed) return false
        overlayView = null
        overlayWindowManager = null
        overlayLayoutParams = null
        return true
    }

    /** A failed WindowManager removal must never leave an interactive orphaned stop button. */
    private fun makeOverlayPassThroughAndRetry() {
        val view = overlayView ?: return
        val manager = overlayWindowManager
        val params = overlayLayoutParams
        if (manager != null && params != null) {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            runCatching { manager.updateViewLayout(view, params) }
        }
        view.visibility = View.INVISIBLE
        view.isEnabled = false
        view.isClickable = false
        view.isFocusable = false
        view.setOnClickListener(null)
        mainHandler.postDelayed({ retryRemoval(view, MAX_REMOVAL_RETRIES) }, REMOVAL_RETRY_DELAY_MILLIS)
    }

    private fun retryRemoval(view: View, attemptsRemaining: Int) {
        if (overlayView !== view || attemptsRemaining <= 0) return
        if (removeOverlay()) return
        mainHandler.postDelayed(
            { retryRemoval(view, attemptsRemaining - 1) },
            REMOVAL_RETRY_DELAY_MILLIS,
        )
    }

    private const val REMOVAL_RETRY_DELAY_MILLIS = 50L
    private const val MAX_REMOVAL_RETRIES = 6
}
