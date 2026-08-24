package com.veyarc.automation.android

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.TouchInteractionController
import android.annotation.TargetApi
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.MotionEvent

sealed interface TouchCaptureUpdate {
    data object UnsupportedApi : TouchCaptureUpdate

    data object ServiceUnavailable : TouchCaptureUpdate

    data object AlreadyArmed : TouchCaptureUpdate

    data object Arming : TouchCaptureUpdate

    data object Armed : TouchCaptureUpdate

    data object Capturing : TouchCaptureUpdate

    data class Replaying(val gesture: CapturedTouchGesture) : TouchCaptureUpdate

    data class Completed(val gesture: CapturedTouchGesture) : TouchCaptureUpdate

    data object Cancelled : TouchCaptureUpdate

    data class Failed(val reason: String) : TouchCaptureUpdate
}

object TouchInterceptionRecorder {
    private const val ARM_SETTLE_MILLIS = 250L

    @Volatile
    private var service: AccessibilityService? = null
    private var activeCapture: Api33Capture? = null

    val isSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    val isArmed: Boolean
        get() = activeCapture != null

    fun arm(onUpdate: (TouchCaptureUpdate) -> Unit) {
        if (!isSupported) {
            onUpdate(TouchCaptureUpdate.UnsupportedApi)
            return
        }
        val activeService = service
        if (activeService == null) {
            onUpdate(TouchCaptureUpdate.ServiceUnavailable)
            return
        }
        if (activeCapture != null) {
            onUpdate(TouchCaptureUpdate.AlreadyArmed)
            return
        }

        activeCapture =
            Api33Capture(activeService, onUpdate) {
                activeCapture = null
            }.also(Api33Capture::arm)
    }

    /** Cancels the currently armed capture, if any. */
    fun cancel() {
        activeCapture?.cancel()
    }

    /** Emergency cleanup for lifecycle interruption; never leaves a callback or flag behind. */
    internal fun reset() {
        activeCapture?.forceReset()
        activeCapture = null
    }

    internal fun attach(accessibilityService: AccessibilityService) {
        service = accessibilityService
    }

    internal fun detach(accessibilityService: AccessibilityService) {
        if (service === accessibilityService) {
            activeCapture?.forceReset()
            activeCapture = null
            service = null
        }
    }

    @TargetApi(Build.VERSION_CODES.TIRAMISU)
    private class Api33Capture(
        private val service: AccessibilityService,
        private val onUpdate: (TouchCaptureUpdate) -> Unit,
        private val onFinished: () -> Unit,
    ) : TouchInteractionController.Callback {
        private val controller = service.getTouchInteractionController(Display.DEFAULT_DISPLAY)
        private val mainHandler = Handler(Looper.getMainLooper())
        private val builder = CapturedTouchGestureBuilder()
        private val originalFlags = service.serviceInfo?.flags ?: 0
        private var pendingGesture: CapturedTouchGesture? = null
        private var callbackRegistered = false
        private var flagsChanged = false
        private var restoreRunnable: Runnable? = null
        private var replayTimeoutRunnable: Runnable? = null
        private var replayFinished = false
        private var finished = false

        fun arm() {
            onUpdate(TouchCaptureUpdate.Arming)
            enableTouchInterception()
            mainHandler.postDelayed(
                arm@{
                    if (finished) return@arm
                    try {
                        controller.registerCallback(null, this)
                        callbackRegistered = true
                        onUpdate(TouchCaptureUpdate.Armed)
                    } catch (exception: RuntimeException) {
                        fail(
                            reason = "Android could not start touch interception: ${exception.message ?: exception.javaClass.simpleName}",
                            delegateCurrentGesture = false,
                        )
                    }
                },
                ARM_SETTLE_MILLIS,
            )
        }

        private fun enableTouchInterception() {
            if (originalFlags and AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE != 0) return

            val updatedInfo =
                service.serviceInfo
                    ?: run {
                        fail(
                            reason = "Accessibility service information is unavailable.",
                            delegateCurrentGesture = false,
                        )
                        return
                    }
            updatedInfo.flags = updatedInfo.flags or AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
            service.serviceInfo = updatedInfo
            flagsChanged = true
        }

        override fun onMotionEvent(event: MotionEvent) {
            if (finished) return

            if (event.pointerCount != 1) {
                fail("The feasibility recorder supports one pointer only.", delegateCurrentGesture = true)
                return
            }

            val action =
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> CapturedTouchAction.Down

                    MotionEvent.ACTION_MOVE -> CapturedTouchAction.Move

                    MotionEvent.ACTION_UP -> CapturedTouchAction.Up

                    MotionEvent.ACTION_CANCEL -> {
                        if (builder.hasStarted) cancel()
                        return
                    }

                    else -> return
                }
            if (!builder.append(action, event.eventTime, ScreenPoint(event.rawX, event.rawY))) {
                fail("Android delivered an invalid touch sequence.", delegateCurrentGesture = true)
                return
            }
            if (action == CapturedTouchAction.Down) {
                onUpdate(TouchCaptureUpdate.Capturing)
            }
            if (action == CapturedTouchAction.Up) {
                pendingGesture = builder.build()
                finishWhenInteractionClears()
            }
        }

