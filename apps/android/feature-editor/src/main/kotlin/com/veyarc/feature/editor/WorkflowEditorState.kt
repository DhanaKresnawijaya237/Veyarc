package com.veyarc.feature.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.veyarc.model.AndCondition
import com.veyarc.model.AssetReference
import com.veyarc.model.Condition
import com.veyarc.model.DisplayOrientation
import com.veyarc.model.DurationMillis
import com.veyarc.model.ForegroundPackageCondition
import com.veyarc.model.IfElse
import com.veyarc.model.ImageAbsentCondition
import com.veyarc.model.ImageMatchMode
import com.veyarc.model.ImagePresentCondition
import com.veyarc.model.LaunchApp
import com.veyarc.model.LongPress
import com.veyarc.model.NormalizedCoordinate
import com.veyarc.model.NormalizedRect
import com.veyarc.model.NotCondition
import com.veyarc.model.OrCondition
import com.veyarc.model.PackageName
import com.veyarc.model.PixelColorCondition
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

class WorkflowEditorSession {
    var draft by mutableStateOf<WorkflowDraft?>(null)
        private set
    var destinationFolderId by mutableStateOf<String?>(null)
        private set
    private var savedDraft by mutableStateOf<WorkflowDraft?>(null)
    private var undoStack by mutableStateOf<List<WorkflowDraft>>(emptyList())
    private var redoStack by mutableStateOf<List<WorkflowDraft>>(emptyList())

    val isDirty: Boolean
        get() = draft?.persistedContent() != savedDraft?.persistedContent()
    val canUndo: Boolean
        get() = undoStack.isNotEmpty()
    val canRedo: Boolean
        get() = redoStack.isNotEmpty()
    val savedOrientation: DisplayOrientation?
        get() = savedDraft?.viewport?.recordedOrientation
    val requiresRevisionSave: Boolean
        get() = draft?.let { current ->
            current.lastSavedRevision != null &&
                current.lastSavedRevision < (current.latestKnownRevision ?: current.lastSavedRevision)
        } == true

    fun create(viewport: ViewportSpec, folderId: String? = null) {
        draft = WorkflowDraft.newDefault(viewport)
        destinationFolderId = folderId
        savedDraft = null
        clearHistory()
    }

    fun open(
        bundle: WorkflowBundle,
        recordingJournalFileName: String? = null,
        latestKnownRevision: Int = bundle.document.revision,
    ) {
        val opened = WorkflowDraft.fromBundle(bundle, recordingJournalFileName, latestKnownRevision)
        draft = opened
        destinationFolderId = null
        savedDraft = opened
        clearHistory()
    }

    fun update(updated: WorkflowDraft) {
        val current = draft ?: return
        if (current.persistedContent() != updated.persistedContent()) {
            undoStack = undoStack + current
            redoStack = emptyList()
        }
        draft = updated
    }

    fun undo() {
        val current = draft ?: return
        val previous = undoStack.lastOrNull() ?: return
        undoStack = undoStack.dropLast(1)
        redoStack = redoStack + current
        draft = previous
    }

    fun redo() {
        val current = draft ?: return
        val next = redoStack.lastOrNull() ?: return
        redoStack = redoStack.dropLast(1)
        undoStack = undoStack + current
        draft = next
    }

    fun markSaved(saved: WorkflowDraft) {
        draft = saved
        savedDraft = saved
        destinationFolderId = null
        clearHistory()
    }

    fun close() {
        draft = null
        destinationFolderId = null
        savedDraft = null
        clearHistory()
    }

    private fun clearHistory() {
        undoStack = emptyList()
        redoStack = emptyList()
    }
}

