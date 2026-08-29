package com.veyarc.engine

import com.veyarc.model.ComparisonOperator
import com.veyarc.model.DisplayOrientation
import com.veyarc.model.DurationMillis
import com.veyarc.model.IfElse
import com.veyarc.model.LaunchApp
import com.veyarc.model.LiteralOperand
import com.veyarc.model.NormalizedCoordinate
import com.veyarc.model.NumberValue
import com.veyarc.model.PackageName
import com.veyarc.model.PixelColorCondition
import com.veyarc.model.Repeat
import com.veyarc.model.RunLimits
import com.veyarc.model.SetVariable
import com.veyarc.model.StepOutcome
import com.veyarc.model.Stop
import com.veyarc.model.StopReason
import com.veyarc.model.TapPoint
import com.veyarc.model.VariableComparisonCondition
import com.veyarc.model.VariableDeclaration
import com.veyarc.model.VariableName
import com.veyarc.model.VariableOperand
import com.veyarc.model.ViewportSpec
import com.veyarc.model.Wait
import com.veyarc.model.WaitUntil
import com.veyarc.model.WhileStep
import com.veyarc.model.WorkflowDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque

class WorkflowRunnerTest {
    @Test
    fun executesActionsAndPublishesDeterministicEvents() {
        val plan = plan(
            variables = mapOf("count" to VariableDeclaration(com.veyarc.model.VariableType.NUMBER, NumberValue(0.0))),
            steps = listOf(
                TapPoint(STEP_1, NormalizedCoordinate(0.1, 0.2)),
                Wait(STEP_2, DurationMillis(100)),
                SetVariable(STEP_3, VariableName("count"), NumberValue(2.0)),
            ),
        )
        val driver = FakeDriver()
        val clock = FakeClock()

        val result = WorkflowRunner().run(plan, driver, clock)

        assertTrue(result.isSuccess)
        assertEquals(RunOutcome.COMPLETED, result.outcome)
        assertEquals(listOf("tap:10.0,40.0"), driver.actions)
        assertEquals(100L, clock.nowMs())
        assertEquals(NumberValue(2.0), result.variables["count"])
        assertTrue(result.events.first() is RunEvent.Started)
        assertTrue(result.events.last() is RunEvent.Finished)
        assertEquals(StepOutcome.COMPLETED, result.stepOutcomes[STEP_1])
        assertEquals(StepOutcome.COMPLETED, result.stepOutcomes[STEP_2])
        assertEquals(StepOutcome.COMPLETED, result.stepOutcomes[STEP_3])
    }

    @Test
    fun executesBranchesLoopsAndVariableComparisons() {
        val count = VariableName("count")
        val condition = VariableComparisonCondition(
            left = VariableOperand(count),
            operator = ComparisonOperator.EQUALS,
            right = LiteralOperand(NumberValue(1.0)),
        )
        val plan = plan(
            variables = mapOf("count" to VariableDeclaration(com.veyarc.model.VariableType.NUMBER, NumberValue(0.0))),
            steps = listOf(
                SetVariable(STEP_1, count, NumberValue(1.0)),
                IfElse(
                    id = STEP_2,
                    condition = condition,
                    thenSteps = listOf(TapPoint(STEP_3, NormalizedCoordinate(0.1, 0.1))),
                    elseSteps = listOf(TapPoint(STEP_4, NormalizedCoordinate(0.9, 0.9))),
                ),
                Repeat(
                    id = STEP_5,
                    count = 2,
                    bodySteps = listOf(Wait(STEP_6, DurationMillis(10))),
                ),
                WhileStep(
                    id = STEP_7,
                    condition = condition,
                    maxIterations = 2,
                    bodySteps = listOf(SetVariable(STEP_8, count, NumberValue(0.0))),
                ),
            ),
        )
        val driver = FakeDriver()
        val clock = FakeClock()

        val result = WorkflowRunner().run(plan, driver, clock)

        assertTrue(result.isSuccess)
        assertEquals(listOf("tap:10.0,20.0"), driver.actions)
        assertEquals(20L, clock.nowMs())
        assertEquals(NumberValue(0.0), result.variables["count"])
        assertEquals(StepOutcome.NOT_EXECUTED, result.stepOutcomes[STEP_4])
    }

