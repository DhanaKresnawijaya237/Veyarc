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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.veyarc.feature.editor.RecordingEditorControls
import com.veyarc.feature.editor.RecordingImagePicker
import com.veyarc.feature.editor.WorkflowEditorScreen
import com.veyarc.model.DurationMillis
import com.veyarc.model.IfElse
import com.veyarc.model.WaitUntil
import com.veyarc.model.WhileStep
import com.veyarc.model.WorkflowIds

@Composable
internal fun RecordingBuilderScreen(controller: CoordinateRecordingController, state: RecordingBuilderState, onResume: () -> Unit, onAdd: () -> Unit) {
    BackHandler(onBack = controller::closeBuilder)
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    Column(Modifier.fillMaxSize().safeDrawingPadding().testTag("recording-builder")) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).animateContentSize(tween(140))) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Recording", style = MaterialTheme.typography.titleLarge)
                        AnimatedContent(controller.recordingDestination, transitionSpec = { fadeIn(tween(130)) togetherWith fadeOut(tween(90)) }, label = "recording destination") { destination ->
                            Text(destination, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    IconButton(onAdd, enabled = !state.preparing) { Icon(Icons.Outlined.Add, "Add recording block") }
                    IconButton(controller::closeBuilder) { Icon(Icons.Outlined.Close, "Close steps") }
                    if (landscape) RecordingActions(controller, state, onResume)
                }
                if (!landscape) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        RecordingActions(controller, state, onResume)
                    }
                }
            }
        }
        WorkflowEditorScreen(
            repository = controller.repository,
            session = controller.editorSession,
            onRunWorkflow = {},
            onOpenWorkflowManager = {},
            recordingControls = RecordingEditorControls(onRecord = controller::setDestination, onClose = controller::closeBuilder, preparing = state.preparing),
            modifier = Modifier.weight(1f),
        )
    }
    state.image?.let { image ->
        val draft = controller.editorSession.draft ?: return@let
        RecordingImagePicker(image, draft.viewport, draft.activeAssetNames(), onDismiss = controller::dismissImage) { condition, assets, name ->
            val step = when (state.imageKind) {
                RecordingImageBlock.IF -> IfElse(WorkflowIds.newUuid(), condition, emptyList(), emptyList())
                RecordingImageBlock.WHILE -> WhileStep(WorkflowIds.newUuid(), condition, 5, emptyList())
                else -> WaitUntil(WorkflowIds.newUuid(), condition, DurationMillis(5000), DurationMillis(250))
            }
            controller.addBlock(step, assets, imageName = name)
        }
    }
}

@Composable
private fun RecordingActions(controller: CoordinateRecordingController, state: RecordingBuilderState, onResume: () -> Unit) {
    TextButton(controller::stop) { Text("Finish") }
    if (controller.isInsideSection) TextButton(controller::continueOutside) { Text("End section") }
    Button(onResume, enabled = !state.preparing) {
        Icon(Icons.Outlined.PlayArrow, null, Modifier.size(18.dp))
        Text("Continue recording")
    }
}
