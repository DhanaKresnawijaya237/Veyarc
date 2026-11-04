package com.veyarc.feature.editor

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Colorize
import androidx.compose.material.icons.outlined.Screenshot
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.veyarc.automation.android.MediaProjectionCaptureRuntime
import com.veyarc.automation.android.MediaProjectionCaptureService
import com.veyarc.automation.android.MediaProjectionConsent
import com.veyarc.data.storage.WorkflowAssetDescriptor
import com.veyarc.model.AssetId
import com.veyarc.model.AssetKind
import com.veyarc.model.NormalizedCoordinate
import com.veyarc.model.bundle.BundleAsset
import com.veyarc.ui.ZoomableImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.util.concurrent.atomic.AtomicBoolean

internal class ColorPickerImages {
    private val images = mutableMapOf<String, MutableState<TemplateCropSource?>>()
    fun image(id: String): MutableState<TemplateCropSource?> = images.getOrPut(id) { mutableStateOf(null) }
    fun current(id: String): TemplateCropSource? = images[id]?.value
    fun clear() {
        images.values.forEach { it.value = null }
        images.clear()
    }
}
internal val LocalColorPickerImages = staticCompositionLocalOf<ColorPickerImages?> { null }

@Composable
internal fun ColorPalette(argb: Long, onChange: (Long) -> Unit) {
    val hsv = remember(argb) { FloatArray(3).also { android.graphics.Color.colorToHSV(argb.toInt(), it) } }
    var hue by rememberSaveable { mutableStateOf(hsv[0]) }
    androidx.compose.runtime.LaunchedEffect(argb) { if (hsv[1] > 0f) hue = hsv[0] }
    val change by rememberUpdatedState(onChange)
    val alpha = (argb ushr 24).toInt()
    fun color(saturation: Float, brightness: Float) = android.graphics.Color.HSVToColor(alpha, floatArrayOf(hue, saturation, brightness)).toLong() and 0xFFFFFFFFL
    Canvas(
        Modifier.fillMaxWidth().height(156.dp).clip(RoundedCornerShape(18.dp)).testTag("color-palette").pointerInput(hue, alpha) {
            awaitEachGesture {
                fun update(position: Offset) {
                    change(color((position.x / size.width).coerceIn(0f, 1f), (1f - position.y / size.height).coerceIn(0f, 1f)))
                }
                val down = awaitFirstDown()
                update(down.position)
                down.consume()
                do {
                    val event = awaitPointerEvent()
                    val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                    update(pointer.position)
                    pointer.consume()
                } while (pointer.pressed)
            }
        },
    ) {
        drawRect(Brush.horizontalGradient(listOf(Color.White, Color.hsv(hue, 1f, 1f))))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
        val position = Offset(hsv[1] * size.width, (1f - hsv[2]) * size.height)
        drawCircle(Color.Black, 7.dp.toPx(), position, style = Stroke(3.dp.toPx()))
        drawCircle(Color.White, 7.dp.toPx(), position, style = Stroke(1.5.dp.toPx()))
    }
    Box(Modifier.fillMaxWidth().height(42.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(8.dp)).background(Brush.horizontalGradient((0..6).map { Color.hsv(it * 60f, 1f, 1f) })))
        Slider(hue, {
            hue = it
            onChange(color(hsv[1], hsv[2]))
        }, valueRange = 0f..360f, colors = SliderDefaults.colors(activeTrackColor = Color.Transparent, inactiveTrackColor = Color.Transparent))
    }
}

