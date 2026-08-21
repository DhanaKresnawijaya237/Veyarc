package com.veyarc.model

/** Cross-field and cross-reference checks for an editable workflow document. */
object WorkflowValidator {
    fun validate(document: WorkflowDocument): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()
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

        val referencedAssets = mutableSetOf<String>()
        val seenStepIds = linkedSetOf<String>()
        validateSteps(
            steps = document.steps,
            path = "/steps",
            depth = 1,
            document = document,
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
        declaredAssets.keys
            .filterNot(referencedAssets::contains)
            .forEach { unused ->
                errors += error(
                    ValidationErrorCode.UNUSED_ASSET,
                    "/assets",
                    "Asset $unused is declared but never referenced",
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
        allStepIds: Set<String>,
        seenStepIds: MutableSet<String>,
        declaredAssets: Map<String, AssetReference>,
        referencedAssets: MutableSet<String>,
        errors: MutableList<ValidationError>,
    ) {
        if (depth > document.runLimits.maxNestingDepth) {
            errors += error(
                ValidationErrorCode.LIMIT_EXCEEDED,
                path,
                "Workflow nesting depth exceeds maxNestingDepth",
            )
        }
        steps.forEachIndexed { index, step ->
            val stepPath = "$path/$index"
            when (step) {
                is WaitUntil -> {
                    validateCondition(
                        condition = step.condition,
                        path = "$stepPath/condition",
                        document = document,
                        allStepIds = allStepIds,
                        seenStepIds = seenStepIds,
                        declaredAssets = declaredAssets,
                        referencedAssets = referencedAssets,
                        errors = errors,
                    )
                    validateDuration(step.timeoutMs.value, "$stepPath/timeoutMs", document, errors)
                    validateDuration(step.pollIntervalMs.value, "$stepPath/pollIntervalMs", document, errors)
                }

                is IfElse -> validateCondition(
                    condition = step.condition,
                    path = "$stepPath/condition",
                    document = document,
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
                    if (step.count > document.runLimits.maxLoopIterations) {
                        errors += error(
                            ValidationErrorCode.LIMIT_EXCEEDED,
                            "$stepPath/count",
                            "Repeat count exceeds maxLoopIterations",
                        )
                    }
                }

                is LongPress -> validateDuration(step.durationMs.value, "$stepPath/durationMs", document, errors)

                is SwipePath -> {
                    validateDuration(step.durationMs.value, "$stepPath/durationMs", document, errors)
                    if (step.points.size > document.runLimits.maxPathPoints) {
                        errors += error(
                            ValidationErrorCode.LIMIT_EXCEEDED,
                            "$stepPath/points",
                            "Path point count exceeds maxPathPoints",
                        )
                    }
                }

                is Wait -> validateDuration(step.durationMs.value, "$stepPath/durationMs", document, errors)

                is LaunchApp -> {
                    validateDuration(
                        step.foregroundTimeoutMs.value,
                        "$stepPath/foregroundTimeoutMs",
                        document,
                        errors,
                    )
                    validatePackage(step.packageName, "$stepPath/packageName", document, errors)
                }

                is SetVariable -> validateSetVariable(step, stepPath, document, errors)

                is Stop, is TapPoint, is TapRegion -> Unit
            }

            seenStepIds += step.id
            when (step) {
                is IfElse -> {
                    validateSteps(
                        step.thenSteps,
                        "$stepPath/thenSteps",
                        depth + 1,
                        document,
                        allStepIds,
                        seenStepIds,
                        declaredAssets,
                        referencedAssets,
                        errors,
                    )
                    validateSteps(
                        step.elseSteps,
                        "$stepPath/elseSteps",
                        depth + 1,
                        document,
                        allStepIds,
                        seenStepIds,
                        declaredAssets,
                        referencedAssets,
                        errors,
                    )
                }

                is Repeat -> validateSteps(
                    step.bodySteps,
                    "$stepPath/bodySteps",
                    depth + 1,
                    document,
                    allStepIds,
                    seenStepIds,
                    declaredAssets,
                    referencedAssets,
                    errors,
                )

                is WhileStep -> validateSteps(
                    step.bodySteps,
                    "$stepPath/bodySteps",
                    depth + 1,
                    document,
                    allStepIds,
                    seenStepIds,
                    declaredAssets,
                    referencedAssets,
                    errors,
                )

                else -> Unit
            }
        }
    }

    private fun validateCondition(
        condition: Condition,
        path: String,
        document: WorkflowDocument,
        allStepIds: Set<String>,
        seenStepIds: Set<String>,
        declaredAssets: Map<String, AssetReference>,
        referencedAssets: MutableSet<String>,
        errors: MutableList<ValidationError>,
    ) {
        when (condition) {
            is PixelColorCondition -> Unit

            is ImagePresentCondition -> validateImageAsset(condition.assetId, path, AssetKind.IMAGE_TEMPLATE, declaredAssets, referencedAssets, errors)

            is ImageAbsentCondition -> validateImageAsset(condition.assetId, path, AssetKind.IMAGE_TEMPLATE, declaredAssets, referencedAssets, errors)

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
                allStepIds,
                seenStepIds,
                declaredAssets,
                referencedAssets,
                errors,
            )
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

    private fun validateDuration(
        durationMs: Long,
        path: String,
        document: WorkflowDocument,
        errors: MutableList<ValidationError>,
    ) {
        if (durationMs > document.runLimits.maxStepDurationMs) {
            errors += error(
                ValidationErrorCode.LIMIT_EXCEEDED,
                path,
                "Duration exceeds maxStepDurationMs",
            )
        }
    }

    private fun error(code: ValidationErrorCode, path: String, message: String) = ValidationError(code = code, path = path, message = message)

    private fun escape(segment: String): String = segment.replace("~", "~0").replace("/", "~1")
}
