package com.veyarc.app

import com.veyarc.data.storage.ChatAttachmentStore
import com.veyarc.data.storage.WorkflowRepository
import com.veyarc.engine.ConditionObservation
import com.veyarc.feature.assistant.AssistantAttachment
import com.veyarc.feature.assistant.AssistantAttachmentImporter
import com.veyarc.feature.assistant.AssistantCandidateValidator
import com.veyarc.feature.assistant.AssistantContextCapture
import com.veyarc.feature.assistant.AssistantContextSnapshot
import com.veyarc.feature.assistant.AssistantImageAddition
import com.veyarc.feature.assistant.AssistantImageCheckCall
import com.veyarc.feature.assistant.AssistantImageCheckResult
import com.veyarc.feature.assistant.AssistantImagePurpose
import com.veyarc.feature.assistant.AttachmentKind
import com.veyarc.feature.editor.WorkflowDraft
import com.veyarc.feature.editor.WorkflowEditorSession
import com.veyarc.model.AndCondition
import com.veyarc.model.AssetId
import com.veyarc.model.IfElse
import com.veyarc.model.ImageAbsentCondition
import com.veyarc.model.ImagePresentCondition
import com.veyarc.model.NotCondition
import com.veyarc.model.OrCondition
import com.veyarc.model.Repeat
import com.veyarc.model.WaitUntil
import com.veyarc.model.WhileStep
import com.veyarc.model.WorkflowJson
import com.veyarc.model.WorkflowStep
import com.veyarc.model.backgroundAssetId
import com.veyarc.model.bundle.BundleAsset
import com.veyarc.model.capturedBackgroundName
import com.veyarc.model.capturedImageName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/** App-owned bridge; only reviewed candidates reach the revision saver. */
internal class AssistantDraftBridge(
    private val editor: WorkflowEditorSession,
    private val save: suspend (WorkflowDraft, WorkflowDraft) -> Int,
) {
    private var observed: WorkflowDraft? = null
    private var binding: String? = null

    fun capture(): AssistantContextCapture {
        if (editor.isBusy) return AssistantContextCapture(error = "Wait for the editor to finish saving or pasting before requesting or applying changes.")
        val current = editor.draft
        if (current !== observed) {
            val onlyFolding = current != observed && current?.copy(collapsedStepIds = observed?.collapsedStepIds.orEmpty()) == observed
            if (!onlyFolding) binding = current?.let { UUID.randomUUID().toString() }
            observed = current
        }
        return current.captureAssistantContext().copy(localBindingId = binding)
    }

    suspend fun testImage(localBindingId: String, base: AssistantContextSnapshot, call: AssistantImageCheckCall, sources: List<AssistantAttachment>, repository: WorkflowRepository, importer: AssistantAttachmentImporter): AssistantImageCheckResult {
        val label = com.veyarc.feature.assistant.imageCheckBlockLabel(base, call.stepId)
        try {
            call.validate()
            val captured = capture()
            require(captured.localBindingId == localBindingId && captured.snapshot == base && captured.error == null) { "The workflow changed. Send a new request to test its current settings." }
            val draft = requireNotNull(editor.draft) { "Open the workflow first." }
            fun find(steps: List<WorkflowStep>): WorkflowStep? {
                for (step in steps) {
                    if (step.id == call.stepId) return step
                    val children = when (step) {
                        is IfElse -> step.thenSteps + step.elseSteps
                        is com.veyarc.model.ReusableSection -> step.bodySteps
                        is Repeat -> step.bodySteps
                        is WhileStep -> step.bodySteps
                        else -> emptyList()
                    }
                    find(children)?.let { return it }
                }
                return null
            }
            var condition = when (val step = find(draft.steps)) {
                is IfElse -> step.condition
                is WhileStep -> step.condition
                is WaitUntil -> step.condition
                else -> throw IllegalArgumentException("That block has no image condition.")
            }
            call.conditionPath.forEach { index ->
                condition = when (val parent = condition) {
                    is AndCondition -> parent.conditions.getOrNull(index)
                    is OrCondition -> parent.conditions.getOrNull(index)
                    is NotCondition -> parent.condition.takeIf { index == 0 }
                    else -> null
                } ?: throw IllegalArgumentException("That nested condition does not exist.")
            }
            val targetId = when (val selected = condition) {
                is ImagePresentCondition -> selected.assetId
                is ImageAbsentCondition -> selected.assetId
                else -> throw IllegalArgumentException("Select one image condition inside that block.")
            }
            suspend fun asset(id: AssetId): BundleAsset {
                require(draft.assetDescriptors.any { it.assetId == id }) { "This image isn't in the captured workflow." }
                return draft.assets.firstOrNull { it.assetId == id } ?: withContext(Dispatchers.IO) {
                    repository.loadRevisionAsset(draft.workflowId, requireNotNull(draft.lastSavedRevision), id)
                }
            }
            require(condition.capturedImageName() == null && condition.capturedBackgroundName() == null) { "Captured images are available only during a workflow run. Use a saved image for a chat image test." }
            val target = asset(requireNotNull(targetId) { "Choose a target image first." })
            val viewport = draft.viewport
            val result: ConditionObservation
            val name: String
            if (call.sourceAttachmentId != null) {
                val attachment = sources.firstOrNull { it.id == call.sourceAttachmentId && it.kind == AttachmentKind.IMAGE }
                    ?: throw IllegalArgumentException("Choose an image already attached to this chat.")
                name = attachment.name
                val bitmap = withContext(Dispatchers.IO) { importer.bitmap(attachment) }
                try {
                    result = com.veyarc.feature.editor.testImageCondition(condition, target, bitmap, viewport.recordedWidthPx, viewport.recordedHeightPx)
                } finally {
                    bitmap.recycle()
                }
            } else {
                val backgroundId = call.backgroundAssetId?.let(::AssetId) ?: condition.backgroundAssetId()
                    ?: throw IllegalArgumentException("Choose a saved background or attach an original screenshot.")
                name = draft.assetNames[backgroundId.value] ?: "Saved background"
                result = com.veyarc.feature.editor.testImageCondition(condition, target, asset(backgroundId), viewport.recordedWidthPx, viewport.recordedHeightPx)
            }
            require(capture() == captured) { "The workflow changed during the test. Send a new request to test its current settings." }
            return AssistantImageCheckResult(label, name, result.matchScore, result.threshold, result.result)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return AssistantImageCheckResult(label, error = (error as? IllegalArgumentException)?.message ?: "Could not read or compare the selected images.")
        }
    }

    suspend fun apply(localBindingId: String, base: AssistantContextSnapshot, candidateJson: String): Int? {
        val current = editor.draft ?: return null
        val captured = capture()
        if (captured.localBindingId != localBindingId || captured.snapshot != base || captured.error != null) return null
        val checked = AssistantCandidateValidator.validate(base, candidateJson, ::assistantCompilationIssues)
        if (!checked.isValid) return null
        val candidate = WorkflowJson.decode(requireNotNull(checked.candidateJson))
        // Copy only the approved fields. Keep unloaded assets, journals, revision metadata and UI state.
        val updated = current.copy(name = if (candidate.name == current.name.trim()) current.name else candidate.name, steps = candidate.steps, variables = candidate.variables)
        if (updated == current) return null
        return save(current, updated)
    }

    suspend fun applyImages(localBindingId: String, base: AssistantContextSnapshot, candidateJson: String, additions: List<AssistantImageAddition>, sources: List<AssistantAttachment>, store: ChatAttachmentStore): Int? {
        val captured = capture()
        if (captured.localBindingId != localBindingId || captured.snapshot != base || captured.error != null) return null
        val checked = AssistantCandidateValidator.validate(base, candidateJson, ::assistantCompilationIssues, additions, sources)
        if (!checked.isValid) return null
        val prepared = withContext(Dispatchers.IO) {
            additions.map { addition -> com.veyarc.feature.editor.importAssistantTemplate(store.read(addition.sourceAttachmentId), AssetId(addition.assetId), addition.crop, addition.purpose == AssistantImagePurpose.BACKGROUND) }
        }
        // Recheck after image preparation. No partial imports or stale editor mutations.
        if (capture() != captured) return null
        val current = editor.draft ?: return null
        var updated = current
        val candidate = WorkflowJson.decode(candidateJson)
        prepared.zip(additions).forEach { (asset, addition) -> updated = updated.addAsset(asset).renameAsset(asset.assetId, addition.name) }
        updated = updated.copy(name = if (candidate.name == updated.name.trim()) updated.name else candidate.name, steps = candidate.steps, variables = candidate.variables)
        if (updated.buildBundle().issues.isNotEmpty()) return null
        return save(current, updated)
    }
}
