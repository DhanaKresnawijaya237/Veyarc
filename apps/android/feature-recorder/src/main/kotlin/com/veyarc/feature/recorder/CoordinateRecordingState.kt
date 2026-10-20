package com.veyarc.feature.recorder

import com.veyarc.model.PackageName
import com.veyarc.model.bundle.WorkflowBundle
import com.veyarc.recording.RecordingRawTrace

sealed interface CoordinateRecordingState {
    data object Idle : CoordinateRecordingState

    data class Arming(
        val elapsedMillis: Long,
    ) : CoordinateRecordingState

    data class Recording(
        val foregroundPackage: String?,
        val elapsedMillis: Long,
        val frameCount: Long,
    ) : CoordinateRecordingState

    data class Paused(
        val foregroundPackage: String?,
        val elapsedMillis: Long,
        val frameCount: Long,
    ) : CoordinateRecordingState

    data class Review(
        val review: CoordinateRecordingReview,
    ) : CoordinateRecordingState

    data class Failed(
        val reason: String,
        val journalFileName: String?,
    ) : CoordinateRecordingState

    data class Unavailable(
        val reason: String,
    ) : CoordinateRecordingState
}

data class CoordinateRecordingReview(
    val sessionId: String,
    val initialForegroundPackage: String?,
    val currentForegroundPackage: String?,
    val journalFileName: String,
    val durationMillis: Long,
    val frameCount: Long,
    val gestureCount: Int,
    val inferredWaitCount: Int,
    val foregroundTransitionCount: Int,
    val incompleteTouchIgnored: Boolean,
    val editableWorkflow: WorkflowBundle? = null,
    val draftWarnings: List<String> = emptyList(),
    val draftUnavailableReason: String? = null,
    val workflowSaved: Boolean = false,
    val workflowSaveError: String? = null,
    val rawReplayTrace: RecordingRawTrace? = null,
    val rawReplayTargetPackage: PackageName? = null,
    val rawReplayUnavailableReason: String? = null,
)

/** Retained with the recording controller across activity recreation. */
data class RecordingBuilderState(
    val isOpen: Boolean = false,
    val showAdd: Boolean = false,
    val preparing: Boolean = false,
    val destination: String = "Workflow",
    val error: String? = null,
)
