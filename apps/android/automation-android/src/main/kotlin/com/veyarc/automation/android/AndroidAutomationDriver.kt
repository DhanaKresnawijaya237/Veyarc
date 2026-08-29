package com.veyarc.automation.android

import android.content.Context
import android.os.Looper
import com.veyarc.engine.ActiveViewport
import com.veyarc.engine.AutomationDriver
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
    ) : this(
        delegate = BlockingAndroidAutomationDriver(
            gesturePort = gesturePort,
            foregroundPackage = foregroundPackage,
            viewport = viewport,
            launch = launch,
            visualConditionObserver = visualConditionObserver,
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
    private val isMainThread: () -> Boolean,
) : AutomationDriver {
    override fun tap(point: DisplayCoordinate) {
        requireBackgroundThread()
        val screenPoint = point.toScreenPoint()
        awaitGesture(DEFAULT_TAP_WAIT_MILLIS) { callback ->
            gesturePort.dispatchTap(screenPoint, callback)
        }
    }

    override fun longPress(point: DisplayCoordinate, durationMs: Long) {
        requireBackgroundThread()
        val screenPoint = point.toScreenPoint()
        awaitGesture(durationMs) { callback ->
            gesturePort.dispatchLongPress(screenPoint, durationMs, callback)
        }
    }

    override fun swipePath(points: List<DisplayTimedPoint>, durationMs: Long) {
        requireBackgroundThread()
        val screenPoints = points.map { it.point.toScreenPoint() }
        awaitGesture(durationMs) { callback ->
            gesturePort.dispatchPath(screenPoints, durationMs, callback)
        }
    }

    override fun launchApp(packageName: PackageName) = launch.invoke(packageName)

    override fun currentForegroundPackage(): PackageName? = foregroundPackage.invoke()

    override fun currentViewport(): ActiveViewport? = viewport.invoke()

    override fun observe(condition: Condition): Boolean = when (condition) {
        is OrientationCondition -> currentViewport()?.orientation == condition.orientation

        is ForegroundPackageCondition -> currentForegroundPackage() == condition.packageName

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

    private fun requireBackgroundThread() {
        check(!isMainThread.invoke()) {
            "Workflow gestures must run off the Android main thread"
        }
    }

    private fun awaitGesture(
        requestedDurationMs: Long,
        dispatch: ((GestureDispatchResult) -> Unit) -> Unit,
    ) {
        val result = AtomicReference<GestureDispatchResult?>(null)
        val completed = CountDownLatch(1)
        dispatch { dispatchResult ->
            result.set(dispatchResult)
            completed.countDown()
        }

        val waitMillis = gestureWaitMillis(requestedDurationMs)
        if (!completed.await(waitMillis, TimeUnit.MILLISECONDS)) {
            throw AndroidAutomationException("Android gesture dispatch timed out")
        }

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

    private companion object {
        const val DEFAULT_TAP_WAIT_MILLIS = 10_000L
        const val GESTURE_CALLBACK_GRACE_MILLIS = 5_000L
        const val MAX_GESTURE_WAIT_MILLIS = 65_000L
        const val MIN_GESTURE_WAIT_MILLIS = 1_000L
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
}

private fun DisplayCoordinate.toScreenPoint(): ScreenPoint {
    val screenPoint = ScreenPoint(x.toFloat(), y.toFloat())
    require(screenPoint.x.isFinite() && screenPoint.y.isFinite()) {
        "Display coordinate cannot be represented as a screen coordinate"
    }
    return screenPoint
}
