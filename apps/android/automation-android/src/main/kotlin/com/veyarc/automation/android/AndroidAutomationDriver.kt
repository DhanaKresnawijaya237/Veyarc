package com.veyarc.automation.android

import android.content.Context
import android.os.Looper
import com.veyarc.engine.ActiveViewport
import com.veyarc.engine.AutomationDriver
import com.veyarc.engine.ConditionObservation
import com.veyarc.engine.ConditionObserver
import com.veyarc.engine.DisplayCoordinate
import com.veyarc.engine.DisplayTimedPoint
import com.veyarc.model.Condition
import com.veyarc.model.ForegroundPackageCondition
import com.veyarc.model.ImageAbsentCondition
import com.veyarc.model.ImagePresentCondition
import com.veyarc.model.OrientationCondition
import com.veyarc.model.PackageName
import com.veyarc.model.PixelColorCondition
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Connects the deterministic workflow engine to Android accessibility and window APIs. */
class AndroidAutomationDriver private constructor(
    private val delegate: AutomationDriver,
) : AutomationDriver by delegate {
    constructor(
        context: Context,
        visualConditionObserver: ConditionObserver? = null,
    ) : this(
        delegate = BlockingAndroidAutomationDriver(
            gesturePort = AccessibilityGesturePort,
            foregroundPackage = ForegroundPackageTracker::current,
            viewport = AndroidViewportSource(context.applicationContext)::currentViewport,
            launch = AndroidAppLauncher(context.applicationContext)::launchApp,
            visualConditionObserver = visualConditionObserver,
            unsafeDeviceState = { AndroidDeviceSafety.current(context.applicationContext) },
            isMainThread = {
                val mainLooper = Looper.getMainLooper()
                mainLooper != null && Looper.myLooper() == mainLooper
            },
        ),
    )

    internal constructor(
        gesturePort: GestureDispatchPort,
        foregroundPackage: () -> PackageName?,
        viewport: () -> ActiveViewport?,
        launch: (PackageName) -> Unit,
        isMainThread: () -> Boolean,
        visualConditionObserver: ConditionObserver? = null,
        unsafeDeviceState: () -> AndroidUnsafeDeviceState? = { null },
    ) : this(
        delegate = BlockingAndroidAutomationDriver(
            gesturePort = gesturePort,
            foregroundPackage = foregroundPackage,
            viewport = viewport,
            launch = launch,
            visualConditionObserver = visualConditionObserver,
            unsafeDeviceState = unsafeDeviceState,
            isMainThread = isMainThread,
        ),
    )
}

