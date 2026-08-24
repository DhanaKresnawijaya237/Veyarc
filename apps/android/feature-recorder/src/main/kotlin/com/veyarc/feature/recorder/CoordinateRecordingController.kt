package com.veyarc.feature.recorder

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Safe placeholder controller until a transparent privileged recording source is verified.
 */
class CoordinateRecordingController {
    private val _state = MutableStateFlow<CoordinateRecordingState>(CoordinateRecordingState.Idle)
    val state: StateFlow<CoordinateRecordingState> = _state.asStateFlow()

    fun start() {
        _state.value = CoordinateRecordingState.Unavailable(
            "Third-party coordinate recording is unavailable until a transparent input observer is verified.",
        )
    }

    fun stop() {
        _state.value = CoordinateRecordingState.Idle
    }
}
