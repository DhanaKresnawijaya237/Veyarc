package com.veyarc.feature.editor

import android.net.Uri
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import com.veyarc.model.ImagePresentCondition
import com.veyarc.model.NormalizedRect
import com.veyarc.model.ViewportSpec
import com.veyarc.model.bundle.BundleAsset
import com.veyarc.ui.renderEditedImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible

/** Retains the crop and its edits when the recording screen rotates. */
class RecordingImageDraft(val background: BundleAsset) {
    internal var crop by mutableStateOf<TemplateCropDraft?>(null)
    internal var error by mutableStateOf<String?>(null)
}

@Composable
fun RecordingImagePicker(
    draft: RecordingImageDraft,
    viewport: ViewportSpec,
    existingNames: Set<String>,
    onDismiss: () -> Unit,
    onApply: (ImagePresentCondition, List<BundleAsset>, String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(draft) {
        if (draft.crop != null) return@LaunchedEffect
        try {
            val preview = runInterruptible(Dispatchers.Default) {
                requireNotNull(decodeTemplatePreview(draft.background, maxOf(viewport.recordedWidthPx, viewport.recordedHeightPx))) { "Could not open screenshot." }
            }
            draft.crop = TemplateCropDraft(TemplateCropSource(Uri.EMPTY, preview.widthPx, preview.heightPx, preview.image))
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            draft.error = failure.message ?: "Could not open screenshot."
        }
    }
    val crop = draft.crop
    if (crop == null) {
        AlertDialog(onDismissRequest = onDismiss, title = { Text("Screenshot") }, text = {
            if (draft.error == null) CircularProgressIndicator() else Text(requireNotNull(draft.error))
        }, confirmButton = { TextButton(onDismiss) { Text("Cancel") } })
    } else {
        TemplateCropEditorDialog(
            crop,
            viewport.recordedWidthPx,
            viewport.recordedHeightPx,
            existingNames,
            busy,
            onDraftChange = { draft.crop = it },
            onDismiss = onDismiss,
            onApply = { region, turns, name, strokes, _ ->
                if (turns.mod(4) != 0) {
                    draft.crop = crop.copy(error = "Keep the screenshot's original rotation for image checks.")
                } else {
                    scope.launch {
                        busy = true
                        try {
                            val template = runInterruptible(Dispatchers.Default) {
                                encryptTemplateBitmap(renderEditedImage(crop.source.preview.asAndroidBitmap(), region, strokes))
                            }
                            val search = recordingSearchRegion(region, viewport.recordedWidthPx, viewport.recordedHeightPx)
                            onApply(ImagePresentCondition(template.assetId, search, 0.9, backgroundAssetId = draft.background.assetId), listOf(template, draft.background), name)
                        } catch (failure: Exception) {
                            if (failure is CancellationException) throw failure
                            draft.crop = crop.copy(error = failure.message ?: "Could not save image.")
                        } finally {
                            busy = false
                        }
                    }
                }
            },
        )
    }
}

/** Search around the demonstrated location, with eight pixels of room on each side. */
internal fun recordingSearchRegion(crop: NormalizedRect, width: Int, height: Int): NormalizedRect {
    require(width > 0 && height > 0)
    val left = (crop.x - 8.0 / width).coerceAtLeast(0.0)
    val top = (crop.y - 8.0 / height).coerceAtLeast(0.0)
    val right = (crop.x + crop.width + 8.0 / width).coerceAtMost(1.0)
    val bottom = (crop.y + crop.height + 8.0 / height).coerceAtMost(1.0)
    return NormalizedRect(left, top, right - left, bottom - top)
}
