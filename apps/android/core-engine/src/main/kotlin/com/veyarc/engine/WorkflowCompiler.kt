package com.veyarc.engine

import com.veyarc.model.AndCondition
import com.veyarc.model.Condition
import com.veyarc.model.ForegroundPackageCondition
import com.veyarc.model.IfElse
import com.veyarc.model.ImageAbsentCondition
import com.veyarc.model.ImagePresentCondition
import com.veyarc.model.Jump
import com.veyarc.model.LaunchApp
import com.veyarc.model.LongPress
import com.veyarc.model.NotCondition
import com.veyarc.model.OrientationCondition
import com.veyarc.model.PixelColorCondition
import com.veyarc.model.Repeat
import com.veyarc.model.SetVariable
import com.veyarc.model.StageBoundary
import com.veyarc.model.StepResultCondition
import com.veyarc.model.Stop
import com.veyarc.model.TapPoint
import com.veyarc.model.TapRegion
import com.veyarc.model.ValidationError
import com.veyarc.model.VariableComparisonCondition
import com.veyarc.model.Wait
import com.veyarc.model.WaitUntil
import com.veyarc.model.WhileStep
import com.veyarc.model.WorkflowDocument
import com.veyarc.model.WorkflowStep
import com.veyarc.model.WorkflowValidator
import com.veyarc.model.resolvePoint

/** Compiles editable workflow documents into bounded, immutable execution plans. */
object WorkflowCompiler {
    fun compile(
        document: WorkflowDocument,
        capabilities: EngineCapabilities = EngineCapabilities.all(),
    ): WorkflowCompilationResult {
        val errors = mutableListOf<CompilationError>()
        errors += WorkflowValidator.validate(document).map(::invalidWorkflowError)
        validateCapabilities(document.steps, "/steps", capabilities, errors)

        val analysis = analyzeWorkflow(document.steps)
        validateAnalysis(document, analysis, errors)

        if (errors.isNotEmpty()) return failure(errors)

        val plan = ExecutionPlan(
            workflowId = document.id,
            workflowRevision = document.revision,
            workflowName = document.name,
            schemaVersion = document.schemaVersion,
            allowedPackages = immutableSet(document.allowedPackages.toSet()),
            viewport = document.viewport,
            runLimits = document.runLimits,
            variables = immutableMap(document.variables),
            assets = immutableList(document.assets),
            steps = compileSteps(document.steps),
            analysis = analysis,
        )
        return WorkflowCompilationResult(plan, emptyList())
    }

    fun compileOrThrow(
        document: WorkflowDocument,
        capabilities: EngineCapabilities = EngineCapabilities.all(),
    ): ExecutionPlan = compile(document, capabilities).let { result ->
        result.plan ?: throw WorkflowCompilationException(result.errors)
    }

    private fun compileSteps(steps: List<WorkflowStep>): List<ExecutionStep> = immutableList(
        steps.map { step ->
            when (step) {
                is TapPoint -> ExecutionStep.TapPoint(step.id, step.point)

                is TapRegion -> ExecutionStep.TapRegion(
                    id = step.id,
                    region = step.region,
                    anchor = step.anchor,
                    resolvedPoint = step.resolvePoint(),
                )

                is LongPress -> ExecutionStep.LongPress(step.id, step.point, step.durationMs)

                is com.veyarc.model.SwipePath -> ExecutionStep.SwipePath(
                    step.id,
                    step.durationMs,
                    immutableList(step.points),
                )

                is Wait -> ExecutionStep.Wait(step.id, step.durationMs, step.durationVariable)

                is StageBoundary -> ExecutionStep.StageBoundary(step.id)

                is Jump -> ExecutionStep.Jump(step.id, step.targetStepId, step.maxVisits)

                is WaitUntil -> ExecutionStep.WaitUntil(
                    step.id,
                    freezeCondition(step.condition),
                    step.timeoutMs,
                    step.pollIntervalMs,
                )

                is IfElse -> ExecutionStep.IfElse(
                    step.id,
                    freezeCondition(step.condition),
                    compileSteps(step.thenSteps),
                    compileSteps(step.elseSteps),
                )

                is Repeat -> ExecutionStep.Repeat(step.id, step.count, compileSteps(step.bodySteps), step.countVariable)

                is WhileStep -> ExecutionStep.While(
                    step.id,
                    freezeCondition(step.condition),
                    step.maxIterations,
                    compileSteps(step.bodySteps),
                )

                is LaunchApp -> ExecutionStep.LaunchApp(
                    step.id,
                    step.packageName,
                    step.foregroundTimeoutMs,
                )

                is Stop -> ExecutionStep.Stop(step.id, step.reason, step.message)

                is SetVariable -> ExecutionStep.SetVariable(step.id, step.name, step.value, step.source, step.calculation)
            }
        },
    )

