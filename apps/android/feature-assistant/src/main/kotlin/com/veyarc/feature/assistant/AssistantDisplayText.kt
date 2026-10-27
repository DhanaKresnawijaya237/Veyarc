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
        putAll(assistantStepNames(root["steps"] as? JsonArray))
        (root["assets"] as? JsonArray)?.forEachIndexed { index, item ->
            val asset = item.jsonObject
            put(asset.getValue("assetId").jsonPrimitive.content, asset["displayName"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content ?: "Image ${index + 1}")
        }
    }
}.getOrDefault(emptyMap())

private fun candidateNames(json: String?): Map<String, String> = runCatching {
    json?.let { assistantStepNames(WorkflowJson.format.parseToJsonElement(it).jsonObject["steps"] as? JsonArray) } ?: emptyMap()
}.getOrDefault(emptyMap())

internal fun assistantStepNames(steps: JsonArray?): Map<String, String> = buildMap {
    fun visit(items: JsonArray?, prefix: String) {
        var blockNumber = 0
        var stageNumber = 0
        items?.forEach { item ->
            val block = item.jsonObject
            val type = block.getValue("type").jsonPrimitive.content
            if (type == "section") {
                val name = block["name"]?.jsonPrimitive?.content ?: "Saved blocks"
                put(block.getValue("id").jsonPrimitive.content, "Saved blocks: $name")
                visit(block["bodySteps"] as? JsonArray, "$name / ")
                return@forEach
            }
            val label = if (type == "stageBoundary") "Stage ${++stageNumber}" else "Step ${++blockNumber}: ${friendlyField(type)}"
            put(block.getValue("id").jsonPrimitive.content, prefix + label)
            listOf(
                "bodySteps" to when (type) {
                    "while" -> "while"
                    "section" -> "section"
                    else -> "repeat"
                },
                "thenSteps" to "true",
                "elseSteps" to "false",
            ).forEach { (field, branch) ->
                visit(block[field] as? JsonArray, "${prefix}Step $blockNumber $branch / ")
            }
        }
    }
    visit(steps, "")
}

private fun readableValue(value: JsonElement, names: Map<String, String>, field: String): String = when (value) {
    JsonNull -> "None"
    is JsonPrimitive -> names[value.content] ?: if (field.endsWith("Ms") && !value.isString) "${value.content} ms" else value.content
    is JsonArray -> value.joinToString("\n") { readableValue(it, names, field) }.ifEmpty { "None" }
    is JsonObject -> value.entries.joinToString("\n") { (key, item) -> "${friendlyField(key)}: ${readableValue(item, names, key)}" }.ifEmpty { "None" }
}

private fun friendlyField(value: String): String = when (value) {
    "durationMs" -> "Duration"
    "timeoutMs" -> "Timeout"
    "pollIntervalMs" -> "Check interval"
    "foregroundTimeoutMs" -> "App wait limit"
    "bodySteps" -> "Actions"
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
    "breakLoop" -> "Leave loop"
    "continueLoop" -> "Next repeat"
    "returnSection" -> "Finish sequence"
    "stop" -> "Stop"
    "stageBoundary" -> "Stage boundary"
    "section" -> "Saved blocks"
    "runSection" -> "Run saved blocks"
    "sectionId" -> "Saved blocks"
    "startStepId" -> "From"
    "endStepId" -> "To"
    else -> value.replace(Regex("([a-z])([A-Z])"), "$1 $2").replaceFirstChar(Char::uppercaseChar)
}
