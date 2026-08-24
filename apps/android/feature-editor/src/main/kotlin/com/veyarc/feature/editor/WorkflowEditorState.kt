package com.veyarc.feature.editor

import com.veyarc.model.AssetReference
import com.veyarc.model.Condition
import com.veyarc.model.DisplayOrientation
import com.veyarc.model.DurationMillis
import com.veyarc.model.ForegroundPackageCondition
import com.veyarc.model.IfElse
import com.veyarc.model.LaunchApp
import com.veyarc.model.LongPress
import com.veyarc.model.NormalizedCoordinate
import com.veyarc.model.NormalizedRect
import com.veyarc.model.PackageName
import com.veyarc.model.RegionAnchor
import com.veyarc.model.Repeat
import com.veyarc.model.RunLimits
import com.veyarc.model.Stop
import com.veyarc.model.StopReason
import com.veyarc.model.SwipePath
import com.veyarc.model.TapPoint
import com.veyarc.model.TapRegion
import com.veyarc.model.TimedPoint
import com.veyarc.model.ViewportSpec
import com.veyarc.model.Wait
import com.veyarc.model.WaitUntil
import com.veyarc.model.WhileStep
import com.veyarc.model.WorkflowDocument
import com.veyarc.model.WorkflowIds
import com.veyarc.model.WorkflowStep
import com.veyarc.model.WorkflowValidator
import com.veyarc.model.bundle.BundleAsset
import com.veyarc.model.bundle.WorkflowBundle

enum class WorkflowStepKind {
    TAP_POINT,
    TAP_REGION,
    LONG_PRESS,
    SWIPE_PATH,
    WAIT,
    WAIT_UNTIL,
    IF_ELSE,
    REPEAT,
    WHILE,
    LAUNCH_APP,
    STOP,
}

enum class NestedBranch {
    REPEAT_BODY,
    WHILE_BODY,
    IF_THEN,
    IF_ELSE,
}

data class StepListSegment(
    val parentId: String,
    val branch: NestedBranch,
)

data class StepListPath(
    val segments: List<StepListSegment> = emptyList(),
) {
    fun child(parentId: String, branch: NestedBranch): StepListPath = copy(segments = segments + StepListSegment(parentId, branch))
}

data class DraftIssue(
    val path: String,
    val message: String,
)

data class DraftBuildResult(
    val bundle: WorkflowBundle?,
    val issues: List<DraftIssue>,
)

