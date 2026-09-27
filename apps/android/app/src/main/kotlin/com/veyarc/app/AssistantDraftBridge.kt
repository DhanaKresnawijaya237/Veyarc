package com.veyarc.app

import com.veyarc.feature.assistant.AssistantCandidateValidator
import com.veyarc.feature.assistant.AssistantContextCapture
import com.veyarc.feature.assistant.AssistantContextSnapshot
import com.veyarc.feature.editor.WorkflowDraft
import com.veyarc.feature.editor.WorkflowEditorSession
import com.veyarc.model.WorkflowJson
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
}
