package com.veyarc.feature.assistant

import com.veyarc.model.IfElse
import com.veyarc.model.PackageName
import com.veyarc.model.Repeat
import com.veyarc.model.WhileStep
import com.veyarc.model.WorkflowDocument
import com.veyarc.model.WorkflowJson
import com.veyarc.model.WorkflowStep
import com.veyarc.model.WorkflowValidator
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

@Serializable
data class AssistantProposalCheck(val candidateJson: String? = null, val issues: List<String> = emptyList(), val imageAdditions: List<AssistantImageAddition> = emptyList()) {
    val isValid: Boolean get() = candidateJson != null && issues.isEmpty()
}

/** Pure validation. The injected compiler returns diagnostics only, never an execution plan. */
object AssistantCandidateValidator {
    const val MAX_CANDIDATE_BYTES = AssistantContextSnapshot.MAX_BYTES
    const val CAPABILITY_NOTICE = "Device capabilities have not been checked. Passing local validation does not mean this device can run the workflow."

    fun validate(snapshot: AssistantContextSnapshot?, candidateJson: String, compileIssues: ((WorkflowDocument) -> List<String>)?): AssistantProposalCheck = validate(snapshot, candidateJson, compileIssues, emptyList(), emptyList())

    fun validate(
        snapshot: AssistantContextSnapshot?,
        candidateJson: String,
        compileIssues: ((WorkflowDocument) -> List<String>)?,
        imageAdditions: List<AssistantImageAddition>,
        availableImages: List<AssistantAttachment>,
    ): AssistantProposalCheck {
        if (snapshot == null) return invalid("A valid captured workflow is required. Supply the missing workflow details first.")
        if (compileIssues == null) return invalid("Local compiler validation is unavailable.")
        val base = try {
            baseDraft(snapshot).also {
                expectedRevision(it)
                allowedPackages(it)
            }
        } catch (_: Exception) {
            return invalid("The workflow context could not be read. Reopen the workflow and try again.")
        }
        val candidate = try {
            require(candidateJson.toByteArray(Charsets.UTF_8).size <= MAX_CANDIDATE_BYTES)
            WorkflowJson.checkNesting(candidateJson, 64)
            AssistantResponseCodec.rejectDuplicateKeys(candidateJson)
            WorkflowJson.decode(candidateJson).also { document ->
                checkBounds(document)
                checkPrimitiveTypes(WorkflowJson.format.parseToJsonElement(candidateJson), WorkflowJson.format.encodeToJsonElement(WorkflowDocument.serializer(), document))
            }
        } catch (_: Exception) {
            return invalid("The proposed workflow has an invalid format or is too large to review safely.")
        }
        val imageReferences = try {
            require(imageAdditions.size <= 64 && imageAdditions.map { it.assetId }.distinct().size == imageAdditions.size)
            imageAdditions.forEach { addition ->
                addition.validate()
                val source = requireNotNull(availableImages.firstOrNull { it.id == addition.sourceAttachmentId && it.kind == AttachmentKind.IMAGE })
                source.validate()
                if (addition.purpose == AssistantImagePurpose.TARGET) {
                    val width = kotlin.math.ceil((addition.crop.x + addition.crop.width) * source.width).toLong() - kotlin.math.floor(addition.crop.x * source.width).toLong()
                    val height = kotlin.math.ceil((addition.crop.y + addition.crop.height) * source.height).toLong() - kotlin.math.floor(addition.crop.y * source.height).toLong()
                    require(width * height <= com.veyarc.data.storage.DecodedTemplate.MAX_TEMPLATE_PIXELS)
                }
                require(base.assets.none { it.assetId.value == addition.assetId })
                require(base.assets.none { it.displayName?.equals(addition.name, ignoreCase = true) == true })
            }
            require(imageAdditions.map { it.name.lowercase() }.distinct().size == imageAdditions.size)
            imageAdditions.map(AssistantImageAddition::reference)
        } catch (_: Exception) {
            return invalid("The proposed image must come from a photo you attached, with a unique name and a valid crop.")
        }
        val locked = buildList {
            if (candidate.id != base.workflowId) add("workflow identity")
            if (candidate.schemaVersion != base.schemaVersion) add("schema version")
            if (candidate.revision != expectedRevision(base)) add("revision")
            if (candidate.viewport != base.viewport) add("viewport and orientation")
            if (candidate.runLimits != base.runLimits) add("run limits")
            if (candidate.allowedPackages.sortedBy { it.value } != allowedPackages(base).sortedBy { it.value }) add("allowed packages")
            if (candidate.assets.sortedBy { it.assetId.value } != (base.assets + imageReferences).sortedBy { it.assetId.value }) add("image catalog")
        }
        if (locked.isNotEmpty()) return invalid("Candidate changed locked fields: ${locked.joinToString()}. Change these in the editor first.")
        val modelIssues = WorkflowValidator.validate(candidate)
        if (modelIssues.isNotEmpty()) return diagnostics(modelIssues.map { "${it.path}: ${it.code}" })
        val before = flatten(base.steps)
        val after = flatten(candidate.steps)
        val afterIds = after.mapTo(mutableSetOf()) { it.id }
        val beforeIds = before.mapTo(mutableSetOf()) { it.id }
        // ponytail: at most 200 blocks; replace the scan with a map if the context limit grows.
        if (before.filter { it.id !in afterIds }.any { old -> after.any { it.id !in beforeIds && body(it) == body(old) } }) {
            return invalid("An unchanged block received a new ID. Preserve existing block IDs.")
        }
        return try {
            val errors = compileIssues(candidate)
            if (errors.isNotEmpty()) {
                diagnostics(errors)
            } else {
                val canonical = WorkflowJson.encode(candidate)
                if (canonical.toByteArray(Charsets.UTF_8).size > MAX_CANDIDATE_BYTES) invalid("The proposed workflow is too large to review safely.") else AssistantProposalCheck(candidateJson = canonical, imageAdditions = imageAdditions)
            }
        } catch (_: Exception) {
            invalid("The proposed workflow could not be validated. Try asking again.")
        }
    }