/** UI-owned editable state. It deliberately keeps text fields separate from the model. */
data class WorkflowDraft(
    val workflowId: String,
    val lastSavedRevision: Int?,
    val name: String,
    val allowedPackagesText: String,
    val viewport: ViewportSpec,
    val runLimits: RunLimits,
    val variables: Map<String, com.veyarc.model.VariableDeclaration>,
    val assets: List<BundleAsset>,
    val producerVersion: String,
    val steps: List<WorkflowStep>,
    val collapsedStepIds: Set<String> = emptySet(),
    val recordingJournalFileName: String? = null,
) {
    val nextRevision: Int
        get() = (lastSavedRevision ?: 0) + 1

    fun buildBundle(revision: Int = nextRevision): DraftBuildResult {
        val issues = mutableListOf<DraftIssue>()
        if (name.isBlank()) {
            issues += DraftIssue("/name", "Workflow name cannot be empty")
        } else if (name.length > 128) {
            issues += DraftIssue("/name", "Workflow name must be at most 128 characters")
        }

        val packageTokens = allowedPackagesText
            .split(',', '\n', ' ', '\t')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
        val allowedPackages = packageTokens.mapNotNull { value ->
            runCatching { PackageName(value) }
                .onFailure { error -> issues += DraftIssue("/allowedPackages", error.message ?: "Invalid package") }
                .getOrNull()
        }
        if (allowedPackages.isEmpty()) {
            issues += DraftIssue("/allowedPackages", "At least one valid Android package is required")
        }
        if (revision <= 0) {
            issues += DraftIssue("/revision", "Revision must be positive")
        }
        if (issues.isNotEmpty()) return DraftBuildResult(null, issues)

        val document = runCatching {
            WorkflowDocument(
                schemaVersion = WorkflowDocument.CURRENT_SCHEMA_VERSION,
                id = workflowId,
                revision = revision,
                name = name.trim(),
                allowedPackages = allowedPackages,
                viewport = viewport,
                runLimits = runLimits,
                variables = variables,
                assets = assets.map { asset -> AssetReference(asset.assetId, asset.kind) },
                steps = steps,
            )
        }.getOrElse { error ->
            return DraftBuildResult(
                null,
                listOf(DraftIssue("/document", error.message ?: "Workflow document is invalid")),
            )
        }

        val validationIssues = WorkflowValidator.validate(document).map { error ->
            DraftIssue(error.path, error.message)
        }
        if (validationIssues.isNotEmpty()) return DraftBuildResult(null, validationIssues)

        return DraftBuildResult(
            WorkflowBundle(
                document = document,
                assets = assets,
                producerVersion = producerVersion,
            ),
            emptyList(),
        )
    }

    fun toggleCollapsed(stepId: String): WorkflowDraft = copy(
        collapsedStepIds = if (stepId in collapsedStepIds) {
            collapsedStepIds - stepId
        } else {
            collapsedStepIds + stepId
        },
    )

    fun stepsAt(path: StepListPath): List<WorkflowStep> = readSteps(steps, path.segments)

    fun addStep(path: StepListPath, kind: WorkflowStepKind, defaultPackage: String): WorkflowDraft = updateList(path) { current -> current + newStep(kind, defaultPackage) }

    fun removeStep(path: StepListPath, index: Int): WorkflowDraft = updateList(path) { current ->
        current.toMutableList().apply {
            if (index in indices) removeAt(index)
        }
    }

    fun moveStep(path: StepListPath, index: Int, direction: Int): WorkflowDraft = updateList(path) { current ->
        val destination = index + direction
        if (index !in current.indices || destination !in current.indices) return@updateList current
        current.toMutableList().apply {
            add(destination, removeAt(index))
        }
    }

    fun updateStep(path: StepListPath, index: Int, transform: (WorkflowStep) -> WorkflowStep): WorkflowDraft = updateList(path) { current ->
        current.toMutableList().apply {
            if (index in indices) this[index] = transform(this[index])
        }
    }

    private fun updateList(path: StepListPath, transform: (List<WorkflowStep>) -> List<WorkflowStep>): WorkflowDraft = copy(steps = replaceSteps(steps, path.segments, transform(readSteps(steps, path.segments))))

    private fun readSteps(current: List<WorkflowStep>, path: List<StepListSegment>): List<WorkflowStep> {
        if (path.isEmpty()) return current
        val segment = path.first()
        val parent = current.firstOrNull { it.id == segment.parentId } ?: return emptyList()
        val children = parent.childrenFor(segment.branch) ?: return emptyList()
        return readSteps(children, path.drop(1))
    }

    private fun replaceSteps(
        current: List<WorkflowStep>,
        path: List<StepListSegment>,
        replacement: List<WorkflowStep>,
    ): List<WorkflowStep> {
        if (path.isEmpty()) return replacement
        val segment = path.first()
        return current.map { step ->
            if (step.id != segment.parentId) return@map step
            step.replaceChildren(segment.branch) { children ->
                replaceSteps(children, path.drop(1), replacement)
            }
        }
    }

    companion object {
        fun newDefault(viewport: ViewportSpec = defaultViewport()): WorkflowDraft {
            val defaultPackage = "com.veyarc.app"
            return WorkflowDraft(
                workflowId = WorkflowIds.newUuid(),
                lastSavedRevision = null,
                name = "New workflow",
                allowedPackagesText = defaultPackage,
                viewport = viewport,
                runLimits = defaultRunLimits(),
                variables = emptyMap(),
                assets = emptyList(),
                producerVersion = "android-editor",
                steps = listOf(
                    TapPoint(WorkflowIds.newUuid(), NormalizedCoordinate(0.1, 0.1)),
                    Wait(WorkflowIds.newUuid(), DurationMillis(500)),
                ),
            )
        }

        fun fromBundle(bundle: WorkflowBundle, recordingJournalFileName: String? = null): WorkflowDraft = WorkflowDraft(
            workflowId = bundle.document.id,
            lastSavedRevision = bundle.document.revision,
            name = bundle.document.name,
            allowedPackagesText = bundle.document.allowedPackages.joinToString(", ") { it.value },
            viewport = bundle.document.viewport,
            runLimits = bundle.document.runLimits,
            variables = bundle.document.variables,
            assets = bundle.assets,
            producerVersion = bundle.producerVersion,
            steps = bundle.document.steps,
            recordingJournalFileName = recordingJournalFileName,
        )

        private fun defaultViewport() = ViewportSpec(
            recordedWidthPx = 1080,
            recordedHeightPx = 2392,
            recordedDensityDpi = 420,
            recordedOrientation = DisplayOrientation.PORTRAIT,
        )

        private fun defaultRunLimits() = RunLimits(
            maxTotalSteps = 100,
            maxRuntimeMs = 60_000,
            maxLoopIterations = 100,
            maxNestingDepth = 8,
            maxPathPoints = 128,
            maxStepDurationMs = 30_000,
        )
    }
}

private fun WorkflowStep.childrenFor(branch: NestedBranch): List<WorkflowStep>? = when (branch) {
    NestedBranch.REPEAT_BODY -> (this as? Repeat)?.bodySteps
    NestedBranch.WHILE_BODY -> (this as? WhileStep)?.bodySteps
    NestedBranch.IF_THEN -> (this as? IfElse)?.thenSteps
    NestedBranch.IF_ELSE -> (this as? IfElse)?.elseSteps
}

private inline fun WorkflowStep.replaceChildren(
    branch: NestedBranch,
    transform: (List<WorkflowStep>) -> List<WorkflowStep>,
): WorkflowStep = when (branch) {
    NestedBranch.REPEAT_BODY -> (this as? Repeat)?.copy(bodySteps = transform(bodySteps)) ?: this
    NestedBranch.WHILE_BODY -> (this as? WhileStep)?.copy(bodySteps = transform(bodySteps)) ?: this
    NestedBranch.IF_THEN -> (this as? IfElse)?.copy(thenSteps = transform(thenSteps)) ?: this
    NestedBranch.IF_ELSE -> (this as? IfElse)?.copy(elseSteps = transform(elseSteps)) ?: this
}

