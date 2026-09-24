package com.veyarc.engine

import com.veyarc.model.AndCondition
import com.veyarc.model.ComparisonOperator
import com.veyarc.model.Condition
import com.veyarc.model.ForegroundPackageCondition
import com.veyarc.model.ImageAbsentCondition
import com.veyarc.model.ImagePresentCondition
import com.veyarc.model.LiteralOperand
import com.veyarc.model.NotCondition
import com.veyarc.model.NumberValue
import com.veyarc.model.Operand
import com.veyarc.model.OrientationCondition
import com.veyarc.model.PackageName
import com.veyarc.model.PixelColorCondition
import com.veyarc.model.StepOutcome
import com.veyarc.model.StepResultCondition
import com.veyarc.model.StopReason
import com.veyarc.model.VariableComparisonCondition
import com.veyarc.model.VariableOperand
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/** Executes one compiled plan at a time using injected, platform-neutral dependencies. */
class WorkflowRunner {
    private val active = AtomicBoolean(false)

    fun run(
        plan: ExecutionPlan,
        driver: AutomationDriver,
        clock: ExecutionClock,
        cancellation: CancellationSignal = CancellationSignals.NONE,
        pause: PauseSignal = PauseSignals.NONE,
        eventSink: (RunEvent) -> Unit = {},
        startupPackage: PackageName? = null,
    ): WorkflowRunResult {
        if (!active.compareAndSet(false, true)) {
            val failure = RunFailure(
                code = RunFailureCode.ALREADY_RUNNING,
                stepId = null,
                path = "/",
                message = "Another workflow is already running",
            )
            val atMs = clock.nowMs()
            return WorkflowRunResult(
                outcome = RunOutcome.FAILED,
                events = listOf(RunEvent.Finished(atMs, RunOutcome.FAILED, failure, null)),
                stepOutcomes = emptyMap(),
                variables = emptyMap(),
                failure = failure,
            )
        }

        val state = RunState(
            plan = plan,
            driver = driver,
            clock = clock,
            cancellation = cancellation,
            pause = pause,
            eventSink = eventSink,
            startupPackage = startupPackage,
            startedAtMs = clock.nowMs(),
        )
        state.emit(
            RunEvent.Started(
                atMs = state.nowMs(),
                workflowId = plan.workflowId,
                workflowRevision = plan.workflowRevision,
            ),
        )

        return try {
            ensureBoundary(state)
            val execution = executeSteps(plan.steps, state)
            if (execution.halt == null) {
                finish(state, RunOutcome.COMPLETED, null, null)
            } else {
                finish(state, execution.halt.outcome, execution.halt.failure, execution.halt.stopReason)
            }
        } catch (abort: RunAbort) {
            finish(state, abort.outcome, abort.failure, abort.stopReason)
        } catch (error: Exception) {
            val failure = RunFailure(
                code = RunFailureCode.DRIVER_ERROR,
                stepId = state.currentStepId,
                path = state.currentPath,
                message = error.message ?: "Execution dependency failed",
            )
            finish(state, RunOutcome.FAILED, failure, null)
        } finally {
            active.set(false)
        }
    }

    private fun executeSteps(steps: List<ExecutionStep>, state: RunState): StepExecution {
        var index = 0
        while (index in steps.indices) {
            val step = steps[index]
            state.currentStepId = step.id
            state.currentPath = "/steps/${step.id}"
            ensureBoundary(state)
            if (step is ExecutionStep.StageBoundary) {
                index++
                continue
            }
            val execution = executeStep(step, state)
            if (execution.halt != null) return execution

            if (step is ExecutionStep.Jump) {
                val visits = state.jumpVisits.getOrDefault(step.id, 0) + 1
                state.jumpVisits[step.id] = visits
                if (visits < step.maxVisits) {
                    val targetIndex = steps.indexOfFirst { it.id == step.targetStepId }
                    if (targetIndex < 0 || targetIndex == index) {
                        throw IllegalStateException("Jump target is not available in this stage")
                    }
                    index = targetIndex
                    continue
                }
            }
            index++
        }
        return StepExecution(StepOutcome.COMPLETED)
    }

