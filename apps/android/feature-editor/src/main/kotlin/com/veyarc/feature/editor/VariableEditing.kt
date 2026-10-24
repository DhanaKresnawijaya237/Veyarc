package com.veyarc.feature.editor

import com.veyarc.model.AndCondition
import com.veyarc.model.ArithmeticOperator
import com.veyarc.model.BooleanValue
import com.veyarc.model.CaptureImage
import com.veyarc.model.CapturedImageCondition
import com.veyarc.model.ComparisonOperator
import com.veyarc.model.Condition
import com.veyarc.model.IfElse
import com.veyarc.model.LiteralOperand
import com.veyarc.model.NotCondition
import com.veyarc.model.NumberValue
import com.veyarc.model.Operand
import com.veyarc.model.OrCondition
import com.veyarc.model.Repeat
import com.veyarc.model.ScalarValue
import com.veyarc.model.SetVariable
import com.veyarc.model.StringValue
import com.veyarc.model.VariableComparisonCondition
import com.veyarc.model.VariableDeclaration
import com.veyarc.model.VariableName
import com.veyarc.model.VariableOperand
import com.veyarc.model.VariableType
import com.veyarc.model.Wait
import com.veyarc.model.WaitUntil
import com.veyarc.model.WhileStep
import com.veyarc.model.WorkflowStep

internal fun VariableType.displayName(): String = when (this) {
    VariableType.NUMBER -> "Number"
    VariableType.STRING -> "Text"
    VariableType.BOOLEAN -> "Boolean"
}

internal fun VariableType.defaultValue(): ScalarValue = when (this) {
    VariableType.NUMBER -> NumberValue(0.0)
    VariableType.STRING -> StringValue("")
    VariableType.BOOLEAN -> BooleanValue(false)
}

internal fun ScalarValue.editorText(): String = when (this) {
    is BooleanValue -> value.toString()
    is NumberValue -> value.toString().removeSuffix(".0")
    is StringValue -> value
}

internal fun parseVariableValue(type: VariableType, text: String): ScalarValue? = when (type) {
    VariableType.NUMBER -> text.toDoubleOrNull()?.takeIf(Double::isFinite)?.let(::NumberValue)
    VariableType.STRING -> text.takeIf { it.length <= 4096 }?.let(::StringValue)
    VariableType.BOOLEAN -> text.toBooleanStrictOrNull()?.let(::BooleanValue)
}

internal fun ComparisonOperator.displayName(): String = when (this) {
    ComparisonOperator.EQUALS -> "equals"
    ComparisonOperator.NOT_EQUALS -> "does not equal"
    ComparisonOperator.LESS_THAN -> "is less than"
    ComparisonOperator.LESS_THAN_OR_EQUAL -> "is at most"
    ComparisonOperator.GREATER_THAN -> "is greater than"
    ComparisonOperator.GREATER_THAN_OR_EQUAL -> "is at least"
}

internal fun Operand.displayName(): String = when (this) {
    is com.veyarc.model.ImageResultOperand -> "${name.value} ${if (field == com.veyarc.model.ImageResultField.FOUND) "was found" else "score"}"
    is VariableOperand -> name.value
    is LiteralOperand -> if (value is StringValue) "\"${value.editorText()}\"" else value.editorText()
}

internal fun ArithmeticOperator.displayName(): String = when (this) {
    ArithmeticOperator.ADD -> "+"
    ArithmeticOperator.SUBTRACT -> "−"
    ArithmeticOperator.MULTIPLY -> "×"
    ArithmeticOperator.DIVIDE -> "÷"
    ArithmeticOperator.REMAINDER -> "%"
}

internal fun SetVariable.assignmentSummary(): String = buildString {
    append("${name.value} = ${source?.displayName() ?: LiteralOperand(value).displayName()}")
    calculation?.let { append(" ${it.operator.displayName()} ${it.operand.displayName()}") }
}

