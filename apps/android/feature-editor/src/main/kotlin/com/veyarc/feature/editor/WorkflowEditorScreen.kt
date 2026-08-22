@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.veyarc.feature.editor

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.veyarc.data.storage.WorkflowRepository
import com.veyarc.data.storage.WorkflowStorageException
import com.veyarc.data.storage.WorkflowSummary
import com.veyarc.model.DisplayOrientation
import com.veyarc.model.IfElse
import com.veyarc.model.LaunchApp
import com.veyarc.model.LongPress
import com.veyarc.model.Repeat
import com.veyarc.model.Stop
import com.veyarc.model.SwipePath
import com.veyarc.model.TapPoint
import com.veyarc.model.TapRegion
import com.veyarc.model.Wait
import com.veyarc.model.WaitUntil
import com.veyarc.model.WhileStep
import com.veyarc.model.WorkflowStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun WorkflowEditorScreen(
    repository: WorkflowRepository,
    onOpenAutomationHarness: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var summaries by remember { mutableStateOf<List<WorkflowSummary>>(emptyList()) }
    var editingDraft by remember { mutableStateOf<WorkflowDraft?>(null) }
    var isBusy by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var refreshGeneration by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshGeneration) {
        isBusy = true
        statusMessage = null
        summaries = runCatching {
            withContext(Dispatchers.IO) { repository.listWorkflows() }
        }.onFailure { error ->
            statusMessage = "Could not load workflows: ${error.userMessage()}"
        }.getOrElse { emptyList() }
        isBusy = false
    }

    fun openWorkflow(summary: WorkflowSummary) {
        scope.launch {
            isBusy = true
            statusMessage = null
            runCatching {
                withContext(Dispatchers.IO) {
                    repository.loadRevision(summary.workflowId, summary.latestRevision)
                }
            }.onSuccess { bundle ->
                editingDraft = WorkflowDraft.fromBundle(bundle)
            }.onFailure { error ->
                statusMessage = "Could not open workflow: ${error.userMessage()}"
            }
            isBusy = false
        }
    }

    fun saveWorkflow(bundle: com.veyarc.model.bundle.WorkflowBundle) {
        scope.launch {
            isBusy = true
            statusMessage = null
            runCatching {
                withContext(Dispatchers.IO) { repository.saveRevision(bundle) }
            }.onSuccess {
                editingDraft = WorkflowDraft.fromBundle(bundle)
                refreshGeneration += 1
                statusMessage = "Saved revision ${bundle.document.revision}."
            }.onFailure { error ->
                statusMessage = "Could not save revision: ${error.userMessage()}"
            }
            isBusy = false
        }
    }

    val draft = editingDraft
    if (draft == null) {
        WorkflowLibraryScreen(
            summaries = summaries,
            isBusy = isBusy,
            statusMessage = statusMessage,
            onCreate = {
                editingDraft = WorkflowDraft.newDefault(viewportFor(context))
                statusMessage = null
            },
            onOpen = ::openWorkflow,
            onOpenAutomationHarness = onOpenAutomationHarness,
            modifier = modifier,
        )
    } else {
        val validation = remember(draft) { draft.buildBundle() }
        WorkflowDraftScreen(
            draft = draft,
            validation = validation,
            isBusy = isBusy,
            statusMessage = statusMessage,
            onDraftChange = { editingDraft = it },
            onBack = {
                editingDraft = null
                refreshGeneration += 1
            },
            onSave = ::saveWorkflow,
            modifier = modifier,
        )
    }
}

@Composable
private fun WorkflowLibraryScreen(
    summaries: List<WorkflowSummary>,
    isBusy: Boolean,
    statusMessage: String?,
    onCreate: () -> Unit,
    onOpen: (WorkflowSummary) -> Unit,
    onOpenAutomationHarness: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Workflow library") },
                actions = {
                    TextButton(onClick = onOpenAutomationHarness) { Text("Accessibility test") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Create, review, and save versioned automation workflows locally.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        "Coordinates are normalized to the recorded display. Point, region, and path geometry editing is the next editor increment.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = onCreate, enabled = !isBusy) { Text("New workflow") }
                }
            }
            if (isBusy) item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
            statusMessage?.let { message ->
                item { Text(message, color = MaterialTheme.colorScheme.primary) }
            }
            if (!isBusy && summaries.isEmpty()) {
                item {
                    Text(
                        "No saved workflows yet. Create one to open the block editor.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(summaries, key = { it.workflowId }) { summary ->
                WorkflowSummaryCard(summary = summary, onClick = { onOpen(summary) })
            }
        }
    }
}