    @Test
    fun evaluatesVisualConditionsInsideIfAndWhileSteps() {
        val condition = PixelColorCondition(
            point = NormalizedCoordinate(0.5, 0.5),
            expectedArgb = 0xFFFFFFFF,
            tolerance = 0,
        )
        val plan = plan(
            steps = listOf(
                IfElse(
                    id = STEP_1,
                    condition = condition,
                    thenSteps = listOf(TapPoint(STEP_2, NormalizedCoordinate(0.1, 0.1))),
                    elseSteps = listOf(TapPoint(STEP_3, NormalizedCoordinate(0.9, 0.9))),
                ),
                WhileStep(
                    id = STEP_4,
                    condition = condition,
                    maxIterations = 2,
                    bodySteps = listOf(TapPoint(STEP_5, NormalizedCoordinate(0.2, 0.2))),
                ),
            ),
        )
        val driver = FakeDriver(observations = listOf(true, true, false))

        val result = WorkflowRunner().run(plan, driver, FakeClock())

        assertTrue(result.isSuccess)
        assertEquals(
            listOf("tap:10.0,20.0", "tap:20.0,40.0"),
            driver.actions,
        )
        assertEquals(3, result.events.filterIsInstance<RunEvent.ConditionEvaluated>().size)
    }

    @Test
    fun waitsUntilConditionBecomesTrueUsingInjectedClock() {
        val plan = plan(
            steps = listOf(
                WaitUntil(
                    id = STEP_1,
                    condition = PixelColorCondition(
                        point = NormalizedCoordinate(0.5, 0.5),
                        expectedArgb = 0xFF00FF00,
                        tolerance = 5,
                    ),
                    timeoutMs = DurationMillis(500),
                    pollIntervalMs = DurationMillis(100),
                ),
            ),
        )
        val driver = FakeDriver(observations = listOf(false, false, true))
        val clock = FakeClock()

        val result = WorkflowRunner().run(plan, driver, clock)

        assertTrue(result.isSuccess)
        assertEquals(200L, clock.nowMs())
        assertEquals(3, result.events.filterIsInstance<RunEvent.ConditionEvaluated>().size)
    }

    @Test
    fun reportsConditionTimeoutWithoutExecutingLaterSteps() {
        val plan = plan(
            steps = listOf(
                WaitUntil(
                    id = STEP_1,
                    condition = PixelColorCondition(
                        point = NormalizedCoordinate(0.5, 0.5),
                        expectedArgb = 0xFFFFFFFF,
                        tolerance = 0,
                    ),
                    timeoutMs = DurationMillis(100),
                    pollIntervalMs = DurationMillis(50),
                ),
                TapPoint(STEP_2, NormalizedCoordinate(0.1, 0.1)),
            ),
        )
        val driver = FakeDriver(observations = listOf(false, false, false))
        val clock = FakeClock()

        val result = WorkflowRunner().run(plan, driver, clock)

        assertEquals(RunOutcome.TIMED_OUT, result.outcome)
        assertEquals(RunFailureCode.CONDITION_TIMEOUT, result.failure?.code)
        assertEquals(StepOutcome.TIMED_OUT, result.stepOutcomes[STEP_1])
        assertEquals(StepOutcome.NOT_EXECUTED, result.stepOutcomes[STEP_2])
        assertEquals(100L, clock.nowMs())
    }

