@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.veyarc.feature.recorder

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Loop
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.veyarc.feature.editor.RecordingEditorControls
import com.veyarc.feature.editor.RecordingImagePicker
import com.veyarc.feature.editor.WorkflowEditorScreen
import com.veyarc.model.DurationMillis
import com.veyarc.model.ForegroundPackageCondition
import com.veyarc.model.IfElse
import com.veyarc.model.PackageName
import com.veyarc.model.Repeat
import com.veyarc.model.Wait
import com.veyarc.model.WaitUntil
import com.veyarc.model.WhileStep
import com.veyarc.model.WorkflowIds

@Composable
internal fun RecordingBuilderScreen(controller: CoordinateRecordingController, state: RecordingBuilderState, onResume: () -> Unit) {
    BackHandler(onBack = controller::closeBuilder)
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    Column(Modifier.fillMaxSize().safeDrawingPadding().testTag("recording-builder")) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).animateContentSize(tween(140))) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Recording", style = MaterialTheme.typography.titleLarge)
                        AnimatedContent(controller.recordingDestination, transitionSpec = { fadeIn(tween(130)) togetherWith fadeOut(tween(90)) }, label = "recording destination") { destination ->
                            Text(destination, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    IconButton(controller::showAdd) { Icon(Icons.Outlined.Add, "Add recording block") }
                    IconButton(controller::closeBuilder) { Icon(Icons.Outlined.Close, "Close steps") }
                    if (landscape) {
                        TextButton(controller::stop) { Text("Finish") }
                        if (controller.isInsideSection) TextButton(controller::continueOutside) { Text("End section") }
                        Button(onResume, enabled = !state.preparing) {
                            Icon(Icons.Outlined.PlayArrow, null, Modifier.size(18.dp))
                            Text("Record actions")
                        }
                    }
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (!landscape) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Row {
                            TextButton(controller::stop) { Text("Finish") }
                            if (controller.isInsideSection) TextButton(controller::continueOutside) { Text("End section") }
                        }
                        Button(onResume, enabled = !state.preparing) {
                            Icon(Icons.Outlined.PlayArrow, null, Modifier.size(18.dp))
                            Text("Record actions")
                        }
                    }
                }
            }
        }
        WorkflowEditorScreen(
            repository = controller.repository,
            session = controller.editorSession,
            onRunWorkflow = {},
            onOpenWorkflowManager = {},
            recordingControls = RecordingEditorControls(onRecord = { path, index ->
                controller.setDestination(path, index)
                onResume()
            }, onClose = controller::closeBuilder),
            modifier = Modifier.weight(1f),
        )
    }
    if (state.showAdd) RecordingAddSheet(controller)
}

@Composable
private fun RecordingAddSheet(controller: CoordinateRecordingController) {
    val draft = controller.editorSession.draft ?: return
    var imageKind by remember { mutableStateOf<String?>(null) }
    var countKind by remember { mutableStateOf<String?>(null) }
    var number by remember { mutableStateOf("2") }
    val packageName = draft.allowedPackagesText.split(',', ' ', '\n').firstOrNull { it.isNotBlank() } ?: "com.veyarc.app"
    fun conditionBlock(kind: String) {
        val condition = ForegroundPackageCondition(PackageName(packageName))
        controller.addBlock(
            when (kind) {
                "if" -> IfElse(WorkflowIds.newUuid(), condition, emptyList(), emptyList())
                "while" -> WhileStep(WorkflowIds.newUuid(), condition, 5, emptyList())
                else -> WaitUntil(WorkflowIds.newUuid(), condition, DurationMillis(5000), DurationMillis(250))
            },
            editCondition = true,
        )
    }
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    ModalBottomSheet(
        onDismissRequest = controller::dismissAdd,
        modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)),
        contentWindowInsets = { WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom) },
        properties = ModalBottomSheetProperties(isAppearanceLightStatusBars = !dark, isAppearanceLightNavigationBars = !dark),
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text("Add to recording", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 8.dp)) }
            item { AddChoice("If image appears", "Crop it, then record what happens next", Icons.Outlined.Image) { imageKind = "if" } }
            item { AddChoice("While image appears", "Record the loop once", Icons.Outlined.Loop) { imageKind = "while" } }
            item { AddChoice("Wait for image", "Continue when it appears", Icons.Outlined.Schedule) { imageKind = "wait" } }
            item { AddChoice("If / otherwise", "Choose a condition in Steps", Icons.AutoMirrored.Outlined.CallSplit) { conditionBlock("if") } }
            item { AddChoice("While", "Choose a condition in Steps", Icons.Outlined.Loop) { conditionBlock("while") } }
            item {
                AddChoice("Repeat actions", "Record once, repeat when running", Icons.Outlined.Loop) {
                    number = "2"
                    countKind = "repeat"
                }
            }
            item {
                AddChoice("Wait", "Pause between actions", Icons.Outlined.Schedule) {
                    number = "1"
                    countKind = "wait"
                }
            }
            item { Text("Use + in Steps to add other blocks or paste copied actions.", Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
    imageKind?.let { kind ->
        RecordingImagePicker(draft.viewport, draft.activeAssetNames(), onDismiss = { imageKind = null }) { condition, assets, name ->
            val step = when (kind) {
                "if" -> IfElse(WorkflowIds.newUuid(), condition, emptyList(), emptyList())
                "while" -> WhileStep(WorkflowIds.newUuid(), condition, 5, emptyList())
                else -> WaitUntil(WorkflowIds.newUuid(), condition, DurationMillis(5000), DurationMillis(250))
            }
            controller.addBlock(step, assets, imageName = name)
        }
    }
    countKind?.let { kind ->
        val count = number.toLongOrNull()
        val valid = count != null && if (kind == "repeat") count in 1..Int.MAX_VALUE.toLong() else count in 1..Long.MAX_VALUE / 1000
        AlertDialog(onDismissRequest = { countKind = null }, title = { Text(if (kind == "repeat") "Repeat actions" else "Wait") }, text = {
            OutlinedTextField(number, { number = it }, label = { Text(if (kind == "repeat") "Times" else "Seconds") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }, confirmButton = {
            TextButton(enabled = valid, onClick = {
                controller.addBlock(if (kind == "repeat") Repeat(WorkflowIds.newUuid(), requireNotNull(count).toInt(), emptyList()) else Wait(WorkflowIds.newUuid(), DurationMillis(requireNotNull(count) * 1000)))
            }) { Text("Add") }
        }, dismissButton = { TextButton({ countKind = null }) { Text("Cancel") } })
    }
}

@Composable
private fun AddChoice(title: String, detail: String, icon: ImageVector, onClick: () -> Unit) {
    Surface(onClick, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
