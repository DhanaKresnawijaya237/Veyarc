package com.veyarc.app

import com.veyarc.feature.assistant.AssistantContextCapture
import com.veyarc.feature.assistant.AssistantContextSnapshot
import com.veyarc.feature.assistant.AssistantDraftContext
import com.veyarc.feature.editor.WorkflowDraft
import com.veyarc.model.AssetReference

/** Read only the selected draft's text/model fields. Never resolve assets or recording journals. */
internal fun WorkflowDraft?.captureAssistantContext(): AssistantContextCapture {
    val draft = this ?: return AssistantContextCapture()
    return try {
        AssistantContextCapture(
            AssistantContextSnapshot.capture(
                AssistantDraftContext(
                    workflowId = draft.workflowId,
                    lastSavedRevision = draft.lastSavedRevision,
                    latestKnownRevision = draft.latestKnownRevision,
                    name = draft.name,
                    allowedPackagesText = draft.allowedPackagesText,
                    viewport = draft.viewport,
                    runLimits = draft.runLimits,
                    variables = draft.variables,
                    assets = draft.assetDescriptors.map { AssetReference(it.assetId, it.kind, draft.assetNames[it.assetId.value] ?: it.displayName) },
                    steps = draft.steps,
                ),
            ),
        )
    } catch (error: IllegalArgumentException) {
        AssistantContextCapture(error = error.message ?: "Workflow context could not be captured. Check the draft before sending.")
    }
}

/** Discard the compiled plan; chat receives only bounded diagnostics and no runtime capability. */
internal fun assistantCompilationIssues(document: com.veyarc.model.WorkflowDocument): List<String> = com.veyarc.engine.WorkflowCompiler.compile(document, com.veyarc.engine.EngineCapabilities.all()).errors
    .take(20).map { "${it.path}: ${it.code}" }

internal fun com.veyarc.feature.editor.WorkflowEditorSession.assistantContext(): com.veyarc.feature.assistant.AssistantPreviewContext = draft?.let {
    com.veyarc.feature.assistant.AssistantPreviewContext(it.workflowId, it.name, it.lastSavedRevision, isDirty, it.allowedPackagesText.isBlank() || it.steps.isEmpty())
} ?: com.veyarc.feature.assistant.AssistantPreviewContext()