    private fun executeStep(step: ExecutionStep, state: RunState): StepExecution {
        state.currentStepId = step.id
        state.currentPath = "/steps/${step.id}"
        ensureBoundary(state)
        state.emit(RunEvent.StepStarted(state.nowMs(), step.id))
        var finished = false
        val startedAtMs = state.nowMs()
        return try {
            val execution = executeStepBody(step, state)
            if (isAtomicStep(step)) checkStepDuration(step.id, startedAtMs, state)
            state.stepOutcomes[step.id] = execution.outcome
            state.emit(RunEvent.StepFinished(state.nowMs(), step.id, execution.outcome))
            finished = true
            if (execution.halt == null) ensureBoundary(state)
            execution
        } catch (abort: RunAbort) {
            if (!finished) {
                state.stepOutcomes[step.id] = abort.stepOutcome
                state.emit(RunEvent.StepFinished(state.nowMs(), step.id, abort.stepOutcome))
            }
            throw abort
        } catch (error: Exception) {
            val failure = RunFailure(
                code = RunFailureCode.DRIVER_ERROR,
                stepId = step.id,
                path = state.currentPath,
                message = error.message ?: "Execution dependency failed",
            )
            state.stepOutcomes[step.id] = StepOutcome.FAILED
            state.emit(RunEvent.StepFinished(state.nowMs(), step.id, StepOutcome.FAILED))
            throw RunAbort(RunOutcome.FAILED, StepOutcome.FAILED, failure, null)
        }
    }

    private fun executeStepBody(step: ExecutionStep, state: RunState): StepExecution = when (step) {
        is ExecutionStep.TapPoint -> {
            dispatchAtViewport(
                state = state,
                map = { viewport -> ViewportMapper.map(step.point, viewport) },
                dispatch = { point -> state.driver.tap(point) },
            )
            StepExecution(StepOutcome.COMPLETED)
        }

        is ExecutionStep.TapRegion -> {
            dispatchAtViewport(
                state = state,
                map = { viewport -> ViewportMapper.map(step.resolvedPoint, viewport) },
                dispatch = { point -> state.driver.tap(point) },
            )
            StepExecution(StepOutcome.COMPLETED)
        }

        is ExecutionStep.LongPress -> {
            dispatchAtViewport(
                state = state,
                map = { viewport -> ViewportMapper.map(step.point, viewport) },
                dispatch = { point -> state.driver.longPress(point, step.durationMs.value) },
            )
            StepExecution(StepOutcome.COMPLETED)
        }

        is ExecutionStep.SwipePath -> {
            dispatchAtViewport(
                state = state,
                map = { viewport -> ViewportMapper.map(step.points, viewport) },
                dispatch = { points -> state.driver.swipePath(points, step.durationMs.value) },
            )
            StepExecution(StepOutcome.COMPLETED)
        }

        is ExecutionStep.Wait -> {
            state.clock.sleep(step.durationMs.value)
            StepExecution(StepOutcome.COMPLETED)
        }

        is ExecutionStep.StageBoundary -> StepExecution(StepOutcome.COMPLETED)

        is ExecutionStep.Jump -> StepExecution(StepOutcome.COMPLETED)

        is ExecutionStep.WaitUntil -> {
            awaitCondition(
                condition = step.condition,
                timeoutMs = step.timeoutMs.value,
                pollIntervalMs = step.pollIntervalMs.value,
                stepId = step.id,
                path = "${step.id}/condition",
                state = state,
            )
            StepExecution(StepOutcome.COMPLETED)
        }

        is ExecutionStep.IfElse -> {
            val condition = evaluateCondition(step.condition, step.id, "${step.id}/condition", state)
            val branch = if (condition) step.thenSteps else step.elseSteps
            executeSteps(branch, state)
        }

        is ExecutionStep.Repeat -> {
            repeat(step.count) {
                ensureBoundary(state)
                val execution = executeSteps(step.bodySteps, state)
                if (execution.halt != null) return execution
            }
            StepExecution(StepOutcome.COMPLETED)
        }

        is ExecutionStep.While -> {
            var iterations = 0
            while (iterations < step.maxIterations) {
                ensureBoundary(state)
                val condition = evaluateCondition(step.condition, step.id, "${step.id}/condition", state)
                if (!condition) break
                val execution = executeSteps(step.bodySteps, state)
                if (execution.halt != null) return execution
                iterations++
            }
            StepExecution(StepOutcome.COMPLETED)
        }

        is ExecutionStep.LaunchApp -> {
            state.driver.launchApp(step.packageName)
            awaitForeground(step.packageName, step.foregroundTimeoutMs.value, state)
            state.startupHandoffPending = false
            StepExecution(StepOutcome.COMPLETED)
        }

        is ExecutionStep.Stop -> StepExecution(
            outcome = StepOutcome.STOPPED,
            halt = RunHalt(
                outcome = RunOutcome.STOPPED,
                failure = null,
                stopReason = step.reason,
            ),
        )

        is ExecutionStep.SetVariable -> {
            state.variables[step.name.value] = step.value
            StepExecution(StepOutcome.COMPLETED)
        }
    }

