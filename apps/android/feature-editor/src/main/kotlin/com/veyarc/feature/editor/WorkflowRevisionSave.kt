package com.veyarc.feature.editor

import com.veyarc.data.storage.WorkflowRepository
import com.veyarc.data.storage.WorkflowRevisionEditorData
import com.veyarc.data.storage.WorkflowStorageException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Saves the candidate without exposing a half-applied draft to the editor. */
suspend fun WorkflowEditorSession.saveRevision(
    repository: WorkflowRepository,
    expected: WorkflowDraft,
    candidate: WorkflowDraft = expected,
): WorkflowRevisionEditorData {
    check(draft === expected && candidate.workflowId == expected.workflowId && beginSave()) { "The workflow changed. Try again." }
    val folder = destinationFolderId
    try {
        return withContext(NonCancellable) {
            val build = withContext(Dispatchers.Default) { candidate.buildBundle() }
            val document = build.document ?: throw WorkflowStorageException(build.issues.firstOrNull()?.message ?: "Workflow is not valid.")
            persistSave(expected) {
                withContext(Dispatchers.IO) {
                    val latest = repository.listRevisions(expected.workflowId).maxOfOrNull { it.revision }
                    if (latest != null && latest > (expected.latestKnownRevision ?: 0)) {
                        throw WorkflowStorageException("A newer saved revision exists. Open it before saving again.")
                    }
                    if (expected.lastSavedRevision == null) {
                        repository.saveRevision(build.bundle ?: throw WorkflowStorageException("Workflow images are incomplete."), folder)
                    } else {
                        repository.saveRevisionFromAssetChanges(expected.workflowId, expected.lastSavedRevision, document, candidate.assets, folder)
                    }
                    repository.loadRevisionForEditor(document.id, document.revision)
                }
            }
        }
    } finally {
        finishSave()
    }
}
