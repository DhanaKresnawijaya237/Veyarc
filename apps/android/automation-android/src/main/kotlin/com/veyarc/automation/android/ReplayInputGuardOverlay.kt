package com.veyarc.automation.android

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.TouchInteractionController
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.MotionEvent
import android.view.ViewConfiguration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * Optional replay-only touch guard owned by the connected accessibility service.
 *
 * A touch-intercepting accessibility overlay cannot be used here: it becomes the top window,
 * so Android's UID-targeted InputManager injection quite correctly refuses to route replay into
 * the target app underneath it. Instead, Android 13+'s TouchInteractionController keeps physical
 * interactions in the accessibility service while the target app remains the top application
 * window. The Shizuku helper can therefore target the real app UID without sending replay into
 * Veyarc's control surface.
 *
 * System edge gestures are deliberately delegated so home/back/notification gestures remain an
 * emergency path. All other physical target-app interactions stay in the accessibility service
 * and are never delegated to the app.
 */
object ReplayInputGuardOverlay {
    private const val SHOW_TIMEOUT_MILLIS = 500L
    private const val SYSTEM_EDGE_SLOP_DP = 24f

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var service: AccessibilityService? = null

    @Volatile
    private var touchController: TouchInteractionController? = null

    @Volatile
    private var touchCallback: TouchInteractionController.Callback? = null

    private var originalServiceFlags: Int? = null
    private var serviceFlagsChanged = false

    val isAvailable: Boolean
        get() = service != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    val isShown: Boolean
        get() = touchController != null && touchCallback != null

    internal fun attach(accessibilityService: AccessibilityService) {
        service = accessibilityService
    }

    internal fun detach(accessibilityService: AccessibilityService) {
        if (service === accessibilityService) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                removeOnMain()
                service = null
            } else {
                mainHandler.post {
                    if (service === accessibilityService) {
                        removeOnMain()
                        service = null
                    }
                }
            }
        }
    }

    /** Enables the guard synchronously when possible so replay cannot start before it is active. */
    fun show(): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) return showOnMain()
        val result = AtomicBoolean(false)
        val completed = CountDownLatch(1)
        mainHandler.post {
            result.set(showOnMain())
            completed.countDown()
        }
        runCatching { completed.await(SHOW_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS) }
        return result.get()
    }

    fun hide() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            removeOnMain()
        } else {
            mainHandler.post(::removeOnMain)
        }
    }

    private fun showOnMain(): Boolean {
        val activeService = service ?: return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        removeOnMain()

        val serviceInfo = activeService.serviceInfo ?: return false
        originalServiceFlags = serviceInfo.flags
        if (serviceInfo.flags and AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE == 0) {
            serviceInfo.flags =
                serviceInfo.flags or AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
            if (!runCatching { activeService.serviceInfo = serviceInfo }.isSuccess) {
                originalServiceFlags = null
                return false
            }
            serviceFlagsChanged = true
        }

        val controller = runCatching {
            activeService.getTouchInteractionController(Display.DEFAULT_DISPLAY)
        }.getOrNull() ?: run {
            restoreServiceFlags(activeService)
            return false
        }
        if (controller.getState() != TouchInteractionController.STATE_CLEAR) {
            // A back/home gesture may still be completing after the previous replay was
            // cancelled. unregisterAllCallbacks() returns control of that interaction to the
            // framework, but Android may keep the controller state as DELEGATING until the
            // user's finger is released. Re-registering our callback here is safe: it does not
            // take over the already delegated gesture, and it makes the next interaction guarded
            // as soon as the framework returns to CLEAR. Refusing to re-arm here leaves the guard
            // permanently unavailable after an otherwise valid emergency back gesture.
            runCatching { controller.unregisterAllCallbacks() }
        }
        val metrics = activeService.resources.displayMetrics
        val edgeSlopPx = max(
            ViewConfiguration.get(activeService).scaledEdgeSlop.toFloat(),
            metrics.density * SYSTEM_EDGE_SLOP_DP,
        )
        val callback = ReplayTouchGuardCallback(
            controller = controller,
            edgeSlopPx = edgeSlopPx,
            displayWidthPx = metrics.widthPixels.toFloat(),
            displayHeightPx = metrics.heightPixels.toFloat(),
        )
        if (!runCatching { controller.registerCallback(null, callback) }.isSuccess) {
            restoreServiceFlags(activeService)
            return false
        }
        touchController = controller
        touchCallback = callback
        return true
    }

    private fun removeOnMain() {
        val controller = touchController
        val callback = touchCallback
        touchController = null
        touchCallback = null
        if (controller != null && callback != null) {
            runCatching { controller.unregisterCallback(callback) }
            if (runCatching {
                    controller.getState() != TouchInteractionController.STATE_CLEAR
                }.getOrDefault(true)
            ) {
                // unregisterCallback removes our listener, while unregisterAllCallbacks also
                // returns an interaction that was already delegated to the framework to CLEAR.
                runCatching { controller.unregisterAllCallbacks() }
            }
        }
        service?.let(::restoreServiceFlags)
    }

    private fun restoreServiceFlags(activeService: AccessibilityService) {
        val originalFlags = originalServiceFlags ?: return
        if (serviceFlagsChanged) {
            val currentInfo = activeService.serviceInfo
            if (currentInfo != null) {
                currentInfo.flags =
                    (currentInfo.flags and AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE.inv()) or
                    (originalFlags and AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE)
                runCatching { activeService.serviceInfo = currentInfo }
            }
        }
        serviceFlagsChanged = false
        this.originalServiceFlags = null
    }

    private class ReplayTouchGuardCallback(
        private val controller: TouchInteractionController,
        private val edgeSlopPx: Float,
        private val displayWidthPx: Float,
        private val displayHeightPx: Float,
    ) : TouchInteractionController.Callback {
        override fun onMotionEvent(event: MotionEvent) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN && isSystemEdgeStart(event)) {
                // Delegate the whole interaction so system back/home/notification gestures remain
                // usable. Non-edge interactions intentionally remain in TOUCH_INTERACTING and are
                // therefore not delivered to the target application.
                runCatching { controller.requestDelegating() }
            }
        }

        override fun onStateChanged(state: Int) = Unit

        private fun isSystemEdgeStart(event: MotionEvent): Boolean = event.rawX <= edgeSlopPx ||
            event.rawX >= displayWidthPx - edgeSlopPx ||
            event.rawY <= edgeSlopPx ||
            event.rawY >= displayHeightPx - edgeSlopPx
    }
}
