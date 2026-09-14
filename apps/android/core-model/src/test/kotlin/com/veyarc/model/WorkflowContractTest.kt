package com.veyarc.model

import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowContractTest {
    @Test
    fun fullDocumentSerializesAndValidates() {
        val document = validDocument()

        val json = WorkflowJson.encode(document)
        val decoded = WorkflowJson.decode(json)

        assertEquals(json, WorkflowJson.encode(decoded))
        assertTrue(WorkflowValidator.validate(decoded).isEmpty())
        assertTrue(json.startsWith("{\"allowedPackages\""))
        assertTrue(json.contains("\"type\":\"tapPoint\""))
    }

    @Test
    fun imageMatchModeRoundTripsAndOlderDocumentsUseRegionSearch() {
        val originalJson = WorkflowJson.encode(validDocument())
        assertTrue(originalJson.contains("\"matchMode\":\"appearsWithinRegion\""))
        val legacyJson = originalJson.replace(",\"matchMode\":\"appearsWithinRegion\"", "")

        val decodedLegacy = WorkflowJson.decode(legacyJson)
        val legacyCondition = (decodedLegacy.steps.filterIsInstance<WaitUntil>().single().condition as ImagePresentCondition)
        assertEquals(ImageMatchMode.APPEARS_WITHIN_REGION, legacyCondition.matchMode)

        val screenState = validDocument().copy(
            steps = validDocument().steps.map { step ->
                if (step is WaitUntil && step.condition is ImagePresentCondition) {
                    step.copy(condition = step.condition.copy(matchMode = ImageMatchMode.SCREEN_STATE))
                } else {
                    step
                }
            },
        )
        val screenStateJson = WorkflowJson.encode(screenState)
        assertTrue(screenStateJson.contains("\"matchMode\":\"screenState\""))
        assertEquals(screenStateJson, WorkflowJson.encode(WorkflowJson.decode(screenStateJson)))
    }

    @Test
    fun canonicalSerializationSortsMetadataWithoutReorderingSteps() {
        val original = validDocument()
        val reordered = original.copy(
            allowedPackages = original.allowedPackages.reversed(),
            assets = original.assets.reversed(),
            variables = original.variables.toList().reversed().toMap(),
        )

        assertEquals(WorkflowJson.encode(original), WorkflowJson.encode(reordered))
        assertTrue(WorkflowJson.encode(original).indexOf("tapPoint") < WorkflowJson.encode(original).indexOf("wait"))
    }

    @Test
    fun tapRegionResolvesCenterAndExplicitOffset() {
        val region = NormalizedRect(x = 0.2, y = 0.1, width = 0.4, height = 0.5)

        assertEquals(NormalizedCoordinate(0.4, 0.35), TapRegion(STEP_1, region).resolvePoint())
        assertEquals(
            NormalizedCoordinate(0.2, 0.6),
            TapRegion(STEP_1, region, RegionAnchor.Offset(0.0, 1.0)).resolvePoint(),
        )
    }

    @Test
    fun validatorRejectsForwardReferencesAndMissingAssets() {
        val document = validDocument().copy(
            assets = emptyList(),
            steps = listOf(
                WaitUntil(
                    id = STEP_1,
                    condition = StepResultCondition(STEP_2, StepOutcome.COMPLETED),
                    timeoutMs = DurationMillis(1000),
                    pollIntervalMs = DurationMillis(100),
                ),
                WaitUntil(
                    id = STEP_2,
                    condition = ImagePresentCondition(
                        assetId = AssetId("missing-template"),
                        region = NormalizedRect(0.0, 0.0, 1.0, 1.0),
                        matchThreshold = 0.9,
                    ),
                    timeoutMs = DurationMillis(1000),
                    pollIntervalMs = DurationMillis(100),
                ),
            ),
        )

        val errors = WorkflowValidator.validate(document)

        assertTrue(errors.any { it.code == ValidationErrorCode.FORWARD_STEP_REFERENCE })
        assertTrue(errors.any { it.code == ValidationErrorCode.MISSING_ASSET })
    }

    @Test
    fun validatorRejectsPackageAndVariableViolations() {
        val document = validDocument().copy(
            allowedPackages = listOf(PackageName("com.example.app")),
            steps = listOf(
                LaunchApp(STEP_1, PackageName("com.example.other"), DurationMillis(1000)),
                SetVariable(STEP_2, VariableName("attempts"), BooleanValue(true)),
            ),
        )

        val errors = WorkflowValidator.validate(document)

        assertTrue(errors.any { it.code == ValidationErrorCode.PACKAGE_NOT_ALLOWED })
        assertTrue(errors.any { it.code == ValidationErrorCode.VARIABLE_TYPE_MISMATCH })
    }

    @Test
    fun validatorAllowsUnusedTemplatesButRejectsDuplicateDisplayNames() {
        val unused = AssetReference(AssetId("template-unused"), AssetKind.IMAGE_TEMPLATE, "Reference")
        val validWithUnused = validDocument().copy(assets = validDocument().assets + unused)

        assertFalse(WorkflowValidator.validate(validWithUnused).any { it.code == ValidationErrorCode.UNUSED_ASSET })

        val duplicateNames = validWithUnused.copy(
            assets = validWithUnused.assets.map { it.copy(displayName = "Same name") },
        )
        assertTrue(
            WorkflowValidator.validate(duplicateNames).any { it.code == ValidationErrorCode.DUPLICATE_ASSET_NAME },
        )
    }

    @Test
    fun strictDecoderRejectsUnknownFields() {
        val json = WorkflowJson.encode(validDocument()).replaceFirst("{", "{\"unknown\":true,")

        var rejected = false
        try {
            WorkflowJson.decode(json)
        } catch (_: SerializationException) {
            rejected = true
        }

        assertTrue(rejected)
    }

    @Test
    fun strictDecoderRejectsExplicitNulls() {
        val json = WorkflowJson.encode(validDocument()).replace("\"reason\":\"workflow\"", "\"reason\":\"workflow\",\"message\":null")

        var rejected = false
        try {
            WorkflowJson.decode(json)
        } catch (_: SerializationException) {
            rejected = true
        }

        assertTrue(rejected)
    }

    @Test
    fun malformedSwipePathIsRejectedAtConstruction() {
        var rejected = false
        try {
            SwipePath(
                id = STEP_1,
                durationMs = DurationMillis(100),
                points = listOf(
                    TimedPoint(NormalizedCoordinate(0.0, 0.0), DurationMillis(10)),
                    TimedPoint(NormalizedCoordinate(1.0, 1.0), DurationMillis(100)),
                ),
            )
        } catch (_: IllegalArgumentException) {
            rejected = true
        }

        assertTrue(rejected)
    }

    @Test
    fun detectsVisualConditionsAtAnyNestedWorkflowDepth() {
        assertTrue(validDocument().usesVisualConditions())
        assertFalse(
            validDocument().copy(
                steps = listOf(
                    IfElse(
                        id = STEP_1,
                        condition = ForegroundPackageCondition(PackageName("com.example.app")),
                        thenSteps = listOf(Wait(STEP_2, DurationMillis(100))),
                        elseSteps = emptyList(),
                    ),
                ),
            ).usesVisualConditions(),
        )
    }

    @Test
    fun unsupportedSchemaVersionIsReported() {
        val errors = WorkflowValidator.validate(validDocument().copy(schemaVersion = 2))

        assertFalse(errors.isEmpty())
        assertEquals(ValidationErrorCode.UNSUPPORTED_SCHEMA_VERSION, errors.first().code)
    }

    private fun validDocument(): WorkflowDocument = WorkflowDocument(
        schemaVersion = WorkflowDocument.CURRENT_SCHEMA_VERSION,
        id = DOCUMENT_ID,
        revision = 1,
        name = "Smoke workflow",
        allowedPackages = listOf(
            PackageName("com.example.other"),
            PackageName("com.example.app"),
        ),
        viewport = ViewportSpec(
            recordedWidthPx = 1080,
            recordedHeightPx = 2392,
            recordedDensityDpi = 420,
            recordedOrientation = DisplayOrientation.PORTRAIT,
        ),
        runLimits = RunLimits(
            maxTotalSteps = 100,
            maxRuntimeMs = 60_000,
            maxLoopIterations = 10,
            maxNestingDepth = 4,
            maxPathPoints = 8,
            maxStepDurationMs = 5_000,
        ),
        variables = mapOf(
            "attempts" to VariableDeclaration(VariableType.NUMBER, NumberValue(0.0)),
            "enabled" to VariableDeclaration(VariableType.BOOLEAN, BooleanValue(true)),
        ),
        assets = listOf(AssetReference(AssetId("template-main"), AssetKind.IMAGE_TEMPLATE)),
        steps = listOf(
            TapPoint(STEP_1, NormalizedCoordinate(0.1, 0.1)),
            Wait(STEP_2, DurationMillis(100)),
            IfElse(
                id = STEP_3,
                condition = VariableComparisonCondition(
                    left = VariableOperand(VariableName("attempts")),
                    operator = ComparisonOperator.LESS_THAN,
                    right = LiteralOperand(NumberValue(3.0)),
                ),
                thenSteps = listOf(
                    SetVariable(STEP_4, VariableName("attempts"), NumberValue(1.0)),
                ),
                elseSteps = listOf(Stop(STEP_5, StopReason.CONDITION)),
            ),
            WaitUntil(
                id = STEP_6,
                condition = ImagePresentCondition(
                    assetId = AssetId("template-main"),
                    region = NormalizedRect(0.0, 0.0, 1.0, 1.0),
                    matchThreshold = 0.9,
                ),
                timeoutMs = DurationMillis(1000),
                pollIntervalMs = DurationMillis(100),
            ),
            Repeat(
                id = STEP_7,
                count = 2,
                bodySteps = listOf(
                    LaunchApp(STEP_8, PackageName("com.example.app"), DurationMillis(1000)),
                ),
            ),
            WhileStep(
                id = STEP_9,
                condition = ForegroundPackageCondition(PackageName("com.example.app")),
                maxIterations = 3,
                bodySteps = listOf(
                    SwipePath(
                        id = STEP_10,
                        durationMs = DurationMillis(200),
                        points = listOf(
                            TimedPoint(NormalizedCoordinate(0.1, 0.8), DurationMillis(0)),
                            TimedPoint(NormalizedCoordinate(0.9, 0.8), DurationMillis(200)),
                        ),
                    ),
                ),
            ),
            Stop(STEP_11, StopReason.WORKFLOW),
        ),
    )

    private companion object {
        const val DOCUMENT_ID = "00000000-0000-4000-8000-000000000001"
        const val STEP_1 = "00000000-0000-4000-8000-000000000011"
        const val STEP_2 = "00000000-0000-4000-8000-000000000012"
        const val STEP_3 = "00000000-0000-4000-8000-000000000013"
        const val STEP_4 = "00000000-0000-4000-8000-000000000014"
        const val STEP_5 = "00000000-0000-4000-8000-000000000015"
        const val STEP_6 = "00000000-0000-4000-8000-000000000016"
        const val STEP_7 = "00000000-0000-4000-8000-000000000017"
        const val STEP_8 = "00000000-0000-4000-8000-000000000018"
        const val STEP_9 = "00000000-0000-4000-8000-000000000019"
        const val STEP_10 = "00000000-0000-4000-8000-000000000020"
        const val STEP_11 = "00000000-0000-4000-8000-000000000021"
    }
}
