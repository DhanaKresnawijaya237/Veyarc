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

/** App-owned, main-thread bridge. It has no repository, save, recording, or replay access. */
internal class AssistantDraftBridge(private val editor: WorkflowEditorSession) {
    private var observed: WorkflowDraft? = null
    private var binding: String? = null

    fun capture(): AssistantContextCapture {
        if (editor.isSaving) return AssistantContextCapture(error = "Wait for the editor to finish saving before requesting or applying changes.")
        val current = editor.draft
        if (current !== observed) {
            observed = current
            binding = current?.let { UUID.randomUUID().toString() }
        }
        return current.captureAssistantContext().copy(localBindingId = binding)
    }

    fun apply(localBindingId: String, base: AssistantContextSnapshot, candidateJson: String): Boolean {
        val current = editor.draft ?: return false
        val captured = capture()
        if (captured.localBindingId != localBindingId || captured.snapshot != base || captured.error != null) return false
        val checked = AssistantCandidateValidator.validate(base, candidateJson, ::assistantCompilationIssues)
        if (!checked.isValid) return false
        val candidate = WorkflowJson.decode(requireNotNull(checked.candidateJson))
        // Copy only the approved fields. Keep unloaded assets, journals, revision metadata and UI state.
        val updated = current.copy(name = if (candidate.name == current.name.trim()) current.name else candidate.name, steps = candidate.steps, variables = candidate.variables)
        if (updated == current) return false
        editor.update(updated)
        return true
    }

    suspend fun applyImages(localBindingId: String, base: AssistantContextSnapshot, candidateJson: String, additions: List<AssistantImageAddition>, sources: List<AssistantAttachment>, store: ChatAttachmentStore): Boolean {
        val captured = capture()
        if (captured.localBindingId != localBindingId || captured.snapshot != base || captured.error != null) return false
        val checked = AssistantCandidateValidator.validate(base, candidateJson, ::assistantCompilationIssues, additions, sources)
        if (!checked.isValid) return false
        val prepared = withContext(Dispatchers.IO) {
            additions.map { addition -> com.veyarc.feature.editor.importAssistantTemplate(store.read(addition.sourceAttachmentId), AssetId(addition.assetId), addition.crop, addition.purpose == AssistantImagePurpose.BACKGROUND) }
        }
        // Recheck after image preparation. No partial imports or stale editor mutations.
        if (capture() != captured) return false
        var updated = editor.draft ?: return false
        val candidate = WorkflowJson.decode(candidateJson)
        prepared.zip(additions).forEach { (asset, addition) -> updated = updated.addAsset(asset).renameAsset(asset.assetId, addition.name) }
        updated = updated.copy(name = if (candidate.name == updated.name.trim()) updated.name else candidate.name, steps = candidate.steps, variables = candidate.variables)
        if (updated.buildBundle().issues.isNotEmpty()) return false
        editor.update(updated)
        return true
    }
}