@Composable
private fun WorkflowSummaryCard(
    summary: WorkflowSummary,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(summary.name, style = MaterialTheme.typography.titleMedium)
            Text(
                "Revision ${summary.latestRevision} · Updated ${summary.updatedAtMs}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                summary.workflowId,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WorkflowDraftScreen(
    draft: WorkflowDraft,
    validation: DraftBuildResult,
    isBusy: Boolean,
    statusMessage: String?,
    onDraftChange: (WorkflowDraft) -> Unit,
    onBack: () -> Unit,
    onSave: (com.veyarc.model.bundle.WorkflowBundle) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(draft.name.ifBlank { "Workflow editor" }) },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                actions = {
                    TextButton(
                        enabled = !isBusy && validation.bundle != null,
                        onClick = { validation.bundle?.let(onSave) },
                    ) {
                        Text("Save v${draft.nextRevision}")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = draft.name,
                onValueChange = { onDraftChange(draft.copy(name = it)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Workflow name") },
                singleLine = true,
            )
            OutlinedTextField(
                value = draft.allowedPackagesText,
                onValueChange = { onDraftChange(draft.copy(allowedPackagesText = it)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Allowed Android packages") },
                supportingText = { Text("Separate packages with commas or spaces.") },
                singleLine = true,
            )
            Text(
                "${draft.lastSavedRevision?.let { "Saved revision $it" } ?: "New workflow"} · next save is revision ${draft.nextRevision}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ValidationCard(validation = validation)
            HorizontalDivider()
            Text("Blocks", style = MaterialTheme.typography.titleLarge)
            StepListEditor(
                draft = draft,
                path = StepListPath(),
                heading = "Main sequence",
                onDraftChange = onDraftChange,
            )
            statusMessage?.let { message ->
                Text(message, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun ValidationCard(validation: DraftBuildResult) {
    val valid = validation.issues.isEmpty()
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (valid) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.errorContainer
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                if (valid) "Workflow is valid and ready to save." else "Fix these issues before saving:",
                style = MaterialTheme.typography.titleSmall,
            )
            validation.issues.forEach { issue ->
                Text("${issue.path}: ${issue.message}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun StepListEditor(
    draft: WorkflowDraft,
    path: StepListPath,
    heading: String,
    onDraftChange: (WorkflowDraft) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(heading, style = MaterialTheme.typography.titleMedium)
        val steps = draft.stepsAt(path)
        if (steps.isEmpty()) {
            Text(
                "No blocks in this branch yet.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        steps.forEachIndexed { index, step ->
            StepCard(
                draft = draft,
                path = path,
                index = index,
                step = step,
                onDraftChange = onDraftChange,
            )
        }
        AddBlockMenu(
            defaultPackage = draft.allowedPackagesText.firstPackageOrDefault(),
            onAdd = { kind -> onDraftChange(draft.addStep(path, kind, draft.allowedPackagesText.firstPackageOrDefault())) },
        )
    }
}

@Composable
private fun StepCard(
    draft: WorkflowDraft,
    path: StepListPath,
    index: Int,
    step: WorkflowStep,
    onDraftChange: (WorkflowDraft) -> Unit,
) {
    val expanded = step.id !in draft.collapsedStepIds
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    "${index + 1}. ${step.displayName()}",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                )
                TextButton(onClick = { onDraftChange(draft.toggleCollapsed(step.id)) }) {
                    Text(if (expanded) "Collapse" else "Expand")
                }
            }
            Text(step.summary(), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    enabled = index > 0,
                    onClick = { onDraftChange(draft.moveStep(path, index, -1)) },
                ) { Text("Up") }
                TextButton(
                    enabled = index < draft.stepsAt(path).lastIndex,
                    onClick = { onDraftChange(draft.moveStep(path, index, 1)) },
                ) { Text("Down") }
                TextButton(onClick = { onDraftChange(draft.removeStep(path, index)) }) { Text("Remove") }
            }
            if (expanded) {
                StepEditorFields(
                    draft = draft,
                    path = path,
                    index = index,
                    step = step,
                    onDraftChange = onDraftChange,
                )
            }
        }
    }
}

@Composable
private fun StepEditorFields(
    draft: WorkflowDraft,
    path: StepListPath,
    index: Int,
    step: WorkflowStep,
    onDraftChange: (WorkflowDraft) -> Unit,
) {
    when (step) {
        is Wait -> {
            LongEditorField(
                label = "Duration (ms)",
                value = step.durationMs.value,
                onValueChange = { value ->
                    if (value >= 0) {
                        onDraftChange(
                            draft.updateStep(path, index) {
                                step.copy(durationMs = com.veyarc.model.DurationMillis(value))
                            },
                        )
                    }
                },
            )
        }

        is Repeat -> {
            LongEditorField(
                label = "Repeat count",
                value = step.count.toLong(),
                onValueChange = { value ->
                    if (value in 0..Int.MAX_VALUE) {
                        onDraftChange(draft.updateStep(path, index) { step.copy(count = value.toInt()) })
                    }
                },
            )
            StepListEditor(
                draft = draft,
                path = path.child(step.id, NestedBranch.REPEAT_BODY),
                heading = "Repeat body",
                onDraftChange = onDraftChange,
            )
        }

        is WhileStep -> {
            LongEditorField(
                label = "Maximum iterations",
                value = step.maxIterations.toLong(),
                onValueChange = { value ->
                    if (value in 1..Int.MAX_VALUE) {
                        onDraftChange(draft.updateStep(path, index) { step.copy(maxIterations = value.toInt()) })
                    }
                },
            )
            StepListEditor(
                draft = draft,
                path = path.child(step.id, NestedBranch.WHILE_BODY),
                heading = "While body",
                onDraftChange = onDraftChange,
            )
        }

        is IfElse -> {
            Text(
                "Condition: foreground package must match an allowed package.",
                style = MaterialTheme.typography.bodySmall,
            )
            StepListEditor(
                draft = draft,
                path = path.child(step.id, NestedBranch.IF_THEN),
                heading = "Then branch",
                onDraftChange = onDraftChange,
            )
            StepListEditor(
                draft = draft,
                path = path.child(step.id, NestedBranch.IF_ELSE),
                heading = "Else branch",
                onDraftChange = onDraftChange,
            )
        }

        is TapPoint,
        is TapRegion,
        is LongPress,
        is SwipePath,
        is WaitUntil,
        is LaunchApp,
        is Stop,
        -> {
            Text(
                "This block’s geometry and advanced settings are edited in the next editor increment.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        else -> {
            Text(
                "This block is available in the workflow contract but does not have a dedicated editor yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LongEditorField(
    label: String,
    value: Long,
    onValueChange: (Long) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { candidate ->
            text = candidate
            candidate.toLongOrNull()?.let(onValueChange)
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
    )
}

@Composable
private fun AddBlockMenu(
    defaultPackage: String,
    onAdd: (WorkflowStepKind) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text("Add block") }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            WorkflowStepKind.entries.forEach { kind ->
                DropdownMenuItem(
                    text = { Text(kind.displayName()) },
                    onClick = {
                        expanded = false
                        onAdd(kind)
                    },
                )
            }
        }
    }
}

private fun String.firstPackageOrDefault(): String = split(',', ' ', '\n', '\t')
    .map(String::trim)
    .firstOrNull { it.isNotEmpty() }
    ?: "com.veyarc.app"

private fun viewportFor(context: Context): com.veyarc.model.ViewportSpec {
    val metrics = context.resources.displayMetrics
    val orientation = if (context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
        DisplayOrientation.LANDSCAPE
    } else {
        DisplayOrientation.PORTRAIT
    }
    return com.veyarc.model.ViewportSpec(
        recordedWidthPx = metrics.widthPixels.coerceAtLeast(1),
        recordedHeightPx = metrics.heightPixels.coerceAtLeast(1),
        recordedDensityDpi = metrics.densityDpi.coerceAtLeast(1),
        recordedOrientation = orientation,
    )
}

private fun Throwable.userMessage(): String = when (this) {
    is WorkflowStorageException -> message ?: "storage operation failed"
    else -> message ?: "unexpected error"
}
