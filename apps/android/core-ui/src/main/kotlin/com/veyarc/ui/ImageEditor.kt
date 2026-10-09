package com.veyarc.ui

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.RotateRight
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.AutoFixNormal
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.veyarc.model.NormalizedRect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot

enum class AnnotationTool { PENCIL, BRUSH, ERASER }
data class AnnotationStroke(val points: List<Offset>, val color: Color, val tool: AnnotationTool)

class ImageEdits(initialCrop: NormalizedRect = NormalizedRect(0.0, 0.0, 1.0, 1.0), initialMode: String = "view") {
    var mode by mutableStateOf(initialMode)
    var turns by mutableIntStateOf(0)
    var crop by mutableStateOf(initialCrop)
    var strokes by mutableStateOf<List<AnnotationStroke>>(emptyList())
    var redoStrokes by mutableStateOf<List<AnnotationStroke>>(emptyList())
    var colorValue by mutableIntStateOf(Color(0xFFFF5A5F).toArgb())
    var tool by mutableStateOf(AnnotationTool.PENCIL)
    fun undo() {
        if (strokes.isNotEmpty()) {
            redoStrokes = redoStrokes + strokes.last()
            strokes = strokes.dropLast(1)
        }
    }
    fun redo() {
        if (redoStrokes.isNotEmpty()) {
            strokes = strokes + redoStrokes.last()
            redoStrokes = redoStrokes.dropLast(1)
        }
    }
    fun rotate() {
        turns = (turns + 1) % 4
        crop = NormalizedRect(1.0 - (crop.y + crop.height), crop.x, crop.height, crop.width)
        fun rotated(items: List<AnnotationStroke>) = items.map { it.copy(points = it.points.map { p -> Offset(1f - p.y, p.x) }) }
        strokes = rotated(strokes)
        redoStrokes = rotated(redoStrokes)
    }
}