    private fun awaitCondition(
        condition: Condition,
        timeoutMs: Long,
        pollIntervalMs: Long,
        stepId: String,
        path: String,
        state: RunState,
    ) {
        val deadline = deadline(state.nowMs(), timeoutMs)
        while (true) {
            ensureBoundary(state)
            if (evaluateCondition(condition, stepId, path, state)) return
            val remaining = deadline - state.nowMs()
            if (remaining <= 0L) {
                throw RunAbort(
                    outcome = RunOutcome.TIMED_OUT,
                    stepOutcome = StepOutcome.TIMED_OUT,
                    failure = RunFailure(
                        code = RunFailureCode.CONDITION_TIMEOUT,
                        stepId = stepId,
                        path = path,
                        message = "Condition did not become true before its timeout",
                    ),
                    stopReason = null,
                )
            }
            sleepWithControls(min(pollIntervalMs, remaining), state)
        }
    }

    private fun awaitForeground(packageName: com.veyarc.model.PackageName, timeoutMs: Long, state: RunState) {
        val deadline = deadline(state.nowMs(), timeoutMs)
        while (true) {
            ensureBoundary(state)
            if (state.driver.currentForegroundPackage() == packageName) return
            val remaining = deadline - state.nowMs()
            if (remaining <= 0L) {
                throw RunAbort(
                    outcome = RunOutcome.TIMED_OUT,
                    stepOutcome = StepOutcome.TIMED_OUT,
                    failure = RunFailure(
                        code = RunFailureCode.FOREGROUND_TIMEOUT,
                        stepId = state.currentStepId,
                        path = state.currentPath,
                        message = "Application did not reach the foreground before its timeout",
                    ),
                    stopReason = null,
                )
            }
            sleepWithControls(min(FOREGROUND_POLL_INTERVAL_MS, remaining), state)
        }
    }

    private fun evaluateCondition(
        condition: Condition,
        stepId: String,
        path: String,
        state: RunState,
    ): Boolean {
        var observation: ConditionObservation? = null
        val result = when (condition) {
            is PixelColorCondition,
            is ImagePresentCondition,
            is ImageAbsentCondition,
            is OrientationCondition,
            -> state.driver.observe(condition).also { observation = it }.result

            is ForegroundPackageCondition -> {
                ensureForeground(state)
                state.driver.currentForegroundPackage() == condition.packageName
            }

            is VariableComparisonCondition -> compare(condition, state)

            is StepResultCondition -> state.stepOutcomes[condition.stepId] == condition.outcome

            is AndCondition -> condition.conditions.allIndexed { index, child ->
                evaluateCondition(child, stepId, "$path/conditions/$index", state)
            }

            is com.veyarc.model.OrCondition -> condition.conditions.anyIndexed { index, child ->
                evaluateCondition(child, stepId, "$path/conditions/$index", state)
            }

            is NotCondition -> !evaluateCondition(condition.condition, stepId, "$path/condition", state)
        }
        state.emit(
            RunEvent.ConditionEvaluated(
                atMs = state.nowMs(),
                stepId = stepId,
                path = path,
                result = result,
                matchScore = observation?.matchScore,
                threshold = observation?.threshold,
            ),
        )
        return result
    }