internal fun WorkflowDraft.usedVariableNames(): Set<String> = buildSet {
    fun operand(value: Operand?) {
        if (value is VariableOperand) add(value.name.value)
    }
    fun condition(value: Condition) {
        when (value) {
            is VariableComparisonCondition -> listOf(value.left, value.right).filterIsInstance<VariableOperand>().forEach { add(it.name.value) }
            is AndCondition -> value.conditions.forEach(::condition)
            is OrCondition -> value.conditions.forEach(::condition)
            is NotCondition -> condition(value.condition)
            else -> Unit
        }
    }
    fun visit(items: List<WorkflowStep>) {
        items.forEach { step ->
            val position = when (step) {
                is com.veyarc.model.TapPoint -> step.imagePosition
                is com.veyarc.model.LongPress -> step.imagePosition
                else -> null
            }
            position?.offsetXVariable?.let { add(it.value) }
            position?.offsetYVariable?.let { add(it.value) }
            when (step) {
                is SetVariable -> {
                    add(step.name.value)
                    operand(step.source)
                    operand(step.calculation?.operand)
                }

                is Wait -> step.durationVariable?.let { add(it.value) }

                is com.veyarc.model.LongPress -> step.durationVariable?.let { add(it.value) }

                is WaitUntil -> condition(step.condition)

                is IfElse -> {
                    condition(step.condition)
                    visit(step.thenSteps)
                    visit(step.elseSteps)
                }

                is Repeat -> {
                    step.countVariable?.let { add(it.value) }
                    visit(step.bodySteps)
                }

                is WhileStep -> {
                    condition(step.condition)
                    visit(step.bodySteps)
                }

                else -> Unit
            }
        }
    }
    visit(steps)
}

internal fun WorkflowDraft.saveVariable(originalName: String?, name: String, declaration: VariableDeclaration): WorkflowDraft {
    val checkedName = VariableName(name)
    require(name == originalName || name !in variables) { "This name is already used" }
    val previous = originalName?.let { requireNotNull(variables[it]) }
    require(previous == null || previous.type == declaration.type || originalName !in usedVariableNames()) { "Change the blocks using this variable before changing its type" }
    val updated = if (originalName == null) {
        variables + (name to declaration)
    } else {
        variables.entries.associate { (key, value) -> if (key == originalName) name to declaration else key to value }
    }
    return copy(
        variables = updated,
        steps = if (originalName == null || originalName == name) steps else steps.mapVariableNames { if (it.value == originalName) checkedName else it },
    )
}

internal fun WorkflowDraft.removeVariable(name: String): WorkflowDraft {
    require(name !in usedVariableNames()) { "This variable is still used by workflow blocks" }
    return copy(variables = variables - name)
}

private fun List<WorkflowStep>.mapVariableNames(transform: (VariableName) -> VariableName): List<WorkflowStep> {
    fun position(value: com.veyarc.model.ImagePositionBinding?) = value?.copy(offsetXVariable = value.offsetXVariable?.let(transform), offsetYVariable = value.offsetYVariable?.let(transform))
    fun operand(value: Operand): Operand = when (value) {
        is VariableOperand -> value.copy(name = transform(value.name))
        is com.veyarc.model.ImageResultOperand -> value
        is LiteralOperand -> value
    }
    fun condition(value: Condition): Condition = when (value) {
        is VariableComparisonCondition -> value.copy(left = operand(value.left), right = operand(value.right))
        is AndCondition -> value.copy(conditions = value.conditions.map(::condition))
        is OrCondition -> value.copy(conditions = value.conditions.map(::condition))
        is NotCondition -> value.copy(condition = condition(value.condition))
        else -> value
    }
    return map { step ->
        when (step) {
            is com.veyarc.model.TapPoint -> step.copy(imagePosition = position(step.imagePosition))
            is com.veyarc.model.LongPress -> step.copy(durationVariable = step.durationVariable?.let(transform), imagePosition = position(step.imagePosition))
            is SetVariable -> step.copy(name = transform(step.name), source = step.source?.let(::operand), calculation = step.calculation?.let { it.copy(operand = operand(it.operand)) })
            is Wait -> step.copy(durationVariable = step.durationVariable?.let(transform))
            is com.veyarc.model.FindImage -> step.copy(condition = condition(step.condition) as com.veyarc.model.ImagePresentCondition)
            is WaitUntil -> step.copy(condition = condition(step.condition))
            is IfElse -> step.copy(condition = condition(step.condition), thenSteps = step.thenSteps.mapVariableNames(transform), elseSteps = step.elseSteps.mapVariableNames(transform))
            is Repeat -> step.copy(bodySteps = step.bodySteps.mapVariableNames(transform), countVariable = step.countVariable?.let(transform))
            is WhileStep -> step.copy(condition = condition(step.condition), bodySteps = step.bodySteps.mapVariableNames(transform))
            else -> step
        }
    }
}