    @Test
    fun cancellationStopsBeforeTheNextStep() {
        val cancellation = MutableCancellationSignal()
        val plan = plan(
            steps = listOf(
                Wait(STEP_1, DurationMillis(100)),
                TapPoint(STEP_2, NormalizedCoordinate(0.1, 0.1)),
            ),
        )
        val clock = FakeClock(onSleep = { cancellation.requested = true })
        val driver = FakeDriver()

        val result = WorkflowRunner().run(plan, driver, clock, cancellation)

        assertEquals(RunOutcome.CANCELLED, result.outcome)
        assertEquals(RunFailureCode.CANCELLATION_REQUESTED, result.failure?.code)
        assertEquals(StepOutcome.COMPLETED, result.stepOutcomes[STEP_1])
        assertEquals(StepOutcome.NOT_EXECUTED, result.stepOutcomes[STEP_2])
        assertTrue(driver.actions.isEmpty())
    }

    @Test
    fun pausesAtASafeBoundaryAndPublishesControlEvents() {
        val control = MutableExecutionControl()
        var sleepCount = 0
        val clock = FakeClock(onSleep = {
            sleepCount += 1
            when (sleepCount) {
                1 -> control.requestPause()
                2 -> control.resume()
            }
        })
        val events = mutableListOf<RunEvent>()
        val driver = FakeDriver()
        val plan = plan(
            steps = listOf(
                Wait(STEP_1, DurationMillis(100)),
                TapPoint(STEP_2, NormalizedCoordinate(0.1, 0.1)),
            ),
        )

        val result = WorkflowRunner().run(
            plan = plan,
            driver = driver,
            clock = clock,
            pause = control,
            eventSink = events::add,
        )

        assertTrue(result.isSuccess)
        assertTrue(events.any { it is RunEvent.Paused })
        assertTrue(events.any { it is RunEvent.Resumed })
        assertEquals(listOf("tap:10.0,20.0"), driver.actions)
    }

    @Test
    fun rejectsUnexpectedForegroundPackageBeforeDispatchingInput() {
        val plan = plan(steps = listOf(TapPoint(STEP_1, NormalizedCoordinate(0.1, 0.1))))
        val driver = FakeDriver(foreground = OTHER_PACKAGE)

        val result = WorkflowRunner().run(plan, driver, FakeClock())

        assertEquals(RunOutcome.FAILED, result.outcome)
        assertEquals(RunFailureCode.UNEXPECTED_FOREGROUND_PACKAGE, result.failure?.code)
        assertTrue(driver.actions.isEmpty())
        assertEquals(StepOutcome.NOT_EXECUTED, result.stepOutcomes[STEP_1])
    }

    @Test
    fun stopsWithTheWorkflowStopReason() {
        val plan = plan(steps = listOf(Stop(STEP_1, StopReason.USER_REQUESTED)))

        val result = WorkflowRunner().run(plan, FakeDriver(), FakeClock())

        assertEquals(RunOutcome.STOPPED, result.outcome)
        assertEquals(StopReason.USER_REQUESTED, result.stopReason)
        assertEquals(StepOutcome.STOPPED, result.stepOutcomes[STEP_1])
    }

    @Test
    fun launchFailsWithASeparateForegroundTimeout() {
        val plan = plan(
            allowedPackages = listOf(APP_PACKAGE, OTHER_PACKAGE),
            steps = listOf(LaunchApp(STEP_1, OTHER_PACKAGE, DurationMillis(100))),
        )
        val driver = FakeDriver(launchUpdatesForeground = false)

        val result = WorkflowRunner().run(plan, driver, FakeClock())

        assertEquals(RunOutcome.TIMED_OUT, result.outcome)
        assertEquals(RunFailureCode.FOREGROUND_TIMEOUT, result.failure?.code)
        assertEquals(StepOutcome.TIMED_OUT, result.stepOutcomes[STEP_1])
    }