    private fun freezeCondition(condition: Condition): Condition = when (condition) {
        is AndCondition -> AndCondition(immutableList(condition.conditions.map(::freezeCondition)))

        is com.veyarc.model.OrCondition ->
            com.veyarc.model.OrCondition(immutableList(condition.conditions.map(::freezeCondition)))

        is NotCondition -> NotCondition(freezeCondition(condition.condition))

        else -> condition
    }

    private fun validateCapabilities(
        steps: List<WorkflowStep>,
        path: String,
        capabilities: EngineCapabilities,
        errors: MutableList<CompilationError>,
    ) {
        steps.forEachIndexed { index, step ->
            val stepPath = "$path/$index"
            requireCapability(stepCapability(step), stepPath, capabilities, errors)
            when (step) {
                is WaitUntil -> validateConditionCapabilities(
                    step.condition,
                    "$stepPath/condition",
                    capabilities,
                    errors,
                )

                is IfElse -> {
                    validateConditionCapabilities(step.condition, "$stepPath/condition", capabilities, errors)
                    validateCapabilities(step.thenSteps, "$stepPath/thenSteps", capabilities, errors)
                    validateCapabilities(step.elseSteps, "$stepPath/elseSteps", capabilities, errors)
                }

                is Repeat -> validateCapabilities(step.bodySteps, "$stepPath/bodySteps", capabilities, errors)

                is WhileStep -> {
                    validateConditionCapabilities(step.condition, "$stepPath/condition", capabilities, errors)
                    validateCapabilities(step.bodySteps, "$stepPath/bodySteps", capabilities, errors)
                }

                else -> Unit
            }
        }
    }

    private fun validateConditionCapabilities(
        condition: Condition,
        path: String,
        capabilities: EngineCapabilities,
        errors: MutableList<CompilationError>,
    ) {
        requireCapability(conditionCapability(condition), path, capabilities, errors)
        when (condition) {
            is com.veyarc.model.AndCondition -> condition.conditions.forEachIndexed { index, child ->
                validateConditionCapabilities(child, "$path/conditions/$index", capabilities, errors)
            }

            is com.veyarc.model.OrCondition -> condition.conditions.forEachIndexed { index, child ->
                validateConditionCapabilities(child, "$path/conditions/$index", capabilities, errors)
            }

            is NotCondition -> validateConditionCapabilities(condition.condition, "$path/condition", capabilities, errors)

            else -> Unit
        }
    }

    private fun stepCapability(step: WorkflowStep): EngineCapability = when (step) {
        is TapPoint -> EngineCapability.TAP_POINT
        is TapRegion -> EngineCapability.TAP_REGION
        is LongPress -> EngineCapability.LONG_PRESS
        is com.veyarc.model.SwipePath -> EngineCapability.SWIPE_PATH
        is Wait -> EngineCapability.WAIT
        is StageBoundary -> EngineCapability.STAGE_BOUNDARY
        is Jump -> EngineCapability.JUMP
        is WaitUntil -> EngineCapability.WAIT_UNTIL
        is IfElse -> EngineCapability.IF_ELSE
        is Repeat -> EngineCapability.REPEAT
        is WhileStep -> EngineCapability.WHILE
        is LaunchApp -> EngineCapability.LAUNCH_APP
        is Stop -> EngineCapability.STOP
        is SetVariable -> EngineCapability.SET_VARIABLE
    }

