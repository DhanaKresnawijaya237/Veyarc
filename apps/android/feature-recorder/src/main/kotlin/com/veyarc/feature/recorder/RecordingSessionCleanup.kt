package com.veyarc.feature.recorder

/** Why a recording is being stopped, used to decide whether an unfinished touch is recoverable. */
internal enum class RecordingStopCause(
    val preservePartialTouch: Boolean,
    val isFailure: Boolean,
) {
    USER_STOP(preservePartialTouch = false, isFailure = false),
    TIMEOUT(preservePartialTouch = true, isFailure = true),
    SERVICE_DISCONNECT(preservePartialTouch = true, isFailure = true),
    PROCESS_CLOSE(preservePartialTouch = true, isFailure = true),
}

/**
 * Serializes stop and terminal cleanup claims so duplicate callbacks cannot close resources twice
 * or make a later callback resurrect a finished recording.
 */
internal class RecordingSessionCleanup {
    private enum class State { IDLE, ACTIVE, STOPPING, TERMINATED }

    private var state = State.IDLE
    private var stopCause: RecordingStopCause? = null

    @Synchronized
    fun begin(): Boolean {
        if (state != State.IDLE) return false
        state = State.ACTIVE
        stopCause = null
        return true
    }

    @Synchronized
    fun requestStop(cause: RecordingStopCause): Boolean {
        if (state != State.ACTIVE) return false
        stopCause = cause
        state = State.STOPPING
        return true
    }

    @Synchronized
    fun claimTerminal(): Boolean {
        if (state != State.ACTIVE && state != State.STOPPING) return false
        state = State.TERMINATED
        return true
    }

    @Synchronized
    fun currentStopCause(): RecordingStopCause? = stopCause

    @Synchronized
    fun reset() {
        state = State.IDLE
        stopCause = null
    }
}
