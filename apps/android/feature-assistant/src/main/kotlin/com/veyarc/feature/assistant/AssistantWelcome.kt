package com.veyarc.feature.assistant

import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.sin

@Composable
internal fun AssistantWelcome(state: AssistantPreviewState) {
    val field = remember { WelcomeParticleField() }
    var frame by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current.density
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val colors = MaterialTheme.colorScheme
    val particleColors = remember(colors) { field.depth.map { lerp(colors.primary, colors.onSurface, 0.35f + it * 0.5f) } }
    LaunchedEffect(lifecycle) {
        val motionScale = coroutineContext[MotionDurationScale]
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                snapshotFlow { motionScale?.scaleFactor ?: 1f }.collectLatest { scale ->
                    field.settle()
                    frame++
                    if (scale > 0f) {
                        var previous = withInfiniteAnimationFrameNanos { it }
                        while (true) {
                            val now = withInfiniteAnimationFrameNanos { it }
                            field.advance((now - previous) / (1_000_000_000f * scale))
                            previous = now
                            // Only Canvas reads this state; the composer and suggestions do not recompose.
                            frame++
                        }
                    }
                }
            } finally {
                field.release()
            }
        }
    }
    BoxWithConstraints(
        Modifier.fillMaxSize().clipToBounds().testTag("assistant-welcome")
            .onSizeChanged {
                field.resize(it.width / density, it.height / density)
                frame++
            }
            .pointerInput(field, density) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    field.touch(down.position.x / density, down.position.y / density)
                    try {
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (pointer.pressed) field.touch(pointer.position.x / density, pointer.position.y / density)
                            // Observe without consuming: suggestions and scrolling keep their gestures.
                        } while (pointer.pressed)
                    } finally {
                        field.release()
                    }
                }
            },
    ) {
        val compact = maxHeight < 280.dp
        Canvas(Modifier.fillMaxSize().testTag("assistant-welcome-particles")) {
            val tick = frame
            val edge = 32.dp.toPx()
            for (i in field.x.indices) {
                val position = Offset(field.x[i] * density, field.y[i] * density)
                val fade = (minOf(position.x, size.width - position.x) / edge).coerceIn(0f, 1f)
                val shimmer = 0.88f + sin(tick * 0.012f + i) * 0.12f
                val alpha = (0.16f + field.depth[i] * 0.44f) * fade * shimmer
                val radius = (0.65f + field.depth[i] * 0.9f) * density
                val color = particleColors[i]
                if (i % 9 == 0) drawCircle(color.copy(alpha = alpha * 0.1f), radius * 3f, position)
                drawCircle(color.copy(alpha = alpha), radius, position)
            }
        }
        Column(
            Modifier.align(Alignment.Center).widthIn(max = if (compact) 680.dp else 420.dp).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = if (compact) 12.dp else 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("How can I help?", style = if (compact) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
            Text("Create, edit or explore a workflow.", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            if (state.context.needsSetup) Text("Choose an app and orientation in the editor first.", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
            val suggestions = if (state.context.needsSetup || state.context.workflowId == null) listOf("Help me create a workflow", "What details do you need?") else listOf("Explain this workflow", "Suggest an improvement", "Describe a change")
            val suggestionButton: @Composable (String, Modifier) -> Unit = { suggestion, modifier ->
                FilledTonalButton(
                    onClick = { state.compose(suggestion) },
                    modifier = modifier,
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = colors.surfaceContainerHigh.copy(alpha = 0.92f), contentColor = colors.onSurface),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                ) { Text(suggestion, textAlign = TextAlign.Center) }
            }
            if (compact) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    suggestions.forEach { suggestionButton(it, Modifier.weight(1f)) }
                }
            } else {
                suggestions.forEach { suggestionButton(it, Modifier.fillMaxWidth()) }
            }
        }
    }
}