    private fun conditionCapability(condition: Condition): EngineCapability = when (condition) {
        is PixelColorCondition -> EngineCapability.PIXEL_COLOR_CONDITION
        is ImagePresentCondition, is ImageAbsentCondition -> EngineCapability.IMAGE_TEMPLATE_CONDITION
        is ForegroundPackageCondition -> EngineCapability.FOREGROUND_PACKAGE_CONDITION
        is OrientationCondition -> EngineCapability.ORIENTATION_CONDITION
        is VariableComparisonCondition -> EngineCapability.VARIABLE_COMPARISON_CONDITION
        is StepResultCondition -> EngineCapability.STEP_RESULT_CONDITION
        is AndCondition, is com.veyarc.model.OrCondition, is NotCondition -> EngineCapability.LOGICAL_CONDITION
    }

    private fun requireCapability(
        capability: EngineCapability,
        path: String,
        available: EngineCapabilities,
        errors: MutableList<CompilationError>,
    ) {
        if (!available.supports(capability)) {
            errors += CompilationError(
                code = CompilationErrorCode.UNSUPPORTED_CAPABILITY,
                path = path,
                message = "Execution target does not support $capability",
                capability = capability,
            )
        }
    }

    private fun analyzeWorkflow(steps: List<WorkflowStep>): ControlFlowAnalysis {
        val dynamicCount = hasDynamicStepCount(steps)
        if (jumpCount(steps, nestedOnly = true).value == 0L) return analyzeSteps(steps, depth = 1).copy(hasDynamicStepCount = dynamicCount)
        val analysis = analyzeSteps(steps, depth = 1, followJumps = false)
        val passes = add(Cost(1L, false), jumpCount(steps, nestedOnly = false))
        // Nested jump counters outlive each invocation of the nest, so later entries can
        // execute code skipped on the first pass even when the destination is local.
        // Each taken jump starts another segment. A full traversal with all loop iterations
        // bounds each segment, even when an outward jump re-enters code skipped on the first pass.
        val stepCost = multiply(analysis.worstCaseStepCount, passes.value, analysis.arithmeticOverflow || passes.overflowed)
        val runtimeCost = multiply(analysis.worstCaseRuntimeMs, passes.value, analysis.arithmeticOverflow || passes.overflowed)
        return analysis.copy(
            worstCaseStepCount = stepCost.value,
            worstCaseRuntimeMs = runtimeCost.value,
            arithmeticOverflow = stepCost.overflowed || runtimeCost.overflowed,
            hasDynamicStepCount = dynamicCount,
        )
    }

    private fun hasDynamicStepCount(steps: List<WorkflowStep>): Boolean = steps.any { step ->
        when (step) {
            is Repeat -> step.countVariable != null || hasDynamicStepCount(step.bodySteps)
            is IfElse -> hasDynamicStepCount(step.thenSteps) || hasDynamicStepCount(step.elseSteps)
            is WhileStep -> hasDynamicStepCount(step.bodySteps)
            else -> false
        }
    }

    private fun jumpCount(steps: List<WorkflowStep>, nestedOnly: Boolean, depth: Int = 1): Cost {
        var count = Cost(0L, false)
        steps.forEach { step ->
            count = add(
                count,
                when (step) {
                    is Jump -> Cost(if (nestedOnly && depth == 1) 0L else step.maxVisits.toLong() - 1L, false)
                    is IfElse -> add(jumpCount(step.thenSteps, nestedOnly, depth + 1), jumpCount(step.elseSteps, nestedOnly, depth + 1))
                    is Repeat -> jumpCount(step.bodySteps, nestedOnly, depth + 1)
                    is WhileStep -> jumpCount(step.bodySteps, nestedOnly, depth + 1)
                    else -> Cost(0L, false)
                },
            )
        }
        return count
    }

