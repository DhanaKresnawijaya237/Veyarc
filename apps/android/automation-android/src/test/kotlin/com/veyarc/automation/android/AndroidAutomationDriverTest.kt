package com.veyarc.automation.android

import com.veyarc.engine.ActiveViewport
import com.veyarc.engine.DisplayCoordinate
import com.veyarc.engine.DisplayTimedPoint
import com.veyarc.model.DisplayOrientation
import com.veyarc.model.ForegroundPackageCondition
import com.veyarc.model.OrientationCondition
import com.veyarc.model.PackageName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAutomationDriverTest {
    private val targetPackage = PackageName("com.example.target")
    private val viewport = ActiveViewport(
        widthPx = 1080,
        heightPx = 2392,
        orientation = DisplayOrientation.PORTRAIT,
    )

    @Test
    fun mapsAndDispatchesTapLongPressAndSwipeThroughTheAndroidPort() {
        val gestures = RecordingGesturePort()
        val driver = AndroidAutomationDriver(
            gesturePort = gestures,
            foregroundPackage = { targetPackage },
            viewport = { viewport },
            launch = { },
            isMainThread = { false },
        )

        driver.tap(DisplayCoordinate(12.5, 20.25))
        driver.longPress(DisplayCoordinate(50.0, 60.0), durationMs = 750L)
        driver.swipePath(
            points = listOf(
                DisplayTimedPoint(DisplayCoordinate(1.0, 2.0), offsetMs = 0L),
                DisplayTimedPoint(DisplayCoordinate(3.0, 4.0), offsetMs = 500L),
            ),
            durationMs = 500L,
        )

        assertEquals(listOf(ScreenPoint(12.5f, 20.25f)), gestures.taps)
        assertEquals(listOf(ScreenPoint(50f, 60f) to 750L), gestures.longPresses)
        assertEquals(
            listOf(
                listOf(ScreenPoint(1f, 2f), ScreenPoint(3f, 4f)) to 500L,
            ),
            gestures.paths,
        )
    }

    @Test
    fun exposesForegroundViewportAndSupportedObservations() {
        val driver = AndroidAutomationDriver(
            gesturePort = RecordingGesturePort(),
            foregroundPackage = { targetPackage },
            viewport = { viewport },
            launch = { },
            isMainThread = { false },
        )

        assertEquals(targetPackage, driver.currentForegroundPackage())
        assertEquals(viewport, driver.currentViewport())
        assertTrue(driver.observe(OrientationCondition(DisplayOrientation.PORTRAIT)))
        assertTrue(driver.observe(ForegroundPackageCondition(targetPackage)))
    }

    @Test
    fun rejectsGestureDispatchFromTheMainThread() {
        val driver = AndroidAutomationDriver(
            gesturePort = RecordingGesturePort(),
            foregroundPackage = { targetPackage },
            viewport = { viewport },
            launch = { },
            isMainThread = { true },
        )

        var failed = false
        try {
            driver.tap(DisplayCoordinate(1.0, 1.0))
        } catch (error: IllegalStateException) {
            failed = error.message?.contains("off the Android main thread") == true
        }
        assertTrue(failed)
    }

    @Test
    fun translatesCancelledGestureToAnAndroidFailure() {
        val gestures = RecordingGesturePort(result = GestureDispatchResult.Cancelled)
        val driver = AndroidAutomationDriver(
            gesturePort = gestures,
            foregroundPackage = { targetPackage },
            viewport = { viewport },
            launch = { },
            isMainThread = { false },
        )

        var failed = false
        try {
            driver.tap(DisplayCoordinate(1.0, 1.0))
        } catch (error: AndroidAutomationException) {
            failed = error.message?.contains("cancelled") == true
        }
        assertTrue(failed)
    }

    private class RecordingGesturePort(
        private val result: GestureDispatchResult = GestureDispatchResult.Completed,
    ) : GestureDispatchPort {
        val taps = mutableListOf<ScreenPoint>()
        val longPresses = mutableListOf<Pair<ScreenPoint, Long>>()
        val paths = mutableListOf<Pair<List<ScreenPoint>, Long>>()

        override fun dispatchTap(point: ScreenPoint, onResult: (GestureDispatchResult) -> Unit) {
            taps += point
            onResult(result)
        }

        override fun dispatchLongPress(
            point: ScreenPoint,
            durationMillis: Long,
            onResult: (GestureDispatchResult) -> Unit,
        ) {
            longPresses += point to durationMillis
            onResult(result)
        }

        override fun dispatchPath(
            points: List<ScreenPoint>,
            durationMillis: Long,
            onResult: (GestureDispatchResult) -> Unit,
        ) {
            paths += points to durationMillis
            onResult(result)
        }
    }
}