    private fun compare(condition: VariableComparisonCondition, state: RunState): Boolean {
        val left = resolveOperand(condition.left, state)
        val right = resolveOperand(condition.right, state)
        return when (condition.operator) {
            ComparisonOperator.EQUALS -> scalarEquals(left, right)
            ComparisonOperator.NOT_EQUALS -> !scalarEquals(left, right)
            ComparisonOperator.LESS_THAN -> number(left) < number(right)
            ComparisonOperator.LESS_THAN_OR_EQUAL -> number(left) <= number(right)
            ComparisonOperator.GREATER_THAN -> number(left) > number(right)
            ComparisonOperator.GREATER_THAN_OR_EQUAL -> number(left) >= number(right)
        }
    }

    private fun scalarEquals(
        left: com.veyarc.model.ScalarValue,
        right: com.veyarc.model.ScalarValue,
    ): Boolean = when {
        left is NumberValue && right is NumberValue -> left.value == right.value
        else -> left == right
    }

    private fun resolveOperand(operand: Operand, state: RunState) = when (operand) {
        is LiteralOperand -> operand.value

        is VariableOperand -> state.variables[operand.name.value] ?: throw RunAbort(
            outcome = RunOutcome.FAILED,
            stepOutcome = StepOutcome.FAILED,
            failure = RunFailure(
                code = RunFailureCode.DRIVER_ERROR,
                stepId = state.currentStepId,
                path = state.currentPath,
                message = "Variable ${operand.name.value} is not available",
            ),
            stopReason = null,
        )
    }

    private fun number(value: com.veyarc.model.ScalarValue): Double = when (value) {
        is NumberValue -> value.value
        else -> throw IllegalStateException("Ordered comparison requires number values")
    }

    private fun ensureBoundary(state: RunState) {
        ensureNotCancelled(state)
        awaitResume(state)
        val now = state.nowMs()
        if (now < state.startedAtMs) {
            throw RunAbort(
                outcome = RunOutcome.FAILED,
                stepOutcome = StepOutcome.FAILED,
                failure = RunFailure(
                    code = RunFailureCode.DRIVER_ERROR,
                    stepId = state.currentStepId,
                    path = state.currentPath,
                    message = "Execution clock moved backwards",
                ),
                stopReason = null,
            )
        }
        if (now - state.startedAtMs > state.plan.runLimits.maxRuntimeMs) {
            throw RunAbort(
                outcome = RunOutcome.TIMED_OUT,
                stepOutcome = StepOutcome.TIMED_OUT,
                failure = RunFailure(
                    code = RunFailureCode.RUNTIME_LIMIT_EXCEEDED,
                    stepId = state.currentStepId,
                    path = state.currentPath,
                    message = "Workflow exceeded maxRuntimeMs",
                ),
                stopReason = null,
            )
        }
        currentViewportOrAbort(state)
        ensureForeground(state)
    }

    private fun ensureNotCancelled(state: RunState) {
        if (state.cancellation.isCancellationRequested()) {
            throw RunAbort(
                outcome = RunOutcome.CANCELLED,
                stepOutcome = StepOutcome.CANCELLED,
                failure = RunFailure(
                    code = RunFailureCode.CANCELLATION_REQUESTED,
                    stepId = state.currentStepId,
                    path = state.currentPath,
                    message = "Cancellation was requested",
                ),
                stopReason = null,
            )
        }
    }

    private fun awaitResume(state: RunState) {
        if (!state.pause.isPauseRequested()) {
            if (state.pauseEventPublished) {
                state.emit(RunEvent.Resumed(state.nowMs()))
                state.pauseEventPublished = false
            }
            return
        }
        if (!state.pauseEventPublished) {
            state.emit(RunEvent.Paused(state.nowMs()))
            state.pauseEventPublished = true
        }
        while (state.pause.isPauseRequested()) {
            ensureNotCancelled(state)
            state.clock.sleep(PAUSE_POLL_INTERVAL_MS)
        }
        state.emit(RunEvent.Resumed(state.nowMs()))
        state.pauseEventPublished = false
    }