internal fun WorkflowDraft.renameCapturedImage(previous: VariableName, name: VariableName): WorkflowDraft {
    fun condition(value: Condition): Condition = when (value) {
        is CapturedImageCondition -> if (value.name == previous) value.copy(name = name) else value
        is com.veyarc.model.ImagePresentCondition -> value.copy(capturedImage = if (value.capturedImage == previous) name else value.capturedImage, capturedBackground = if (value.capturedBackground == previous) name else value.capturedBackground)
        is com.veyarc.model.ImageAbsentCondition -> value.copy(capturedImage = if (value.capturedImage == previous) name else value.capturedImage, capturedBackground = if (value.capturedBackground == previous) name else value.capturedBackground)
        is com.veyarc.model.PixelColorCondition -> value.copy(capturedBackground = if (value.capturedBackground == previous) name else value.capturedBackground)
        is AndCondition -> value.copy(conditions = value.conditions.map(::condition))
        is OrCondition -> value.copy(conditions = value.conditions.map(::condition))
        is NotCondition -> value.copy(condition = condition(value.condition))
        else -> value
    }
    fun update(items: List<WorkflowStep>): List<WorkflowStep> = items.map { step ->
        when (step) {
            is CaptureImage -> if (step.name == previous) step.copy(name = name) else step
            is com.veyarc.model.FindImage -> step.copy(condition = condition(step.condition) as com.veyarc.model.ImagePresentCondition)
            is WaitUntil -> step.copy(condition = condition(step.condition))
            is IfElse -> step.copy(condition = condition(step.condition), thenSteps = update(step.thenSteps), elseSteps = update(step.elseSteps))
            is Repeat -> step.copy(bodySteps = update(step.bodySteps))
            is WhileStep -> step.copy(condition = condition(step.condition), bodySteps = update(step.bodySteps))
            else -> step
        }
    }
    return copy(steps = update(steps))
}

internal fun WorkflowDraft.renameImageSearch(previous: VariableName, name: VariableName): WorkflowDraft {
    fun operand(value: Operand): Operand = if (value is com.veyarc.model.ImageResultOperand && value.name == previous) value.copy(name = name) else value
    fun condition(value: Condition): Condition = when (value) {
        is VariableComparisonCondition -> value.copy(left = operand(value.left), right = operand(value.right))
        is AndCondition -> value.copy(conditions = value.conditions.map(::condition))
        is OrCondition -> value.copy(conditions = value.conditions.map(::condition))
        is NotCondition -> value.copy(condition = condition(value.condition))
        else -> value
    }
    fun update(items: List<WorkflowStep>): List<WorkflowStep> = items.map { step ->
        when (step) {
            is com.veyarc.model.FindImage -> if (step.name == previous) step.copy(name = name) else step
            is com.veyarc.model.TapPoint -> step.copy(imagePosition = step.imagePosition?.let { if (it.name == previous) it.copy(name = name) else it })
            is com.veyarc.model.LongPress -> step.copy(imagePosition = step.imagePosition?.let { if (it.name == previous) it.copy(name = name) else it })
            is SetVariable -> step.copy(source = step.source?.let(::operand), calculation = step.calculation?.let { it.copy(operand = operand(it.operand)) })
            is WaitUntil -> step.copy(condition = condition(step.condition))
            is IfElse -> step.copy(condition = condition(step.condition), thenSteps = update(step.thenSteps), elseSteps = update(step.elseSteps))
            is Repeat -> step.copy(bodySteps = update(step.bodySteps))
            is WhileStep -> step.copy(condition = condition(step.condition), bodySteps = update(step.bodySteps))
            else -> step
        }
    }
    return copy(steps = update(steps))
}

internal fun imageResultFields(type: VariableType?): List<com.veyarc.model.ImageResultField> = com.veyarc.model.ImageResultField.entries.filter {
    type == null || type == if (it == com.veyarc.model.ImageResultField.FOUND) VariableType.BOOLEAN else VariableType.NUMBER
}

internal fun com.veyarc.model.ImageResultField.displayName(): String = when (this) {
    com.veyarc.model.ImageResultField.FOUND -> "Found"
    com.veyarc.model.ImageResultField.SCORE -> "Score"
}

internal fun VariableComparisonCondition.comparisonText(): String {
    val found = left as? com.veyarc.model.ImageResultOperand
    val expected = (right as? LiteralOperand)?.value as? BooleanValue
    if (found?.field == com.veyarc.model.ImageResultField.FOUND && expected != null && (operator == ComparisonOperator.EQUALS || operator == ComparisonOperator.NOT_EQUALS)) {
        val wantsFound = expected.value == (operator == ComparisonOperator.EQUALS)
        return "${found.name.value} was ${if (wantsFound) "" else "not "}found"
    }
    return "${left.displayName()} ${operator.displayName()} ${right.displayName()}"
}

internal fun numberValueChoices(variables: Map<String, VariableDeclaration>): List<VeyarcChoice> = listOf(VeyarcChoice("fixed", "Fixed number", groupTitle = "Value")) +
    variables.filterValues { it.type == VariableType.NUMBER }.map { VeyarcChoice("variable:${it.key}", it.key, group = 1, groupTitle = "Variables") }
