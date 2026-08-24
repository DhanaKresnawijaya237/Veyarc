package com.veyarc.recording

import com.veyarc.model.DisplayOrientation
import com.veyarc.model.LongPress
import com.veyarc.model.PackageName
import com.veyarc.model.SwipePath
import com.veyarc.model.TapPoint
import com.veyarc.model.ViewportSpec
import com.veyarc.model.Wait
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingWorkflowConverterTest {
    @Test
    fun convertsRecordedFactsIntoValidatedEditableSteps() {
        val analysis = RecordingAnalysis(
            header = RecordingJournalHeader(
                sessionId = "00000000-0000-4000-8000-000000000404",
                startedAtEpochMillis = 4_000L,
                viewport = viewport(),
            ),
            events = listOf(
                RecordingAnalysisEvent.Gesture(
                    ClassifiedTouchGesture(
                        startElapsedMillis = 100L,
                        durationMillis = 20L,
                        kind = ClassifiedGestureKind.TAP,
                        points = listOf(
                            RecordedTimedPoint(RecordedPoint(100.0, 200.0), 0L),
                            RecordedTimedPoint(RecordedPoint(100.0, 200.0), 20L),
                        ),
                    ),
                ),
                RecordingAnalysisEvent.InferredWait(120L, 380L),
                RecordingAnalysisEvent.Gesture(
                    ClassifiedTouchGesture(
                        startElapsedMillis = 500L,
                        durationMillis = 700L,
                        kind = ClassifiedGestureKind.HOLD,
                        points = listOf(
                            RecordedTimedPoint(RecordedPoint(300.0, 400.0), 0L),
                            RecordedTimedPoint(RecordedPoint(300.0, 400.0), 700L),
                        ),
                    ),
                ),
                RecordingAnalysisEvent.Gesture(
                    ClassifiedTouchGesture(
                        startElapsedMillis = 1_200L,
                        durationMillis = 300L,
                        kind = ClassifiedGestureKind.PATH,
                        points = listOf(
                            RecordedTimedPoint(RecordedPoint(10.0, 20.0), 0L),
                            RecordedTimedPoint(RecordedPoint(999.0, 1_999.0), 300L),
                        ),
                    ),
                ),
                RecordingAnalysisEvent.ForegroundPackageTransition(
                    elapsedMillis = 900L,
                    previousPackage = PackageName("com.example.app"),
                    currentPackage = PackageName("com.example.other"),
                ),
            ),
            initialForegroundPackage = PackageName("com.example.app"),
            incompleteTouchIgnored = true,
        )

        val converted = RecordingWorkflowConverter.convert(
            analysis = analysis,
            allowedPackages = listOf(PackageName("com.example.app"), PackageName("com.example.other")),
            workflowId = "00000000-0000-4000-8000-000000000405",
        )
        val steps = converted.bundle.document.steps

        assertEquals(4, steps.size)
        assertTrue(steps[0] is TapPoint)
        assertTrue(steps[1] is Wait)
        assertTrue(steps[2] is LongPress)
        assertTrue(steps[3] is SwipePath)
        assertEquals(100.0 / 999.0, (steps[0] as TapPoint).point.x, 0.000001)
        assertEquals(200.0 / 1_999.0, (steps[0] as TapPoint).point.y, 0.000001)
        assertEquals(700L, (steps[2] as LongPress).durationMs.value)
        assertEquals(1.0, (steps[3] as SwipePath).points.last().point.x, 0.0001)
        assertEquals(1.0, (steps[3] as SwipePath).points.last().point.y, 0.0001)
        assertEquals(1, converted.foregroundPackageSuggestions.size)
        assertTrue(converted.warnings.any { it.contains("incomplete", ignoreCase = true) })
        assertTrue(converted.warnings.any { it.contains("Launch app", ignoreCase = true) })
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesToImportWhenNoTargetPackageWasObserved() {
        RecordingWorkflowConverter.convert(
            analysis = RecordingAnalysis(
                header = RecordingJournalHeader(
                    sessionId = "00000000-0000-4000-8000-000000000406",
                    startedAtEpochMillis = 5_000L,
                    viewport = viewport(),
                ),
                events = listOf(
                    RecordingAnalysisEvent.Gesture(
                        ClassifiedTouchGesture(
                            startElapsedMillis = 0L,
                            durationMillis = 10L,
                            kind = ClassifiedGestureKind.TAP,
                            points = listOf(
                                RecordedTimedPoint(RecordedPoint(1.0, 1.0), 0L),
                                RecordedTimedPoint(RecordedPoint(1.0, 1.0), 10L),
                            ),
                        ),
                    ),
                ),
                initialForegroundPackage = null,
                incompleteTouchIgnored = false,
            ),
            allowedPackages = emptyList(),
        )
    }

    private fun viewport() = ViewportSpec(
        recordedWidthPx = 1_000,
        recordedHeightPx = 2_000,
        recordedDensityDpi = 420,
        recordedOrientation = DisplayOrientation.PORTRAIT,
    )
}
