package com.veyarc.feature.editor

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.veyarc.automation.android.AndroidForegroundPackage
import com.veyarc.automation.android.MediaProjectionCaptureRuntime
import com.veyarc.automation.android.MediaProjectionCaptureService
import com.veyarc.automation.android.MediaProjectionConsent
import com.veyarc.automation.android.ReplayFloatingController
import com.veyarc.automation.android.currentRecordingViewport
import com.veyarc.model.ImagePresentCondition
import com.veyarc.model.NormalizedRect
import com.veyarc.model.ViewportSpec
import com.veyarc.model.bundle.BundleAsset
import com.veyarc.ui.renderEditedImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.util.concurrent.atomic.AtomicBoolean

/** Uses the same crop editor and encrypted images as ordinary image-condition editing. */
@Composable
fun RecordingImagePicker(
    viewport: ViewportSpec,
    existingNames: Set<String>,
    onDismiss: () -> Unit,
    onApply: (ImagePresentCondition, List<BundleAsset>, String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val apply by rememberUpdatedState(onApply)
    val owner = remember { Any() }
    val alive = remember { AtomicBoolean(true) }
    var source by remember { mutableStateOf<TemplateCropDraft?>(null) }
    var busy by remember { mutableStateOf(false) }
    var countdown by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var work by remember { mutableStateOf<Job?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            alive.set(false)
            work?.cancel()
            MediaProjectionCaptureService.stop(context, owner = owner)
            ReplayFloatingController.finishRecordingScreenshot(owner, returnToSteps = false)
        }
    }
    val currentWork by rememberUpdatedState(work)
    val image = source?.source
    DisposableEffect(image) {
        onDispose {
            val job = currentWork
            if (job != null && !job.isCompleted) {
                job.invokeOnCompletion { image?.preview?.asAndroidBitmap()?.recycle() }
            } else {
                image?.preview?.asAndroidBitmap()?.recycle()
            }
        }
    }
    fun accept(bitmap: Bitmap) {
        if (!alive.get()) {
            bitmap.recycle()
            return
        }
        if (bitmap.width != viewport.recordedWidthPx || bitmap.height != viewport.recordedHeightPx) {
            bitmap.recycle()
            error = "Use a screenshot at ${viewport.recordedWidthPx} × ${viewport.recordedHeightPx} px. Keep the recording orientation."
            return
        }
        source = TemplateCropDraft(TemplateCropSource(Uri.EMPTY, bitmap.width, bitmap.height, bitmap.asImageBitmap()))
    }
    val upload = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null && alive.get()) {
            work = scope.launch {
                busy = true
                var bitmap: Bitmap? = null
                try {
                    runInterruptible(Dispatchers.IO) { bitmap = loadTemplateCropSource(context.contentResolver, uri).preview.asAndroidBitmap() }
                    accept(requireNotNull(bitmap))
                    bitmap = null
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    error = failure.message ?: "Could not open screenshot."
                } finally {
                    bitmap?.recycle()
                    busy = false
                }
            }
        }
    }
    val screen = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (alive.get() && MediaProjectionConsent.wasGranted(result.resultCode, result.data)) {
            work = scope.launch {
                busy = true
                var bitmap: Bitmap? = null
                try {
                    MediaProjectionCaptureService.start(context, result.resultCode, requireNotNull(result.data), owner = owner)
                    runInterruptible(Dispatchers.IO) { check(MediaProjectionCaptureRuntime.awaitActive(5000)) { "Screen capture did not start." } }
                    check(ReplayFloatingController.hideForRecordingScreenshot(owner)) { "Pause recording before capturing the screen." }
                    var host = context
                    while (host is android.content.ContextWrapper && host !is android.app.Activity) host = host.baseContext
                    check((host as? android.app.Activity)?.moveTaskToBack(true) == true) { "Could not return to the app screen. Use a screenshot instead." }
                    for (seconds in 2 downTo 1) {
                        countdown = seconds
                        delay(1000)
                    }
                    check(currentRecordingViewport(context) == viewport) { "Keep the recording orientation and screen size." }
                    val foreground = AndroidForegroundPackage.currentForRecording()
                    check(foreground != null && foreground.value != context.packageName) { "Open your app screen before capturing. Try again." }
                    runInterruptible(Dispatchers.Default) {
                        bitmap = requireNotNull(MediaProjectionCaptureRuntime.captureLatestFrame()) { "No screenshot arrived. Try again." }.use { it.copyBitmap() }
                    }
                    accept(requireNotNull(bitmap))
                    bitmap = null
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    error = failure.message ?: "Could not capture this screen."
                } finally {
                    bitmap?.recycle()
                    MediaProjectionCaptureService.stop(context, owner = owner)
                    ReplayFloatingController.finishRecordingScreenshot(owner, returnToSteps = alive.get())
                    busy = false
                    countdown = 0
                }
            }
        }
    }
    val crop = source
    if (crop == null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Choose an image to check") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Capture the app screen, then crop what to look for. Recording stays paused.")
                    if (busy) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                            Text(if (countdown > 0) "Screenshot in $countdown…" else "Opening…")
                        }
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    if (MediaProjectionCaptureRuntime.isActive) {
                        error = "Finish the current screen capture first."
                    } else {
                        error = null
                        runCatching { screen.launch(MediaProjectionConsent.createRequestIntent(context, fullDisplay = true)) }.onFailure { error = "Screen capture is unavailable." }
                    }
                }) { Text("Capture screen") }
            },
            dismissButton = {
                Row {
                    TextButton(enabled = !busy, onClick = { upload.launch("image/*") }) { Text("Screenshot") }
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                }
            },
        )
    } else {
        TemplateCropEditorDialog(
            crop,
            viewport.recordedWidthPx,
            viewport.recordedHeightPx,
            existingNames,
            busy,
            onDraftChange = { source = it },
            onDismiss = onDismiss,
            onApply = { region, turns, name, strokes, _ ->
                if (turns.mod(4) != 0) {
                    source = crop.copy(error = "Keep the screenshot's original rotation for image checks.")
                } else {
                    work = scope.launch {
                        busy = true
                        try {
                            val assets = runInterruptible(Dispatchers.Default) {
                                val original = crop.source.preview.asAndroidBitmap()
                                val template = encryptTemplateBitmap(renderEditedImage(original, region, strokes))
                                val background = encryptTemplateBitmap(original.copy(Bitmap.Config.ARGB_8888, false))
                                listOf(template, background)
                            }
                            apply(ImagePresentCondition(assets[0].assetId, NormalizedRect(0.0, 0.0, 1.0, 1.0), 0.9, backgroundAssetId = assets[1].assetId), assets, name)
                        } catch (failure: Exception) {
                            if (failure is CancellationException) throw failure
                            source = crop.copy(error = failure.message ?: "Could not save image.")
                        } finally {
                            busy = false
                        }
                    }
                }
            },
        )
    }
}