        override fun onStateChanged(state: Int) {
            if (state == TouchInteractionController.STATE_CLEAR) {
                finishWhenInteractionClears()
            }
        }

        fun cancel() {
            if (finished) {
                replayTimeoutRunnable?.let(mainHandler::removeCallbacks)
                replayTimeoutRunnable = null
                replayFinished = true
                AccessibilityGestureDispatcher.cancelActiveGesture()
                releaseInterception(restoreDelayMillis = null)
                restoreServiceFlags()
                onFinished()
                return
            }
            finished = true
            releaseInterception()
            onFinished()
            onUpdate(TouchCaptureUpdate.Cancelled)
        }

        fun forceReset() {
            finished = true
            replayFinished = true
            replayTimeoutRunnable?.let(mainHandler::removeCallbacks)
            replayTimeoutRunnable = null
            restoreRunnable?.let(mainHandler::removeCallbacks)
            restoreRunnable = null
            releaseInterception(restoreDelayMillis = null)
            runCatching { controller.unregisterAllCallbacks() }
            restoreServiceFlags()
        }

        private fun finishWhenInteractionClears() {
            val gesture = pendingGesture ?: return
            if (controller.state != TouchInteractionController.STATE_CLEAR) return

            finished = true
            // Changing the service flags while dispatchGesture is active cancels the replay.
            releaseInterception(restoreDelayMillis = null)
            onUpdate(TouchCaptureUpdate.Replaying(gesture))
            replayTimeoutRunnable =
                Runnable {
                    completeReplay(
                        gesture = gesture,
                        result = null,
                    )
                }.also { timeout ->
                    mainHandler.postDelayed(
                        timeout,
                        gesture.durationMillis + ARM_SETTLE_MILLIS,
                    )
                }
            AccessibilityGestureDispatcher.dispatchRecordedGesture(
                points = gesture.points,
                durationMillis = gesture.durationMillis,
            ) { result ->
                completeReplay(gesture, result)
            }
        }

        private fun completeReplay(
            gesture: CapturedTouchGesture,
            result: GestureDispatchResult?,
        ) {
            if (replayFinished) return
            replayFinished = true
            replayTimeoutRunnable?.let(mainHandler::removeCallbacks)
            replayTimeoutRunnable = null
            restoreServiceFlags()
            onFinished()
            if (result == null) {
                onUpdate(TouchCaptureUpdate.Failed("Replay timed out."))
                return
            }
            when (result) {
                GestureDispatchResult.Completed -> onUpdate(TouchCaptureUpdate.Completed(gesture))
                else -> onUpdate(TouchCaptureUpdate.Failed("Replay failed: $result"))
            }
        }

        private fun fail(
            reason: String,
            delegateCurrentGesture: Boolean,
        ) {
            if (finished) return
            if (delegateCurrentGesture) controller.requestDelegating()
            finished = true
            releaseInterception()
            onFinished()
            onUpdate(TouchCaptureUpdate.Failed(reason))
        }

        private fun releaseInterception(restoreDelayMillis: Long? = ARM_SETTLE_MILLIS) {
            if (callbackRegistered) {
                try {
                    controller.unregisterCallback(this)
                } catch (_: RuntimeException) {
                    // The service may have been disconnected during teardown.
                }
                callbackRegistered = false
            }
            restoreDelayMillis?.let(::scheduleFlagRestore)
        }

        private fun scheduleFlagRestore(delayMillis: Long) {
            if (!flagsChanged) return
            restoreRunnable?.let(mainHandler::removeCallbacks)
            restoreRunnable =
                Runnable {
                    restoreRunnable = null
                    restoreServiceFlags()
                }.also { restore ->
                    mainHandler.postDelayed(restore, delayMillis)
                }
        }

        private fun restoreServiceFlags() {
            if (!flagsChanged) return
            val currentInfo = service.serviceInfo
            if (currentInfo == null) {
                flagsChanged = false
                return
            }
            if (currentInfo.flags and AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE == 0) {
                flagsChanged = false
                return
            }
            currentInfo.flags =
                (currentInfo.flags and AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE.inv()) or
                (originalFlags and AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE)
            try {
                service.serviceInfo = currentInfo
            } catch (_: RuntimeException) {
                // The service may have been disconnected during teardown.
            }
            flagsChanged = false
        }
    }
}