    private fun analyzeSteps(steps: List<WorkflowStep>, depth: Int, followJumps: Boolean = true): ControlFlowAnalysis {
        var totalSteps = Cost(0L, false)
        var totalRuntime = Cost(0L, false)
        var maxDepth = depth
        var maxLoopBound = 0
        val indices = steps.mapIndexed { index, step -> step.id to index }.toMap()
        val jumpVisits = mutableMapOf<String, Int>()
        val stepCosts = if (followJumps && steps.any { it is Jump }) mutableMapOf<Int, StepCost>() else null
        var index = 0
        var analyzedSteps = 0

        while (index in steps.indices) {
            if (++analyzedSteps > MAX_CONTROL_FLOW_ANALYSIS_STEPS) {
                return ControlFlowAnalysis(Long.MAX_VALUE, Long.MAX_VALUE, maxDepth, maxLoopBound, true)
            }
            val step = steps[index]
            val cost = stepCosts?.getOrPut(index) { analyzeStep(step, depth, followJumps) } ?: analyzeStep(step, depth, followJumps)
            totalSteps = add(totalSteps, cost.steps)
            totalRuntime = add(totalRuntime, cost.runtime)
            maxDepth = maxOf(maxDepth, cost.maxDepth)
            maxLoopBound = maxOf(maxLoopBound, cost.maxLoopBound)

            if (followJumps && step is Jump) {
                val visits = jumpVisits.getOrDefault(step.id, 0) + 1
                jumpVisits[step.id] = visits
                val target = indices[step.targetStepId]
                if (visits < step.maxVisits && target != null && target != index) {
                    index = target
                    continue
                }
            }
            index++
        }

        return ControlFlowAnalysis(
            worstCaseStepCount = totalSteps.value,
            worstCaseRuntimeMs = totalRuntime.value,
            maxNestingDepth = maxDepth,
            maxLoopBound = maxLoopBound,
            arithmeticOverflow = totalSteps.overflowed || totalRuntime.overflowed,
        )
    }

    private fun analyzeStep(step: WorkflowStep, depth: Int, followJumps: Boolean): StepCost {
        val ownRuntime = when (step) {
            is LongPress -> step.durationMs.value
            is com.veyarc.model.SwipePath -> step.durationMs.value
            is Wait -> if (step.durationVariable == null) step.durationMs.value else Long.MAX_VALUE
            is WaitUntil -> step.timeoutMs.value
            is LaunchApp -> step.foregroundTimeoutMs.value
            else -> 0L
        }
        val own = StepCost(
            steps = Cost(if (step is StageBoundary) 0L else 1L, false),
            runtime = Cost(ownRuntime, false),
            maxDepth = depth,
            maxLoopBound = when (step) {
                is Repeat -> if (step.countVariable == null) step.count else 0
                is WhileStep -> step.maxIterations
                is Jump -> step.maxVisits
                else -> 0
            },
        )

        return when (step) {
            is IfElse -> {
                val thenCost = analyzeSteps(step.thenSteps, depth + 1, followJumps)
                val elseCost = analyzeSteps(step.elseSteps, depth + 1, followJumps)
                own.copy(
                    steps = add(own.steps, maxCost(thenCost, elseCost, CostSelector.STEPS)),
                    runtime = add(own.runtime, maxCost(thenCost, elseCost, CostSelector.RUNTIME)),
                    maxDepth = maxOf(own.maxDepth, thenCost.maxNestingDepth, elseCost.maxNestingDepth),
                    maxLoopBound = maxOf(own.maxLoopBound, thenCost.maxLoopBound, elseCost.maxLoopBound),
                    overflowed = thenCost.arithmeticOverflow || elseCost.arithmeticOverflow,
                )
            }

            is Repeat -> {
                val bodyCost = analyzeSteps(step.bodySteps, depth + 1, followJumps)
                val count = if (step.countVariable == null) step.count.toLong() else Int.MAX_VALUE.toLong()
                own.copy(
                    steps = add(own.steps, multiply(bodyCost.worstCaseStepCount, count, bodyCost.arithmeticOverflow)),
                    runtime = add(own.runtime, multiply(bodyCost.worstCaseRuntimeMs, count, bodyCost.arithmeticOverflow)),
                    maxDepth = maxOf(own.maxDepth, bodyCost.maxNestingDepth),
                    maxLoopBound = maxOf(own.maxLoopBound, bodyCost.maxLoopBound),
                    overflowed = bodyCost.arithmeticOverflow,
                )
            }

            is WhileStep -> {
                val bodyCost = analyzeSteps(step.bodySteps, depth + 1, followJumps)
                own.copy(
                    steps = add(own.steps, multiply(bodyCost.worstCaseStepCount, step.maxIterations.toLong(), bodyCost.arithmeticOverflow)),
                    runtime = add(own.runtime, multiply(bodyCost.worstCaseRuntimeMs, step.maxIterations.toLong(), bodyCost.arithmeticOverflow)),
                    maxDepth = maxOf(own.maxDepth, bodyCost.maxNestingDepth),
                    maxLoopBound = maxOf(own.maxLoopBound, bodyCost.maxLoopBound),
                    overflowed = bodyCost.arithmeticOverflow,
                )
            }

            else -> own
        }
    }

