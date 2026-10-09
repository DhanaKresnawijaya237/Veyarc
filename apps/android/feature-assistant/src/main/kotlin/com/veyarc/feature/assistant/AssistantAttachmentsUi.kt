package com.veyarc.feature.assistant

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.veyarc.ui.ImageEditor
import com.veyarc.ui.ImageEdits
import com.veyarc.ui.ImageEditsSaver
import com.veyarc.ui.ZoomableImage
import com.veyarc.ui.renderEditedImage
import com.veyarc.ui.rotatedForEditing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

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
                    Surface(onClick = { onOpen(attachment) }, shape = RoundedCornerShape(14.dp), color = if (attachment.kind == AttachmentKind.IMAGE) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.testTag("attachment-${attachment.id}")) {
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
                                Column(Modifier.weight(1f).padding(end = if (onRemove != null) 20.dp else 0.dp)) {
                                    Text(attachment.name, style = MaterialTheme.typography.bodyMedium, maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis)
                                    Text(if (attachment.kind == AttachmentKind.PDF) "PDF · ${attachment.pageCount} pages" else "${if (attachment.isMarkdown) "Markdown" else attachment.name.substringAfterLast('.', "Text").uppercase()} · ${(attachment.byteCount + 1023) / 1024} KB", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    if (onRemove != null) {
                        // Inset the badge while keeping its full touch target clear of the image's center.
                        IconButton(onClick = { onRemove(attachment.id) }, modifier = Modifier.align(Alignment.TopEnd).size(48.dp).offset(x = 12.dp, y = (-12).dp)) {
                            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.8f), contentColor = Color.White, modifier = Modifier.size(24.dp).offset(x = (-6).dp, y = 6.dp)) {
                                Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Close, "Remove ${attachment.name}", Modifier.size(16.dp)) }
                            }
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
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
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
                        if (landscape && attachment.kind == AttachmentKind.PDF) Text("${page + 1} / ${attachment.pageCount}", Modifier.padding(end = 16.dp), style = MaterialTheme.typography.labelLarge)
                    }
                    if (attachment.kind == AttachmentKind.PDF) {
                        PdfAttachmentPreview(attachment, importer, page, { page = it }, landscape, Modifier.weight(1f))
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
private fun PdfAttachmentPreview(attachment: AssistantAttachment, importer: AssistantAttachmentImporter, initialPage: Int, onPageChanged: (Int) -> Unit, landscape: Boolean, modifier: Modifier) {
    val pager = rememberPagerState(initialPage = initialPage) { attachment.pageCount }
    val scope = rememberCoroutineScope()
    LaunchedEffect(pager.settledPage) { onPageChanged(pager.settledPage) }
    val previous: @Composable () -> Unit = {
        IconButton(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }, enabled = pager.currentPage > 0 && !pager.isScrollInProgress) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, "Previous page") }
    }
    val next: @Composable () -> Unit = {
        IconButton(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }, enabled = pager.currentPage + 1 < pager.pageCount && !pager.isScrollInProgress) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, "Next page") }
    }
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (landscape) previous()
            HorizontalPager(pager, Modifier.weight(1f).fillMaxHeight().testTag("pdf-pages")) { index ->
                val image by produceState<Result<Bitmap>?>(null, attachment.id, index) {
                    value = withContext(Dispatchers.IO) { runCatching { importer.pdfPage(attachment, index) } }
                }
                Box(Modifier.fillMaxSize().testTag("pdf-page-$index"), contentAlignment = Alignment.Center) {
                    when {
                        image == null -> CircularProgressIndicator()
                        image!!.isFailure -> Text("This page could not be opened.")
                        else -> ZoomableImage(image!!.getOrThrow())
                    }
                }
            }
            if (landscape) next()
        }
        if (!landscape) {
            Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                previous()
                Text("${pager.currentPage + 1} / ${pager.pageCount}")
                next()
            }
        }
    }
}

@Composable
private fun ImageAttachmentPreview(attachment: AssistantAttachment, importer: AssistantAttachmentImporter, editable: Boolean, edits: ImageEdits, onDismiss: () -> Unit, onSaved: suspend (() -> AssistantAttachment) -> Unit) {
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val source by produceState<Bitmap?>(null, attachment.id) {
        val result = withContext(Dispatchers.IO) { runCatching { importer.bitmap(attachment) } }
        if (result.isFailure) error = "This image could not be opened."
        value = result.getOrNull()
    }
    val scope = rememberCoroutineScope()
    ImageEditor(source, edits, editable, saving, error, onDismiss = onDismiss, onApply = {
        val image = source ?: return@ImageEditor
        val crop = edits.crop
        val turns = edits.turns
        val strokes = edits.strokes
        saving = true
        error = null
        scope.launch {
            try {
                onSaved {
                    val rotated = image.rotatedForEditing(turns)
                    try {
                        val edited = renderEditedImage(rotated, crop, strokes)
                        try {
                            importer.saveImage(attachment.name, edited)
                        } finally {
                            edited.recycle()
                        }
                    } finally {
                        if (rotated !== image) rotated.recycle()
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
    })
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
