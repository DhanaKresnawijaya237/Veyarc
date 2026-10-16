package com.veyarc.feature.editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Title
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.veyarc.data.storage.DecodedTemplate
import com.veyarc.model.NormalizedRect
import com.veyarc.ui.AnnotationStroke
import com.veyarc.ui.ImageEditor
import com.veyarc.ui.ImageEdits
import com.veyarc.ui.ImageEditsSaver
import kotlin.math.ceil
import kotlin.math.floor

private val DEFAULT_TEMPLATE_CROP = NormalizedRect(0.25, 0.4, 0.5, 0.2)
internal val FULL_TEMPLATE_CROP = NormalizedRect(0.0, 0.0, 1.0, 1.0)
internal data class TemplateCropDraft(val source: TemplateCropSource, val name: String = "", val edits: ImageEdits = ImageEdits(DEFAULT_TEMPLATE_CROP, "crop"), val error: String? = null)

@Composable
internal fun TemplateCropEditorDialog(draft: TemplateCropDraft, displayWidthPx: Int, displayHeightPx: Int, existingNames: Set<String>, saving: Boolean, onDraftChange: (TemplateCropDraft) -> Unit, onDismiss: () -> Unit, onApply: (NormalizedRect, Int, String, List<AnnotationStroke>, Boolean) -> Unit, backgroundInUse: Boolean = false) {
    val edits = rememberSaveable(draft.source.uri.toString(), saver = ImageEditsSaver) { draft.edits }
    LaunchedEffect(edits) { if (edits !== draft.edits) onDraftChange(draft.copy(edits = edits)) }
    var naming by rememberSaveable { mutableStateOf(false) }
    var saveChoice by rememberSaveable { mutableStateOf(false) }
    val swapped = edits.turns % 2 != 0
    val sourceWidth = if (swapped) draft.source.heightPx else draft.source.widthPx
    val sourceHeight = if (swapped) draft.source.widthPx else draft.source.heightPx
    val width = (ceil((edits.crop.x + edits.crop.width) * sourceWidth) - floor(edits.crop.x * sourceWidth)).toInt().coerceAtLeast(1)
    val height = (ceil((edits.crop.y + edits.crop.height) * sourceHeight) - floor(edits.crop.y * sourceHeight)).toInt().coerceAtLeast(1)
    val removesBackgrounds = backgroundInUse && (width != draft.source.widthPx || height != draft.source.heightPx)
    val nameValid = draft.name.isBlank() || (draft.name.trim().length <= 64 && existingNames.none { it.equals(draft.name.trim(), true) })
    val supported = width.toLong() * height <= DecodedTemplate.MAX_TEMPLATE_PIXELS && width <= displayWidthPx && height <= displayHeightPx
    fun save(replace: Boolean) {
        saveChoice = false
        onApply(edits.crop, edits.turns, draft.name.trim(), edits.strokes, replace)
    }
    Dialog(onDismissRequest = { if (!saving) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize().testTag("template-crop-editor"), color = Color.Black) {
            ImageEditor(
                draft.source.preview.asAndroidBitmap(), edits, true, saving, draft.error, applyEnabled = supported && nameValid,
                onDismiss = onDismiss, onApply = { if (draft.source.existingAsset != null) saveChoice = true else save(false) },
                tools = {
                    IconButton(onClick = { naming = !naming }, enabled = !saving) { Icon(Icons.Outlined.Title, "Template name") }
                    if (edits.mode == "crop") {
                        IconButton(onClick = { edits.crop = FULL_TEMPLATE_CROP }, enabled = !saving) { Icon(Icons.Outlined.Fullscreen, "Full image") }
                        IconButton(onClick = { edits.crop = DEFAULT_TEMPLATE_CROP }, enabled = !saving) { Icon(Icons.Outlined.RestartAlt, "Reset crop") }
                    }
                }, details = {
                    Surface(shape = RoundedCornerShape(14.dp), color = Color.Black.copy(alpha = 0.62f)) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("$width × $height px", style = MaterialTheme.typography.bodySmall, color = Color.White)
                            if (!supported) Text("Crop must fit $displayWidthPx × $displayHeightPx px and contain at most 3 million pixels.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                            if (!nameValid && !naming) Text("Choose a unique name up to 64 characters.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                        }
                    }
                }, overlay = {
                    Box(Modifier.fillMaxSize().safeDrawingPadding().padding(top = 58.dp, start = 16.dp, end = 16.dp), contentAlignment = Alignment.TopCenter) {
                        AnimatedVisibility(naming, enter = fadeIn(tween(120)), exit = fadeOut(tween(90))) {
                            val focus = remember { FocusRequester() }
                            val manager = LocalFocusManager.current
                            LaunchedEffect(Unit) { focus.requestFocus() }
                            TextField(
                                draft.name, { onDraftChange(draft.copy(name = it, error = null)) },
                                Modifier.widthIn(max = 420.dp).fillMaxWidth().focusRequester(focus).testTag("template-crop-name"),
                                label = { Text("Template name") }, singleLine = true, enabled = !saving, isError = !nameValid,
                                shape = RoundedCornerShape(18.dp), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = {
                                    naming = false
                                    manager.clearFocus()
                                }),
                                trailingIcon = {
                                    IconButton({
                                        naming = false
                                        manager.clearFocus()
                                    }) { Icon(Icons.Outlined.Check, "Done naming") }
                                },
                                colors = TextFieldDefaults.colors(focusedContainerColor = Color.Black.copy(alpha = 0.78f), unfocusedContainerColor = Color.Black.copy(alpha = 0.68f), focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                            )
                        }
                    }
                },
            )
        }
        if (saveChoice) {
            AlertDialog(
                onDismissRequest = { saveChoice = false },
                title = { Text("Save image") },
                text = {
                    Text(
                        if (removesBackgrounds) "Replacing removes this image as the background from all blocks using it. Save a copy to keep their backgrounds." else "Replacing updates every block using this image.",
                        color = if (removesBackgrounds) MaterialTheme.colorScheme.error else Color.Unspecified,
                    )
                },
                confirmButton = {
                    Row {
                        TextButton(onClick = { save(false) }) { Text("Save a copy") }
                        TextButton(onClick = { save(true) }) { Text("Replace") }
                    }
                },
                dismissButton = { TextButton(onClick = { saveChoice = false }) { Text("Cancel") } },
            )
        }
    }
}