@Composable
internal fun ColorSampleTools(
    assets: List<WorkflowAssetDescriptor>,
    assetNames: Map<String, String>,
    loadAsset: suspend (AssetId) -> BundleAsset?,
    loadPreview: suspend (AssetId, Int) -> TemplatePreviewData?,
    previewWidthPx: Int,
    previewHeightPx: Int,
    onPick: (NormalizedCoordinate?, Long) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pick by rememberUpdatedState(onPick)
    val alive = remember { AtomicBoolean(true) }
    val owner = remember { Any() }
    val imageId = rememberSaveable { java.util.UUID.randomUUID().toString() }
    val retainedImages = LocalColorPickerImages.current
    var source by remember(imageId, retainedImages) { retainedImages?.image(imageId) ?: mutableStateOf(null) }
    var busy by remember { mutableStateOf(false) }
    var countdown by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var explainCapture by rememberSaveable { mutableStateOf(false) }
    var showImages by rememberSaveable { mutableStateOf(false) }
    var captureJob by remember { mutableStateOf<Job?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            alive.set(false)
            captureJob?.cancel()
            MediaProjectionCaptureService.stop(context, owner = owner)
        }
    }
    DisposableEffect(source) {
        val displayed = source
        onDispose { if (displayed?.existingAsset == null && (retainedImages == null || retainedImages.current(imageId) !== displayed)) displayed?.preview?.asAndroidBitmap()?.recycle() }
    }
    val upload = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null && alive.get()) {
            scope.launch {
                busy = true
                var opened: TemplateCropSource? = null
                try {
                    runInterruptible(Dispatchers.IO) { opened = loadTemplateCropSource(context.contentResolver, uri) }
                    source = opened
                    opened = null
                    error = null
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    error = "Could not open image."
                } finally {
                    opened?.preview?.asAndroidBitmap()?.recycle()
                    busy = false
                }
            }
        }
    }
    val screen = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (alive.get() && MediaProjectionConsent.wasGranted(result.resultCode, result.data)) {
            captureJob = scope.launch {
                busy = true
                var snapshot: android.graphics.Bitmap? = null
                try {
                    MediaProjectionCaptureService.start(context, result.resultCode, requireNotNull(result.data), owner = owner)
                    runInterruptible(Dispatchers.IO) { check(MediaProjectionCaptureRuntime.awaitActive(5000)) { "Screen capture did not start." } }
                    for (seconds in 5 downTo 1) {
                        countdown = seconds
                        delay(1000)
                    }
                    runInterruptible(Dispatchers.Default) {
                        snapshot = requireNotNull(MediaProjectionCaptureRuntime.captureLatestFrame()) { "No screenshot arrived. Try again." }.use { it.copyBitmap() }
                    }
                    val bitmap = requireNotNull(snapshot)
                    source = TemplateCropSource(Uri.EMPTY, bitmap.width, bitmap.height, bitmap.asImageBitmap())
                    snapshot = null
                    error = null
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    error = failure.message ?: "Could not capture the screen."
                } finally {
                    snapshot?.recycle()
                    MediaProjectionCaptureService.stop(context, owner = owner)
                    busy = false
                    countdown = 0
                }
            }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { showImages = true }, enabled = !busy, modifier = Modifier.weight(1f)) {
            Icon(Icons.Outlined.Colorize, null, Modifier.size(18.dp))
            Text(" From image")
        }
        OutlinedButton(onClick = {
            MediaProjectionCaptureService.stopIdleWorkflowSession(context)
            if (MediaProjectionCaptureRuntime.isActive) error = "Finish the current screen capture first." else explainCapture = true
        }, enabled = !busy, modifier = Modifier.weight(1f)) {
            Icon(Icons.Outlined.Screenshot, null, Modifier.size(18.dp))
            Text(" Screen")
        }
    }
    TemplatePickerSheet(
        visible = showImages,
        title = "Choose image",
        assets = assets.filter { it.kind == AssetKind.IMAGE_TEMPLATE },
        assetNames = assetNames,
        displayWidthPx = previewWidthPx,
        displayHeightPx = previewHeightPx,
        fullScreenOnly = false,
        selectedAssetId = null,
        allowNone = false,
        allowAnySize = true,
        onDismiss = { showImages = false },
        onAddImage = {
            showImages = false
            upload.launch("image/*")
        },
        onSelect = { id ->
            showImages = false
            if (id != null && alive.get()) {
                scope.launch {
                    busy = true
                    try {
                        val asset = requireNotNull(loadAsset(id))
                        source = runInterruptible(Dispatchers.IO) { loadTemplateCropSource(asset) }
                        error = null
                    } catch (failure: Exception) {
                        if (failure is CancellationException) throw failure
                        error = "Could not open image."
                    } finally {
                        busy = false
                    }
                }
            }
        },
        loadPreview = loadPreview,
    )
    if (busy) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Text(if (countdown > 0) "Screenshot in $countdown…" else "Opening…", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            if (captureJob?.isActive == true) TextButton(onClick = { captureJob?.cancel() }) { Text("Cancel") }
        }
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    if (explainCapture) {
        AlertDialog(
            onDismissRequest = { explainCapture = false },
            title = { Text("Pick from screen") },
            text = { Text("• Allow screen capture.\n• Switch to the screen you want within 5 seconds.\n• Return here to pick a color.") },
            confirmButton = {
                TextButton(onClick = {
                    explainCapture = false
                    runCatching { screen.launch(MediaProjectionConsent.createRequestIntent(context, fullDisplay = true)) }.onFailure { error = "Screen capture is unavailable." }
                }) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { explainCapture = false }) { Text("Cancel") } },
        )
    }
    source?.let { image ->
        PixelSampleDialog(image, onDismiss = { source = null }, onPick = { point, argb ->
            // A crop has no screen origin, so it supplies only the color.
            val screenPoint = point.takeUnless { image.existingAsset != null && (image.widthPx != previewWidthPx || image.heightPx != previewHeightPx) }
            pick(screenPoint, argb)
            source = null
        })
    }
}

@Composable
private fun PixelSampleDialog(source: TemplateCropSource, onDismiss: () -> Unit, onPick: (NormalizedCoordinate, Long) -> Unit) {
    val context = LocalContext.current
    var point by rememberSaveable { mutableStateOf<List<Double>?>(null) }
    var argb by remember { mutableStateOf<Long?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val selected = point?.let { NormalizedCoordinate(it[0], it[1]) }
    androidx.compose.runtime.LaunchedEffect(point) {
        argb = null
        selected?.let {
            runCatching { runInterruptible(Dispatchers.IO) { sampleImagePixel(context.contentResolver, source, it) } }
                .onSuccess {
                    argb = it
                    error = null
                }.onFailure {
                    if (it is CancellationException) throw it
                    error = "Could not read that pixel."
                }
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = Color.Black) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onDismiss) { Icon(Icons.Outlined.Close, "Close color picker", tint = Color.White) }
                    Column(Modifier.weight(1f)) {
                        Text(argb?.let { "#%08X".format(it) } ?: "Tap a pixel · Pinch to zoom", color = Color.White)
                        selected?.let { Text("X %.4f · Y %.4f".format(java.util.Locale.ROOT, it.x, it.y), style = MaterialTheme.typography.bodySmall, color = Color.LightGray) }
                    }
                    argb?.let { Box(Modifier.padding(8.dp).size(30.dp).background(Color(it.toInt()), RoundedCornerShape(8.dp))) }
                    FilledIconButton(onClick = { onPick(requireNotNull(selected), requireNotNull(argb)) }, enabled = argb != null) { Icon(Icons.Outlined.Check, "Use color") }
                }
                Box(Modifier.weight(1f).fillMaxWidth().testTag("pixel-image")) { ZoomableImage(source.preview.asAndroidBitmap(), selected) { point = listOf(it.x, it.y) } }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}
