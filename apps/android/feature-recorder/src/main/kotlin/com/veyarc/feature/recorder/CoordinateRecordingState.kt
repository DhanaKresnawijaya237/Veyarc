package com.veyarc.feature.recorder

sealed interface CoordinateRecordingState {
    data object Idle : CoordinateRecordingState

    data class Unavailable(
        val reason: String,
    ) : CoordinateRecordingState
}
