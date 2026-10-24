package com.veyarc.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class WorkflowDocument(
    val schemaVersion: Int,
    val id: String,
    val revision: Int,
    val name: String,
    val allowedPackages: List<PackageName>,
    val viewport: ViewportSpec,
    val runLimits: RunLimits,
    val variables: Map<String, VariableDeclaration>,
    val assets: List<AssetReference>,
    val steps: List<WorkflowStep>,
) {
    init {
        require(schemaVersion > 0) { "schemaVersion must be positive" }
        ContractChecks.requireStableId(id, "Workflow id")
        require(revision > 0) { "revision must be positive" }
        require(name.isNotBlank() && name.length <= 128) { "Workflow name must be 1 to 128 characters" }
        require(allowedPackages.isNotEmpty()) { "At least one allowed package is required" }
        require(steps.isNotEmpty()) { "Workflow must contain at least one step" }
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 1
    }
}

@Serializable
enum class ValidationErrorCode {
    @SerialName("invalidSection")
    INVALID_SECTION,

    @SerialName("unsupportedSchemaVersion")
    UNSUPPORTED_SCHEMA_VERSION,

    @SerialName("invalidWorkflowId")
    INVALID_WORKFLOW_ID,

    @SerialName("invalidVariableName")
    INVALID_VARIABLE_NAME,

    @SerialName("invalidPackageName")
    INVALID_PACKAGE_NAME,

    @SerialName("duplicatePackage")
    DUPLICATE_PACKAGE,

    @SerialName("duplicateStepId")
    DUPLICATE_STEP_ID,

    @SerialName("forwardStepReference")
    FORWARD_STEP_REFERENCE,

    @SerialName("unknownStepReference")
    UNKNOWN_STEP_REFERENCE,

    @SerialName("missingVariable")
    MISSING_VARIABLE,

    @SerialName("variableTypeMismatch")
    VARIABLE_TYPE_MISMATCH,

    @SerialName("orderedComparisonRequiresNumber")
    ORDERED_COMPARISON_REQUIRES_NUMBER,

    @SerialName("packageNotAllowed")
    PACKAGE_NOT_ALLOWED,

    @SerialName("duplicateAsset")
    DUPLICATE_ASSET,

    @SerialName("duplicateAssetName")
    DUPLICATE_ASSET_NAME,

    @SerialName("invalidAssetName")
    INVALID_ASSET_NAME,

    @SerialName("missingAsset")
    MISSING_ASSET,

    @SerialName("unusedAsset")
    UNUSED_ASSET,

    @SerialName("wrongAssetKind")
    WRONG_ASSET_KIND,

    @SerialName("invalidLogicalArity")
    INVALID_LOGICAL_ARITY,

    @SerialName("limitExceeded")
    LIMIT_EXCEEDED,
}

@Serializable
data class ValidationError(
    val code: ValidationErrorCode,
    val path: String,
    val message: String,
)
