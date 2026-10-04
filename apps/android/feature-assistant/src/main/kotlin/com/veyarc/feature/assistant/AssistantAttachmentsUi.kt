package com.veyarc.feature.assistant

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.RotateRight
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.veyarc.model.NormalizedRect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.hypot

/** Pickers return to the same composer. Taking a photo grants only the temporary camera URI. */
@Composable
internal fun rememberAttachmentPicker(state: AssistantPreviewState): (String) -> Unit {
    val context = LocalContext.current
    var owner by rememberSaveable { mutableStateOf<String?>(null) }
    var cameraName by rememberSaveable { mutableStateOf<String?>(null) }
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        owner?.let { if (uris.isNotEmpty()) state.importAttachments(uris, it) }
    }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        owner?.let { if (uris.isNotEmpty()) state.importAttachments(uris.toList(), it) }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val file = cameraName?.let { File(File(context.cacheDir, "assistant-camera"), it) }
        cameraName = null
        if (success && file != null && owner != null) {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.assistant-camera", file)
            state.importAttachments(listOf(uri), owner!!) { file.delete() }
        } else {
            file?.delete()
        }
    }
    return { action ->
        if (state.attachments != null && state.canChange && !state.importingAttachments && !state.isResponding) {
            owner = state.selectedId
            when (action) {
                "Camera" -> {
                    val directory = File(context.cacheDir, "assistant-camera").apply { mkdirs() }
                    directory.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 24 * 60 * 60 * 1000L }?.forEach(File::delete)
                    val name = "photo-${UUID.randomUUID()}.jpg"
                    val file = File(directory, name)
                    cameraName = name
                    try {
                        camera.launch(FileProvider.getUriForFile(context, "${context.packageName}.assistant-camera", file))
                    } catch (_: android.content.ActivityNotFoundException) {
                        cameraName = null
                        file.delete()
                        state.attachmentError("No camera app is available.")
                    }
                }

                "Photos" -> photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))

                "Files" -> files.launch(AssistantAttachmentImporter.FILE_MIME_TYPES)
            }
        }
    }
}

