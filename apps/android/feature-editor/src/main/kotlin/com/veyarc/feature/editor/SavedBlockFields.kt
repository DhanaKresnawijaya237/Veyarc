package com.veyarc.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.veyarc.model.ReusableSection
import com.veyarc.model.RunSection
import com.veyarc.model.StageBoundary
import com.veyarc.model.WorkflowIds
import com.veyarc.model.cyclicSections
import com.veyarc.model.resolveRange
import com.veyarc.model.reusableSections

@Composable
internal fun SavedBlockMode(section: ReusableSection) {
    Text(if (section.isLinked) "Linked" else "Copy", Modifier.background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
}

@Composable
internal fun SavedBlocksSection(session: WorkflowEditorSession, draft: WorkflowDraft, validation: DraftBuildResult, onChange: (WorkflowDraft) -> Unit, onCreate: () -> Unit) {
    val sections = draft.steps.reusableSections()
    if (sections.isEmpty()) return
    var deleting by remember { mutableStateOf<String?>(null) }
    EditorSetupSection("Saved blocks", Icons.AutoMirrored.Outlined.CallSplit, session.savedBlocksExpanded, { session.savedBlocksExpanded = !session.savedBlocksExpanded }, onCreate, "Save a block range") {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            sections.values.forEach { section ->
                Row(
                    Modifier.fillMaxWidth().clickable(indication = null, interactionSource = null) {
                        session.openSavedBlocks(section.id)
                    }.padding(start = 8.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(section.name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                        SavedBlockUsage(session, draft, section.id)
                        val index = draft.steps.indexOfFirst { it.id == section.id }
                        if (validation.issues.any { it.path == "/steps/$index" || it.path.startsWith("/steps/$index/") }) Text("Needs attention", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                    SavedBlockMode(section)
                    IconButton(onClick = { deleting = section.id }) { Icon(Icons.Outlined.Delete, "Delete saved blocks ${section.name}") }
                }
            }
        }
    }
    deleting?.let { id ->
        val section = sections[id] ?: return@let
        val callers = draft.sequenceCallers(id)
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete ${section.name}?") }, text = {
            Column {
                if (callers.isEmpty()) {
                    Text("This sequence is not used.")
                } else {
                    Text("These run blocks will need another sequence:")
                    callers.take(4).forEach { Text(it.title, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
                    if (callers.size > 4) Text("And ${callers.size - 4} more")
                }
            }
        }, confirmButton = {
            TextButton(onClick = {
                onChange(draft.copy(steps = draft.steps.filterNot { it.id == id }))
                deleting = null
            }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        }, dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } })
    }
}

@Composable
private fun SavedBlockUsage(session: WorkflowEditorSession, draft: WorkflowDraft, id: String) {
    val callers = remember(draft.steps, id) { draft.sequenceCallers(id) }
    var showing by remember(id) { mutableStateOf(false) }
    Text(if (callers.isEmpty()) "Not used" else "Used in ${callers.size} ${if (callers.size == 1) "place" else "places"}", Modifier.clickable(enabled = callers.isNotEmpty(), indication = null, interactionSource = null) { showing = true }.padding(vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = if (callers.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
    if (showing) {
        VeyarcChoicePicker("Used in", callers.map { VeyarcChoice(it.step.id, it.label, detail = it.context) }, onDismiss = { showing = false }, onSelect = { choice ->
            showing = false
            session.revealBlock(choice.id)
        })
    }
}

@Composable
internal fun SavedRangeFields(draft: WorkflowDraft, startId: String, endId: String, onChange: (String, String) -> Unit) {
    var pickingEnd by remember { mutableStateOf<Boolean?>(null) }
    val sources = remember(draft.steps) { draft.savedBlockSources() }
    val source = sources.firstOrNull { list -> list.steps.any { it.id == startId } }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(false to "From", true to "To").forEach { (end, label) ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                val id = if (end) endId else startId
                Text(sources.firstOrNull { list -> list.steps.any { it.id == id } }?.label(id) ?: "Choose a block", Modifier.clickable(indication = null, interactionSource = null) { pickingEnd = end }.padding(vertical = 8.dp).testTag(if (end) "saved-range-end" else "saved-range-start"), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
    pickingEnd?.let { end ->
        val offered = if (end && source != null) listOf(source) else sources
        VeyarcChoicePicker(
            title = if (end) "End block" else "Start block",
            selectedId = if (end) endId else startId,
            options = offered.flatMapIndexed { group, list ->
                list.steps.filter { !end || source == null || list.steps.indexOf(it) >= list.steps.indexOfFirst { item -> item.id == startId } }.map { VeyarcChoice(it.id, list.label(it.id), group = group, groupTitle = list.title) }
            },
            onDismiss = { pickingEnd = null },
            onSelect = { choice ->
                if (end) {
                    onChange(if (source == null) choice.id else startId, choice.id)
                } else {
                    val selectedSource = sources.first { list -> list.steps.any { it.id == choice.id } }
                    val first = selectedSource.steps.indexOfFirst { it.id == choice.id }
                    val last = selectedSource.steps.indexOfFirst { it.id == endId }
                    onChange(choice.id, endId.takeIf { last >= first } ?: choice.id)
                }
                pickingEnd = null
            },
        )
    }
}

@Composable
internal fun SaveBlocksDialog(session: WorkflowEditorSession, draft: WorkflowDraft, onChange: (WorkflowDraft) -> Unit) {
    val pending = session.pendingSavedBlocks ?: return
    val range = draft.steps.resolveRange(pending.startId, pending.endId)
    val validName = pending.name.isNotBlank() && pending.name.trim().length <= 80 && draft.steps.reusableSections().values.none { it.name.trim().equals(pending.name.trim(), true) }
    EditorSheetDialog(compact = true, onDismiss = { session.pendingSavedBlocks = null }) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Save blocks", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                Button(enabled = !session.isBusy && validName && range?.any { it !is StageBoundary } == true, onClick = {
                    runCatching { draft.saveBlocks(pending) }.onSuccess { (updated, _) ->
                        onChange(updated)
                        session.savedBlocksExpanded = true
                        session.pendingSavedBlocks = null
                        session.clearBlockSelection()
                    }.onFailure { session.savedBlockError = it.message ?: "Could not save these blocks" }
                }) { Text("Save") }
                IconButton(onClick = { session.pendingSavedBlocks = null }) { Icon(Icons.Outlined.Close, "Close save blocks") }
            }
            TextField(pending.name, { session.pendingSavedBlocks = pending.copy(name = it) }, Modifier.fillMaxWidth().testTag("saved-block-name"), label = { Text("Name") }, singleLine = true, isError = !validName, shape = RoundedCornerShape(14.dp))
            if (pending.selectedIds == null) {
                InlineVariableOptions(listOf("linked" to "Linked range", "copy" to "Saved copy"), if (pending.linked) "linked" else "copy") { session.pendingSavedBlocks = pending.copy(linked = it == "linked") }
            } else {
                Text("Saved copy · ${pending.selectedIds.size} selected blocks", style = MaterialTheme.typography.bodyMedium)
            }
            SavedRangeFields(draft, pending.startId, pending.endId) { start, end -> session.pendingSavedBlocks = pending.copy(startId = start, endId = end, selectedIds = null) }
        }
    }
}

@Composable
internal fun SavedBlocksEditor(session: WorkflowEditorSession, draft: WorkflowDraft, validation: DraftBuildResult, onChange: (WorkflowDraft) -> Unit, onGeometry: (StepListPath, Int) -> Unit, onCondition: (StepListPath, Int) -> Unit, onRun: ((String) -> Unit)? = null) {
    val id = session.savedBlockEditorId ?: return
    val section = draft.steps.reusableSections()[id]
    if (section == null) {
        val caller = session.savedBlockCaller ?: return
        val index = draft.stepsAt(caller.path).indexOfFirst { it.id == caller.stepId }
        val offered = draft.steps.reusableSections().values.filter { target -> index >= 0 && target.id !in draft.updateStep(caller.path, index) { (it as RunSection).copy(sectionId = target.id) }.steps.cyclicSections() }
        VeyarcChoicePicker("Choose saved blocks", offered.map { VeyarcChoice(it.id, it.name, detail = if (it.isLinked) "Linked" else "Copy") }, onDismiss = {
            session.savedBlockEditorId = null
            session.savedBlockCaller = null
        }, onSelect = { choice ->
            onChange(draft.updateStep(caller.path, index) { (it as RunSection).copy(sectionId = choice.id, inputs = emptyMap(), outputs = emptyMap()) })
            session.openSavedBlocks(choice.id, session.savedBlockCaller)
        })
        return
    }
    val index = draft.steps.indexOfFirst { it.id == id }
    val coordinator = remember(id) { StepDragCoordinator() }
    var choosing by remember(id) { mutableStateOf(false) }
    val dismiss = {
        val parent = session.savedBlockCaller?.path?.sectionId
        session.savedBlockEditorId = parent.takeIf { it != id }
        session.savedBlockCaller = null
    }
    EditorSheetDialog(compact = false, onDismiss = dismiss) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        ReusableSectionName(section.name, draft.steps.reusableSections().values.filterNot { it.id == id }.map { it.name.trim() }) { name -> onChange(draft.updateStep(StepListPath(), index) { section.copy(name = name) }) }
                    }
                    SavedBlockMode(section)
                    IconButton(onClick = dismiss) { Icon(Icons.Outlined.Close, "Close saved blocks") }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { SavedBlockUsage(session, draft, id) }
                    if (session.savedBlockCaller != null) {
                        IconButton(onClick = {
                            val caller = session.savedBlockCaller ?: return@IconButton
                            runCatching { draft.makeCallIndependent(caller) }.onSuccess { (updated, copyId) ->
                                onChange(updated)
                                session.openSavedBlocks(copyId, caller)
                            }.onFailure { session.savedBlockError = it.message ?: "Could not copy saved blocks" }
                        }) { Icon(Icons.Outlined.ContentCopy, "Make independent") }
                        IconButton(onClick = { choosing = true }) { Icon(Icons.Outlined.SwapHoriz, "Choose saved blocks") }
                    }
                    if (onRun != null) {
                        IconButton(onClick = { onRun(id) }, enabled = !session.isBusy) {
                            Icon(Icons.Outlined.PlayArrow, "Try sequence", tint = if (session.isBusy) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f) else MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            validation.issues.filter { it.path == "/steps/$index" || it.path.startsWith("/steps/$index/") }.forEach { issue -> EditorIssueRow(draft, issue) { session.revealIssue(issue) } }
            val caller = session.savedBlockCaller
            val callIndex = caller?.let { draft.stepsAt(it.path).indexOfFirst { item -> item.id == it.stepId } } ?: -1
            val call = caller?.let { draft.stepsAt(it.path).getOrNull(callIndex) as? RunSection }
            if (caller != null && call != null) SequenceCallFields(section, call, draft) { updated -> onChange(draft.updateStep(caller.path, callIndex) { updated }) }
            SequenceParameters(section, draft) { inputs, outputs -> onChange(draft.updateSequenceParameters(section.id, inputs, outputs)) }
            if (section.isLinked) {
                SavedRangeFields(draft, requireNotNull(section.startStepId), requireNotNull(section.endStepId)) { start, end -> onChange(draft.updateStep(StepListPath(), index) { section.copy(startStepId = start, endStepId = end) }) }
            } else {
                StepListEditor(draft, StepListPath().child(section.id, NestedBranch.SECTION_BODY), "Actions", validation, session.blockSelection, true, session::selectBlock, onChange, onGeometry, onCondition, coordinator)
            }
        }
    }
    if (choosing) {
        val caller = session.savedBlockCaller
        val callIndex = caller?.let { draft.stepsAt(it.path).indexOfFirst { step -> step.id == it.stepId } } ?: -1
        val options = draft.steps.reusableSections().values.filter { target ->
            caller != null && callIndex >= 0 && target.id !in draft.updateStep(caller.path, callIndex) { (it as RunSection).copy(sectionId = target.id) }.steps.cyclicSections()
        }
        VeyarcChoicePicker("Run saved blocks", options.map { VeyarcChoice(it.id, it.name, detail = if (it.isLinked) "Linked" else "Copy") }, selectedId = id, onDismiss = { choosing = false }, onSelect = { choice ->
            if (caller != null && callIndex >= 0) {
                onChange(draft.updateStep(caller.path, callIndex) { (it as RunSection).copy(sectionId = choice.id, inputs = emptyMap(), outputs = emptyMap()) })
                session.openSavedBlocks(choice.id, session.savedBlockCaller)
            }
            choosing = false
        })
    }
}
