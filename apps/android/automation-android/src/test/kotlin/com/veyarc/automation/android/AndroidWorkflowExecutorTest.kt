package com.veyarc.automation.android

import com.veyarc.engine.ActiveViewport
import com.veyarc.engine.AutomationDriver
import com.veyarc.engine.ConditionObserver
import com.veyarc.engine.ExecutionClock
import com.veyarc.engine.WorkflowRunner
import com.veyarc.model.DisplayOrientation
import com.veyarc.model.DurationMillis
import com.veyarc.model.NormalizedCoordinate
import com.veyarc.model.PackageName
import com.veyarc.model.PixelColorCondition
import com.veyarc.model.RunLimits
import com.veyarc.model.ViewportSpec
import com.veyarc.model.WaitUntil
import com.veyarc.model.WorkflowDocument
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidWorkflowExecutorTest {
    @Test
    fun suppliesAVisualObserverWhenTheCompiledWorkflowUsesVisualConditions() {
        var observer: ConditionObserver? = null
        val executor = AndroidWorkflowExecutor(
            capabilities = {
                AndroidAutomationCapabilities.snapshot(
                    accessibilityEnabled = true,
                    serviceConnected = true,
                    mediaProjectionAvailable = true,
                )
            },
            driverProvider = { visualObserver ->
                observer = visualObserver
                VisualConditionDriver()
            },
            clock = ImmediateClock,
            runner = WorkflowRunner(),
        )

        val result = executor.execute(workflow()).run

        assertTrue(result?.isSuccess == true)
        assertTrue(observer is VisualConditionEvaluator)
    }

    private fun workflow(): WorkflowDocument = WorkflowDocument(
        schemaVersion = WorkflowDocument.CURRENT_SCHEMA_VERSION,
        id = "00000000-0000-4000-8000-000000000301",
        revision = 1,
        name = "Visual executor test",
        allowedPackages = listOf(PackageName("com.example.app")),
        viewport = ViewportSpec(
            recordedWidthPx = 1080,
            recordedHeightPx = 2392,
            recordedDensityDpi = 420,
            recordedOrientation = DisplayOrientation.PORTRAIT,
        ),
        runLimits = RunLimits(
            maxTotalSteps = 10,
            maxRuntimeMs = 1_000,
            maxLoopIterations = 2,
            maxNestingDepth = 2,
            maxPathPoints = 4,
            maxStepDurationMs = 500,
        ),
        variables = emptyMap(),
        assets = emptyList(),
        steps = listOf(
            WaitUntil(
                id = "00000000-0000-4000-8000-000000000302",
                condition = PixelColorCondition(
                    point = NormalizedCoordinate(0.5, 0.5),
                    expectedArgb = 0xFFFFFFFF,
                    tolerance = 0,
                ),
                timeoutMs = DurationMillis(100),
                pollIntervalMs = DurationMillis(10),
            ),
        ),
    )

    private class VisualConditionDriver : AutomationDriver {
        override fun tap(point: com.veyarc.engine.DisplayCoordinate) = Unit

        override fun longPress(point: com.veyarc.engine.DisplayCoordinate, durationMs: Long) = Unit

        override fun swipePath(
            points: List<com.veyarc.engine.DisplayTimedPoint>,
            durationMs: Long,
        ) = Unit

        override fun launchApp(packageName: PackageName) = Unit

        override fun currentForegroundPackage(): PackageName = PackageName("com.example.app")

        override fun currentViewport(): ActiveViewport = ActiveViewport(
            widthPx = 1080,
            heightPx = 2392,
            orientation = DisplayOrientation.PORTRAIT,
        )

        override fun observe(condition: com.veyarc.model.Condition): Boolean = true
    }

    private object ImmediateClock : ExecutionClock {
        override fun nowMs(): Long = 0L

        override fun sleep(durationMs: Long) = Unit
    }
}
