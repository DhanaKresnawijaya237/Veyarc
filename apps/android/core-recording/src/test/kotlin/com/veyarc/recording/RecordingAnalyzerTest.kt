package com.veyarc.recording

import com.veyarc.model.DisplayOrientation
import com.veyarc.model.PackageName
import com.veyarc.model.ViewportSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class RecordingAnalyzerTest {
    @Test
    fun classifiesTapsHoldsPathsInfersWaitsAndSuggestsPackageTransitions() {
        val snapshot = snapshot(
            RecordingJournalLine.ForegroundPackage(0L, PackageName("com.example.app")),
            RecordingJournalLine.Touch(100L, RecordingTouchAction.DOWN, 10.0, 20.0),
            RecordingJournalLine.Touch(130L, RecordingTouchAction.UP, 10.5, 20.5),
            RecordingJournalLine.ForegroundPackage(200L, PackageName("com.example.app")),
            RecordingJournalLine.ForegroundPackage(250L, PackageName("com.example.other")),
            RecordingJournalLine.Touch(400L, RecordingTouchAction.DOWN, 100.0, 200.0),
            RecordingJournalLine.Touch(1_000L, RecordingTouchAction.UP, 100.0, 200.0),
            RecordingJournalLine.Touch(1_500L, RecordingTouchAction.DOWN, 200.0, 300.0),
            RecordingJournalLine.Touch(1_600L, RecordingTouchAction.MOVE, 400.0, 450.0),
            RecordingJournalLine.Touch(1_700L, RecordingTouchAction.MOVE, 600.0, 500.0),
            RecordingJournalLine.Touch(1_800L, RecordingTouchAction.UP, 800.0, 600.0),
            RecordingJournalLine.Touch(2_300L, RecordingTouchAction.DOWN, 900.0, 700.0),
            RecordingJournalLine.Touch(2_320L, RecordingTouchAction.UP, 900.0, 700.0),
        )

        val analysis = RecordingAnalyzer.analyze(snapshot, config())

        assertEquals(
            listOf(
                ClassifiedGestureKind.TAP,
                ClassifiedGestureKind.HOLD,
                ClassifiedGestureKind.PATH,
                ClassifiedGestureKind.TAP,
            ),
            analysis.gestures.map(ClassifiedTouchGesture::kind),
        )
        assertEquals(listOf(270L, 500L, 500L), analysis.inferredWaits.map { it.durationMillis })
        assertEquals(1, analysis.foregroundTransitions.size)
        assertEquals(250L, analysis.foregroundTransitions.single().elapsedMillis)
        assertEquals(PackageName("com.example.app"), analysis.foregroundTransitions.single().previousPackage)
        assertEquals(PackageName("com.example.other"), analysis.foregroundTransitions.single().currentPackage)
        assertEquals(300L, analysis.gestures[2].durationMillis)
        assertEquals(0L, analysis.gestures[2].points.first().offsetMillis)
        assertEquals(300L, analysis.gestures[2].points.last().offsetMillis)
        assertTrue(!analysis.incompleteTouchIgnored)
    }

    @Test
    fun simplifiesCollinearPathWhileKeepingEndpointsAndTiming() {
        val snapshot = snapshot(
            RecordingJournalLine.Touch(0L, RecordingTouchAction.DOWN, 0.0, 0.0),
            RecordingJournalLine.Touch(50L, RecordingTouchAction.MOVE, 100.0, 0.0),
            RecordingJournalLine.Touch(100L, RecordingTouchAction.MOVE, 200.0, 0.0),
            RecordingJournalLine.Touch(150L, RecordingTouchAction.MOVE, 300.0, 0.0),
            RecordingJournalLine.Touch(200L, RecordingTouchAction.UP, 400.0, 0.0),
        )

        val gesture = RecordingAnalyzer.analyze(
            snapshot,
            config(touchSlopPx = 1.0, pathSimplificationFraction = 0.01),
        ).gestures.single()

        assertEquals(ClassifiedGestureKind.PATH, gesture.kind)
        assertEquals(2, gesture.points.size)
        assertEquals(RecordedPoint(0.0, 0.0), gesture.points.first().point)
        assertEquals(RecordedPoint(400.0, 0.0), gesture.points.last().point)
        assertEquals(listOf(0L, 200L), gesture.points.map(RecordedTimedPoint::offsetMillis))
    }

    @Test
    fun boundsSimplifiedPathPointCountAndPreservesEndpoints() {
        val events = buildList {
            add(RecordingJournalLine.Touch(0L, RecordingTouchAction.DOWN, 0.0, 0.0))
            for (index in 1..20) {
                add(
                    RecordingJournalLine.Touch(
                        elapsedMillis = index * 10L,
                        action = RecordingTouchAction.MOVE,
                        xPx = index * 10.0,
                        yPx = if (index % 2 == 0) 20.0 else 0.0,
                    ),
                )
            }
            add(RecordingJournalLine.Touch(210L, RecordingTouchAction.UP, 210.0, 0.0))
        }

        val gesture = RecordingAnalyzer.analyze(
            snapshot(*events.toTypedArray()),
            config(touchSlopPx = 1.0, pathSimplificationFraction = 0.0001, maxPathPoints = 5),
        ).gestures.single()

        assertEquals(ClassifiedGestureKind.PATH, gesture.kind)
        assertTrue(gesture.points.size <= 5)
        assertEquals(RecordedPoint(0.0, 0.0), gesture.points.first().point)
        assertEquals(RecordedPoint(210.0, 0.0), gesture.points.last().point)
        assertTrue(gesture.points.zipWithNext().all { (first, second) -> first.offsetMillis < second.offsetMillis })
    }

    @Test
    fun doesNotInventACompletedGestureForAnIncompleteTail() {
        val snapshot = snapshot(
            RecordingJournalLine.Touch(100L, RecordingTouchAction.DOWN, 10.0, 20.0),
            RecordingJournalLine.Touch(150L, RecordingTouchAction.MOVE, 12.0, 22.0),
        )

        val analysis = RecordingAnalyzer.analyze(snapshot, config())

        assertTrue(analysis.gestures.isEmpty())
        assertTrue(analysis.incompleteTouchIgnored)
    }

    private fun config(
        touchSlopPx: Double = 5.0,
        tapTimeoutMillis: Long = 100L,
        longPressTimeoutMillis: Long = 500L,
        minimumWaitMillis: Long = 200L,
        pathSimplificationFraction: Double = 0.0025,
        maxPathPoints: Int = 128,
    ): RecordingClassificationConfig = RecordingClassificationConfig(
        touchSlopPx = touchSlopPx,
        tapTimeoutMillis = tapTimeoutMillis,
        longPressTimeoutMillis = longPressTimeoutMillis,
        minimumWaitMillis = minimumWaitMillis,
        pathSimplificationFraction = pathSimplificationFraction,
        maxPathPoints = maxPathPoints,
    )

    private fun snapshot(vararg events: RecordingJournalLine): RecordingJournalSnapshot {
        val header = RecordingJournalHeader(
            sessionId = "00000000-0000-4000-8000-000000000403",
            startedAtEpochMillis = 3_000L,
            viewport = ViewportSpec(
                recordedWidthPx = 1_000,
                recordedHeightPx = 1_000,
                recordedDensityDpi = 420,
                recordedOrientation = DisplayOrientation.PORTRAIT,
            ),
        )
        val lines = listOf(RecordingJournalLine.Header(header)) + events.toList()
        val bytes = ByteArrayOutputStream().use { output ->
            lines.forEach { output.write(RecordingJournalCodec.encodeLine(it)) }
            output.toByteArray()
        }
        return RecordingJournalCodec.parseJsonLines(bytes)
    }
}
