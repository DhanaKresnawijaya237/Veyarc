package com.veyarc.feature.assistant

import com.veyarc.model.WorkflowJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Display labels never participate in validation, request binding, or applying a candidate. */
internal fun readableReply(turn: PreviewTurn): String {
    if (Regex("\\b(ids?|hash(es)?|uuid|technical|json)\\b", RegexOption.IGNORE_CASE).containsMatchIn(turn.prompt)) return turn.reply
    val names = displayNames(turn.baseSnapshot) + turn.proposal?.imageAdditions.orEmpty().associate { it.assetId to it.name } + turn.attachments.associate { it.id to it.name } + mapOf(turn.id to "this message", turn.requestId to "this request")
    val prefixes = names.entries.filter { it.key.matches(Regex("[0-9a-f-]{36,64}")) }.groupBy { it.key.take(8) }
        .filterValues { it.size == 1 }.mapValues { it.value.single().value }
    return (names + prefixes).entries.sortedByDescending { it.key.length }.fold(turn.reply) { text, (id, label) ->
        val pattern = if (id.length >= 8) "(?<![A-Za-z0-9_.-])${Regex.escape(id)}(?![A-Za-z0-9_.-])" else "[`\"]${Regex.escape(id)}[`\"]"
        text.replace(Regex(pattern)) { label }
    }
}

internal fun readableChanges(base: AssistantContextSnapshot, proposal: AssistantProposalCheck): List<AssistantValueChange> {
    val names = displayNames(base) + candidateNames(proposal.candidateJson) + proposal.imageAdditions.associate { it.assetId to it.name }
    return AssistantProposalReview.changes(base, proposal).map { change ->
        val label = names.entries.fold(change.label) { text, (id, name) -> text.replace("block $id", name).replace("Block $id", name) }
            .split(" / ").joinToString(" · ") { friendlyField(it) }
        fun value(raw: String?): String? = raw?.let {
            val parsed = runCatching { WorkflowJson.format.parseToJsonElement(it) }.getOrNull()
            if (parsed == null) {
                it.replace("bodySteps", "repeat steps").replace("thenSteps", "if true").replace("elseSteps", "if false")
            } else {
                readableValue(parsed, names, change.label.substringAfterLast(" / "))
            }
        }
        AssistantValueChange(label, value(change.before), value(change.after))
    }
}

private fun displayNames(snapshot: AssistantContextSnapshot?): Map<String, String> = runCatching {
    if (snapshot == null) return@runCatching emptyMap()
    val root = WorkflowJson.format.parseToJsonElement(snapshot.json).jsonObject
    buildMap {
        put(snapshot.workflowId, "this workflow")
        put(snapshot.snapshotId, "the captured draft")
        putAll(stepNames(root["steps"] as? JsonArray))
        (root["assets"] as? JsonArray)?.forEachIndexed { index, item ->
            val asset = item.jsonObject
            put(asset.getValue("assetId").jsonPrimitive.content, asset["displayName"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content ?: "Image ${index + 1}")
        }
    }
}.getOrDefault(emptyMap())

private fun candidateNames(json: String?): Map<String, String> = runCatching {
    json?.let { stepNames(WorkflowJson.format.parseToJsonElement(it).jsonObject["steps"] as? JsonArray) } ?: emptyMap()
}.getOrDefault(emptyMap())

private fun stepNames(steps: JsonArray?): Map<String, String> = buildMap {
    fun visit(items: JsonArray?, prefix: String) {
        items?.forEachIndexed { index, item ->
            val block = item.jsonObject
            val position = "$prefix${index + 1}"
            put(block.getValue("id").jsonPrimitive.content, "Step $position: ${friendlyField(block.getValue("type").jsonPrimitive.content)}")
            listOf("bodySteps" to "repeat", "thenSteps" to "true", "elseSteps" to "false").forEach { (field, branch) ->
                visit(block[field] as? JsonArray, "$position $branch / ")
            }
        }
    }
    visit(steps, "")
}

private fun readableValue(value: JsonElement, names: Map<String, String>, field: String): String = when (value) {
    JsonNull -> "None"
    is JsonPrimitive -> names[value.content] ?: if (field.endsWith("Ms") && !value.isString) "${value.content} ms" else value.content
    is JsonArray -> value.joinToString("; ") { readableValue(it, names, field) }.ifEmpty { "None" }
    is JsonObject -> value.entries.joinToString("\n") { (key, item) -> "${friendlyField(key)}: ${readableValue(item, names, key)}" }.ifEmpty { "None" }
}

private fun friendlyField(value: String): String = when (value) {
    "durationMs" -> "Duration"
    "timeoutMs" -> "Timeout"
    "pollIntervalMs" -> "Check interval"
    "foregroundTimeoutMs" -> "App wait limit"
    "bodySteps" -> "Repeat steps"
    "thenSteps" -> "If true"
    "elseSteps" -> "If false"
    "tapPoint" -> "Tap"
    "tapRegion" -> "Tap area"
    "longPress" -> "Hold"
    "swipePath" -> "Swipe"
    "wait" -> "Wait"
    "launchApp" -> "Open app"
    "if" -> "If / else"
    "repeat" -> "Repeat"
    "while" -> "While"
    "waitUntil" -> "Wait until"
    "jump" -> "Go to"
    "stop" -> "Stop"
    "stageBoundary" -> "Stage boundary"
    else -> value.replace(Regex("([a-z])([A-Z])"), "$1 $2").replaceFirstChar(Char::uppercaseChar)
}
