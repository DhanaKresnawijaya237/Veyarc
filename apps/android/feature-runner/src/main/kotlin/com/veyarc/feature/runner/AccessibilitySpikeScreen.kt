package com.veyarc.feature.runner

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.veyarc.automation.android.AccessibilityGestureDispatcher
import com.veyarc.automation.android.GestureDispatchResult
import com.veyarc.automation.android.PassiveTouchObservationRecorder
import com.veyarc.automation.android.ScreenPoint
import com.veyarc.automation.android.ShizukuInputReplay
import com.veyarc.automation.android.ShizukuInputReplaySnapshot
import com.veyarc.automation.android.ShizukuTouchProbe
import com.veyarc.automation.android.ShizukuTouchProbeSnapshot
import com.veyarc.automation.android.TouchCaptureUpdate
import com.veyarc.automation.android.TouchInterceptionRecorder
import kotlin.math.hypot

@Composable
fun AccessibilitySpikeScreen(
    resumeGeneration: Int,
    onBack: () -> Unit = {},
    onStartRecording: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val resources = LocalResources.current
    var statusGeneration by remember { mutableIntStateOf(resumeGeneration) }
    val accessibilityEnabled = remember(resumeGeneration, statusGeneration) {
        AccessibilityGestureDispatcher.isEnabled(context)
    }
    val serviceConnected = remember(resumeGeneration, statusGeneration) {
        AccessibilityGestureDispatcher.isConnected
    }
    var surfaceBounds by remember { mutableStateOf<Rect?>(null) }
    var statusMessage by remember { mutableStateOf("") }
    var observedMessage by remember { mutableStateOf("") }
    var observedPath by remember { mutableStateOf(emptyList<Offset>()) }
    var observedGestureCount by remember { mutableIntStateOf(0) }
    var replaySurfaceBounds by remember { mutableStateOf<Rect?>(null) }
    var captureMessage by remember { mutableStateOf("") }
    val shizukuProbe = remember(context) { ShizukuTouchProbe(context) }
    var shizukuSnapshot by remember { mutableStateOf(shizukuProbe.snapshot()) }
    val shizukuReplay = remember(context) { ShizukuInputReplay(context) }
    var shizukuReplaySnapshot by remember { mutableStateOf(shizukuReplay.snapshot()) }
    val viewConfiguration = LocalViewConfiguration.current

    DisposableEffect(shizukuProbe) {
        shizukuProbe.setCallback { shizukuSnapshot = it }
        onDispose { shizukuProbe.close() }
    }

    DisposableEffect(shizukuReplay) {
        shizukuReplay.setCallback { shizukuReplaySnapshot = it }
        onDispose { shizukuReplay.close() }
    }

    fun updateDispatchResult(result: GestureDispatchResult) {
        statusMessage =
            when (result) {
                GestureDispatchResult.Completed -> resources.getString(R.string.gesture_completed)
                GestureDispatchResult.Cancelled -> resources.getString(R.string.gesture_cancelled)
                GestureDispatchResult.ServiceUnavailable -> resources.getString(R.string.gesture_service_unavailable)
                GestureDispatchResult.AlreadyInProgress -> resources.getString(R.string.gesture_already_running)
                GestureDispatchResult.RejectedBySystem -> resources.getString(R.string.gesture_rejected)
                is GestureDispatchResult.InvalidRequest -> resources.getString(R.string.gesture_invalid, result.reason)
            }
        statusGeneration += 1
    }

    fun measuredBoundsOrReport(): Rect? = surfaceBounds.also { bounds ->
        if (bounds == null) {
            statusMessage = resources.getString(R.string.gesture_surface_unavailable)
        }
    }

    fun isReplaySurfaceUsable(bounds: Rect?): Boolean = bounds?.let {
        val displayMetrics = context.resources.displayMetrics
        it.width > 0f &&
            it.height > 0f &&
            it.left >= 0f &&
            it.top >= 0f &&
            it.right <= displayMetrics.widthPixels.toFloat() &&
            it.bottom <= displayMetrics.heightPixels.toFloat()
    } == true

    fun handleObservedGesture(samples: List<Offset>, duration: Long) {
        if (samples.isEmpty()) return
        val start = samples.first()
        val maximumDistance = samples.maxOf { point -> hypot(point.x - start.x, point.y - start.y) }
        observedPath = samples
        observedMessage =
            when {
                maximumDistance <= viewConfiguration.touchSlop &&
                    duration >= viewConfiguration.longPressTimeoutMillis -> {
                    resources.getString(R.string.long_press_received)
                }

                maximumDistance <= viewConfiguration.touchSlop -> {
                    resources.getString(R.string.tap_received)
                }

                else ->
                    resources.getQuantityString(
                        R.plurals.path_received,
                        samples.size,
                        samples.size,
                    )
            }
        observedGestureCount += 1
    }

    Column(
        modifier =
        modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.accessibility_spike_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = stringResource(R.string.accessibility_spike_explanation),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text =
            stringResource(
                if (accessibilityEnabled) R.string.accessibility_enabled else R.string.accessibility_disabled,
            ),
        )
        Text(
            text =
            stringResource(
                if (serviceConnected) R.string.accessibility_connected else R.string.accessibility_disconnected,
            ),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    if (!AccessibilityGestureDispatcher.openAccessibilitySettings(context)) {
                        statusMessage = resources.getString(R.string.settings_unavailable)
                    }
                },
            ) {
                Text(stringResource(R.string.open_accessibility_settings))
            }
            OutlinedButton(onClick = { statusGeneration += 1 }) {
                Text(stringResource(R.string.refresh_accessibility_status))
            }
        }
        Text(
            text = stringResource(R.string.gesture_test_surface),
            style = MaterialTheme.typography.titleMedium,
        )
        Canvas(
            modifier =
            Modifier
                .fillMaxWidth()
                .height(240.dp)
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(16.dp),
                ).onGloballyPositioned { coordinates ->
                    val topLeft = coordinates.positionOnScreen()
                    surfaceBounds =
                        Rect(
                            left = topLeft.x,
                            top = topLeft.y,
                            right = topLeft.x + coordinates.size.width,
                            bottom = topLeft.y + coordinates.size.height,
                        )
                }
                .observeSinglePointerGesture(viewConfiguration, ::handleObservedGesture),
        ) {
            drawCircle(
                color = Color(0xFF6750A4),
                radius = 18.dp.toPx(),
                center = center,
            )
            if (observedPath.size > 1) {
                val path = Path().apply {
                    moveTo(observedPath.first().x, observedPath.first().y)
                    observedPath.drop(1).forEach { point -> lineTo(point.x, point.y) }
                }
                drawPath(path, Color(0xFFB3261E), style = Stroke(width = 4.dp.toPx()))
            }
        }
        Text(
            text = observedMessage.ifEmpty { stringResource(R.string.gesture_test_hint) },
            style = MaterialTheme.typography.bodyMedium,
        )
        Text("Observed gestures: $observedGestureCount")
        Text(
            text = stringResource(R.string.touch_capture_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.touch_capture_explanation),
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(
            enabled = !TouchInterceptionRecorder.isArmed,
            onClick = {
                TouchInterceptionRecorder.arm { update ->
                    captureMessage =
                        when (update) {
                            TouchCaptureUpdate.UnsupportedApi -> resources.getString(R.string.touch_capture_unsupported)

                            TouchCaptureUpdate.ServiceUnavailable -> resources.getString(R.string.gesture_service_unavailable)

                            TouchCaptureUpdate.AlreadyArmed -> resources.getString(R.string.touch_capture_already_armed)

                            TouchCaptureUpdate.Arming -> resources.getString(R.string.touch_capture_arming)

                            TouchCaptureUpdate.Armed -> resources.getString(R.string.touch_capture_armed)

                            TouchCaptureUpdate.Capturing -> resources.getString(R.string.touch_capture_capturing)

                            is TouchCaptureUpdate.Replaying ->
                                resources.getQuantityString(
                                    R.plurals.touch_capture_replaying,
                                    update.gesture.samples.size,
                                    update.gesture.samples.size,
                                )

                            is TouchCaptureUpdate.Completed ->
                                resources.getString(
                                    R.string.touch_capture_completed,
                                    update.gesture.samples.size,
                                    update.gesture.durationMillis,
                                )

                            TouchCaptureUpdate.Cancelled -> resources.getString(R.string.touch_capture_cancelled)

                            is TouchCaptureUpdate.Failed -> resources.getString(R.string.touch_capture_failed, update.reason)
                        }
                }
            },
        ) {
            Text(stringResource(R.string.arm_touch_capture))
        }
        if (captureMessage.isNotEmpty()) {
            Text(captureMessage, color = MaterialTheme.colorScheme.primary)
        }
        ShizukuProbeContent(
            snapshot = shizukuSnapshot,
            onRefresh = shizukuProbe::refresh,
            onRequestPermission = shizukuProbe::requestPermission,
            onStart = shizukuProbe::startThirdPartyTest,
            onStop = shizukuProbe::stop,
        )
        ShizukuReplayContent(
            snapshot = shizukuReplaySnapshot,
            surfaceBounds = replaySurfaceBounds,
            observedPath = observedPath,
            observedMessage = observedMessage,
            observedGestureCount = observedGestureCount,
            onSurfaceBoundsChanged = { bounds ->
                replaySurfaceBounds = bounds.takeIf(::isReplaySurfaceUsable)
            },
            onObservedGesture = ::handleObservedGesture,
            onRefresh = shizukuReplay::refresh,
            onRequestPermission = shizukuReplay::requestPermission,
            onTap = {
                replaySurfaceBounds?.let { bounds ->
                    shizukuReplay.dispatchTap(bounds.center.x, bounds.center.y)
                }
            },
            onSwipe = {
                replaySurfaceBounds?.let { bounds ->
                    val insetX = bounds.width * 0.25f
                    shizukuReplay.dispatchSwipe(
                        startX = bounds.left + insetX,
                        startY = bounds.center.y,
                        endX = bounds.right - insetX,
                        endY = bounds.center.y,
                        durationMillis = 700L,
                    )
                }
            },
            onQuickSequence = {
                replaySurfaceBounds?.let { bounds ->
                    val insetX = bounds.width * 0.25f
                    shizukuReplay.dispatchQuickSequence(
                        firstTapX = bounds.left + insetX,
                        firstTapY = bounds.center.y,
                        secondTapX = bounds.right - insetX,
                        secondTapY = bounds.center.y,
                        swipeStartX = bounds.left + insetX,
                        swipeStartY = bounds.bottom - bounds.height * 0.25f,
                        swipeEndX = bounds.right - insetX,
                        swipeEndY = bounds.bottom - bounds.height * 0.25f,
                    )
                }
            },
            onCancel = shizukuReplay::cancel,
        )
        OutlinedButton(
            enabled = accessibilityEnabled && serviceConnected && PassiveTouchObservationRecorder.isSupported,
            onClick = onStartRecording,
        ) {
            Text(stringResource(R.string.start_coordinate_recording))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = {
                    measuredBoundsOrReport()?.let { bounds ->
                        AccessibilityGestureDispatcher.dispatchTap(
                            ScreenPoint(bounds.center.x, bounds.center.y),
                            ::updateDispatchResult,
                        )
                    }
                },
            ) {
                Text(stringResource(R.string.dispatch_tap))
            }
            OutlinedButton(
                onClick = {
                    measuredBoundsOrReport()?.let { bounds ->
                        AccessibilityGestureDispatcher.dispatchLongPress(
                            ScreenPoint(bounds.center.x, bounds.center.y),
                            ::updateDispatchResult,
                        )
                    }
                },
            ) {
                Text(stringResource(R.string.dispatch_long_press))
            }
        }
        Button(
            onClick = {
                measuredBoundsOrReport()?.let { bounds ->
                    val insetX = bounds.width * 0.2f
                    val insetY = bounds.height * 0.2f
                    AccessibilityGestureDispatcher.dispatchPath(
                        points =
                        listOf(
                            ScreenPoint(bounds.left + insetX, bounds.top + insetY),
                            ScreenPoint(bounds.right - insetX, bounds.top + insetY),
                            ScreenPoint(bounds.right - insetX, bounds.bottom - insetY),
                            ScreenPoint(bounds.left + insetX, bounds.bottom - insetY),
                            ScreenPoint(bounds.left + insetX, bounds.top + insetY),
                        ),
                        durationMillis = 1_200,
                        onResult = ::updateDispatchResult,
                    )
                }
            },
        ) {
            Text(stringResource(R.string.dispatch_rectangle))
        }
        if (statusMessage.isNotEmpty()) {
            Text(statusMessage, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun ShizukuProbeContent(
    snapshot: ShizukuTouchProbeSnapshot,
    onRefresh: () -> Unit,
    onRequestPermission: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    HorizontalDivider()
    Text("Shizuku raw-input feasibility test", style = MaterialTheme.typography.titleMedium)
    Text(
        "This is a read-only 30-second diagnostic for the Vivo V50. It counts touchscreen events through a Shizuku shell-identity helper; it never injects, intercepts, or saves touch data.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Text("Binder: ${if (snapshot.binderAvailable) "available" else "not available"}")
    Text("Veyarc permission: ${if (snapshot.permissionGranted) "granted" else "not granted"}")
    Text("Shizuku server UID: ${snapshot.serverUid ?: "unknown"}")
    if (snapshot.serverSelinuxContext != null) {
        Text("Shizuku SELinux context: ${snapshot.serverSelinuxContext}")
    }
    if (snapshot.remoteProcessUid != null) {
        Text("Probe process UID: ${snapshot.remoteProcessUid}")
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onRefresh) { Text("Refresh Shizuku") }
        if (snapshot.binderAvailable && !snapshot.permissionGranted) {
            Button(onClick = onRequestPermission) { Text("Grant Veyarc access") }
        }
    }
    Button(
        enabled = snapshot.binderAvailable && snapshot.permissionGranted && !snapshot.running,
        onClick = onStart,
    ) {
        Text("Start third-party test")
    }
    if (snapshot.running) {
        Text(
            "Now press Home, open a third-party app such as Settings or Chrome, make several taps and a short drag, then return to Veyarc before the timer ends.",
            color = MaterialTheme.colorScheme.primary,
        )
        OutlinedButton(onClick = onStop) { Text("Stop probe") }
    }
    Text("Raw event lines: ${snapshot.eventCount}")
    Text("Touch contacts: ${snapshot.touchContactCount} · position samples: ${snapshot.positionSampleCount}")
    Text(snapshot.message, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun ShizukuReplayContent(
    snapshot: ShizukuInputReplaySnapshot,
    surfaceBounds: Rect?,
    observedPath: List<Offset>,
    observedMessage: String,
    observedGestureCount: Int,
    onSurfaceBoundsChanged: (Rect) -> Unit,
    onObservedGesture: (List<Offset>, Long) -> Unit,
    onRefresh: () -> Unit,
    onRequestPermission: () -> Unit,
    onTap: () -> Unit,
    onSwipe: () -> Unit,
    onQuickSequence: () -> Unit,
    onCancel: () -> Unit,
) {
    HorizontalDivider()
    Text("Shizuku input-replay feasibility test", style = MaterialTheme.typography.titleMedium)
    Text(
        "This diagnostic sends only fixed tap and swipe commands through a shell-identity " +
            "Shizuku helper. Test the Veyarc surface first. It does not write to /dev/input, " +
            "use sendevent/uinput, run workflows, or replay while recording.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Text("Binder: ${if (snapshot.binderAvailable) "available" else "not available"}")
    Text("Veyarc permission: ${if (snapshot.permissionGranted) "granted" else "not granted"}")
    if (snapshot.remoteProcessUid != null) {
        Text("Replay helper UID: ${snapshot.remoteProcessUid}")
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onRefresh) { Text("Refresh Shizuku") }
        if (snapshot.binderAvailable && !snapshot.permissionGranted) {
            Button(onClick = onRequestPermission) { Text("Grant Veyarc access") }
        }
    }
    Text(
        "Remain on the Veyarc gesture test surface for the first three checks. " +
            "Use the replay target below; the commands use its current display coordinates.",
        color = MaterialTheme.colorScheme.primary,
    )
    Text("Replay target surface", style = MaterialTheme.typography.titleSmall)
    Canvas(
        modifier =
        Modifier
            .fillMaxWidth()
            .height(160.dp)
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(16.dp),
            ).onGloballyPositioned { coordinates ->
                val topLeft = coordinates.positionOnScreen()
                onSurfaceBoundsChanged(
                    Rect(
                        left = topLeft.x,
                        top = topLeft.y,
                        right = topLeft.x + coordinates.size.width,
                        bottom = topLeft.y + coordinates.size.height,
                    ),
                )
            }.observeSinglePointerGesture(
                LocalViewConfiguration.current,
                onObservedGesture,
            ),
    ) {
        drawCircle(
            color = Color(0xFF6750A4),
            radius = 14.dp.toPx(),
            center = center,
        )
        if (observedPath.size > 1) {
            val path = Path().apply {
                moveTo(observedPath.first().x, observedPath.first().y)
                observedPath.drop(1).forEach { point -> lineTo(point.x, point.y) }
            }
            drawPath(path, Color(0xFFB3261E), style = Stroke(width = 4.dp.toPx()))
        }
    }
    if (surfaceBounds == null) {
        Text(
            "Keep the replay target fully visible before pressing a replay button.",
            color = MaterialTheme.colorScheme.error,
        )
    }
    Text("Observed gestures: $observedGestureCount")
    if (observedMessage.isNotEmpty()) {
        Text(observedMessage, color = MaterialTheme.colorScheme.primary)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            enabled = snapshot.binderAvailable && snapshot.permissionGranted &&
                !snapshot.running && surfaceBounds != null,
            onClick = onTap,
        ) {
            Text("Shizuku tap")
        }
        OutlinedButton(
            enabled = snapshot.binderAvailable && snapshot.permissionGranted &&
                !snapshot.running && surfaceBounds != null,
            onClick = onSwipe,
        ) {
            Text("Shizuku swipe")
        }
    }
    Button(
        enabled = snapshot.binderAvailable && snapshot.permissionGranted &&
            !snapshot.running && surfaceBounds != null,
        onClick = onQuickSequence,
    ) {
        Text("Run two taps + swipe")
    }
    if (snapshot.running) {
        OutlinedButton(onClick = onCancel) { Text("Cancel replay") }
    }
    Text("Queued commands: ${snapshot.queuedCount} · completed: ${snapshot.completedCount}")
    snapshot.lastCommand?.let { command -> Text("Last command: $command") }
    snapshot.lastError?.let { error -> Text("Error: $error") }
    Text(snapshot.message, color = MaterialTheme.colorScheme.primary)
}

private fun Modifier.observeSinglePointerGesture(
    viewConfiguration: androidx.compose.ui.platform.ViewConfiguration,
    onGesture: (List<Offset>, Long) -> Unit,
): Modifier = pointerInput(viewConfiguration) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        // Keep the test surface gesture local. Without consuming the pointer changes, the
        // surrounding verticalScroll modifier receives the same swipe and scrolls the page.
        down.consume()
        val startedAt = SystemClock.uptimeMillis()
        val samples = mutableListOf(down.position)
        var pressed = true
        while (pressed) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            change.consume()
            samples += change.position
            pressed = change.pressed
        }
        onGesture(samples, SystemClock.uptimeMillis() - startedAt)
    }
}