fun newStep(kind: WorkflowStepKind, defaultPackage: String): WorkflowStep {
    val id = WorkflowIds.newUuid()
    val packageName = runCatching { PackageName(defaultPackage) }.getOrElse { PackageName("com.veyarc.app") }
    val condition: Condition = ForegroundPackageCondition(packageName)
    return when (kind) {
        WorkflowStepKind.TAP_POINT -> TapPoint(id, NormalizedCoordinate(0.5, 0.5))

        WorkflowStepKind.TAP_REGION -> TapRegion(id, NormalizedRect(0.4, 0.4, 0.2, 0.2), RegionAnchor.Center)

        WorkflowStepKind.LONG_PRESS -> LongPress(id, NormalizedCoordinate(0.5, 0.5), DurationMillis(1_000))

        WorkflowStepKind.SWIPE_PATH -> SwipePath(
            id = id,
            durationMs = DurationMillis(750),
            points = listOf(
                TimedPoint(NormalizedCoordinate(0.2, 0.5), DurationMillis(0)),
                TimedPoint(NormalizedCoordinate(0.8, 0.5), DurationMillis(750)),
            ),
        )

        WorkflowStepKind.WAIT -> Wait(id, DurationMillis(500))

        WorkflowStepKind.WAIT_UNTIL -> WaitUntil(id, condition, DurationMillis(5_000), DurationMillis(250))

        WorkflowStepKind.IF_ELSE -> IfElse(
            id = id,
            condition = condition,
            thenSteps = listOf(TapPoint(WorkflowIds.newUuid(), NormalizedCoordinate(0.5, 0.5))),
            elseSteps = emptyList(),
        )

        WorkflowStepKind.REPEAT -> Repeat(
            id = id,
            count = 2,
            bodySteps = listOf(Wait(WorkflowIds.newUuid(), DurationMillis(500))),
        )

        WorkflowStepKind.WHILE -> WhileStep(
            id = id,
            condition = condition,
            maxIterations = 5,
            bodySteps = listOf(Wait(WorkflowIds.newUuid(), DurationMillis(500))),
        )

        WorkflowStepKind.LAUNCH_APP -> LaunchApp(id, packageName, DurationMillis(5_000))

        WorkflowStepKind.STOP -> Stop(id, StopReason.USER_REQUESTED, "Stopped by workflow")
    }
}

fun WorkflowStepKind.displayName(): String = when (this) {
    WorkflowStepKind.TAP_POINT -> "Tap point"
    WorkflowStepKind.TAP_REGION -> "Tap region"
    WorkflowStepKind.LONG_PRESS -> "Long press"
    WorkflowStepKind.SWIPE_PATH -> "Swipe path"
    WorkflowStepKind.WAIT -> "Wait"
    WorkflowStepKind.WAIT_UNTIL -> "Wait until"
    WorkflowStepKind.IF_ELSE -> "If / else"
    WorkflowStepKind.REPEAT -> "Repeat"
    WorkflowStepKind.WHILE -> "While"
    WorkflowStepKind.LAUNCH_APP -> "Launch app"
    WorkflowStepKind.STOP -> "Stop"
}

fun WorkflowStep.displayName(): String = when (this) {
    is TapPoint -> "Tap point"
    is TapRegion -> "Tap region"
    is LongPress -> "Long press"
    is SwipePath -> "Swipe path"
    is Wait -> "Wait"
    is WaitUntil -> "Wait until"
    is IfElse -> "If / else"
    is Repeat -> "Repeat"
    is WhileStep -> "While"
    is LaunchApp -> "Launch app"
    is Stop -> "Stop"
    else -> "Step"
}

fun WorkflowStep.summary(): String = when (this) {
    is TapPoint -> "At ${(point.x * 100).toInt()}%, ${(point.y * 100).toInt()}% of the display"
    is TapRegion -> "${(region.width * 100).toInt()}% × ${(region.height * 100).toInt()}% region"
    is LongPress -> "${durationMs.value} ms at ${(point.x * 100).toInt()}%, ${(point.y * 100).toInt()}%"
    is SwipePath -> "${points.size} points over ${durationMs.value} ms"
    is Wait -> "Wait ${durationMs.value} ms"
    is WaitUntil -> "Timeout ${timeoutMs.value} ms, poll every ${pollIntervalMs.value} ms"
    is IfElse -> "Condition with ${thenSteps.size} then / ${elseSteps.size} else blocks"
    is Repeat -> "$count iterations, ${bodySteps.size} nested blocks"
    is WhileStep -> "Up to $maxIterations iterations, ${bodySteps.size} nested blocks"
    is LaunchApp -> "${packageName.value} (timeout ${foregroundTimeoutMs.value} ms)"
    is Stop -> message ?: reason.name.lowercase().replace('_', ' ')
    else -> "Configured workflow step"
}
