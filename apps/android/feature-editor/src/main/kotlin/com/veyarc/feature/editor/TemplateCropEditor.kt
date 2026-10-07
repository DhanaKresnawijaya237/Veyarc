package com.veyarc.feature.editor

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
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
internal data class TemplateCropDraft(val source: TemplateCropSource, val name: String = "", val edits: ImageEdits = ImageEdits(DEFAULT_TEMPLATE_CROP, "crop"))

@Composable
internal fun TemplateCropEditorDialog(draft: TemplateCropDraft, displayWidthPx: Int, displayHeightPx: Int, existingNames: Set<String>, saving: Boolean, onDraftChange: (TemplateCropDraft) -> Unit, onDismiss: () -> Unit, onApply: (NormalizedRect, Int, String, List<AnnotationStroke>) -> Unit) {
    // Keep edit state outside the dialog's separate save registry.
    val edits = rememberSaveable(draft.source.uri.toString(), saver = ImageEditsSaver) { draft.edits }
    LaunchedEffect(edits) { if (edits !== draft.edits) onDraftChange(draft.copy(edits = edits)) }
    val swapped = edits.turns % 2 != 0
    val sourceWidth = if (swapped) draft.source.heightPx else draft.source.widthPx
    val sourceHeight = if (swapped) draft.source.widthPx else draft.source.heightPx
    val width = (ceil((edits.crop.x + edits.crop.width) * sourceWidth) - floor(edits.crop.x * sourceWidth)).toInt().coerceAtLeast(1)
    val height = (ceil((edits.crop.y + edits.crop.height) * sourceHeight) - floor(edits.crop.y * sourceHeight)).toInt().coerceAtLeast(1)
    val nameValid = draft.name.isBlank() || (draft.name.trim().length <= 64 && existingNames.none { it.equals(draft.name.trim(), true) })
    val fitsDisplay = width <= displayWidthPx && height <= displayHeightPx
    val supported = width.toLong() * height <= DecodedTemplate.MAX_TEMPLATE_PIXELS && fitsDisplay
    Dialog(onDismissRequest = { if (!saving) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize().testTag("template-crop-editor"), color = Color.Black) {
            ImageEditor(draft.source.preview.asAndroidBitmap(), edits, true, saving, null, applyEnabled = supported && nameValid, onDismiss = onDismiss, onApply = { onApply(edits.crop, edits.turns, draft.name.trim(), edits.strokes) }, details = {
                val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
                val nameField: @Composable () -> Unit = {
                    OutlinedTextField(
                        draft.name,
                        onValueChange = { onDraftChange(draft.copy(name = it)) },
                        Modifier.fillMaxWidth().testTag("template-crop-name"),
                        enabled = !saving,
                        label = { Text("Template name") },
                        singleLine = true,
                        isError = !nameValid,
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, unfocusedLabelColor = Color.LightGray),
                    )
                }
                val cropDetails: @Composable () -> Unit = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                        if (edits.mode == "crop") IconButton(onClick = { edits.crop = FULL_TEMPLATE_CROP }, enabled = !saving) { Icon(Icons.Outlined.Fullscreen, "Full image") }
                        Text("$width × $height px", style = MaterialTheme.typography.bodySmall)
                        if (edits.mode == "crop") IconButton(onClick = { edits.crop = DEFAULT_TEMPLATE_CROP }, enabled = !saving) { Icon(Icons.Outlined.RestartAlt, "Reset crop") }
                    }
                }
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (landscape) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.foundation.layout.Box(Modifier.weight(1f)) { nameField() }
                            cropDetails()
                        }
                    } else {
                        nameField()
                        cropDetails()
                    }
                    if (!nameValid) Text("Use a unique name up to 64 characters.", Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                    if (!supported) Text(if (!fitsDisplay) "Crop must fit $displayWidthPx × $displayHeightPx px." else "Crop is too large. Select a smaller area.", Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                }
            })
        }
    }
}
