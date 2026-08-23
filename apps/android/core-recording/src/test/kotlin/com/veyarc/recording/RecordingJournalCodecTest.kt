package com.veyarc.recording

import com.veyarc.model.DisplayOrientation
import com.veyarc.model.PackageName
import com.veyarc.model.ViewportSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class RecordingJournalCodecTest {
    @Test
    fun roundTripsTimestampedTouchAndForegroundRecords() {
        val header = header()
        val records = listOf(
            RecordingJournalLine.Header(header),
            RecordingJournalLine.ForegroundPackage(0L, PackageName("com.example.target")),
            RecordingJournalLine.Touch(10L, RecordingTouchAction.DOWN, 100.0, 200.0),
            RecordingJournalLine.Touch(35L, RecordingTouchAction.MOVE, 140.0, 240.0),
            RecordingJournalLine.Touch(70L, RecordingTouchAction.UP, 180.0, 280.0),
            RecordingJournalLine.ForegroundPackage(90L, null),
        )

        val bytes = records.joinToByteArray()
        val snapshot = RecordingJournalCodec.parseJsonLines(bytes)

        assertEquals(header, snapshot.header)
        assertEquals(records.drop(1), snapshot.events)
        assertEquals(90L, snapshot.cursor.lastElapsedMillis)
        assertTrue(!snapshot.cursor.touchInProgress)
        assertEquals(0, snapshot.trailingPartialBytes)
        assertEquals(bytes.size, snapshot.validPrefixBytes)
        assertTrue(snapshot.endsWithNewline)
    }

    @Test
    fun ignoresOnlyAnIncompleteFinalJsonRecord() {
        val prefix = listOf(
            RecordingJournalLine.Header(header()),
            RecordingJournalLine.Touch(0L, RecordingTouchAction.DOWN, 1.0, 2.0),
        ).joinToByteArray()
        val partial = "{\"type\":\"touch\",\"elapsedMillis\":20".encodeToByteArray()

        val snapshot = RecordingJournalCodec.parseJsonLines(prefix + partial)

        assertEquals(1, snapshot.events.size)
        assertEquals(prefix.size, snapshot.validPrefixBytes)
        assertEquals(partial.size, snapshot.trailingPartialBytes)
        assertTrue(snapshot.cursor.touchInProgress)
    }

    @Test
    fun rejectsACompleteButInvalidTouchSequence() {
        val bytes = listOf(
            RecordingJournalLine.Header(header()),
            RecordingJournalLine.Touch(0L, RecordingTouchAction.UP, 1.0, 2.0),
        ).joinToByteArray()

        try {
            RecordingJournalCodec.parseJsonLines(bytes)
            throw AssertionError("Expected an invalid touch sequence to be rejected")
        } catch (_: IllegalArgumentException) {
            // A complete malformed sequence is corruption, not a recoverable crash tail.
        }
    }

    private fun header(): RecordingJournalHeader = RecordingJournalHeader(
        sessionId = "00000000-0000-4000-8000-000000000401",
        startedAtEpochMillis = 1_000L,
        viewport = ViewportSpec(
            recordedWidthPx = 1080,
            recordedHeightPx = 2392,
            recordedDensityDpi = 420,
            recordedOrientation = DisplayOrientation.PORTRAIT,
        ),
    )

    private fun List<RecordingJournalLine>.joinToByteArray(): ByteArray = ByteArrayOutputStream().use { output ->
        forEach { output.write(RecordingJournalCodec.encodeLine(it)) }
        output.toByteArray()
    }
}