    private fun sleepWithControls(durationMs: Long, state: RunState) {
        var remaining = durationMs
        while (remaining > 0L) {
            ensureBoundary(state)
            val slice = min(PAUSE_POLL_INTERVAL_MS, remaining)
            state.clock.sleep(slice)
            remaining -= slice
        }
    }

    private inline fun <T> dispatchAtViewport(
        state: RunState,
        map: (ActiveViewport) -> T,
        dispatch: (T) -> Unit,
    ) {
        val before = currentViewportOrAbort(state)
        dispatch(map(before))
        val after = state.driver.currentViewport()
            ?: throw viewportAbort(
                state = state,
                code = RunFailureCode.VIEWPORT_UNAVAILABLE,
                message = "Active viewport became unavailable during a gesture",
            )
        if (after != before) {
            throw viewportAbort(
                state = state,
                code = RunFailureCode.VIEWPORT_CHANGED_DURING_GESTURE,
                message = "Active viewport changed during a gesture",
            )
        }
    }

    private fun currentViewportOrAbort(state: RunState): ActiveViewport {
        val viewport = state.driver.currentViewport()
            ?: throw viewportAbort(
                state = state,
                code = RunFailureCode.VIEWPORT_UNAVAILABLE,
                message = "Active viewport is unavailable",
            )
        if (viewport.windowMode != ActiveWindowMode.FULL_DISPLAY) {
            throw viewportAbort(
                state = state,
                code = RunFailureCode.UNSUPPORTED_WINDOW_MODE,
                message = "Workflow playback requires full-display mode",
            )
        }
        val initialRotation = state.initialRotationQuarterTurns
        if (initialRotation != null && viewport.rotationQuarterTurns != initialRotation) {
            throw viewportAbort(
                state = state,
                code = RunFailureCode.DISPLAY_ROTATION_CHANGED_DURING_RUN,
                message = "Display rotation changed during the workflow",
            )
        }
        if (viewport.orientation != state.plan.viewport.recordedOrientation) {
            throw viewportAbort(
                state = state,
                code = RunFailureCode.ORIENTATION_MISMATCH,
                message = "Workflow requires ${state.plan.viewport.recordedOrientation.name.lowercase()} orientation",
            )
        }
        if (initialRotation == null) state.initialRotationQuarterTurns = viewport.rotationQuarterTurns
        return viewport
    }

    private fun viewportAbort(
        state: RunState,
        code: RunFailureCode,
        message: String,
    ): RunAbort = RunAbort(
        outcome = RunOutcome.FAILED,
        stepOutcome = StepOutcome.FAILED,
        failure = RunFailure(
            code = code,
            stepId = state.currentStepId,
            path = state.currentPath,
            message = message,
        ),
        stopReason = null,
    )

    private fun checkStepDuration(stepId: String, startedAtMs: Long, state: RunState) {
        val now = state.nowMs()
        if (now < startedAtMs || now - startedAtMs > state.plan.runLimits.maxStepDurationMs) {
            throw RunAbort(
                outcome = RunOutcome.TIMED_OUT,
                stepOutcome = StepOutcome.TIMED_OUT,
                failure = RunFailure(
                    code = RunFailureCode.STEP_TIMEOUT,
                    stepId = stepId,
                    path = state.currentPath,
                    message = "Step exceeded maxStepDurationMs",
                ),
                stopReason = null,
            )
        }
    }

    private fun isAtomicStep(step: ExecutionStep): Boolean = when (step) {
        is ExecutionStep.IfElse, is ExecutionStep.Repeat, is ExecutionStep.While -> false
        else -> true
    }

