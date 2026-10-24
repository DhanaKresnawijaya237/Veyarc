package com.veyarc.model

/** Cross-field and cross-reference checks for an editable workflow document. */
object WorkflowValidator {
    fun validate(document: WorkflowDocument): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()
        val runtimeCaptures = document.steps.capturedImages()
        val imageSearchScopes = document.steps.imageSearchesByStep()
        val imageSearchNames = document.steps.imageSearches().keys
        fun validateImageResultOrder(items: List<WorkflowStep>, path: String) {
            items.forEachIndexed { index, step ->
                val stepPath = "$path/$index"
                step.imageResultNames().filter { it.value !in imageSearchScopes[step.id].orEmpty() }.forEach { name ->
                    errors += error(if (name.value in imageSearchNames) ValidationErrorCode.FORWARD_STEP_REFERENCE else ValidationErrorCode.MISSING_VARIABLE, stepPath, "Find image for ${name.value} must appear before this block on the same execution path.")
                }
                when (step) {
                    is IfElse -> {
                        validateImageResultOrder(step.thenSteps, "$stepPath/thenSteps")
                        validateImageResultOrder(step.elseSteps, "$stepPath/elseSteps")
                    }

                    is Repeat -> validateImageResultOrder(step.bodySteps, "$stepPath/bodySteps")

                    is WhileStep -> validateImageResultOrder(step.bodySteps, "$stepPath/bodySteps")

                    else -> Unit
                }
            }
        }
        validateImageResultOrder(document.steps, "/steps")
        if (document.schemaVersion != WorkflowDocument.CURRENT_SCHEMA_VERSION) {
            errors += error(
                ValidationErrorCode.UNSUPPORTED_SCHEMA_VERSION,
                "/schemaVersion",
                "Only schema version ${WorkflowDocument.CURRENT_SCHEMA_VERSION} is supported",
            )
        }

        val packageNames = document.allowedPackages.map(PackageName::value)
        packageNames
            .groupingBy { it }
            .eachCount()
            .filterValues { it > 1 }
            .keys
            .forEach { duplicate ->
                errors += error(
                    ValidationErrorCode.DUPLICATE_PACKAGE,
                    "/allowedPackages",
                    "Package $duplicate is listed more than once",
                )
            }

        document.variables.forEach { (name, declaration) ->
            if (!runCatching { VariableName(name) }.isSuccess) {
                errors += error(
                    ValidationErrorCode.INVALID_VARIABLE_NAME,
                    "/variables/${escape(name)}",
                    "Variable name is invalid",
                )
            }
            if (declaration.type != declaration.initialValue.variableType()) {
                errors += error(
                    ValidationErrorCode.VARIABLE_TYPE_MISMATCH,
                    "/variables/${escape(name)}",
                    "Variable type must match its initial value",
                )
            }
        }

        val allStepIds = linkedSetOf<String>()
        val duplicateStepIds = mutableSetOf<String>()
        collectStepIds(document.steps, allStepIds, duplicateStepIds)
        duplicateStepIds.forEach { duplicate ->
            errors += error(
                ValidationErrorCode.DUPLICATE_STEP_ID,
                "/steps",
                "Step ID $duplicate occurs more than once",
            )
        }

        val declaredAssets = document.assets.associateBy { it.assetId.value }
        document.assets
            .groupingBy { it.assetId.value }
            .eachCount()
            .filterValues { it > 1 }
            .keys
            .forEach { duplicate ->
                errors += error(
                    ValidationErrorCode.DUPLICATE_ASSET,
                    "/assets",
                    "Asset $duplicate is listed more than once",
                )
            }
        document.assets.forEachIndexed { index, asset ->
            asset.displayName?.let { name ->
                if (name.isBlank() || name.length > 64) {
                    errors += error(
                        ValidationErrorCode.INVALID_ASSET_NAME,
                        "/assets/$index/displayName",
                        "Template name must be 1 to 64 characters",
                    )
                }
            }
        }
        document.assets.mapNotNull(AssetReference::displayName)
            .groupingBy { it.trim().lowercase() }
            .eachCount()
            .filterValues { it > 1 }
            .keys
            .forEach { duplicate ->
                errors += error(
                    ValidationErrorCode.DUPLICATE_ASSET_NAME,
                    "/assets",
                    "Template name $duplicate is used more than once",
                )
            }

