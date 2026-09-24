package com.veyarc.feature.runner

import com.veyarc.engine.RunEvent
import com.veyarc.model.AssetId
import com.veyarc.model.AssetKind
import com.veyarc.model.AssetReference
import com.veyarc.model.DisplayOrientation
import com.veyarc.model.DurationMillis
import com.veyarc.model.ImageMatchMode
import com.veyarc.model.ImagePresentCondition
import com.veyarc.model.NormalizedCoordinate
import com.veyarc.model.NormalizedRect
import com.veyarc.model.PackageName
import com.veyarc.model.RunLimits
import com.veyarc.model.StepOutcome
import com.veyarc.model.TapPoint
import com.veyarc.model.ViewportSpec
import com.veyarc.model.WaitUntil
import com.veyarc.model.WorkflowDocument
import org.junit.Assert.assertEquals
import org.junit.Test

class RunEventFormatterTest {
    private val formatter = RunEventFormatter(document())

    @Test
    fun describesGestureStartAndCompletionUsingRecordedCoordinates() {
        assertEquals(
            "Tap at (25, 149 px recorded) started",
            formatter.format(RunEvent.StepStarted(10L, TAP_ID)),
        )
        assertEquals(
            "Tap completed",
            formatter.format(RunEvent.StepFinished(11L, TAP_ID, StepOutcome.COMPLETED)),
        )
    }

    @Test
    fun describesScreenStateResultScoreAndThreshold() {
        assertEquals(
            "Screen state: false · match 82.5% · threshold 90.0%",
            formatter.format(
                RunEvent.ConditionEvaluated(
                    atMs = 20L,
                    stepId = WAIT_ID,
                    path = "$WAIT_ID/condition",
                    result = false,
                    matchScore = 0.825,
                    threshold = 0.9,
                ),
            ),
        )
    }

    @Test
    fun neverSubstitutesTheThresholdWhenAnExactScoreIsUnavailable() {
        assertEquals(
            "Screen state: false · match unavailable · threshold 90.0%",
            formatter.format(
                RunEvent.ConditionEvaluated(
                    atMs = 20L,
                    stepId = WAIT_ID,
                    path = "$WAIT_ID/condition",
                    result = false,
                    threshold = 0.9,
                ),
            ),
        )
    }

    @Test
    fun usesTheTemplateDisplayNameWhenAvailable() {
        val namedFormatter = RunEventFormatter(
            document().copy(
                assets = listOf(AssetReference(AssetId(ASSET_ID), AssetKind.IMAGE_TEMPLATE, "Login screen")),
            ),
        )

        assertEquals(
            "Wait for Screen matches Login screen started",
            namedFormatter.format(RunEvent.StepStarted(10L, WAIT_ID)),
        )
    }

    private fun document(): WorkflowDocument = WorkflowDocument(
        schemaVersion = WorkflowDocument.CURRENT_SCHEMA_VERSION,
        id = WORKFLOW_ID,
        revision = 1,
        name = "Event details",
        allowedPackages = listOf(PackageName("com.example.app")),
        viewport = ViewportSpec(
            recordedWidthPx = 100,
            recordedHeightPx = 200,
            recordedDensityDpi = 320,
            recordedOrientation = DisplayOrientation.PORTRAIT,
        ),
        runLimits = RunLimits(
            maxTotalSteps = 10,
            maxRuntimeMs = 10_000L,
            maxLoopIterations = 3,
            maxNestingDepth = 2,
            maxPathPoints = 8,
            maxStepDurationMs = 2_000L,
        ),
        variables = emptyMap(),
        assets = emptyList(),
        steps = listOf(
            TapPoint(TAP_ID, NormalizedCoordinate(0.25, 0.75)),
            WaitUntil(
                id = WAIT_ID,
                condition = ImagePresentCondition(
                    assetId = AssetId(ASSET_ID),
                    region = NormalizedRect(0.0, 0.0, 1.0, 1.0),
                    matchThreshold = 0.9,
                    matchMode = ImageMatchMode.SCREEN_STATE,
                ),
                timeoutMs = DurationMillis(1_000L),
                pollIntervalMs = DurationMillis(100L),
            ),
        ),
    )

    private companion object {
        const val WORKFLOW_ID = "00000000-0000-4000-8000-000000000501"
        const val TAP_ID = "00000000-0000-4000-8000-000000000502"
        const val WAIT_ID = "00000000-0000-4000-8000-000000000503"
        const val ASSET_ID = "00000000-0000-4000-8000-000000000504"
    }
}