/** UI-owned editable state. It deliberately keeps text fields separate from the model. */
data class WorkflowDraft(
    val workflowId: String,
    val lastSavedRevision: Int?,
    val latestKnownRevision: Int? = lastSavedRevision,
    val name: String,
    val allowedPackagesText: String,
    val viewport: ViewportSpec,
    val runLimits: RunLimits,
    val variables: Map<String, com.veyarc.model.VariableDeclaration>,
    val assets: List<BundleAsset>,
    val producerVersion: String,
    val steps: List<WorkflowStep>,
    val assetNames: Map<String, String> = emptyMap(),
    val collapsedStepIds: Set<String> = emptySet(),
    val recordingJournalFileName: String? = null,
) {
    val nextRevision: Int
        get() = maxOf(lastSavedRevision ?: 0, latestKnownRevision ?: 0) + 1

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
                assets = assets.map { asset ->
                    AssetReference(asset.assetId, asset.kind, assetNames[asset.assetId.value])
                },
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

    fun addStep(path: StepListPath, kind: WorkflowStepKind, defaultPackage: String): WorkflowDraft = insertStep(path, stepsAt(path).size, kind, defaultPackage)

    fun insertStep(path: StepListPath, index: Int, kind: WorkflowStepKind, defaultPackage: String): WorkflowDraft {
        val step = newStep(kind, defaultPackage)
        return updateList(path) { current ->
            current.toMutableList().apply { add(index.coerceIn(0, size), step) }
        }
            .copy(collapsedStepIds = collapsedStepIds + step.id)
    }

    fun removeStep(path: StepListPath, index: Int): WorkflowDraft = updateList(path) { current ->
        current.toMutableList().apply {
            if (index in indices) removeAt(index)
        }
    }

    fun moveStep(path: StepListPath, index: Int, direction: Int): WorkflowDraft = updateList(path) { current ->
        moveStepInList(current, index, index + direction)
    }

    fun moveStepTo(path: StepListPath, index: Int, destination: Int): WorkflowDraft = updateList(path) { current ->
        moveStepInList(current, index, destination)
    }

    private fun moveStepInList(current: List<WorkflowStep>, index: Int, destination: Int): List<WorkflowStep> {
        if (index !in current.indices || destination !in current.indices) return current
        return current.toMutableList().apply {
            add(destination, removeAt(index))
        }
    }

    fun updateStep(path: StepListPath, index: Int, transform: (WorkflowStep) -> WorkflowStep): WorkflowDraft = updateList(path) { current ->
        current.toMutableList().apply {
            if (index in indices) this[index] = transform(this[index])
        }
    }

    fun updateCondition(
        path: StepListPath,
        index: Int,
        transform: (Condition) -> Condition,
    ): WorkflowDraft = updateStep(path, index) { step ->
        when (step) {
            is WaitUntil -> step.copy(condition = transform(step.condition))
            is IfElse -> step.copy(condition = transform(step.condition))
            is WhileStep -> step.copy(condition = transform(step.condition))
            else -> step
        }
    }

    fun addAsset(asset: BundleAsset): WorkflowDraft {
        val usedNames = assetNames.values.mapTo(mutableSetOf()) { it.lowercase() }
        var index = 1
        while ("template $index" in usedNames) index += 1
        return copy(
            assets = (assets.filterNot { it.assetId == asset.assetId } + asset).sortedBy { it.assetId.value },
            assetNames = assetNames + (asset.assetId.value to "Template $index"),
        )
    }

    fun removeAsset(assetId: com.veyarc.model.AssetId): WorkflowDraft = copy(
        assets = assets.filterNot { it.assetId == assetId },
        assetNames = assetNames - assetId.value,
    )

    fun renameAsset(assetId: com.veyarc.model.AssetId, name: String): WorkflowDraft {
        val normalized = name.trim()
        require(normalized.isNotEmpty() && normalized.length <= 64) { "Template name must be 1 to 64 characters" }
        require(assetNames.none { (id, existing) -> id != assetId.value && existing.equals(normalized, ignoreCase = true) }) {
            "Template names must be unique"
        }
        return copy(assetNames = assetNames + (assetId.value to normalized))
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
            val defaultSteps = listOf(
                TapPoint(WorkflowIds.newUuid(), NormalizedCoordinate(0.1, 0.1)),
                Wait(WorkflowIds.newUuid(), DurationMillis(500)),
            )
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
                steps = defaultSteps,
                assetNames = emptyMap(),
                collapsedStepIds = defaultSteps.allStepIds(),
            )
        }

        fun fromBundle(
            bundle: WorkflowBundle,
            recordingJournalFileName: String? = null,
            latestKnownRevision: Int = bundle.document.revision,
        ): WorkflowDraft = WorkflowDraft(
            workflowId = bundle.document.id,
            lastSavedRevision = bundle.document.revision,
            latestKnownRevision = latestKnownRevision,
            name = bundle.document.name,
            allowedPackagesText = bundle.document.allowedPackages.joinToString(", ") { it.value },
            viewport = bundle.document.viewport,
            runLimits = bundle.document.runLimits,
            variables = bundle.document.variables,
            assets = bundle.assets,
            producerVersion = bundle.producerVersion,
            steps = bundle.document.steps,
            assetNames = defaultAssetNames(bundle.document.assets),
            collapsedStepIds = bundle.document.steps.allStepIds(),
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

        private fun defaultAssetNames(assets: List<AssetReference>): Map<String, String> {
            val used = assets.mapNotNullTo(mutableSetOf()) { it.displayName?.lowercase() }
            var index = 1
            return assets.associate { asset ->
                val name = asset.displayName ?: run {
                    while ("template $index" in used) index += 1
                    "Template ${index++}"
                }
                used += name.lowercase()
                asset.assetId.value to name
            }
        }
    }
}