        val referencedAssets = mutableSetOf<String>()
        val seenStepIds = linkedSetOf<String>()
        validateSteps(
            steps = document.steps,
            path = "/steps",
            depth = 1,
            document = document,
            runtimeCaptures = runtimeCaptures,
            allStepIds = allStepIds,
            seenStepIds = seenStepIds,
            declaredAssets = declaredAssets,
            referencedAssets = referencedAssets,
            errors = errors,
        )

        referencedAssets
            .filterNot(declaredAssets::containsKey)
            .forEach { missing ->
                errors += error(
                    ValidationErrorCode.MISSING_ASSET,
                    "/assets",
                    "Asset $missing is referenced but not declared",
                )
            }
        return errors.sortedWith(compareBy<ValidationError> { it.path }.thenBy { it.code.name })
    }

    private fun collectStepIds(
        steps: List<WorkflowStep>,
        ids: MutableSet<String>,
        duplicates: MutableSet<String>,
    ) {
        steps.forEach { step ->
            if (!ids.add(step.id)) {
                duplicates += step.id
            }
            when (step) {
                is IfElse -> {
                    collectStepIds(step.thenSteps, ids, duplicates)
                    collectStepIds(step.elseSteps, ids, duplicates)
                }

                is Repeat -> collectStepIds(step.bodySteps, ids, duplicates)

                is WhileStep -> collectStepIds(step.bodySteps, ids, duplicates)

                else -> Unit
            }
        }
    }

    private fun validateSteps(
        steps: List<WorkflowStep>,
        path: String,
        depth: Int,
        document: WorkflowDocument,
        runtimeCaptures: Map<String, List<CaptureImage>>,
        allStepIds: Set<String>,
        seenStepIds: MutableSet<String>,
        declaredAssets: Map<String, AssetReference>,
        referencedAssets: MutableSet<String>,
        errors: MutableList<ValidationError>,
        enclosingStepIds: Set<String> = emptySet(),
    ) {
        if (depth > document.runLimits.maxNestingDepth) {
            errors += error(
                ValidationErrorCode.LIMIT_EXCEEDED,
                path,
                "Workflow nesting depth exceeds maxNestingDepth",
            )
        }
        val availableJumpTargets = enclosingStepIds + steps.map { it.id }
        steps.forEachIndexed { index, step ->
            val stepPath = "$path/$index"
            step.backgroundAssetId()?.let { validateImageAsset(it, "$stepPath/backgroundAssetId", AssetKind.IMAGE_TEMPLATE, declaredAssets, referencedAssets, errors) }
            val imagePosition = when (step) {
                is TapPoint -> step.imagePosition
                is LongPress -> step.imagePosition
                else -> null
            }
            imagePosition?.offsetXVariable?.let { validateNumberVariable(it, "$stepPath/imagePosition/offsetXVariable", document, errors) }
            imagePosition?.offsetYVariable?.let { validateNumberVariable(it, "$stepPath/imagePosition/offsetYVariable", document, errors) }
            when (step) {
                is FindImage -> validateCondition(step.condition, "$stepPath/condition", document, runtimeCaptures, allStepIds, seenStepIds, declaredAssets, referencedAssets, errors)

                is WaitUntil -> {
                    validateCondition(
                        condition = step.condition,
                        path = "$stepPath/condition",
                        document = document,
                        runtimeCaptures = runtimeCaptures,
                        allStepIds = allStepIds,
                        seenStepIds = seenStepIds,
                        declaredAssets = declaredAssets,
                        referencedAssets = referencedAssets,
                        errors = errors,
                    )
                }

                is IfElse -> validateCondition(
                    condition = step.condition,
                    path = "$stepPath/condition",
                    document = document,
                    runtimeCaptures = runtimeCaptures,
                    allStepIds = allStepIds,
                    seenStepIds = seenStepIds,
                    declaredAssets = declaredAssets,
                    referencedAssets = referencedAssets,
                    errors = errors,
                )

                is WhileStep -> {
                    validateCondition(
                        condition = step.condition,
                        path = "$stepPath/condition",
                        document = document,
                        runtimeCaptures = runtimeCaptures,
                        allStepIds = allStepIds,
                        seenStepIds = seenStepIds,
                        declaredAssets = declaredAssets,
                        referencedAssets = referencedAssets,
                        errors = errors,
                    )
                    if (step.maxIterations > document.runLimits.maxLoopIterations) {
                        errors += error(
                            ValidationErrorCode.LIMIT_EXCEEDED,
                            "$stepPath/maxIterations",
                            "While maxIterations exceeds maxLoopIterations",
                        )
                    }
                }

                is Repeat -> {
                    step.countVariable?.let { validateNumberVariable(it, "$stepPath/countVariable", document, errors) }
                    if (step.countVariable == null && step.count > document.runLimits.maxLoopIterations) {
                        errors += error(
                            ValidationErrorCode.LIMIT_EXCEEDED,
                            "$stepPath/count",
                            "Repeat count exceeds maxLoopIterations",
                        )
                    }
                }

                is Jump -> {
                    if (step.targetStepId == step.id || step.targetStepId !in availableJumpTargets) {
                        errors += error(
                            ValidationErrorCode.UNKNOWN_STEP_REFERENCE,
                            "$stepPath/targetStepId",
                            "Go to must target another block or boundary in this nest or an enclosing level",
                        )
                    }
                    if (step.maxVisits > document.runLimits.maxLoopIterations) {
                        errors += error(
                            ValidationErrorCode.LIMIT_EXCEEDED,
                            "$stepPath/maxVisits",
                            "Jump count exceeds maxLoopIterations",
                        )
                    }
                }

                is SwipePath -> {
                    if (step.points.size > document.runLimits.maxPathPoints) {
                        errors += error(
                            ValidationErrorCode.LIMIT_EXCEEDED,
                            "$stepPath/points",
                            "Path point count exceeds maxPathPoints",
                        )
                    }
                }

                is LaunchApp -> {
                    validatePackage(step.packageName, "$stepPath/packageName", document, errors)
                }

                is SetVariable -> validateSetVariable(step, stepPath, document, errors)

                is Wait -> step.durationVariable?.let { validateNumberVariable(it, "$stepPath/durationVariable", document, errors) }

                is LongPress -> step.durationVariable?.let { validateNumberVariable(it, "$stepPath/durationVariable", document, errors) }

                is CaptureImage, is StageBoundary, is Stop, is TapPoint, is TapRegion -> Unit
            }

            seenStepIds += step.id
            when (step) {
                is IfElse -> {
                    validateSteps(
                        step.thenSteps,
                        "$stepPath/thenSteps",
                        depth + 1,
                        document,
                        runtimeCaptures,
                        allStepIds,
                        seenStepIds,
                        declaredAssets,
                        referencedAssets,
                        errors,
                        availableJumpTargets,
                    )
                    validateSteps(
                        step.elseSteps,
                        "$stepPath/elseSteps",
                        depth + 1,
                        document,
                        runtimeCaptures,
                        allStepIds,
                        seenStepIds,
                        declaredAssets,
                        referencedAssets,
                        errors,
                        availableJumpTargets,
                    )
                }

                is Repeat -> validateSteps(
                    step.bodySteps,
                    "$stepPath/bodySteps",
                    depth + 1,
                    document,
                    runtimeCaptures,
                    allStepIds,
                    seenStepIds,
                    declaredAssets,
                    referencedAssets,
                    errors,
                    availableJumpTargets,
                )

                is WhileStep -> validateSteps(
                    step.bodySteps,
                    "$stepPath/bodySteps",
                    depth + 1,
                    document,
                    runtimeCaptures,
                    allStepIds,
                    seenStepIds,
                    declaredAssets,
                    referencedAssets,
                    errors,
                    availableJumpTargets,
                )

                else -> Unit
            }
        }
    }

    private fun validateCondition(
        condition: Condition,
        path: String,
        document: WorkflowDocument,
        runtimeCaptures: Map<String, List<CaptureImage>>,
        allStepIds: Set<String>,
        seenStepIds: Set<String>,
        declaredAssets: Map<String, AssetReference>,
        referencedAssets: MutableSet<String>,
        errors: MutableList<ValidationError>,
    ) {
        condition.capturedBackgroundName()?.let { errors += error(ValidationErrorCode.WRONG_ASSET_KIND, "$path/capturedBackground", "Captured images cannot be visual backgrounds. Choose a saved preview; checks use the current screen.") }
        condition.takeIf { it.capturedImageName() != null }?.capturedTargetIssue(runtimeCaptures, document.viewport.recordedWidthPx, document.viewport.recordedHeightPx)?.let { errors += error(ValidationErrorCode.LIMIT_EXCEEDED, "$path/region", it) }
        condition.backgroundAssetId()?.let { validateImageAsset(it, "$path/backgroundAssetId", AssetKind.IMAGE_TEMPLATE, declaredAssets, referencedAssets, errors) }
        when (condition) {
            is CapturedImageCondition -> validateCondition(condition.integratedImageCondition(), path, document, runtimeCaptures, allStepIds, seenStepIds, declaredAssets, referencedAssets, errors)

            is PixelColorCondition -> Unit

            is ImagePresentCondition -> validateVisualImage(condition.assetId, condition.capturedImage, condition.region, condition.matchMode == ImageMatchMode.SCREEN_STATE, path, document, runtimeCaptures, declaredAssets, referencedAssets, errors)

            is ImageAbsentCondition -> validateVisualImage(condition.assetId, condition.capturedImage, condition.region, false, path, document, runtimeCaptures, declaredAssets, referencedAssets, errors)

            is ForegroundPackageCondition -> validatePackage(condition.packageName, "$path/packageName", document, errors)

            is OrientationCondition -> Unit

            is VariableComparisonCondition -> validateComparison(condition, path, document, errors)

            is StepResultCondition -> {
                when {
                    condition.stepId !in allStepIds ->
                        errors += error(
                            ValidationErrorCode.UNKNOWN_STEP_REFERENCE,
                            "$path/stepId",
                            "Referenced step does not exist",
                        )

                    condition.stepId !in seenStepIds ->
                        errors += error(
                            ValidationErrorCode.FORWARD_STEP_REFERENCE,
                            "$path/stepId",
                            "Referenced step must appear earlier in document traversal order",
                        )
                }
            }

            is AndCondition -> {
                if (condition.conditions.size < 2) {
                    errors += error(
                        ValidationErrorCode.INVALID_LOGICAL_ARITY,
                        path,
                        "And requires at least two conditions",
                    )
                }
                condition.conditions.forEachIndexed { index, child ->
                    validateCondition(
                        child,
                        "$path/conditions/$index",
                        document,
                        runtimeCaptures,
                        allStepIds,
                        seenStepIds,
                        declaredAssets,
                        referencedAssets,
                        errors,
                    )
                }
            }

            is OrCondition -> {
                if (condition.conditions.size < 2) {
                    errors += error(
                        ValidationErrorCode.INVALID_LOGICAL_ARITY,
                        path,
                        "Or requires at least two conditions",
                    )
                }
                condition.conditions.forEachIndexed { index, child ->
                    validateCondition(
                        child,
                        "$path/conditions/$index",
                        document,
                        runtimeCaptures,
                        allStepIds,
                        seenStepIds,
                        declaredAssets,
                        referencedAssets,
                        errors,
                    )
                }
            }

            is NotCondition -> validateCondition(
                condition.condition,
                "$path/condition",
                document,
                runtimeCaptures,
                allStepIds,
                seenStepIds,
                declaredAssets,
                referencedAssets,
                errors,
            )
        }
    }

    private fun captureDimensions(name: VariableName, path: String, document: WorkflowDocument, captures: Map<String, List<CaptureImage>>, errors: MutableList<ValidationError>): List<Pair<Int, Int>> {
        val definitions = captures[name.value]
        if (definitions.isNullOrEmpty()) errors += error(ValidationErrorCode.MISSING_VARIABLE, path, "Add a Capture image block for ${name.value}")
        fun span(start: Double, size: Double, pixels: Int) = kotlin.math.ceil((start + size) * pixels).toInt().coerceAtMost(pixels) - kotlin.math.floor(start * pixels).toInt()
        return definitions.orEmpty().map { span(it.region.x, it.region.width, document.viewport.recordedWidthPx) to span(it.region.y, it.region.height, document.viewport.recordedHeightPx) }.distinct()
    }

    private fun validateVisualImage(assetId: AssetId?, captured: VariableName?, region: NormalizedRect, screenState: Boolean, path: String, document: WorkflowDocument, captures: Map<String, List<CaptureImage>>, assets: Map<String, AssetReference>, references: MutableSet<String>, errors: MutableList<ValidationError>) {
        if ((assetId == null) == (captured == null)) errors += error(ValidationErrorCode.MISSING_ASSET, path, "Choose one saved or captured target image.")
        val sizes = if (captured != null) {
            captureDimensions(captured, "$path/capturedImage", document, captures, errors)
        } else {
            assetId?.let { validateImageAsset(it, path, AssetKind.IMAGE_TEMPLATE, assets, references, errors) }
            assetId?.let { assets[it.value] }?.let { image -> image.widthPx?.let { w -> image.heightPx?.let { h -> listOf(w to h) } } }.orEmpty()
        }
        val backgrounds = listOf(document.viewport.recordedWidthPx to document.viewport.recordedHeightPx)
        sizes.forEach { (w, h) ->
            backgrounds.forEach { (bw, bh) ->
                val rw = kotlin.math.ceil((region.x + region.width) * bw).toInt().coerceAtMost(bw) - kotlin.math.floor(region.x * bw).toInt()
                val rh = kotlin.math.ceil((region.y + region.height) * bh).toInt().coerceAtMost(bh) - kotlin.math.floor(region.y * bh).toInt()
                val invalid = if (screenState) w != bw || h != bh else w > rw || h > rh
                if (invalid) errors += error(ValidationErrorCode.LIMIT_EXCEEDED, "$path/region", if (screenState) "The target image must match the $bw × $bh px background size." else "The $w × $h px image cannot fit the $rw × $rh px search area. Enlarge the area or use a smaller crop.")
            }
        }
    }

    private fun validateImageAsset(
        assetId: AssetId,
        path: String,
        expectedKind: AssetKind,
        declaredAssets: Map<String, AssetReference>,
        referencedAssets: MutableSet<String>,
        errors: MutableList<ValidationError>,
    ) {
        referencedAssets += assetId.value
        val asset = declaredAssets[assetId.value]
        when {
            asset == null -> Unit

            asset.kind != expectedKind -> errors += error(
                ValidationErrorCode.WRONG_ASSET_KIND,
                "$path/assetId",
                "Asset kind does not support this condition",
            )
        }
    }

    private fun validateSetVariable(
        step: SetVariable,
        path: String,
        document: WorkflowDocument,
        errors: MutableList<ValidationError>,
    ) {
        val declaration = document.variables[step.name.value]
        if (declaration == null) {
            errors += error(
                ValidationErrorCode.MISSING_VARIABLE,
                "$path/name",
                "Variable is not declared",
            )
        } else if (declaration.type != step.value.variableType()) {
            errors += error(
                ValidationErrorCode.VARIABLE_TYPE_MISMATCH,
                "$path/value",
                "Assigned value does not match the declared variable type",
            )
        }
        val sourceType = step.source?.let { operandType(it, "$path/source", document, errors) } ?: step.value.variableType()
        if (declaration != null && sourceType != declaration.type) {
            errors += error(ValidationErrorCode.VARIABLE_TYPE_MISMATCH, "$path/source", "Source must match the assigned variable's type")
        }
        step.calculation?.let { calculation ->
            val rightType = operandType(calculation.operand, "$path/calculation/operand", document, errors)
            if (declaration?.type != VariableType.NUMBER || sourceType != VariableType.NUMBER || rightType != VariableType.NUMBER) {
                errors += error(ValidationErrorCode.VARIABLE_TYPE_MISMATCH, "$path/calculation", "Calculations require number values and a number variable")
            }
        }
    }

    private fun validateNumberVariable(name: VariableName, path: String, document: WorkflowDocument, errors: MutableList<ValidationError>) {
        if (operandType(VariableOperand(name), path, document, errors)?.let { it != VariableType.NUMBER } == true) {
            errors += error(ValidationErrorCode.VARIABLE_TYPE_MISMATCH, path, "Choose a number variable")
        }
    }

    private fun validateComparison(
        condition: VariableComparisonCondition,
        path: String,
        document: WorkflowDocument,
        errors: MutableList<ValidationError>,
    ) {
        val leftType = operandType(condition.left, "$path/left", document, errors)
        val rightType = operandType(condition.right, "$path/right", document, errors)
        if (leftType != null && rightType != null && leftType != rightType) {
            errors += error(
                ValidationErrorCode.VARIABLE_TYPE_MISMATCH,
                path,
                "Comparison operands must have the same type",
            )
        }
        if (
            condition.operator in setOf(
                ComparisonOperator.LESS_THAN,
                ComparisonOperator.LESS_THAN_OR_EQUAL,
                ComparisonOperator.GREATER_THAN,
                ComparisonOperator.GREATER_THAN_OR_EQUAL,
            ) && (leftType != VariableType.NUMBER || rightType != VariableType.NUMBER)
        ) {
            errors += error(
                ValidationErrorCode.ORDERED_COMPARISON_REQUIRES_NUMBER,
                path,
                "Ordered comparisons require number operands",
            )
        }
    }

    private fun operandType(
        operand: Operand,
        path: String,
        document: WorkflowDocument,
        errors: MutableList<ValidationError>,
    ): VariableType? = when (operand) {
        is ImageResultOperand -> {
            if (operand.name.value !in document.steps.imageSearches()) errors += error(ValidationErrorCode.MISSING_VARIABLE, path, "Add a Find image block for ${operand.name.value}")
            if (operand.field == ImageResultField.FOUND) VariableType.BOOLEAN else VariableType.NUMBER
        }

        is LiteralOperand -> operand.value.variableType()

        is VariableOperand -> document.variables[operand.name.value]?.type ?: run {
            errors += error(
                ValidationErrorCode.MISSING_VARIABLE,
                "$path/name",
                "Variable is not declared",
            )
            null
        }
    }

    private fun validatePackage(
        packageName: PackageName,
        path: String,
        document: WorkflowDocument,
        errors: MutableList<ValidationError>,
    ) {
        if (packageName !in document.allowedPackages) {
            errors += error(
                ValidationErrorCode.PACKAGE_NOT_ALLOWED,
                path,
                "Package must appear in allowedPackages",
            )
        }
    }

    private fun error(code: ValidationErrorCode, path: String, message: String) = ValidationError(code = code, path = path, message = message)

    private fun escape(segment: String): String = segment.replace("~", "~0").replace("/", "~1")
}