val ImageEditsSaver = listSaver<ImageEdits, Any>(
    save = { edit ->
        buildList {
            addAll(listOf(edit.mode, edit.turns, edit.crop.x, edit.crop.y, edit.crop.width, edit.crop.height, edit.colorValue, edit.tool.name))
            listOf(edit.strokes, edit.redoStrokes).forEach { items ->
                add(items.size)
                items.forEach { stroke ->
                    addAll(listOf(stroke.color.toArgb(), stroke.tool.name, stroke.points.size))
                    stroke.points.forEach {
                        add(it.x)
                        add(it.y)
                    }
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
            tool = AnnotationTool.valueOf(values[7] as String)
            var i = 8
            fun readStrokes() = List(values[i++] as Int) {
                val color = Color(values[i++] as Int)
                val tool = AnnotationTool.valueOf(values[i++] as String)
                val count = values[i++] as Int
                AnnotationStroke(List(count) { Offset(values[i++] as Float, values[i++] as Float) }, color, tool)
            }
            strokes = readStrokes()
            redoStrokes = readStrokes()
        }
    },
)

/** One editor for chat images and original-resolution workflow template crops. */
@Composable
fun ImageEditor(source: Bitmap?, edits: ImageEdits, editable: Boolean, saving: Boolean, error: String?, applyEnabled: Boolean = true, onDismiss: () -> Unit, onApply: () -> Unit, details: (@Composable () -> Unit)? = null) {
    val turns = edits.turns
    val previewBitmap by produceState<Pair<Bitmap, Boolean>?>(null, source, turns) {
        value = null
        var created: Bitmap? = null
        try {
            withContext(Dispatchers.Default) { created = source?.rotatedForEditing(turns) }
            value = created?.let { it to (it !== source) }
        } catch (cancelled: CancellationException) {
            if (created !== source) created?.recycle()
            throw cancelled
        }
    }
    val rotated = previewBitmap?.first
    DisposableEffect(previewBitmap) {
        val displayed = previewBitmap
        onDispose { if (displayed?.second == true) displayed.first.recycle() }
    }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val active = editable && !saving && rotated != null
    val preview: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize().testTag("attachment-image-viewport"), contentAlignment = Alignment.Center) {
            when {
                source == null && error != null -> Text(error, color = Color.White)

                rotated == null -> CircularProgressIndicator()

                edits.mode == "view" -> ZoomableImage(rotated!!)

                else -> ImageEditCanvas(rotated!!, edits.crop, edits.mode == "crop", edits.strokes, Color(edits.colorValue), edits.tool, active, { edits.crop = it }, {
                    edits.strokes = it
                    edits.redoStrokes = emptyList()
                })
            }
        }
    }
    val colors = listOf("Red" to Color(0xFFFF5A5F), "Orange" to Color(0xFFFF922B), "Yellow" to Color(0xFFFFD43B), "Green" to Color(0xFF51CF66), "Blue" to Color(0xFF4DABF7), "Purple" to Color(0xFFB197FC), "White" to Color.White, "Black" to Color.Black)
    val swatch: @Composable (Pair<String, Color>) -> Unit = { (name, ink) ->
        Box(Modifier.size(48.dp).selectable(selected = edits.colorValue == ink.toArgb(), enabled = active, role = Role.RadioButton, onClick = { edits.colorValue = ink.toArgb() }).semantics { contentDescription = "$name ink" }, contentAlignment = Alignment.Center) {
            Surface(color = ink, shape = CircleShape, border = androidx.compose.foundation.BorderStroke(if (edits.colorValue == ink.toArgb()) 3.dp else 1.dp, if (edits.colorValue == ink.toArgb()) Color.White else Color.Gray), modifier = Modifier.size(30.dp)) {}
        }
    }
    val undoRedo: @Composable () -> Unit = {
        IconButton(onClick = edits::undo, enabled = active && edits.strokes.isNotEmpty()) { Icon(Icons.AutoMirrored.Outlined.Undo, "Undo annotation") }
        IconButton(onClick = edits::redo, enabled = active && edits.redoStrokes.isNotEmpty()) { Icon(Icons.AutoMirrored.Outlined.Redo, "Redo annotation") }
    }
    val modes: @Composable () -> Unit = {
        IconButton(onClick = {
            if (edits.mode == "draw") {
                edits.tool = when (edits.tool) {
                    AnnotationTool.PENCIL -> AnnotationTool.BRUSH
                    AnnotationTool.BRUSH -> AnnotationTool.ERASER
                    AnnotationTool.ERASER -> AnnotationTool.PENCIL
                }
            }
            edits.mode = "draw"
        }, enabled = active) {
            Icon(
                when (edits.tool) {
                    AnnotationTool.PENCIL -> Icons.Outlined.Edit
                    AnnotationTool.BRUSH -> Icons.Outlined.Brush
                    AnnotationTool.ERASER -> Icons.Outlined.AutoFixNormal
                },
                "Annotate",
                modifier = Modifier.semantics { stateDescription = edits.tool.name.lowercase().replaceFirstChar { it.uppercase() } },
                tint = if (edits.mode == "draw") MaterialTheme.colorScheme.primary else Color.White,
            )
        }
        IconButton(onClick = { edits.mode = if (edits.mode == "crop") "view" else "crop" }, enabled = active) { Icon(Icons.Outlined.Crop, "Crop", tint = if (edits.mode == "crop") MaterialTheme.colorScheme.primary else Color.White) }
    }
    val accept: @Composable () -> Unit = {
        FilledIconButton(onClick = onApply, enabled = active && applyEnabled) {
            if (saving) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Icon(Icons.Outlined.Check, "Use image")
        }
    }
    val close: @Composable () -> Unit = { IconButton(onClick = onDismiss, enabled = !saving) { Icon(Icons.Outlined.Close, "Close preview") } }
    val rotate: @Composable () -> Unit = { IconButton(onClick = edits::rotate, enabled = active) { Icon(Icons.AutoMirrored.Outlined.RotateRight, "Rotate") } }
    CompositionLocalProvider(LocalContentColor provides Color.White) {
        if (landscape) {
            Row(Modifier.fillMaxSize().safeDrawingPadding()) {
                Column(Modifier.width(if (details != null) 196.dp else 56.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (details != null) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            close()
                            if (editable) rotate()
                        }
                    } else {
                        close()
                        if (editable) rotate()
                    }
                    if (details != null) {
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) { details() }
                        }
                    }
                }
                Box(Modifier.weight(1f).fillMaxHeight()) { preview() }
                if (editable) {
                    Column(Modifier.width(112.dp).fillMaxHeight().padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)) {
                        if (edits.mode == "draw" && edits.tool != AnnotationTool.ERASER) LazyVerticalGrid(GridCells.Fixed(2), Modifier.weight(1f).testTag("annotation-colors")) { items(colors) { swatch(it) } }
                        Row { undoRedo() }
                        Row { modes() }
                        accept()
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    close()
                    if (editable) {
                        rotate()
                        accept()
                    } else {
                        Spacer(Modifier.size(48.dp))
                    }
                }
                details?.invoke()
                Box(Modifier.weight(1f).fillMaxWidth()) { preview() }
                if (editable) {
                    if (edits.mode == "draw" && edits.tool != AnnotationTool.ERASER) LazyRow(Modifier.fillMaxWidth().testTag("annotation-colors"), contentPadding = PaddingValues(horizontal = 12.dp)) { items(colors) { swatch(it) } }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.Center) {
                        undoRedo()
                        Spacer(Modifier.weight(1f))
                        modes()
                    }
                }
            }
        }
        if (source != null && error != null) Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.BottomCenter) { Text(error, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error) }
    }
}

