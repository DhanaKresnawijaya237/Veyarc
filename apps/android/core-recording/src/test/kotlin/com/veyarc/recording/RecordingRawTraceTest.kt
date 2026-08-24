package com.veyarc.recording

import com.veyarc.model.DisplayOrientation
import com.veyarc.model.ViewportSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RecordingRawTraceTest {
    @Test
    fun extractsCompleteTraceAndPreservesFrameTiming() {
        val trace = RecordingRawTrace.fromJournal(
            snapshot(
                RecordingJournalLine.Touch(100L, RecordingTouchAction.DOWN, 100.0, 200.0),
                RecordingJournalLine.Touch(115L, RecordingTouchAction.MOVE, 200.0, 400.0),
                RecordingJournalLine.Touch(140L, RecordingTouchAction.UP, 300.0, 600.0),
            ),
        )

        val remapped = trace.remapTo(viewport(width = 2_000, height = 4_000))

        assertEquals(3, trace.frames.size)
        assertEquals(40L, trace.durationMillis)
        assertEquals(0L, remapped[0].delayMillis)
        assertEquals(15L, remapped[1].delayMillis)
        assertEquals(25L, remapped[2].delayMillis)
        assertEquals(100.0 / 999.0 * 1_999.0, remapped[0].xPx, 0.000001)
        assertEquals(200.0 / 1_999.0 * 3_999.0, remapped[0].yPx, 0.000001)
        assertEquals(300.0 / 999.0 * 1_999.0, remapped.last().xPx, 0.000001)
        assertEquals(600.0 / 1_999.0 * 3_999.0, remapped.last().yPx, 0.000001)
    }

    @Test
    fun refusesIncompleteTraceBeforeReplay() {
        assertThrows(IllegalArgumentException::class.java) {
            RecordingRawTrace.fromJournal(
                snapshot(
                    RecordingJournalLine.Touch(0L, RecordingTouchAction.DOWN, 1.0, 1.0),
                    RecordingJournalLine.Touch(10L, RecordingTouchAction.MOVE, 2.0, 2.0),
                ),
            )
        }
    }

    @Test
    fun keepsZeroDelayFramesForRapidInput() {
        val trace = RecordingRawTrace.fromJournal(
            snapshot(
                RecordingJournalLine.Touch(0L, RecordingTouchAction.DOWN, 10.0, 10.0),
                RecordingJournalLine.Touch(0L, RecordingTouchAction.MOVE, 20.0, 20.0),
                RecordingJournalLine.Touch(0L, RecordingTouchAction.UP, 30.0, 30.0),
            ),
        )

        val remapped = trace.remapTo(viewport())

        assertEquals(listOf(0L, 0L, 0L), remapped.map(ReplayRawTouchFrame::delayMillis))
    }

    private fun snapshot(vararg lines: RecordingJournalLine): RecordingJournalSnapshot {
        val header = RecordingJournalHeader(
            sessionId = "00000000-0000-4000-8000-000000000501",
            startedAtEpochMillis = 1_000L,
            viewport = viewport(),
        )
        var cursor = RecordingJournalCursor()
        lines.forEach { cursor = cursor.accept(it) }
        return RecordingJournalSnapshot(
            header = header,
            events = lines.toList(),
            cursor = cursor,
            trailingPartialBytes = 0,
            validPrefixBytes = 0,
            endsWithNewline = true,
        )
    }

    private fun viewport(width: Int = 1_000, height: Int = 2_000) = ViewportSpec(
        recordedWidthPx = width,
        recordedHeightPx = height,
        recordedDensityDpi = 420,
        recordedOrientation = DisplayOrientation.PORTRAIT,
    )
}
