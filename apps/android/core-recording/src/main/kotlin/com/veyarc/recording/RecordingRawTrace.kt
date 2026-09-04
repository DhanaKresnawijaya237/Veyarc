package com.veyarc.recording

import com.veyarc.model.ViewportSpec
import kotlin.math.max

/** One raw single-pointer frame retained for a later, explicitly approved replay. */
data class RecordingRawTouchFrame(
    val elapsedMillis: Long,
    val action: RecordingTouchAction,
    val xPx: Double,
    val yPx: Double,
) {
    init {
        require(elapsedMillis >= 0L) { "Raw touch timestamp must be nonnegative" }
        require(xPx.isFinite() && yPx.isFinite()) { "Raw touch coordinates must be finite" }
        require(xPx >= 0.0 && yPx >= 0.0) { "Raw touch coordinates must be nonnegative" }
    }
}

/** A complete raw trace and the viewport in which it was captured. */
data class RecordingRawTrace(
    val recordedViewport: ViewportSpec,
    val frames: List<RecordingRawTouchFrame>,
    val coordinateSpace: RecordingCoordinateSpace = RecordingCoordinateSpace.LEGACY_START_VIEWPORT,
) {
    init {
        require(frames.isNotEmpty()) { "A raw trace must contain at least one frame" }
        require(frames.size <= MAX_FRAME_COUNT) {
            "A raw trace cannot contain more than $MAX_FRAME_COUNT frames"
        }
        validateFrames(recordedViewport, frames)
    }

    val firstElapsedMillis: Long
        get() = frames.first().elapsedMillis

    val lastElapsedMillis: Long
        get() = frames.last().elapsedMillis

    /** Replay duration excludes the idle time before the first captured touch. */
    val durationMillis: Long
        get() = (lastElapsedMillis - firstElapsedMillis).coerceAtLeast(0L)

    /** Maps the recorded full-display coordinates to a current full-display viewport. */
    fun remapTo(currentViewport: ViewportSpec): List<ReplayRawTouchFrame> {
        require(currentViewport.windowMode == recordedViewport.windowMode) {
            "Raw replay requires the same full-display window mode"
        }
        require(
            RecordingOrientationPolicy.canReplay(
                recorded = recordedViewport.recordedOrientation,
                current = currentViewport.recordedOrientation,
            ),
        ) {
            "Raw replay requires ${recordedViewport.recordedOrientation.name.lowercase()} orientation. " +
                "Rotate the device before replaying."
        }
        val recordedWidthDenominator = max(1, recordedViewport.recordedWidthPx - 1).toDouble()
        val recordedHeightDenominator = max(1, recordedViewport.recordedHeightPx - 1).toDouble()
        val currentWidthDenominator = max(1, currentViewport.recordedWidthPx - 1).toDouble()
        val currentHeightDenominator = max(1, currentViewport.recordedHeightPx - 1).toDouble()
        return frames.mapIndexed { index, frame ->
            ReplayRawTouchFrame(
                delayMillis = if (index == 0) {
                    0L
                } else {
                    (frame.elapsedMillis - frames[index - 1].elapsedMillis).coerceAtLeast(0L)
                },
                action = frame.action,
                xPx = ((frame.xPx / recordedWidthDenominator) * currentWidthDenominator).coerceIn(
                    0.0,
                    currentWidthDenominator,
                ),
                yPx = ((frame.yPx / recordedHeightDenominator) * currentHeightDenominator).coerceIn(
                    0.0,
                    currentHeightDenominator,
                ),
            )
        }
    }

    companion object {
        const val MAX_FRAME_COUNT = 16_384

        /** Extracts only complete touch sequences from an authenticated journal snapshot. */
        fun fromJournal(snapshot: RecordingJournalSnapshot): RecordingRawTrace {
            val touchFrames = snapshot.events
                .filterIsInstance<RecordingJournalLine.Touch>()
                .map { line ->
                    RecordingRawTouchFrame(
                        elapsedMillis = line.elapsedMillis,
                        action = line.action,
                        xPx = line.xPx,
                        yPx = line.yPx,
                    )
                }
            return RecordingRawTrace(
                recordedViewport = snapshot.header.viewport,
                frames = touchFrames,
                coordinateSpace = snapshot.header.coordinateSpace,
            )
        }

        private fun validateFrames(
            viewport: ViewportSpec,
            frames: List<RecordingRawTouchFrame>,
        ) {
            val maxX = (viewport.recordedWidthPx - 1).toDouble()
            val maxY = (viewport.recordedHeightPx - 1).toDouble()
            var active = false
            var previousElapsed = -1L
            frames.forEach { frame ->
                require(frame.elapsedMillis >= previousElapsed) {
                    "Raw touch timestamps must be monotonic"
                }
                require(frame.xPx in 0.0..maxX && frame.yPx in 0.0..maxY) {
                    "Raw touch coordinates must be inside the recorded viewport"
                }
                when (frame.action) {
                    RecordingTouchAction.DOWN -> {
                        require(!active) { "A raw trace cannot start a second touch before UP" }
                        active = true
                    }

                    RecordingTouchAction.MOVE -> {
                        require(active) { "A raw MOVE requires an active touch" }
                    }

                    RecordingTouchAction.UP -> {
                        require(active) { "A raw UP requires an active touch" }
                        active = false
                    }
                }
                previousElapsed = frame.elapsedMillis
            }
            require(!active) { "A raw trace cannot replay an incomplete touch" }
            require(frames.first().action == RecordingTouchAction.DOWN) {
                "A raw trace must begin with DOWN"
            }
            require(frames.last().action == RecordingTouchAction.UP) {
                "A raw trace must end with UP"
            }
        }
    }
}

/** One remapped raw frame plus the delay since the previous frame. */
data class ReplayRawTouchFrame(
    val delayMillis: Long,
    val action: RecordingTouchAction,
    val xPx: Double,
    val yPx: Double,
) {
    init {
        require(delayMillis >= 0L) { "Replay frame delay must be nonnegative" }
        require(xPx.isFinite() && yPx.isFinite()) { "Replay coordinates must be finite" }
    }
}
