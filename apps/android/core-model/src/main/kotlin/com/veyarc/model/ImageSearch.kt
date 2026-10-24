package com.veyarc.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("findImage")
data class FindImage(override val id: String, val name: VariableName, val condition: Condition) : WorkflowStep {
    init {
        ContractChecks.requireStableId(id, "Step id")
        require(condition is ImagePresentCondition) { "Find image needs an image target" }
    }
    val image: ImagePresentCondition get() = condition as ImagePresentCondition
}

@Serializable
enum class ImageResultField {
    @SerialName("found")
    FOUND,

    @SerialName("score")
    SCORE,
}

@Serializable
@SerialName("imageResult")
data class ImageResultOperand(val name: VariableName, val field: ImageResultField) : Operand

fun List<WorkflowStep>.imageSearches(): Map<String, List<FindImage>> = buildMap<String, MutableList<FindImage>> {
    fun visit(items: List<WorkflowStep>) {
        items.forEach { step ->
            when (step) {
                is FindImage -> getOrPut(step.name.value) { mutableListOf() }.add(step)

                is IfElse -> {
                    visit(step.thenSteps)
                    visit(step.elseSteps)
                }

                is WhileStep -> visit(step.bodySteps)

                is Repeat -> visit(step.bodySteps)

                else -> Unit
            }
        }
    }
    visit(this@imageSearches)
}

@Serializable
data class ImagePositionBinding(
    val name: VariableName,
    val offsetXPx: Int = 0,
    val offsetYPx: Int = 0,
    val offsetXVariable: VariableName? = null,
    val offsetYVariable: VariableName? = null,
)

/** Results are available only after their search on this execution path. */
fun List<WorkflowStep>.imageSearchesByStep(): Map<String, Map<String, List<FindImage>>> = buildMap {
    fun visit(items: List<WorkflowStep>, inherited: Map<String, List<FindImage>>) {
        var available = inherited
        items.forEach { step ->
            put(step.id, available)
            val children = when (step) {
                is IfElse -> listOf(step.thenSteps, step.elseSteps)
                is Repeat -> listOf(step.bodySteps)
                is WhileStep -> listOf(step.bodySteps)
                else -> emptyList()
            }
            children.forEach { visit(it, available) }
            if (step is FindImage) {
                available = available + (step.name.value to listOf(step))
            } else if (children.isNotEmpty()) {
                // Conditional writes can replace an existing result, but cannot introduce one outside their branch.
                val overrides = children.flatten().imageSearches().filterKeys { it in available }
                if (overrides.isNotEmpty()) available = available + overrides.mapValues { (name, definitions) -> (available.getValue(name) + definitions).distinct() }
            }
        }
    }
    visit(this@imageSearchesByStep, emptyMap())
}

fun WorkflowStep.imageResultNames(): Set<VariableName> = buildSet {
    fun operand(value: Operand?) {
        if (value is ImageResultOperand) add(value.name)
    }
    fun condition(value: Condition) {
        when (value) {
            is VariableComparisonCondition -> {
                operand(value.left)
                operand(value.right)
            }

            is AndCondition -> value.conditions.forEach(::condition)

            is OrCondition -> value.conditions.forEach(::condition)

            is NotCondition -> condition(value.condition)

            else -> Unit
        }
    }
    when (val step = this@imageResultNames) {
        is TapPoint -> step.imagePosition?.let { add(it.name) }

        is LongPress -> step.imagePosition?.let { add(it.name) }

        is SetVariable -> {
            operand(step.source)
            operand(step.calculation?.operand)
        }

        is WaitUntil -> condition(step.condition)

        is IfElse -> condition(step.condition)

        is WhileStep -> condition(step.condition)

        else -> Unit
    }
}
