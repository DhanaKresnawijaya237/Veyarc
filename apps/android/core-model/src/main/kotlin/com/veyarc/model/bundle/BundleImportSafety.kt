package com.veyarc.model.bundle

import com.veyarc.model.AndCondition
import com.veyarc.model.Condition
import com.veyarc.model.IfElse
import com.veyarc.model.NotCondition
import com.veyarc.model.OrCondition
import com.veyarc.model.Repeat
import com.veyarc.model.SwipePath
import com.veyarc.model.WaitUntil
import com.veyarc.model.WhileStep
import com.veyarc.model.WorkflowDocument
import com.veyarc.model.WorkflowStep
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque

/** Resource limits applied to untrusted bundle contents before recursive model validation. */
internal object BundleImportSafety {
    fun decodeUtf8(entryName: String, bytes: ByteArray): String = try {
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (error: Exception) {
        throw BundleFormatException("$entryName is not valid UTF-8", error)
    }

    /**
     * Rejects excessive nesting without first building a recursive JSON tree. The JSON parser
     * remains responsible for syntax and bracket matching.
     */
    fun checkJsonNesting(entryName: String, json: String, maximumDepth: Int) {
        var depth = 0
        var inString = false
        var escaped = false
        json.forEach { character ->
            if (inString) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == '"' -> inString = false
                }
            } else {
                when (character) {
                    '"' -> inString = true

                    '{', '[' -> {
                        depth++
                        if (depth > maximumDepth) {
                            throw BundleFormatException("$entryName exceeds its nesting safety limit")
                        }
                    }

                    '}', ']' -> depth--
                }
            }
        }
    }

    fun checkDocumentComplexity(document: WorkflowDocument, limits: BundleLimits) {
        checkCount("allowed packages", document.allowedPackages.size, limits.maxAllowedPackages)
        checkCount("variables", document.variables.size, limits.maxVariables)
        checkCount("assets", document.assets.size, limits.maxEntries - 2)

        val pendingSteps = ArrayDeque<DepthValue<WorkflowStep>>()
        document.steps.forEach { pendingSteps.addLast(DepthValue(it, 1)) }
        var stepCount = 0
        var totalPathPoints = 0L
        val pendingConditions = ArrayDeque<DepthValue<Condition>>()

        while (pendingSteps.isNotEmpty()) {
            val (step, depth) = pendingSteps.removeFirst()
            if (depth > limits.maxDocumentNestingDepth) {
                throw BundleFormatException("Workflow steps exceed their nesting safety limit")
            }
            stepCount++
            checkCount("workflow steps", stepCount, limits.maxWorkflowSteps)
            when (step) {
                is SwipePath -> {
                    totalPathPoints += step.points.size
                    if (totalPathPoints > limits.maxTotalPathPoints) {
                        throw BundleFormatException("Workflow paths exceed their point safety limit")
                    }
                }

                is WaitUntil -> pendingConditions.addLast(DepthValue(step.condition, 1))

                is IfElse -> {
                    pendingConditions.addLast(DepthValue(step.condition, 1))
                    step.thenSteps.forEach { pendingSteps.addLast(DepthValue(it, depth + 1)) }
                    step.elseSteps.forEach { pendingSteps.addLast(DepthValue(it, depth + 1)) }
                }

                is com.veyarc.model.ReusableSection -> step.bodySteps.forEach { pendingSteps.addLast(DepthValue(it, depth + 1)) }

                is Repeat -> step.bodySteps.forEach { pendingSteps.addLast(DepthValue(it, depth + 1)) }

                is WhileStep -> {
                    pendingConditions.addLast(DepthValue(step.condition, 1))
                    step.bodySteps.forEach { pendingSteps.addLast(DepthValue(it, depth + 1)) }
                }

                else -> Unit
            }
        }

        var conditionCount = 0
        while (pendingConditions.isNotEmpty()) {
            val (condition, depth) = pendingConditions.removeFirst()
            if (depth > limits.maxDocumentNestingDepth) {
                throw BundleFormatException("Workflow conditions exceed their nesting safety limit")
            }
            conditionCount++
            checkCount("workflow conditions", conditionCount, limits.maxConditionNodes)
            when (condition) {
                is AndCondition -> condition.conditions.forEach {
                    pendingConditions.addLast(DepthValue(it, depth + 1))
                }

                is OrCondition -> condition.conditions.forEach {
                    pendingConditions.addLast(DepthValue(it, depth + 1))
                }

                is NotCondition -> pendingConditions.addLast(DepthValue(condition.condition, depth + 1))

                else -> Unit
            }
        }
    }

    private fun checkCount(name: String, actual: Int, maximum: Int) {
        if (actual > maximum) throw BundleFormatException("Bundle contains too many $name")
    }

    private data class DepthValue<T>(val value: T, val depth: Int)
}
