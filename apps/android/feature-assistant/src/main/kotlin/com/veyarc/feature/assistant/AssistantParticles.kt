package com.veyarc.feature.assistant

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.sin

@Composable
internal fun AssistantParticles(interactive: Boolean, modifier: Modifier, content: @Composable BoxScope.() -> Unit) {
    val field = remember { WelcomeParticleField() }
    var frame by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current.density
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val colors = MaterialTheme.colorScheme
    val particleColors = remember(colors) { field.depth.map { lerp(colors.primary, colors.onSurface, 0.35f + it * 0.5f) } }
    val opacity = animateFloatAsState(if (interactive) 1f else 0.22f, tween(180), label = "particle background")
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
                            // Only Canvas reads this state; messages and the composer do not recompose.
                            frame++
                        }
                    }
                }
            } finally {
                field.release()
            }
        }
    }
    val touch = if (interactive) {
        Modifier.pointerInput(field, density) {
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
        }
    } else {
        Modifier
    }
    Box(
        modifier.clipToBounds().onSizeChanged {
            field.resize(it.width / density, it.height / density)
            frame++
        }.then(touch),
    ) {
        Canvas(Modifier.matchParentSize().testTag(if (interactive) "assistant-welcome-particles" else "assistant-chat-particles")) {
            val tick = frame
            val edge = 24.dp.toPx()
            for (i in field.x.indices) {
                val position = Offset(field.x[i] * density, field.y[i] * density)
                val fade = 0.4f + 0.6f * (minOf(position.x, size.width - position.x) / edge).coerceIn(0f, 1f)
                val shimmer = 0.88f + sin(tick * 0.012f + i) * 0.12f
                val alpha = (0.16f + field.depth[i] * 0.44f) * fade * shimmer * opacity.value
                val radius = (0.65f + field.depth[i] * 0.9f) * density
                val color = particleColors[i]
                if (i % 9 == 0) drawCircle(color.copy(alpha = alpha * 0.1f), radius * 3f, position)
                drawCircle(color.copy(alpha = alpha), radius, position)
            }
        }
        content()
    }
}