    @Test
    fun allowsTheControllerPackageForAnInitialDeclaredLaunchHandoff() {
        val targetPackage = OTHER_PACKAGE
        val plan = plan(
            allowedPackages = listOf(targetPackage),
            steps = listOf(LaunchApp(STEP_1, targetPackage, DurationMillis(100))),
        )
        val driver = FakeDriver(
            foreground = CONTROLLER_PACKAGE,
            launchUpdatesForeground = true,
        )

        val result = WorkflowRunner().run(
            plan = plan,
            driver = driver,
            clock = FakeClock(),
            startupPackage = CONTROLLER_PACKAGE,
        )

        assertTrue(result.isSuccess)
        assertEquals(listOf("launch:${targetPackage.value}"), driver.actions)
    }

    @Test
    fun driverFailureBecomesAStableFailureResult() {
        val plan = plan(steps = listOf(TapPoint(STEP_1, NormalizedCoordinate(0.1, 0.1))))
        val driver = FakeDriver(failOnTap = true)

        val result = WorkflowRunner().run(plan, driver, FakeClock())

        assertEquals(RunOutcome.FAILED, result.outcome)
        assertEquals(RunFailureCode.DRIVER_ERROR, result.failure?.code)
        assertEquals(StepOutcome.FAILED, result.stepOutcomes[STEP_1])
        assertNotNull(result.events.filterIsInstance<RunEvent.Finished>().single().failure)
    }

    @Test
    fun remapsCoordinatesAgainstTheViewportAtEachStepBoundary() {
        val driver = FakeDriver()
        val clock = FakeClock(onSleep = {
            driver.viewport = ActiveViewport(
                widthPx = 201,
                heightPx = 101,
                orientation = DisplayOrientation.LANDSCAPE,
            )
        })
        val plan = plan(
            steps = listOf(
                Wait(STEP_1, DurationMillis(100)),
                TapPoint(STEP_2, NormalizedCoordinate(0.5, 0.5)),
            ),
        )

        val result = WorkflowRunner().run(plan, driver, clock)

        assertTrue(result.isSuccess)
        assertEquals(listOf("tap:100.0,50.0"), driver.actions)
    }

    @Test
    fun failsIfTheViewportChangesDuringAgesture() {
        val driver = FakeDriver(changeViewportAfterTap = true)
        val plan = plan(
            steps = listOf(
                TapPoint(STEP_1, NormalizedCoordinate(0.5, 0.5)),
                TapPoint(STEP_2, NormalizedCoordinate(0.5, 0.5)),
            ),
        )

        val result = WorkflowRunner().run(plan, driver, FakeClock())

        assertEquals(RunOutcome.FAILED, result.outcome)
        assertEquals(RunFailureCode.VIEWPORT_CHANGED_DURING_GESTURE, result.failure?.code)
        assertEquals(StepOutcome.FAILED, result.stepOutcomes[STEP_1])
        assertEquals(StepOutcome.NOT_EXECUTED, result.stepOutcomes[STEP_2])
    }

    @Test
    fun rejectsSplitScreenBeforeDispatchingInput() {
        val driver = FakeDriver()
        driver.viewport = driver.viewport.copy(windowMode = ActiveWindowMode.SPLIT_SCREEN)
        val plan = plan(steps = listOf(TapPoint(STEP_1, NormalizedCoordinate(0.5, 0.5))))

        val result = WorkflowRunner().run(plan, driver, FakeClock())

        assertEquals(RunOutcome.FAILED, result.outcome)
        assertEquals(RunFailureCode.UNSUPPORTED_WINDOW_MODE, result.failure?.code)
        assertTrue(driver.actions.isEmpty())
        assertEquals(StepOutcome.NOT_EXECUTED, result.stepOutcomes[STEP_1])
    }

