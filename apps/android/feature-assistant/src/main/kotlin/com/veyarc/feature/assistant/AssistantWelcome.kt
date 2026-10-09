package com.veyarc.feature.assistant

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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.abs
import kotlin.random.Random

private data class WelcomeWave(val origin: Offset, val startedAt: Float)

@Composable
internal fun AssistantWelcome(state: AssistantPreviewState) {
    val waves = remember { mutableStateListOf<WelcomeWave>() }
    var time by remember { mutableFloatStateOf(0f) }
    val particles = remember {
        val random = Random(29)
        List(96) { Offset(random.nextFloat(), random.nextFloat()) }
    }
    val colors = MaterialTheme.colorScheme
    LaunchedEffect(Unit) {
        // Only interaction animates. Reading the clock in Canvas avoids recomposing the chat.
        snapshotFlow { waves.isNotEmpty() }.collectLatest { active ->
            if (active) {
                var previous = withFrameNanos { it }
                while (waves.isNotEmpty()) {
                    val durationScale = coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
                    if (durationScale == 0f) {
                        waves.clear()
                        break
                    }
                    val now = withFrameNanos { it }
                    time += ((now - previous) / (1_000_000_000f * durationScale)).coerceAtMost(0.05f)
                    previous = now
                    waves.removeAll { time - it.startedAt >= 1.6f }
                }
            }
        }
    }
    BoxWithConstraints(
        Modifier.fillMaxSize().clipToBounds().testTag("assistant-welcome")
            .pointerInput(Unit) {
                fun ripple(point: Offset) {
                    if (waves.size == 12) waves.removeAt(0)
                    waves.add(WelcomeWave(point, time))
                }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    ripple(down.position)
                    var last = down.position
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (pointer.pressed && (pointer.position - last).getDistance() >= 14.dp.toPx()) {
                            ripple(pointer.position)
                            last = pointer.position
                        }
                        // Observe without consuming: suggestions and scrolling keep their gestures.
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        val compact = maxHeight < 280.dp
        Canvas(Modifier.fillMaxSize().testTag("assistant-welcome-particles")) {
            val center = Offset(size.width / 2, size.height * 0.42f)
            drawRect(Brush.radialGradient(listOf(colors.primary.copy(alpha = 0.09f), Color.Transparent), center, size.minDimension.coerceAtLeast(1f) * 0.7f))
            waves.forEach { wave ->
                val age = time - wave.startedAt
                drawCircle(colors.primary.copy(alpha = (1f - age / 1.6f) * 0.12f), (age * 180.dp.toPx()).coerceAtLeast(1f), wave.origin, style = Stroke(1.dp.toPx()))
            }
            particles.forEachIndexed { index, normalized ->
                val origin = Offset(normalized.x * size.width, normalized.y * size.height)
                var position = origin
                var brightness = 0f
                waves.forEach { wave ->
                    val age = time - wave.startedAt
                    val direction = origin - wave.origin
                    val distance = direction.getDistance()
                    val crest = (1f - abs(distance - age * 180.dp.toPx()) / 60.dp.toPx()).coerceAtLeast(0f) * (1f - age / 1.6f)
                    if (distance > 0f) position += direction / distance * (crest * 22.dp.toPx())
                    brightness = maxOf(brightness, crest)
                }
                drawCircle(colors.primary.copy(alpha = (0.2f + brightness * 0.6f).coerceAtMost(0.8f)), (if (index % 4 == 0) 2f else 1.2f).dp.toPx(), position)
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