    internal fun baseDraft(snapshot: AssistantContextSnapshot): AssistantDraftContext {
        snapshot.validate()
        return WorkflowJson.decodeStrict(AssistantDraftContext.serializer(), snapshot.json)
    }

    internal fun expectedRevision(draft: AssistantDraftContext): Int = Math.addExact(maxOf(draft.lastSavedRevision ?: 0, draft.latestKnownRevision ?: 0), 1)
    private fun allowedPackages(draft: AssistantDraftContext): List<PackageName> = draft.allowedPackagesText.split(',', '\n', ' ', '\t').map(String::trim).filter(String::isNotEmpty).distinct().map(::PackageName)

    internal fun baseDocument(snapshot: AssistantContextSnapshot): WorkflowDocument {
        val draft = baseDraft(snapshot)
        return WorkflowDocument(
            draft.schemaVersion, draft.workflowId, expectedRevision(draft), draft.name.trim(), allowedPackages(draft),
            draft.viewport, draft.runLimits, draft.variables, draft.assets, draft.steps,
        )
    }

    private fun checkPrimitiveTypes(input: JsonElement, decoded: JsonElement) {
        when (input) {
            is JsonObject -> input.forEach { (key, value) -> checkPrimitiveTypes(value, decoded.jsonObject.getValue(key)) }
            is JsonArray -> input.forEachIndexed { index, value -> checkPrimitiveTypes(value, (decoded as JsonArray)[index]) }
            is JsonPrimitive -> require(decoded is JsonPrimitive && input.isString == decoded.isString)
        }
    }

    private fun checkBounds(document: WorkflowDocument) = AssistantContextSnapshot.checkBounds(
        AssistantDraftContext(document.id, document.revision, document.revision, document.name, document.allowedPackages.joinToString { it.value }, document.viewport, document.runLimits, document.variables, document.assets, document.steps, document.schemaVersion),
    )

    private fun flatten(steps: List<WorkflowStep>): List<WorkflowStep> = steps.flatMap { listOf(it) + flatten(children(it)) }
    private fun children(step: WorkflowStep): List<WorkflowStep> = when (step) {
        is Repeat -> step.bodySteps
        is WhileStep -> step.bodySteps
        is IfElse -> step.thenSteps + step.elseSteps
        else -> emptyList()
    }
    private fun body(step: WorkflowStep) = WorkflowJson.format.encodeToJsonElement(WorkflowStep.serializer(), step).jsonObject - "id"

    private fun invalid(message: String) = AssistantProposalCheck(issues = listOf(message))
    private fun diagnostics(messages: List<String>) = AssistantProposalCheck(issues = messages.take(20).map { it.take(300) })
}