private class BlockingAndroidAutomationDriver(
    private val gesturePort: GestureDispatchPort,
    private val foregroundPackage: () -> PackageName?,
    private val viewport: () -> ActiveViewport?,
    private val launch: (PackageName) -> Unit,
    private val visualConditionObserver: ConditionObserver?,
    private val unsafeDeviceState: () -> AndroidUnsafeDeviceState?,
    private val isMainThread: () -> Boolean,
) : AutomationDriver {
    override fun tap(point: DisplayCoordinate) {
        requireBackgroundThread()
        requireSafeDeviceState()
        val expectedViewport = requireGestureViewport()
        val expectedPackage = requireGesturePackage()
        val screenPoint = point.toScreenPoint()
        awaitGesture(DEFAULT_TAP_WAIT_MILLIS, expectedViewport, expectedPackage) { callback ->
            gesturePort.dispatchTap(screenPoint, callback)
        }
    }

    override fun longPress(point: DisplayCoordinate, durationMs: Long) {
        requireBackgroundThread()
        requireSafeDeviceState()
        val expectedViewport = requireGestureViewport()
        val expectedPackage = requireGesturePackage()
        val screenPoint = point.toScreenPoint()
        awaitGesture(durationMs, expectedViewport, expectedPackage) { callback ->
            gesturePort.dispatchLongPress(screenPoint, durationMs, callback)
        }
    }

    override fun swipePath(points: List<DisplayTimedPoint>, durationMs: Long) {
        requireBackgroundThread()
        requireSafeDeviceState()
        val expectedViewport = requireGestureViewport()
        val expectedPackage = requireGesturePackage()
        val screenPoints = points.map { it.point.toScreenPoint() }
        awaitGesture(durationMs, expectedViewport, expectedPackage) { callback ->
            gesturePort.dispatchPath(screenPoints, durationMs, callback)
        }
    }

    override fun launchApp(packageName: PackageName) {
        requireSafeDeviceState()
        launch.invoke(packageName)
    }

    override fun currentForegroundPackage(): PackageName? {
        requireSafeDeviceState()
        return foregroundPackage.invoke()
    }

    override fun currentViewport(): ActiveViewport? {
        requireSafeDeviceState()
        return viewport.invoke()
    }

    override fun observe(condition: Condition): ConditionObservation {
        requireSafeDeviceState()
        val observation = when (condition) {
            is OrientationCondition -> ConditionObservation(
                currentViewport()?.orientation == condition.orientation,
            )

            is ForegroundPackageCondition -> ConditionObservation(
                currentForegroundPackage() == condition.packageName,
            )

            is PixelColorCondition,
            is ImagePresentCondition,
            is ImageAbsentCondition,
            -> visualConditionObserver?.observe(condition)
                ?: throw AndroidAutomationException(
                    "Visual condition capture is not configured for this workflow run",
                )

            else -> throw AndroidAutomationException(
                "Condition ${condition::class.simpleName} is evaluated by the workflow engine",
            )
        }
        requireSafeDeviceState()
        return observation
    }

    private fun requireBackgroundThread() {
        check(!isMainThread.invoke()) {
            "Workflow gestures must run off the Android main thread"
        }
    }

    private fun awaitGesture(
        requestedDurationMs: Long,
        expectedViewport: ActiveViewport,
        expectedPackage: PackageName,
        dispatch: ((GestureDispatchResult) -> Unit) -> Unit,
    ) {
        val result = AtomicReference<GestureDispatchResult?>(null)
        val completed = CountDownLatch(1)
        dispatch { dispatchResult ->
            result.set(dispatchResult)
            completed.countDown()
        }

        val waitMillis = gestureWaitMillis(requestedDurationMs)
        var remainingMillis = waitMillis
        while (remainingMillis > 0L && !completed.await(
                minOf(GESTURE_SAFETY_POLL_MILLIS, remainingMillis),
                TimeUnit.MILLISECONDS,
            )
        ) {
            remainingMillis -= minOf(GESTURE_SAFETY_POLL_MILLIS, remainingMillis)
            try {
                requireSafeDeviceState()
                requireGestureViewport(expectedViewport)
                requireGesturePackage(expectedPackage)
            } catch (error: AndroidAutomationException) {
                gesturePort.cancelActiveGesture()
                throw error
            }
        }
        if (result.get() == null) {
            gesturePort.cancelActiveGesture()
            throw AndroidAutomationException("Android gesture dispatch timed out")
        }
        requireSafeDeviceState()
        requireGestureViewport(expectedViewport)
        requireGesturePackage(expectedPackage)

        when (val dispatchResult = result.get()) {
            GestureDispatchResult.Completed -> Unit

            GestureDispatchResult.Cancelled -> throw AndroidAutomationException("Android cancelled the gesture")

            GestureDispatchResult.ServiceUnavailable -> throw AndroidAutomationException(
                "Accessibility service is not connected",
            )

            GestureDispatchResult.AlreadyInProgress -> throw AndroidAutomationException(
                "Another Android gesture is already in progress",
            )

            GestureDispatchResult.RejectedBySystem -> throw AndroidAutomationException(
                "Android rejected the gesture",
            )

            is GestureDispatchResult.InvalidRequest -> throw AndroidAutomationException(
                dispatchResult.reason,
            )

            null -> throw AndroidAutomationException("Android returned no gesture result")
        }
    }

    private fun gestureWaitMillis(requestedDurationMs: Long): Long {
        require(requestedDurationMs >= 0L) { "Gesture duration must be nonnegative" }
        return (requestedDurationMs + GESTURE_CALLBACK_GRACE_MILLIS)
            .coerceAtMost(MAX_GESTURE_WAIT_MILLIS)
            .coerceAtLeast(MIN_GESTURE_WAIT_MILLIS)
    }

    private fun requireSafeDeviceState() {
        unsafeDeviceState.invoke()?.let { unsafe ->
            throw AndroidAutomationException("Workflow stopped because ${unsafe.reason}.")
        }
    }

    private fun requireGestureViewport(expected: ActiveViewport? = null): ActiveViewport {
        val current = viewport.invoke()
            ?: throw AndroidAutomationException("Workflow stopped because the active viewport is unavailable.")
        if (expected != null && current != expected) {
            throw AndroidAutomationException("Workflow stopped because the display changed during a gesture.")
        }
        return current
    }

    private fun requireGesturePackage(expected: PackageName? = null): PackageName {
        val current = foregroundPackage.invoke()
            ?: throw AndroidAutomationException("Workflow stopped because the foreground app is unavailable.")
        if (expected != null && current != expected) {
            throw AndroidAutomationException("Workflow stopped because the foreground app changed during a gesture.")
        }
        return current
    }

    private companion object {
        const val DEFAULT_TAP_WAIT_MILLIS = 10_000L
        const val GESTURE_CALLBACK_GRACE_MILLIS = 5_000L
        const val MAX_GESTURE_WAIT_MILLIS = 65_000L
        const val MIN_GESTURE_WAIT_MILLIS = 1_000L
        const val GESTURE_SAFETY_POLL_MILLIS = 100L
    }
}

/** Platform failure surfaced to the engine as a driver error. */
class AndroidAutomationException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

internal interface GestureDispatchPort {
    fun dispatchTap(point: ScreenPoint, onResult: (GestureDispatchResult) -> Unit)

    fun dispatchLongPress(
        point: ScreenPoint,
        durationMillis: Long,
        onResult: (GestureDispatchResult) -> Unit,
    )

    fun dispatchPath(
        points: List<ScreenPoint>,
        durationMillis: Long,
        onResult: (GestureDispatchResult) -> Unit,
    )

    fun cancelActiveGesture() = Unit
}

private object AccessibilityGesturePort : GestureDispatchPort {
    override fun dispatchTap(point: ScreenPoint, onResult: (GestureDispatchResult) -> Unit) = AccessibilityGestureDispatcher.dispatchTap(point, onResult)

    override fun dispatchLongPress(
        point: ScreenPoint,
        durationMillis: Long,
        onResult: (GestureDispatchResult) -> Unit,
    ) = AccessibilityGestureDispatcher.dispatchLongPress(point, durationMillis, onResult)

    override fun dispatchPath(
        points: List<ScreenPoint>,
        durationMillis: Long,
        onResult: (GestureDispatchResult) -> Unit,
    ) = AccessibilityGestureDispatcher.dispatchPath(points, durationMillis, onResult)

    override fun cancelActiveGesture() = AccessibilityGestureDispatcher.cancelActiveGesture()
}

private fun DisplayCoordinate.toScreenPoint(): ScreenPoint {
    val screenPoint = ScreenPoint(x.toFloat(), y.toFloat())
    require(screenPoint.x.isFinite() && screenPoint.y.isFinite()) {
        "Display coordinate cannot be represented as a screen coordinate"
    }
    return screenPoint
}