    private fun plan(
        steps: List<com.veyarc.model.WorkflowStep>,
        variables: Map<String, VariableDeclaration> = emptyMap(),
        allowedPackages: List<PackageName> = listOf(APP_PACKAGE),
    ): ExecutionPlan = WorkflowCompiler.compileOrThrow(
        WorkflowDocument(
            schemaVersion = WorkflowDocument.CURRENT_SCHEMA_VERSION,
            id = DOCUMENT_ID,
            revision = 1,
            name = "Runner test",
            allowedPackages = allowedPackages,
            viewport = ViewportSpec(
                recordedWidthPx = 1080,
                recordedHeightPx = 2392,
                recordedDensityDpi = 420,
                recordedOrientation = DisplayOrientation.PORTRAIT,
            ),
            runLimits = RunLimits(
                maxTotalSteps = 100,
                maxRuntimeMs = 10_000,
                maxLoopIterations = 10,
                maxNestingDepth = 4,
                maxPathPoints = 16,
                maxStepDurationMs = 5_000,
            ),
            variables = variables,
            assets = emptyList(),
            steps = steps,
        ),
    )

    private class FakeClock(
        private val onSleep: ((Long) -> Unit)? = null,
    ) : ExecutionClock {
        private var currentMs = 0L

        override fun nowMs(): Long = currentMs

        override fun sleep(durationMs: Long) {
            require(durationMs >= 0L)
            currentMs += durationMs
            onSleep?.invoke(durationMs)
        }
    }

    private class MutableCancellationSignal : CancellationSignal {
        var requested = false

        override fun isCancellationRequested(): Boolean = requested
    }

    private class FakeDriver(
        var foreground: PackageName = APP_PACKAGE,
        private val observations: List<Boolean> = emptyList(),
        private val launchUpdatesForeground: Boolean = true,
        private val failOnTap: Boolean = false,
        private val changeViewportAfterTap: Boolean = false,
    ) : AutomationDriver {
        val actions = mutableListOf<String>()
        var viewport: ActiveViewport = DEFAULT_VIEWPORT
        private val conditionResults = ArrayDeque(observations)

        override fun tap(point: DisplayCoordinate) {
            if (failOnTap) throw IllegalStateException("fake tap failed")
            actions += "tap:${point.x},${point.y}"
            if (changeViewportAfterTap) {
                viewport = ActiveViewport(
                    widthPx = 201,
                    heightPx = 101,
                    orientation = DisplayOrientation.LANDSCAPE,
                )
            }
        }

        override fun longPress(point: DisplayCoordinate, durationMs: Long) {
            actions += "longPress:${point.x},${point.y}:$durationMs"
        }

        override fun swipePath(points: List<DisplayTimedPoint>, durationMs: Long) {
            actions += "swipe:${points.size}:$durationMs"
        }

        override fun launchApp(packageName: PackageName) {
            actions += "launch:${packageName.value}"
            if (launchUpdatesForeground) foreground = packageName
        }

        override fun currentForegroundPackage(): PackageName = foreground

        override fun currentViewport(): ActiveViewport = viewport

        override fun observe(condition: com.veyarc.model.Condition): Boolean = if (conditionResults.isEmpty()) false else conditionResults.removeFirst()
    }

    private companion object {
        val DEFAULT_VIEWPORT = ActiveViewport(
            widthPx = 101,
            heightPx = 201,
            orientation = DisplayOrientation.PORTRAIT,
        )
        val APP_PACKAGE = PackageName("com.example.app")
        val OTHER_PACKAGE = PackageName("com.example.other")
        val CONTROLLER_PACKAGE = PackageName("com.veyarc.app")
        const val DOCUMENT_ID = "00000000-0000-4000-8000-000000000201"
        const val STEP_1 = "00000000-0000-4000-8000-000000000211"
        const val STEP_2 = "00000000-0000-4000-8000-000000000212"
        const val STEP_3 = "00000000-0000-4000-8000-000000000213"
        const val STEP_4 = "00000000-0000-4000-8000-000000000214"
        const val STEP_5 = "00000000-0000-4000-8000-000000000215"
        const val STEP_6 = "00000000-0000-4000-8000-000000000216"
        const val STEP_7 = "00000000-0000-4000-8000-000000000217"
        const val STEP_8 = "00000000-0000-4000-8000-000000000218"
    }
}
