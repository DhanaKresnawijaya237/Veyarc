package com.veyarc.engine

import com.veyarc.model.AndCondition
import com.veyarc.model.AssetId
import com.veyarc.model.AssetKind
import com.veyarc.model.AssetReference
import com.veyarc.model.DisplayOrientation
import com.veyarc.model.DurationMillis
import com.veyarc.model.IfElse
import com.veyarc.model.ImageAbsentCondition
import com.veyarc.model.ImageMatchMode
import com.veyarc.model.ImagePresentCondition
import com.veyarc.model.LaunchApp
import com.veyarc.model.NormalizedCoordinate
import com.veyarc.model.NormalizedRect
import com.veyarc.model.PackageName
import com.veyarc.model.Repeat
import com.veyarc.model.RunLimits
import com.veyarc.model.TapPoint
import com.veyarc.model.TapRegion
import com.veyarc.model.ValidationErrorCode
import com.veyarc.model.ViewportSpec
import com.veyarc.model.Wait
import com.veyarc.model.WaitUntil
import com.veyarc.model.WorkflowDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowCompilerTest {
    @Test
    fun compilesToImmutablePlanAndCalculatesWorstCaseCost() {
        val document = document(
            steps = listOf(
                TapPoint(STEP_1, NormalizedCoordinate(0.1, 0.1)),
                Repeat(
                    id = STEP_2,
                    count = 2,
                    bodySteps = listOf(Wait(STEP_3, DurationMillis(100))),
                ),
                TapRegion(STEP_4, NormalizedRect(0.2, 0.2, 0.4, 0.4)),
            ),
        )

        val result = WorkflowCompiler.compile(document)

        assertTrue(result.isSuccess)
        assertNotNull(result.plan)
        assertEquals(5L, result.plan!!.analysis.worstCaseStepCount)
        assertEquals(200L, result.plan.analysis.worstCaseRuntimeMs)
        assertEquals(2, result.plan.analysis.maxNestingDepth)
        assertEquals(2, result.plan.analysis.maxLoopBound)
        val region = result.plan.steps[2] as ExecutionStep.TapRegion
        assertEquals(NormalizedCoordinate(0.4, 0.4), region.resolvedPoint)

        @Suppress("UNCHECKED_CAST")
        val planSteps = result.plan.steps as MutableList<ExecutionStep>
        var rejected = false
        try {
            planSteps.add(ExecutionStep.Wait(STEP_5, DurationMillis(1)))
        } catch (_: UnsupportedOperationException) {
            rejected = true
        }
        assertTrue(rejected)
    }

    @Test
    fun rejectsUnsupportedStepCapabilities() {
        val result = WorkflowCompiler.compile(
            document(steps = listOf(Wait(STEP_1, DurationMillis(100)))),
            EngineCapabilities.of(setOf(EngineCapability.TAP_POINT)),
        )

        assertFalse(result.isSuccess)
        assertTrue(
            result.errors.any {
                it.code == CompilationErrorCode.UNSUPPORTED_CAPABILITY &&
                    it.capability == EngineCapability.WAIT &&
                    it.path == "/steps/0"
            },
        )
    }

    @Test
    fun rejectsWorstCaseStepBudgetBeforeCreatingPlan() {
        val result = WorkflowCompiler.compile(
            document(
                limits = limits(maxTotalSteps = 5),
                steps = listOf(
                    Repeat(
                        id = STEP_1,
                        count = 3,
                        bodySteps = listOf(
                            TapPoint(STEP_2, NormalizedCoordinate(0.1, 0.1)),
                            TapPoint(STEP_3, NormalizedCoordinate(0.2, 0.2)),
                        ),
                    ),
                ),
            ),
        )

        assertFalse(result.isSuccess)
        assertTrue(result.errors.any { it.code == CompilationErrorCode.LIMIT_EXCEEDED })
        assertTrue(result.errors.any { it.message.contains("maxTotalSteps") })
    }

    @Test
    fun allowsConservativeRuntimeEstimateAboveRuntimeLimit() {
        val result = WorkflowCompiler.compile(
            document(
                limits = limits(maxRuntimeMs = 100),
                steps = listOf(Wait(STEP_1, DurationMillis(500))),
            ),
        )

        assertTrue(result.isSuccess)
        assertEquals(500L, result.plan?.analysis?.worstCaseRuntimeMs)
    }

    @Test
    fun carriesModelValidationErrorsIntoCompilationResult() {
        val result = WorkflowCompiler.compile(
            document(
                steps = listOf(
                    LaunchApp(
                        id = STEP_1,
                        packageName = PackageName("com.example.undeclared"),
                        foregroundTimeoutMs = DurationMillis(100),
                    ),
                ),
            ),
        )

        assertFalse(result.isSuccess)
        assertTrue(
            result.errors.any {
                it.code == CompilationErrorCode.INVALID_WORKFLOW &&
                    it.validationCode == ValidationErrorCode.PACKAGE_NOT_ALLOWED
            },
        )
    }

    @Test
    fun discoversOnlyReferencedImagesAcrossNestedCompositeConditions() {
        val crop = AssetId("crop")
        val screen = AssetId("screen")
        val unused = AssetId("unused")
        val document = document(
            assets = listOf(
                AssetReference(crop, AssetKind.IMAGE_TEMPLATE, "Crop"),
                AssetReference(screen, AssetKind.IMAGE_TEMPLATE, "Screen"),
                AssetReference(unused, AssetKind.IMAGE_TEMPLATE, "Unused"),
            ),
            steps = listOf(
                IfElse(
                    id = STEP_1,
                    condition = AndCondition(
                        listOf(
                            ImagePresentCondition(
                                crop,
                                NormalizedRect(0.0, 0.0, 1.0, 1.0),
                                0.9,
                            ),
                            ImageAbsentCondition(
                                crop,
                                NormalizedRect(0.0, 0.0, 1.0, 1.0),
                                0.9,
                            ),
                        ),
                    ),
                    thenSteps = listOf(
                        WaitUntil(
                            STEP_2,
                            ImagePresentCondition(
                                screen,
                                NormalizedRect(0.0, 0.0, 1.0, 1.0),
                                0.9,
                                ImageMatchMode.SCREEN_STATE,
                            ),
                            DurationMillis(100),
                            DurationMillis(10),
                        ),
                    ),
                    elseSteps = emptyList(),
                ),
            ),
        )

        val referenced = WorkflowCompiler.compileOrThrow(document).referencedImageAssets()

        assertEquals(setOf(crop, screen), referenced.keys)
        assertEquals(setOf(ImageAssetUse.CROPPED_TARGET), referenced[crop])
        assertEquals(setOf(ImageAssetUse.FULL_SCREEN), referenced[screen])
    }

    private fun document(
        limits: RunLimits = limits(),
        assets: List<AssetReference> = emptyList(),
        steps: List<com.veyarc.model.WorkflowStep>,
    ): WorkflowDocument = WorkflowDocument(
        schemaVersion = WorkflowDocument.CURRENT_SCHEMA_VERSION,
        id = DOCUMENT_ID,
        revision = 1,
        name = "Compiler test",
        allowedPackages = listOf(PackageName("com.example.app")),
        viewport = ViewportSpec(
            recordedWidthPx = 1080,
            recordedHeightPx = 2392,
            recordedDensityDpi = 420,
            recordedOrientation = DisplayOrientation.PORTRAIT,
        ),
        runLimits = limits,
        variables = emptyMap(),
        assets = assets,
        steps = steps,
    )

    private fun limits(
        maxTotalSteps: Int = 100,
        maxRuntimeMs: Long = 10_000,
    ): RunLimits = RunLimits(
        maxTotalSteps = maxTotalSteps,
        maxRuntimeMs = maxRuntimeMs,
        maxLoopIterations = 10,
        maxNestingDepth = 4,
        maxPathPoints = 16,
        maxStepDurationMs = 5_000,
    )

    private companion object {
        const val DOCUMENT_ID = "00000000-0000-4000-8000-000000000101"
        const val STEP_1 = "00000000-0000-4000-8000-000000000111"
        const val STEP_2 = "00000000-0000-4000-8000-000000000112"
        const val STEP_3 = "00000000-0000-4000-8000-000000000113"
        const val STEP_4 = "00000000-0000-4000-8000-000000000114"
        const val STEP_5 = "00000000-0000-4000-8000-000000000115"
    }
}