fun Bitmap.rotatedForEditing(turns: Int): Bitmap = if (turns.mod(4) == 0) this else Bitmap.createBitmap(this, 0, 0, width, height, Matrix().apply { postRotate(turns.mod(4) * 90f) }, true)

private fun drawAnnotations(canvas: android.graphics.Canvas, strokes: List<AnnotationStroke>, width: Float, height: Float, left: Float = 0f, top: Float = 0f) {
    strokes.forEach { stroke ->
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = stroke.color.toArgb()
            alpha = if (stroke.tool == AnnotationTool.BRUSH) 90 else 255
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = width * if (stroke.tool == AnnotationTool.PENCIL) 0.006f else 0.025f
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
            if (stroke.tool == AnnotationTool.ERASER) xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
        val path = android.graphics.Path()
        stroke.points.forEachIndexed { i, p -> if (i == 0) path.moveTo(left + p.x * width, top + p.y * height) else path.lineTo(left + p.x * width, top + p.y * height) }
        canvas.drawPath(path, paint)
    }
}

/** The input is already cropped; normalized strokes still refer to the full rotated image. */
fun annotateCroppedImage(source: Bitmap, crop: NormalizedRect, strokes: List<AnnotationStroke>): Bitmap {
    val result = source.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = android.graphics.Canvas(result)
    val layer = canvas.saveLayer(0f, 0f, result.width.toFloat(), result.height.toFloat(), null)
    val width = result.width / crop.width.toFloat()
    val height = result.height / crop.height.toFloat()
    drawAnnotations(canvas, strokes, width, height, -crop.x.toFloat() * width, -crop.y.toFloat() * height)
    canvas.restoreToCount(layer)
    return result
}

fun renderEditedImage(source: Bitmap, crop: NormalizedRect, strokes: List<AnnotationStroke>): Bitmap {
    val left = floor(crop.x * source.width).toInt().coerceIn(0, source.width - 1)
    val top = floor(crop.y * source.height).toInt().coerceIn(0, source.height - 1)
    val right = ceil((crop.x + crop.width) * source.width).toInt().coerceIn(left + 1, source.width)
    val bottom = ceil((crop.y + crop.height) * source.height).toInt().coerceIn(top + 1, source.height)
    val cropped = Bitmap.createBitmap(source, left, top, right - left, bottom - top)
    try {
        return annotateCroppedImage(cropped, NormalizedRect(left.toDouble() / source.width, top.toDouble() / source.height, (right - left).toDouble() / source.width, (bottom - top).toDouble() / source.height), strokes)
    } finally {
        if (cropped !== source) cropped.recycle()
    }
}
private fun fittedImage(size: Size, bitmap: Bitmap): Rect {
    val scale = minOf(size.width / bitmap.width, size.height / bitmap.height)
    val width = bitmap.width * scale
    val height = bitmap.height * scale
    return Rect((size.width - width) / 2, (size.height - height) / 2, (size.width + width) / 2, (size.height + height) / 2)
}

