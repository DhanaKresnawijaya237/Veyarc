package com.veyarc.automation.android

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.provider.Settings
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityManager
import java.util.concurrent.atomic.AtomicBoolean

data class ScreenPoint(
    val x: Float,
    val y: Float,
)

sealed interface GestureDispatchResult {
    data object Completed : GestureDispatchResult

    data object Cancelled : GestureDispatchResult

    data object ServiceUnavailable : GestureDispatchResult

    data object AlreadyInProgress : GestureDispatchResult

    data object RejectedBySystem : GestureDispatchResult

    data class InvalidRequest(val reason: String) : GestureDispatchResult
}

object AccessibilityGestureDispatcher {
    private const val MAX_GESTURE_DURATION_MILLIS = 60_000L
    private const val LONG_PRESS_MARGIN_MILLIS = 100L

    @Volatile
    private var service: AccessibilityService? = null
    private val dispatchInProgress = AtomicBoolean(false)

    val isConnected: Boolean
        get() = service != null

    fun isEnabled(context: Context): Boolean {
        val manager = context.getSystemService(AccessibilityManager::class.java)
        return manager
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { serviceInfo ->
                val info = serviceInfo.resolveInfo.serviceInfo
                info.packageName == context.packageName &&
                    info.name == VeyarcAccessibilityService::class.java.name
            }
    }

    fun openAccessibilitySettings(context: Context): Boolean = try {
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

    fun dispatchTap(
        point: ScreenPoint,
        onResult: (GestureDispatchResult) -> Unit,
    ) {
        dispatch(listOf(point), ViewConfiguration.getTapTimeout().toLong(), onResult)
    }

    fun dispatchLongPress(
        point: ScreenPoint,
        onResult: (GestureDispatchResult) -> Unit,
    ) {
        val duration = ViewConfiguration.getLongPressTimeout() + LONG_PRESS_MARGIN_MILLIS
        dispatch(listOf(point), duration, onResult)
    }

    /** Dispatches a long press for the duration declared by the workflow. */
    fun dispatchLongPress(
        point: ScreenPoint,
        durationMillis: Long,
        onResult: (GestureDispatchResult) -> Unit,
    ) {
        dispatch(listOf(point), durationMillis, onResult)
    }

    fun dispatchPath(
        points: List<ScreenPoint>,
        durationMillis: Long,
        onResult: (GestureDispatchResult) -> Unit,
    ) {
        dispatch(points, durationMillis, onResult, minimumPoints = 2)
    }

    internal fun dispatchRecordedGesture(
        points: List<ScreenPoint>,
        durationMillis: Long,
        onResult: (GestureDispatchResult) -> Unit,
    ) {
        dispatch(points, durationMillis, onResult)
    }

    internal fun attach(accessibilityService: AccessibilityService) {
        service = accessibilityService
    }

    internal fun detach(accessibilityService: AccessibilityService) {
        if (service === accessibilityService) {
            service = null
            dispatchInProgress.set(false)
        }
    }

    private fun dispatch(
        points: List<ScreenPoint>,
        durationMillis: Long,
        onResult: (GestureDispatchResult) -> Unit,
        minimumPoints: Int = 1,
    ) {
        validateRequest(points, durationMillis, minimumPoints)?.let {
            onResult(it)
            return
        }

        val activeService = service
        if (activeService == null) {
            onResult(GestureDispatchResult.ServiceUnavailable)
            return
        }
        if (!dispatchInProgress.compareAndSet(false, true)) {
            onResult(GestureDispatchResult.AlreadyInProgress)
            return
        }

        val path = Path().apply {
            moveTo(points.first().x, points.first().y)
            points.drop(1).forEach { point -> lineTo(point.x, point.y) }
        }
        val gesture =
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMillis))
                .build()
        val accepted =
            activeService.dispatchGesture(
                gesture,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        dispatchInProgress.set(false)
                        onResult(GestureDispatchResult.Completed)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        dispatchInProgress.set(false)
                        onResult(GestureDispatchResult.Cancelled)
                    }
                },
                null,
            )

        if (!accepted) {
            dispatchInProgress.set(false)
            onResult(GestureDispatchResult.RejectedBySystem)
        }
    }

    internal fun validateRequest(
        points: List<ScreenPoint>,
        durationMillis: Long,
        minimumPoints: Int,
    ): GestureDispatchResult.InvalidRequest? {
        if (points.size < minimumPoints) {
            return GestureDispatchResult.InvalidRequest("Gesture requires at least $minimumPoints point(s).")
        }
        if (durationMillis !in 1..MAX_GESTURE_DURATION_MILLIS) {
            return GestureDispatchResult.InvalidRequest("Gesture duration is outside Android limits.")
        }
        if (points.any { !it.x.isFinite() || !it.y.isFinite() || it.x < 0f || it.y < 0f }) {
            return GestureDispatchResult.InvalidRequest("Gesture contains an invalid screen coordinate.")
        }
        return null
    }
}
