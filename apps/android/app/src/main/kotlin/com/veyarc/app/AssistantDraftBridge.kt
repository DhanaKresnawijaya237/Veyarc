package com.veyarc.app

import com.veyarc.data.storage.ChatAttachmentStore
import com.veyarc.feature.assistant.AssistantAttachment
import com.veyarc.feature.assistant.AssistantCandidateValidator
import com.veyarc.feature.assistant.AssistantContextCapture
import com.veyarc.feature.assistant.AssistantContextSnapshot
import com.veyarc.feature.assistant.AssistantImageAddition
import com.veyarc.feature.assistant.AssistantImagePurpose
import com.veyarc.feature.editor.WorkflowDraft
import com.veyarc.feature.editor.WorkflowEditorSession
import com.veyarc.model.AssetId
import com.veyarc.model.WorkflowJson
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
        if (editor.isSaving) return AssistantContextCapture(error = "Wait for the editor to finish saving before requesting or applying changes.")
        val current = editor.draft
        if (current !== observed) {
            val onlyFolding = current != observed && current?.copy(collapsedStepIds = observed?.collapsedStepIds.orEmpty()) == observed
            if (!onlyFolding) binding = current?.let { UUID.randomUUID().toString() }
            observed = current
        }
        return current.captureAssistantContext().copy(localBindingId = binding)
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