@Composable
private fun ImageEditCanvas(bitmap: Bitmap, crop: NormalizedRect, cropping: Boolean, strokes: List<AnnotationStroke>, color: Color, tool: AnnotationTool, enabled: Boolean, onCrop: (NormalizedRect) -> Unit, onStrokes: (List<AnnotationStroke>) -> Unit) {
    val currentCrop by rememberUpdatedState(crop)
    val currentStrokes by rememberUpdatedState(strokes)
    val currentColor by rememberUpdatedState(color)
    val currentTool by rememberUpdatedState(tool)
    val cropCallback by rememberUpdatedState(onCrop)
    val strokesCallback by rememberUpdatedState(onStrokes)
    Box(Modifier.fillMaxSize().padding(20.dp)) {
        Image(bitmap.asImageBitmap(), "Edit image", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        Canvas(
            Modifier.fillMaxSize().testTag("attachment-image-editor").pointerInput(bitmap, cropping, enabled) {
                var handle = -1
                var anchor = Offset.Zero
                var startedInside = false
                var drawingStrokes = emptyList<AnnotationStroke>()
                detectDragGestures(onDragStart = { point ->
                    val bounds = fittedImage(Size(size.width.toFloat(), size.height.toFloat()), bitmap)
                    startedInside = enabled && bounds.contains(point)
                    val normalized = Offset(((point.x - bounds.left) / bounds.width).coerceIn(0f, 1f), ((point.y - bounds.top) / bounds.height).coerceIn(0f, 1f))
                    anchor = normalized
                    if (cropping && startedInside) {
                        val c = currentCrop
                        val corners = listOf(Offset(c.x.toFloat(), c.y.toFloat()), Offset((c.x + c.width).toFloat(), c.y.toFloat()), Offset((c.x + c.width).toFloat(), (c.y + c.height).toFloat()), Offset(c.x.toFloat(), (c.y + c.height).toFloat()))
                        handle = corners.indices.minBy { index -> hypot((corners[index].x - normalized.x) * bounds.width, (corners[index].y - normalized.y) * bounds.height) }
                        if (hypot((corners[handle].x - normalized.x) * bounds.width, (corners[handle].y - normalized.y) * bounds.height) > 64.dp.toPx()) handle = -1
                    } else if (startedInside) {
                        drawingStrokes = currentStrokes + AnnotationStroke(listOf(normalized), currentColor, currentTool)
                        strokesCallback(drawingStrokes)
                    }
                }) { change, _ ->
                    change.consume()
                    val bounds = fittedImage(Size(size.width.toFloat(), size.height.toFloat()), bitmap)
                    val point = Offset(((change.position.x - bounds.left) / bounds.width).coerceIn(0f, 1f), ((change.position.y - bounds.top) / bounds.height).coerceIn(0f, 1f))
                    if (cropping && startedInside) {
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
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                val layer = native.saveLayer(bounds.left, bounds.top, bounds.right, bounds.bottom, null)
                drawAnnotations(native, strokes, bounds.width, bounds.height, bounds.left, bounds.top)
                native.restoreToCount(layer)
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

@Composable
fun ZoomableImage(bitmap: Bitmap) {
    var scale by remember(bitmap) { mutableFloatStateOf(1f) }
    var offset by remember(bitmap) { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val transform = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 5f)
        val limitX = viewport.width * (scale - 1) / 2
        val limitY = viewport.height * (scale - 1) / 2
        offset = Offset((offset.x + pan.x).coerceIn(-limitX, limitX), (offset.y + pan.y).coerceIn(-limitY, limitY))
    }
    Box(
        // Let the pager handle a one-finger swipe until the image is zoomed.
        Modifier.fillMaxSize().clipToBounds().onSizeChanged { viewport = it }.transformable(transform, canPan = { scale > 1f }),
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
