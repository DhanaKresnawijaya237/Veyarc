package com.veyarc.feature.assistant

import com.veyarc.model.WorkflowJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class AssistantReviewStatus(val label: String) {
    READY("Ready for review"),
    INVALID("Invalid proposal"),
    STALE("Stale proposal"),
    DISMISSED("Dismissed proposal"),
}

data class AssistantValueChange(val label: String, val before: String?, val after: String?)

/** Derived locally, never persisted or supplied by the provider. No mutation or execution capability. */
object AssistantProposalReview {
    fun status(base: AssistantContextSnapshot?, current: AssistantContextCapture, proposal: AssistantProposalCheck, dismissed: Boolean): AssistantReviewStatus = when {
        dismissed -> AssistantReviewStatus.DISMISSED
        !proposal.isValid || base == null -> AssistantReviewStatus.INVALID
        current.error != null || current.snapshot == null || base != current.snapshot -> AssistantReviewStatus.STALE
        else -> AssistantReviewStatus.READY
    }

    fun changes(base: AssistantContextSnapshot, proposal: AssistantProposalCheck): List<AssistantValueChange> {
        require(proposal.isValid)
        val draft = AssistantCandidateValidator.baseDraft(base)
        val before = WorkflowJson.format.parseToJsonElement(WorkflowJson.encodeCanonical(AssistantDraftContext.serializer(), draft.copy(name = draft.name.trim()))).jsonObject
        val after = WorkflowJson.format.parseToJsonElement(requireNotNull(proposal.candidateJson)).jsonObject
        return buildList {
            values("Workflow name", before["name"], after["name"], this)
            values("Variables", before["variables"], after["variables"], this)
            proposal.imageAdditions.forEach { addition ->
                val crop = addition.crop
                add(AssistantValueChange(if (addition.purpose == AssistantImagePurpose.BACKGROUND) "Background image" else "Cropped image", null, "${addition.name} · ${if (addition.purpose == AssistantImagePurpose.BACKGROUND) "Full image" else "Crop ${(crop.width * 100).toInt()}% × ${(crop.height * 100).toInt()}%"}"))
            }
            val old = blocks(before.getValue("steps") as JsonArray)
            val new = blocks(after.getValue("steps") as JsonArray)
            val common = old.keys intersect new.keys
            for (id in old.keys + new.keys) {
                val a = old[id]
                val b = new[id]
                when {
                    a == null -> add(AssistantValueChange("Added block $id at ${b!!.path}", null, b.fields.toString()))

                    b == null -> add(AssistantValueChange("Removed block $id at ${a.path}", a.fields.toString(), null))

                    else -> {
                        // Ignore index shifts caused only by added/removed siblings.
                        // ponytail: scan at most 200 blocks; group sibling orders if this bound grows.
                        val sameList = common.filter { old.getValue(it).parent == a.parent && new.getValue(it).parent == b.parent }.toSet()
                        val oldOrder = old.keys.filter { it in sameList }
                        val newOrder = new.keys.filter { it in sameList }
                        if (a.parent != b.parent || (a.index != b.index && oldOrder.indexOf(id) != newOrder.indexOf(id))) {
                            add(AssistantValueChange("Moved block $id", a.path, b.path))
                        }
                        values("Block $id at ${b.path}", a.fields, b.fields, this)
                    }
                }
            }
        }
    }

    private data class Block(val parent: String, val path: String, val index: Int, val fields: JsonObject)
    private val children = setOf("bodySteps", "thenSteps", "elseSteps")

    private fun blocks(steps: JsonArray): Map<String, Block> = buildMap {
        fun visit(items: JsonArray, parent: String, path: String) {
            var blockNumber = 0
            var stageNumber = 0
            items.forEachIndexed { index, item ->
                val block = item.jsonObject
                val id = block.getValue("id").jsonPrimitive.content
                val position = if (block.getValue("type").jsonPrimitive.content == "stageBoundary") {
                    "Stage ${++stageNumber} ${if (blockNumber == 0) "at start" else "after step $blockNumber"}"
                } else {
                    (++blockNumber).toString()
                }
                val location = "$path / $position"
                put(id, Block(parent, location, index, JsonObject(block.filterKeys { it !in children && it != "id" })))
                children.forEach { field ->
                    (block[field] as? JsonArray)?.let { visit(it, "$id/$field", "$location / $field") }
                }
            }
        }
        visit(steps, "steps", "Steps")
    }

    private fun values(path: String, before: JsonElement?, after: JsonElement?, changes: MutableList<AssistantValueChange>) {
        if (before == after) return
        if (before is JsonObject && after is JsonObject) {
            (before.keys + after.keys).sorted().forEach { key -> values("$path / $key", before[key], after[key], changes) }
        } else {
            changes.add(AssistantValueChange(path, before?.toString(), after?.toString()))
        }
    }
}
