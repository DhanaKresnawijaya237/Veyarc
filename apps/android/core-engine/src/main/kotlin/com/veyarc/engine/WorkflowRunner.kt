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
            startedAtMs = clock.nowMs(),
        )
        state.events += RunEvent.Started(
            atMs = state.nowMs(),
            workflowId = plan.workflowId,
            workflowRevision = plan.workflowRevision,
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
        steps.forEach { step ->
            state.currentStepId = step.id
            state.currentPath = "/steps/${step.id}"
            ensureBoundary(state)
            val execution = executeStep(step, state)
            if (execution.halt != null) return execution
        }
        return StepExecution(StepOutcome.COMPLETED)
    }

    private fun executeStep(step: ExecutionStep, state: RunState): StepExecution {
        state.currentStepId = step.id
        state.currentPath = "/steps/${step.id}"
        ensureBoundary(state)
        state.events += RunEvent.StepStarted(state.nowMs(), step.id)
        var finished = false
        val startedAtMs = state.nowMs()
        return try {
            val execution = executeStepBody(step, state)
            if (isAtomicStep(step)) checkStepDuration(step.id, startedAtMs, state)
            state.stepOutcomes[step.id] = execution.outcome
            state.events += RunEvent.StepFinished(state.nowMs(), step.id, execution.outcome)
            finished = true
            if (execution.halt == null) ensureBoundary(state)
            execution
        } catch (abort: RunAbort) {
            if (!finished) {
                state.stepOutcomes[step.id] = abort.stepOutcome
                state.events += RunEvent.StepFinished(state.nowMs(), step.id, abort.stepOutcome)
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
            state.events += RunEvent.StepFinished(state.nowMs(), step.id, StepOutcome.FAILED)
            throw RunAbort(RunOutcome.FAILED, StepOutcome.FAILED, failure, null)
        }
    }

    private fun executeStepBody(step: ExecutionStep, state: RunState): StepExecution = when (step) {
        is ExecutionStep.TapPoint -> {
            state.driver.tap(step.point)
            StepExecution(StepOutcome.COMPLETED)
        }

        is ExecutionStep.TapRegion -> {
            state.driver.tap(step.resolvedPoint)
            StepExecution(StepOutcome.COMPLETED)
        }

        is ExecutionStep.LongPress -> {
            state.driver.longPress(step.point, step.durationMs.value)
            StepExecution(StepOutcome.COMPLETED)
        }

        is ExecutionStep.SwipePath -> {
            state.driver.swipePath(step.points, step.durationMs.value)
            StepExecution(StepOutcome.COMPLETED)
        }

        is ExecutionStep.Wait -> {
            state.clock.sleep(step.durationMs.value)
            StepExecution(StepOutcome.COMPLETED)
        }

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
            state.clock.sleep(min(pollIntervalMs, remaining))
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
            state.clock.sleep(min(FOREGROUND_POLL_INTERVAL_MS, remaining))
        }
    }

    private fun evaluateCondition(
        condition: Condition,
        stepId: String,
        path: String,
        state: RunState,
    ): Boolean {
        val result = when (condition) {
            is PixelColorCondition,
            is ImagePresentCondition,
            is ImageAbsentCondition,
            is OrientationCondition,
            -> state.driver.observe(condition)

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
        state.events += RunEvent.ConditionEvaluated(state.nowMs(), stepId, path, result)
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
        ensureForeground(state)
    }

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
        state.events += RunEvent.Finished(state.nowMs(), outcome, failure, stopReason)
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
        val startedAtMs: Long,
    ) {
        val events = mutableListOf<RunEvent>()
        val stepOutcomes = collectStepIds(plan.steps).associateWith { StepOutcome.NOT_EXECUTED }.toMutableMap()
        val variables = LinkedHashMap(plan.variables.mapValues { it.value.initialValue })
        var currentStepId: String? = null
        var currentPath: String = "/"

        fun nowMs(): Long = clock.nowMs()
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

        fun collectStepIds(steps: List<ExecutionStep>): List<String> = buildList {
            steps.forEach { step ->
                add(step.id)
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
