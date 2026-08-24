package com.veyarc.feature.recorder

import com.veyarc.model.bundle.WorkflowBundle

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
)
