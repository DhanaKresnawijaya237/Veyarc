package com.veyarc.feature.assistant

import com.veyarc.model.WorkflowJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Legacy format fields remain local for existing revisions, never in the model's contract. */
internal object AssistantWireWorkflow {
    private val obsolete = setOf("maxTotalSteps", "maxRuntimeMs", "maxStepDurationMs")

    fun snapshot(value: AssistantContextSnapshot): JsonObject {
        val fields = WorkflowJson.format.parseToJsonElement(value.json).jsonObject
        return JsonObject(fields + ("runLimits" to JsonObject(fields.getValue("runLimits").jsonObject.filterKeys { it !in obsolete })))
    }

    fun schema(json: String): String {
        val root = WorkflowJson.format.parseToJsonElement(json).jsonObject
        val defs = root.getValue("\$defs").jsonObject
        val limits = defs.getValue("runLimits").jsonObject
        val cleaned = JsonObject(
            limits + mapOf(
                "properties" to JsonObject(limits.getValue("properties").jsonObject.filterKeys { it !in obsolete }),
                "required" to JsonArray((limits.getValue("required") as JsonArray).filter { it.jsonPrimitive.content !in obsolete }),
            ),
        )
        return JsonObject(root + ("\$defs" to JsonObject(defs + ("runLimits" to cleaned)))).toString()
    }

    fun restoreLocalFields(base: AssistantContextSnapshot?, candidate: JsonObject): JsonObject {
        if (base == null) return candidate
        val original = WorkflowJson.format.parseToJsonElement(base.json).jsonObject.getValue("runLimits").jsonObject
        val limits = candidate["runLimits"] as? JsonObject ?: return candidate
        return JsonObject(candidate + ("runLimits" to JsonObject(limits.filterKeys { it !in obsolete } + original.filterKeys { it in obsolete })))
    }
}