private fun List<WorkflowStep>.allStepIds(): Set<String> = buildSet {
    fun collect(step: WorkflowStep) {
        add(step.id)
        when (step) {
            is Repeat -> step.bodySteps.forEach(::collect)

            is WhileStep -> step.bodySteps.forEach(::collect)

            is IfElse -> {
                step.thenSteps.forEach(::collect)
                step.elseSteps.forEach(::collect)
            }

            else -> Unit
        }
    }
    this@allStepIds.forEach(::collect)
}

private fun WorkflowDraft.persistedContent(): WorkflowDraft = copy(
    latestKnownRevision = null,
    collapsedStepIds = emptySet(),
    recordingJournalFileName = null,
)

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
    is WaitUntil -> "${condition.summary()} · timeout ${timeoutMs.value} ms, poll every ${pollIntervalMs.value} ms"
    is IfElse -> "${condition.summary()} · ${thenSteps.size} then / ${elseSteps.size} else blocks"
    is Repeat -> "$count iterations, ${bodySteps.size} nested blocks"
    is WhileStep -> "${condition.summary()} · up to $maxIterations iterations, ${bodySteps.size} nested blocks"
    is LaunchApp -> "${packageName.value} (timeout ${foregroundTimeoutMs.value} ms)"
    is Stop -> message ?: reason.name.lowercase().replace('_', ' ')
    else -> "Configured workflow step"
}

fun Condition.summary(): String = when (this) {
    is PixelColorCondition -> "Pixel ${(point.x * 100).toInt()}%, ${(point.y * 100).toInt()}%"

    is ImagePresentCondition -> when (matchMode) {
        ImageMatchMode.APPEARS_WITHIN_REGION -> "Image ${assetId.value} appears in region"
        ImageMatchMode.SCREEN_STATE -> "Screen state matches ${assetId.value}"
    }

    is ImageAbsentCondition -> "Image ${assetId.value} absent"

    is ForegroundPackageCondition -> "Foreground ${packageName.value}"

    is com.veyarc.model.OrientationCondition -> "Orientation ${orientation.name.lowercase()}"

    is com.veyarc.model.VariableComparisonCondition -> "Variable comparison"

    is com.veyarc.model.StepResultCondition -> "Step result ${stepId.take(8)}…"

    is AndCondition -> "All of ${conditions.size} conditions"

    is OrCondition -> "Any of ${conditions.size} conditions"

    is NotCondition -> "Not (${condition.summary()})"
}
