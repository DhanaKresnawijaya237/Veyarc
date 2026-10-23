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