    private fun ensureForeground(state: RunState) {
        val current = state.driver.currentForegroundPackage()
            ?: throw RunAbort(
                outcome = RunOutcome.FAILED,
                stepOutcome = StepOutcome.FAILED,
                failure = RunFailure(
                    code = RunFailureCode.FOREGROUND_UNAVAILABLE,
                    stepId = state.currentStepId,
                    path = state.currentPath,
                    message = "Current foreground package is unavailable",
                ),
                stopReason = null,
            )
        if (current !in state.plan.allowedPackages) {
            if (state.startupHandoffPending && current == state.startupPackage) return
            throw RunAbort(
                outcome = RunOutcome.FAILED,
                stepOutcome = StepOutcome.FAILED,
                failure = RunFailure(
                    code = RunFailureCode.UNEXPECTED_FOREGROUND_PACKAGE,
                    stepId = state.currentStepId,
                    path = state.currentPath,
                    message = "Foreground package ${current.value} is not declared by the workflow",
                ),
                stopReason = null,
            )
        }
    }

    private fun finish(
        state: RunState,
        outcome: RunOutcome,
        failure: RunFailure?,
        stopReason: StopReason?,
    ): WorkflowRunResult {
        state.emit(RunEvent.Finished(state.nowMs(), outcome, failure, stopReason))
        return WorkflowRunResult(
            outcome = outcome,
            events = immutableRuntimeList(state.events),
            stepOutcomes = immutableRuntimeMap(state.stepOutcomes),
            variables = immutableRuntimeMap(state.variables),
            failure = failure,
            stopReason = stopReason,
        )
    }

    private fun deadline(startMs: Long, durationMs: Long): Long = if (durationMs > Long.MAX_VALUE - startMs) Long.MAX_VALUE else startMs + durationMs

    private class RunState(
        val plan: ExecutionPlan,
        val driver: AutomationDriver,
        val clock: ExecutionClock,
        val cancellation: CancellationSignal,
        val pause: PauseSignal,
        val eventSink: (RunEvent) -> Unit,
        val startupPackage: PackageName?,
        val startedAtMs: Long,
    ) {
        val events = mutableListOf<RunEvent>()
        val stepOutcomes = collectStepIds(plan.steps).associateWith { StepOutcome.NOT_EXECUTED }.toMutableMap()
        val variables = LinkedHashMap(plan.variables.mapValues { it.value.initialValue })
        val jumpVisits = mutableMapOf<String, Int>()
        var currentStepId: String? = null
        var currentPath: String = "/"
        var startupHandoffPending: Boolean = startupPackage != null && plan.steps.firstOrNull() is ExecutionStep.LaunchApp
        var pauseEventPublished = false
        var initialRotationQuarterTurns: Int? = null

        fun nowMs(): Long = clock.nowMs()

        fun emit(event: RunEvent) {
            events += event
            runCatching { eventSink(event) }
        }
    }

    private data class StepExecution(
        val outcome: StepOutcome,
        val halt: RunHalt? = null,
    )

    private data class RunHalt(
        val outcome: RunOutcome,
        val failure: RunFailure?,
        val stopReason: StopReason?,
    )

    private class RunAbort(
        val outcome: RunOutcome,
        val stepOutcome: StepOutcome,
        val failure: RunFailure,
        val stopReason: StopReason?,
    ) : RuntimeException(null, null, false, false)

    private companion object {
        const val FOREGROUND_POLL_INTERVAL_MS = 50L
        const val PAUSE_POLL_INTERVAL_MS = 50L

        fun collectStepIds(steps: List<ExecutionStep>): List<String> = buildList {
            steps.forEach { step ->
                if (step !is ExecutionStep.StageBoundary) add(step.id)
                when (step) {
                    is ExecutionStep.IfElse -> {
                        addAll(collectStepIds(step.thenSteps))
                        addAll(collectStepIds(step.elseSteps))
                    }

                    is ExecutionStep.Repeat -> addAll(collectStepIds(step.bodySteps))

                    is ExecutionStep.While -> addAll(collectStepIds(step.bodySteps))

                    else -> Unit
                }
            }
        }

        private fun <T> Iterable<T>.allIndexed(predicate: (Int, T) -> Boolean): Boolean {
            forEachIndexed { index, value ->
                if (!predicate(index, value)) return false
            }
            return true
        }

        private fun <T> Iterable<T>.anyIndexed(predicate: (Int, T) -> Boolean): Boolean {
            forEachIndexed { index, value ->
                if (predicate(index, value)) return true
            }
            return false
        }
    }
}
