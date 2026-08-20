package com.veyarc.feature.runner

import android.os.SystemClock
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.veyarc.automation.android.ScreenPoint
import com.veyarc.automation.android.TouchCaptureUpdate
import com.veyarc.automation.android.TouchInterceptionRecorder
import kotlin.math.hypot

@Composable
fun AccessibilitySpikeScreen(
    resumeGeneration: Int,
    modifier: Modifier = Modifier,
) {
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
    var captureMessage by remember { mutableStateOf("") }
    val viewConfiguration = LocalViewConfiguration.current

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
                }.pointerInput(viewConfiguration) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val startedAt = SystemClock.uptimeMillis()
                        val samples = mutableListOf(down.position)
                        var pressed = true
                        while (pressed) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            samples += change.position
                            pressed = change.pressed
                        }

                        val duration = SystemClock.uptimeMillis() - startedAt
                        val start = samples.first()
                        val maximumDistance =
                            samples.maxOf { point -> hypot(point.x - start.x, point.y - start.y) }
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
                    }
                },
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