@Composable
internal fun AttachmentStrip(attachments: List<AssistantAttachment>, importer: AssistantAttachmentImporter?, onOpen: (AssistantAttachment) -> Unit, onRemove: ((String) -> Unit)? = null, compact: Boolean = false, alignEnd: Boolean = false) {
    // Lazy item animation retains removed cards for their fade and moves the remaining cards.
    LazyRow(Modifier.fillMaxWidth().animateContentSize(spring()).testTag("assistant-attachments"), contentPadding = PaddingValues(horizontal = 10.dp, vertical = if (attachments.isEmpty()) 0.dp else 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, if (alignEnd) Alignment.End else Alignment.Start)) {
        items(attachments, key = { it.id }) { attachment ->
            Box(Modifier.animateItem()) {
                Box {
                    Surface(onClick = { onOpen(attachment) }, shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.testTag("attachment-${attachment.id}")) {
                        if (attachment.kind == AttachmentKind.IMAGE) {
                            val image by produceState<Bitmap?>(null, attachment.id) {
                                value = withContext(Dispatchers.IO) { runCatching { importer?.preview(attachment) }.getOrNull() }
                            }
                            Box(Modifier.size(if (compact) 72.dp else 80.dp), contentAlignment = Alignment.Center) {
                                if (image != null) {
                                    Image(image!!.asImageBitmap(), attachment.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                } else {
                                    Icon(Icons.Outlined.Image, attachment.name)
                                }
                            }
                        } else {
                            Row(Modifier.width(if (compact) 240.dp else 216.dp).height(if (compact) 72.dp else 80.dp).padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Icon(if (attachment.kind == AttachmentKind.PDF) Icons.Outlined.PictureAsPdf else Icons.Outlined.Description, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
                                Column(Modifier.weight(1f).padding(end = if (onRemove != null) 16.dp else 0.dp)) {
                                    Text(attachment.name, style = MaterialTheme.typography.bodyMedium, maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis)
                                    Text(if (attachment.kind == AttachmentKind.PDF) "PDF · ${attachment.pageCount} pages" else "${if (attachment.isMarkdown) "Markdown" else attachment.name.substringAfterLast('.', "Text").uppercase()} · ${(attachment.byteCount + 1023) / 1024} KB", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    if (onRemove != null) {
                        Surface(onClick = { onRemove(attachment.id) }, shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.align(Alignment.TopEnd).size(30.dp)) {
                            Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Close, "Remove ${attachment.name}", Modifier.size(17.dp)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun AttachmentPreview(attachment: AssistantAttachment, state: AssistantPreviewState, editable: Boolean, onDismiss: () -> Unit) {
    val importer = state.attachments ?: return
    val chatId = state.selectedId
    // Dialogs have their own save registry. Keep the edit in the parent chat's saved state.
    val edits = rememberSaveable(attachment.id, saver = ImageEditsSaver) { ImageEdits() }
    var page by rememberSaveable(attachment.id) { mutableIntStateOf(0) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize().testTag("attachment-preview"), color = if (attachment.kind == AttachmentKind.IMAGE) Color.Black else MaterialTheme.colorScheme.surface) {
            if (attachment.kind == AttachmentKind.IMAGE) {
                ImageAttachmentPreview(attachment, importer, editable, edits, onDismiss) { create ->
                    if (chatId != null && state.editAttachment(attachment.id, chatId, create)) onDismiss()
                }
            } else {
                Column(Modifier.safeDrawingPadding()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Close preview") }
                        Text(attachment.name, Modifier.weight(1f).padding(end = 16.dp), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (attachment.kind == AttachmentKind.PDF) {
                        var failed by remember(attachment.id, page) { mutableStateOf(false) }
                        val image by produceState<Bitmap?>(null, attachment.id, page) {
                            value = null
                            val result = withContext(Dispatchers.IO) { runCatching { importer.pdfPage(attachment, page) } }
                            failed = result.isFailure
                            value = result.getOrNull()
                        }
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            when {
                                failed -> Text("This page could not be opened.")
                                image == null -> CircularProgressIndicator()
                                else -> ZoomableImage(image!!)
                            }
                        }
                        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { page-- }, enabled = page > 0) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Previous page") }
                            Text("${page + 1} / ${attachment.pageCount}")
                            IconButton(onClick = { page++ }, enabled = page + 1 < attachment.pageCount) { Icon(Icons.Outlined.ChevronRight, "Next page") }
                        }
                    } else {
                        var failed by remember { mutableStateOf(false) }
                        val text by produceState<String?>(null, attachment.id) {
                            val result = withContext(Dispatchers.IO) { runCatching { importer.content(attachment).text } }
                            failed = result.isFailure
                            value = result.getOrNull()
                        }
                        when {
                            failed -> Text("This file could not be opened.", Modifier.padding(20.dp))

                            text == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }

                            else -> if (attachment.isMarkdown) {
                                SelectionContainer { MarkdownAttachmentPreview(text!!) }
                            } else {
                                SelectionContainer { LazyColumn(contentPadding = PaddingValues(20.dp)) { items(text!!.lines()) { Text(it, style = MaterialTheme.typography.bodyMedium) } } }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ZoomableImage(bitmap: Bitmap) {
    var scale by remember(bitmap) { mutableFloatStateOf(1f) }
    var offset by remember(bitmap) { mutableStateOf(Offset.Zero) }
    Box(
        Modifier.fillMaxSize().pointerInput(bitmap) {
            detectTransformGestures { _, pan, zoom, _ ->
                scale = (scale * zoom).coerceIn(1f, 5f)
                val limitX = size.width * (scale - 1) / 2
                val limitY = size.height * (scale - 1) / 2
                offset = Offset((offset.x + pan.x).coerceIn(-limitX, limitX), (offset.y + pan.y).coerceIn(-limitY, limitY))
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap.asImageBitmap(),
            "Image preview",
            Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
            contentScale = ContentScale.Fit,
        )
    }
}

private data class AnnotationStroke(val points: List<Offset>, val color: Color, val marker: Boolean)

private class ImageEdits {
    var mode by mutableStateOf("view")
    var turns by mutableIntStateOf(0)
    var crop by mutableStateOf(NormalizedRect(0.0, 0.0, 1.0, 1.0))
    var strokes by mutableStateOf<List<AnnotationStroke>>(emptyList())
    var colorValue by mutableIntStateOf(Color(0xFFFF5A5F).toArgb())
    var marker by mutableStateOf(false)
}

private val ImageEditsSaver = listSaver<ImageEdits, Any>(
    save = { edits ->
        buildList {
            addAll(listOf(edits.mode, edits.turns, edits.crop.x, edits.crop.y, edits.crop.width, edits.crop.height, edits.colorValue, edits.marker))
            edits.strokes.forEach { stroke ->
                add(stroke.color.toArgb())
                add(stroke.marker)
                add(stroke.points.size)
                stroke.points.forEach {
                    add(it.x)
                    add(it.y)
                }
            }
        }
    },
    restore = { values ->
        ImageEdits().apply {
            mode = values[0] as String
            turns = values[1] as Int
            crop = NormalizedRect(values[2] as Double, values[3] as Double, values[4] as Double, values[5] as Double)
            colorValue = values[6] as Int
            marker = values[7] as Boolean
            var index = 8
            strokes = buildList {
                while (index < values.size) {
                    val color = Color(values[index++] as Int)
                    val marker = values[index++] as Boolean
                    val count = values[index++] as Int
                    add(AnnotationStroke(List(count) { Offset(values[index++] as Float, values[index++] as Float) }, color, marker))
                }
            }
        }
    },
)

@Composable
private fun ImageAttachmentPreview(attachment: AssistantAttachment, importer: AssistantAttachmentImporter, editable: Boolean, edits: ImageEdits, onDismiss: () -> Unit, onSaved: suspend (() -> AssistantAttachment) -> Unit) {
    var error by remember { mutableStateOf<String?>(null) }
    val source by produceState<Bitmap?>(null, attachment.id) {
        val result = withContext(Dispatchers.IO) { runCatching { importer.bitmap(attachment) } }
        if (result.isFailure) error = "This image could not be opened."
        value = result.getOrNull()
    }
    var mode by edits::mode
    var turns by edits::turns
    var crop by edits::crop
    var strokes by edits::strokes
    var colorValue by edits::colorValue
    val color = Color(colorValue)
    var marker by edits::marker
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val rotatedState by produceState<Bitmap?>(null, source, turns) {
        value = null
        value = withContext(Dispatchers.Default) { source?.let { Bitmap.createBitmap(it, 0, 0, it.width, it.height, Matrix().apply { postRotate(turns * 90f) }, true) } }
    }
    val rotated = rotatedState
    Column(Modifier.safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDismiss, enabled = !saving) { Icon(Icons.Outlined.Close, "Close preview", tint = Color.White) }
            Spacer(Modifier.weight(1f))
            if (editable && mode != "view") {
                TextButton(onClick = {
                    turns = (turns + 1) % 4
                    crop = NormalizedRect(1.0 - (crop.y + crop.height), crop.x, crop.height, crop.width)
                    strokes = strokes.map { it.copy(points = it.points.map { point -> Offset(1f - point.y, point.x) }) }
                }, enabled = !saving) {
                    Icon(Icons.AutoMirrored.Outlined.RotateRight, null, tint = Color.White)
                    Text("Rotate", color = Color.White)
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when {
                error != null -> Text(error!!, color = Color.White)
                rotated == null -> CircularProgressIndicator()
                mode == "view" -> ZoomableImage(rotated)
                else -> ImageEditCanvas(rotated, crop, mode == "crop", strokes, color, marker, { crop = it }, { strokes = it })
            }
        }
        if (mode == "draw") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LazyRow(Modifier.weight(1f).testTag("annotation-colors"), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(listOf("Red" to Color(0xFFFF5A5F), "Orange" to Color(0xFFFF922B), "Yellow" to Color(0xFFFFD43B), "Green" to Color(0xFF51CF66), "Blue" to Color(0xFF4DABF7), "Purple" to Color(0xFFB197FC), "White" to Color.White, "Black" to Color.Black)) { (name, swatch) ->
                        Box(Modifier.size(48.dp).selectable(selected = color.toArgb() == swatch.toArgb(), role = Role.RadioButton, onClick = { colorValue = swatch.toArgb() }).semantics { contentDescription = "$name ink" }, contentAlignment = Alignment.Center) {
                            Surface(color = swatch, shape = CircleShape, border = androidx.compose.foundation.BorderStroke(if (color.toArgb() == swatch.toArgb()) 3.dp else 1.dp, if (color.toArgb() == swatch.toArgb()) Color.White else Color.Gray), modifier = Modifier.size(30.dp)) {}
                        }
                    }
                }
                IconButton(onClick = { marker = !marker }) { Icon(if (marker) Icons.Outlined.Brush else Icons.Outlined.Edit, if (marker) "Use pen" else "Use marker", tint = Color.White) }
                IconButton(onClick = { strokes = strokes.dropLast(1) }, enabled = strokes.isNotEmpty()) { Icon(Icons.AutoMirrored.Outlined.Undo, "Undo annotation", tint = Color.White) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (editable && rotated != null) {
                if (mode == "view") {
                    Spacer(Modifier.weight(1f))
                    FilledTonalButton(onClick = { mode = "draw" }) {
                        Icon(Icons.Outlined.Edit, null, Modifier.size(18.dp))
                        Text("Annotate", Modifier.padding(start = 6.dp))
                    }
                    FilledTonalButton(onClick = { mode = "crop" }) {
                        Icon(Icons.Outlined.Crop, null, Modifier.size(18.dp))
                        Text("Crop", Modifier.padding(start = 6.dp))
                    }
                } else {
                    IconButton(onClick = { mode = if (mode == "crop") "draw" else "crop" }, enabled = !saving) { Icon(if (mode == "crop") Icons.Outlined.Edit else Icons.Outlined.Crop, if (mode == "crop") "Annotate" else "Crop", tint = Color.White) }
                    Button(onClick = {
                        saving = true
                        error = null
                        val image = rotated
                        val selectedCrop = crop
                        val selectedStrokes = strokes
                        scope.launch {
                            try {
                                onSaved {
                                    val edited = renderEditedImage(image, selectedCrop, selectedStrokes)
                                    try {
                                        importer.saveImage(attachment.name, edited)
                                    } finally {
                                        edited.recycle()
                                    }
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                error = "Could not save this edit. Try again."
                            } finally {
                                saving = false
                            }
                        }
                    }, enabled = !saving, modifier = Modifier.weight(1f), shape = RoundedCornerShape(28.dp)) {
                        if (saving) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Use image")
                    }
                }
            }
        }
    }
}

private fun fittedImage(size: Size, bitmap: Bitmap): Rect {
    val scale = minOf(size.width / bitmap.width, size.height / bitmap.height)
    val width = bitmap.width * scale
    val height = bitmap.height * scale
    return Rect((size.width - width) / 2, (size.height - height) / 2, (size.width + width) / 2, (size.height + height) / 2)
}

@Composable
private fun ImageEditCanvas(bitmap: Bitmap, crop: NormalizedRect, cropping: Boolean, strokes: List<AnnotationStroke>, color: Color, marker: Boolean, onCrop: (NormalizedRect) -> Unit, onStrokes: (List<AnnotationStroke>) -> Unit) {
    val currentCrop by rememberUpdatedState(crop)
    val currentStrokes by rememberUpdatedState(strokes)
    val currentColor by rememberUpdatedState(color)
    val currentMarker by rememberUpdatedState(marker)
    val cropCallback by rememberUpdatedState(onCrop)
    val strokesCallback by rememberUpdatedState(onStrokes)
    Box(Modifier.fillMaxSize().padding(20.dp)) {
        Image(bitmap.asImageBitmap(), "Edit image", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        Canvas(
            Modifier.fillMaxSize().testTag("attachment-image-editor").pointerInput(bitmap, cropping) {
                var handle = -1
                var anchor = Offset.Zero
                var startedInside = false
                var drawingStrokes = emptyList<AnnotationStroke>()
                detectDragGestures(onDragStart = { point ->
                    val bounds = fittedImage(Size(size.width.toFloat(), size.height.toFloat()), bitmap)
                    startedInside = bounds.contains(point)
                    val normalized = Offset(((point.x - bounds.left) / bounds.width).coerceIn(0f, 1f), ((point.y - bounds.top) / bounds.height).coerceIn(0f, 1f))
                    anchor = normalized
                    if (cropping) {
                        val c = currentCrop
                        val corners = listOf(Offset(c.x.toFloat(), c.y.toFloat()), Offset((c.x + c.width).toFloat(), c.y.toFloat()), Offset((c.x + c.width).toFloat(), (c.y + c.height).toFloat()), Offset(c.x.toFloat(), (c.y + c.height).toFloat()))
                        handle = corners.indices.minBy { index -> hypot((corners[index].x - normalized.x) * bounds.width, (corners[index].y - normalized.y) * bounds.height) }
                        if (hypot((corners[handle].x - normalized.x) * bounds.width, (corners[handle].y - normalized.y) * bounds.height) > 64.dp.toPx()) handle = -1
                    } else if (bounds.contains(point)) {
                        drawingStrokes = currentStrokes + AnnotationStroke(listOf(normalized), currentColor, currentMarker)
                        strokesCallback(drawingStrokes)
                    }
                }) { change, _ ->
                    change.consume()
                    val bounds = fittedImage(Size(size.width.toFloat(), size.height.toFloat()), bitmap)
                    val point = Offset(((change.position.x - bounds.left) / bounds.width).coerceIn(0f, 1f), ((change.position.y - bounds.top) / bounds.height).coerceIn(0f, 1f))
                    if (cropping) {
                        val c = currentCrop
                        var left = c.x
                        var top = c.y
                        var right = c.x + c.width
                        var bottom = c.y + c.height
                        when (handle) {
                            0 -> {
                                left = point.x.toDouble().coerceIn(0.0, right - 0.02)
                                top = point.y.toDouble().coerceIn(0.0, bottom - 0.02)
                            }

                            1 -> {
                                right = point.x.toDouble().coerceIn(left + 0.02, 1.0)
                                top = point.y.toDouble().coerceIn(0.0, bottom - 0.02)
                            }

                            2 -> {
                                right = point.x.toDouble().coerceIn(left + 0.02, 1.0)
                                bottom = point.y.toDouble().coerceIn(top + 0.02, 1.0)
                            }

                            3 -> {
                                left = point.x.toDouble().coerceIn(0.0, right - 0.02)
                                bottom = point.y.toDouble().coerceIn(top + 0.02, 1.0)
                            }

                            else -> {
                                left = (left + point.x - anchor.x).coerceIn(0.0, 1.0 - c.width)
                                top = (top + point.y - anchor.y).coerceIn(0.0, 1.0 - c.height)
                                right = left + c.width
                                bottom = top + c.height
                            }
                        }
                        cropCallback(NormalizedRect(left, top, minOf(right - left, 1.0 - left), minOf(bottom - top, 1.0 - top)))
                    } else if (startedInside && drawingStrokes.isNotEmpty()) {
                        // Drag start and its first update can arrive before the next Compose frame.
                        drawingStrokes = drawingStrokes.dropLast(1) + drawingStrokes.last().copy(points = drawingStrokes.last().points + point)
                        strokesCallback(drawingStrokes)
                    }
                    anchor = point
                }
            },
        ) {
            val bounds = fittedImage(size, bitmap)
            strokes.forEach { stroke ->
                val path = Path()
                stroke.points.forEachIndexed { index, point ->
                    val x = bounds.left + point.x * bounds.width
                    val y = bounds.top + point.y * bounds.height
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, stroke.color.copy(alpha = if (stroke.marker) 0.35f else 1f), style = Stroke(bounds.width * if (stroke.marker) 0.025f else 0.006f, cap = StrokeCap.Round))
            }
            if (cropping) {
                val selected = Rect(bounds.left + (crop.x * bounds.width).toFloat(), bounds.top + (crop.y * bounds.height).toFloat(), bounds.left + ((crop.x + crop.width) * bounds.width).toFloat(), bounds.top + ((crop.y + crop.height) * bounds.height).toFloat())
                val shade = Color.Black.copy(alpha = 0.65f)
                drawRect(shade, Offset(bounds.left, bounds.top), Size(bounds.width, selected.top - bounds.top))
                drawRect(shade, Offset(bounds.left, selected.bottom), Size(bounds.width, bounds.bottom - selected.bottom))
                drawRect(shade, Offset(bounds.left, selected.top), Size(selected.left - bounds.left, selected.height))
                drawRect(shade, Offset(selected.right, selected.top), Size(bounds.right - selected.right, selected.height))
                drawRect(Color.White, selected.topLeft, selected.size, style = Stroke(2.dp.toPx()))
                for (fraction in listOf(1f / 3, 2f / 3)) {
                    drawLine(Color.White.copy(alpha = 0.4f), Offset(selected.left + selected.width * fraction, selected.top), Offset(selected.left + selected.width * fraction, selected.bottom))
                    drawLine(Color.White.copy(alpha = 0.4f), Offset(selected.left, selected.top + selected.height * fraction), Offset(selected.right, selected.top + selected.height * fraction))
                }
                listOf(selected.topLeft, selected.topRight, selected.bottomLeft, selected.bottomRight).forEach { drawCircle(Color.White, 5.dp.toPx(), it) }
            }
        }
    }
}

private fun renderEditedImage(source: Bitmap, crop: NormalizedRect, strokes: List<AnnotationStroke>): Bitmap {
    val copy = source.copy(Bitmap.Config.ARGB_8888, true)
    try {
        val canvas = android.graphics.Canvas(copy)
        strokes.forEach { stroke ->
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = stroke.color.toArgb()
                alpha = if (stroke.marker) 90 else 255
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = copy.width * if (stroke.marker) 0.025f else 0.006f
                strokeCap = android.graphics.Paint.Cap.ROUND
                strokeJoin = android.graphics.Paint.Join.ROUND
            }
            val path = android.graphics.Path()
            stroke.points.forEachIndexed { index, point -> if (index == 0) path.moveTo(point.x * copy.width, point.y * copy.height) else path.lineTo(point.x * copy.width, point.y * copy.height) }
            canvas.drawPath(path, paint)
        }
        val left = (crop.x * copy.width).toInt().coerceIn(0, copy.width - 1)
        val top = (crop.y * copy.height).toInt().coerceIn(0, copy.height - 1)
        val width = (crop.width * copy.width).toInt().coerceIn(1, copy.width - left)
        val height = (crop.height * copy.height).toInt().coerceIn(1, copy.height - top)
        val result = Bitmap.createBitmap(copy, left, top, width, height)
        return if (result === copy) copy.copy(Bitmap.Config.ARGB_8888, false) else result
    } finally {
        copy.recycle()
    }
}

/** The review shows precisely the submitted crop; tapping opens a larger, read-only preview. */
@Composable
internal fun ProposalImageStrip(state: AssistantPreviewState, turn: PreviewTurn) {
    val additions = turn.proposal?.imageAdditions.orEmpty()
    val importer = state.attachments ?: return
    if (additions.isEmpty()) return
    val sources = state.proposalImages(turn)
    var selected by remember(turn.id) { mutableStateOf<AssistantImageAddition?>(null) }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("proposal-images")) {
        items(additions, key = { it.assetId }) { addition ->
            val source = sources.firstOrNull { it.id == addition.sourceAttachmentId }
            val image by produceState<Bitmap?>(null, addition, source) {
                value = withContext(Dispatchers.IO) { runCatching { source?.let { importer.cropPreview(it, addition.crop, 240) } }.getOrNull() }
            }
            Surface(onClick = { selected = addition }, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.testTag("proposal-image-${addition.assetId}")) {
                Column(Modifier.width(120.dp)) {
                    Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                        if (image != null) {
                            Image(image!!.asImageBitmap(), addition.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                        } else {
                            Icon(Icons.Outlined.Image, addition.name)
                        }
                    }
                    Text(addition.name, Modifier.padding(horizontal = 8.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    selected?.let { addition ->
        val source = sources.firstOrNull { it.id == addition.sourceAttachmentId }
        Dialog(onDismissRequest = { selected = null }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            Surface(Modifier.fillMaxSize(), color = Color.Black) {
                Column(Modifier.safeDrawingPadding()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { selected = null }) { Icon(Icons.Outlined.Close, "Close image review", tint = Color.White) }
                        Text(addition.name, color = Color.White, style = MaterialTheme.typography.titleMedium)
                    }
                    val result by produceState<Result<Bitmap>?>(null, addition, source) {
                        value = withContext(Dispatchers.IO) { runCatching { importer.cropPreview(requireNotNull(source), addition.crop) } }
                    }
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        when {
                            result == null -> CircularProgressIndicator()
                            result!!.isFailure -> Text("This image could not be opened.", color = Color.White)
                            else -> ZoomableImage(result!!.getOrThrow())
                        }
                    }
                }
            }
        }
    }
}