    private enum class CostSelector { STEPS, RUNTIME }

    private fun maxCost(
        first: ControlFlowAnalysis,
        second: ControlFlowAnalysis,
        selector: CostSelector,
    ): Cost {
        val firstValue = when (selector) {
            CostSelector.STEPS -> first.worstCaseStepCount
            CostSelector.RUNTIME -> first.worstCaseRuntimeMs
        }
        val secondValue = when (selector) {
            CostSelector.STEPS -> second.worstCaseStepCount
            CostSelector.RUNTIME -> second.worstCaseRuntimeMs
        }
        return Cost(maxOf(firstValue, secondValue), first.arithmeticOverflow || second.arithmeticOverflow)
    }

    private data class Cost(val value: Long, val overflowed: Boolean)

    private data class StepCost(
        val steps: Cost,
        val runtime: Cost,
        val maxDepth: Int,
        val maxLoopBound: Int,
        val overflowed: Boolean = false,
    )

    private fun add(first: Cost, second: Cost): Cost {
        if (first.value > Long.MAX_VALUE - second.value) return Cost(Long.MAX_VALUE, true)
        return Cost(first.value + second.value, first.overflowed || second.overflowed)
    }

    private fun multiply(value: Long, multiplier: Long, inheritedOverflow: Boolean): Cost {
        if (multiplier <= 0 || value == 0L) return Cost(0L, inheritedOverflow)
        if (value > Long.MAX_VALUE / multiplier) return Cost(Long.MAX_VALUE, true)
        return Cost(value * multiplier, inheritedOverflow)
    }

    private const val MAX_CONTROL_FLOW_ANALYSIS_STEPS = 1_000_000

    private fun validateAnalysis(
        document: WorkflowDocument,
        analysis: ControlFlowAnalysis,
        errors: MutableList<CompilationError>,
    ) {
        if (analysis.maxNestingDepth > document.runLimits.maxNestingDepth) {
            errors += limitError(
                "/runLimits/maxNestingDepth",
                "Workflow nesting depth ${analysis.maxNestingDepth} exceeds maxNestingDepth ${document.runLimits.maxNestingDepth}",
            )
        }
        if (analysis.maxLoopBound > document.runLimits.maxLoopIterations) {
            errors += limitError(
                "/runLimits/maxLoopIterations",
                "Loop bound ${analysis.maxLoopBound} exceeds maxLoopIterations ${document.runLimits.maxLoopIterations}",
            )
        }
    }

    private fun invalidWorkflowError(error: ValidationError): CompilationError = CompilationError(
        code = CompilationErrorCode.INVALID_WORKFLOW,
        path = error.path,
        message = error.message,
        validationCode = error.code,
    )

    private fun limitError(path: String, message: String): CompilationError = CompilationError(
        code = CompilationErrorCode.LIMIT_EXCEEDED,
        path = path,
        message = message,
    )

    private fun failure(errors: List<CompilationError>): WorkflowCompilationResult = WorkflowCompilationResult(
        plan = null,
        errors = immutableList(errors.sortedWith(compareBy<CompilationError> { it.path }.thenBy { it.code.name })),
    )
}
