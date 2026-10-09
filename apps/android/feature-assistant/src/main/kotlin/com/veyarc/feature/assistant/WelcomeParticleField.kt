package com.veyarc.feature.assistant

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

internal class WelcomeParticleField(random: Random = Random.Default) {
    private val originsX = FloatArray(560) { (it % 20 + random.nextFloat()) / 20f }
    private val originsY = FloatArray(560) { (it / 20 + random.nextFloat()) / 28f }
    val depth = FloatArray(560) { random.nextFloat() }
    private val phases = FloatArray(560) { random.nextFloat() * 6.283185f }
    val x = FloatArray(560)
    val y = FloatArray(560)
    private val displacementX = FloatArray(560)
    private val displacementY = FloatArray(560)
    private val velocityX = FloatArray(560)
    private val velocityY = FloatArray(560)
    private var width = 0f
    private var height = 0f
    private var time = 0f
    private var pressed = false
    private var pointerX = 0f
    private var pointerY = 0f
    private var dragX = 0f
    private var dragY = 0f

    fun resize(width: Float, height: Float) {
        this.width = width
        this.height = height
        settle()
    }

    fun touch(x: Float, y: Float) {
        if (pressed) {
            dragX += x - pointerX
            dragY += y - pointerY
        } else {
            // A quick tap still nudges particles even when it ends between two frames.
            for (i in this.x.indices) {
                val dx = this.x[i] - x
                val dy = this.y[i] - y
                val weight = (1f - (dx * dx + dy * dy) / (110f * 110f)).coerceAtLeast(0f)
                velocityX[i] += dx * 2f * weight * weight
                velocityY[i] += dy * 2f * weight * weight
            }
        }
        pointerX = x
        pointerY = y
        pressed = true
    }

    fun release() {
        pressed = false
        dragX = 0f
        dragY = 0f
    }

    fun settle() {
        release()
        displacementX.fill(0f)
        displacementY.fill(0f)
        velocityX.fill(0f)
        velocityY.fill(0f)
        advance(0f)
    }

    fun advance(seconds: Float) {
        if (width <= 0f || height <= 0f) return
        // A delayed frame must not fling particles or jump the stream after resuming.
        val dt = seconds.coerceIn(0f, 1f / 30f)
        time += dt
        val damping = exp(-5f * dt)
        val amplitude = minOf(width, height, 360f) * 0.12f
        val pullX = dragX.coerceIn(-48f, 48f) * 18f
        val pullY = dragY.coerceIn(-48f, 48f) * 18f
        for (i in x.indices) {
            val phase = phases[i]
            val pace = 0.16f + depth[i] * 0.22f
            val travel = amplitude * (0.55f + depth[i] * 0.45f)
            val baseX = reflect(originsX[i] * width + travel * (sin(time * pace + phase) + sin(time * pace * 0.61f + phase * 1.73f) * 0.45f), width)
            val baseY = reflect(originsY[i] * height + travel * (cos(time * pace * 0.83f + phase * 1.31f) + sin(time * pace * 1.13f + phase) * 0.35f), height)
            if (pressed) {
                val dx = baseX + displacementX[i] - pointerX
                val dy = baseY + displacementY[i] - pointerY
                val weight = (1f - (dx * dx + dy * dy) / (110f * 110f)).coerceAtLeast(0f)
                val influence = weight * weight
                velocityX[i] += (dx * 14f * dt + pullX) * influence
                velocityY[i] += (dy * 14f * dt + pullY) * influence
            }
            velocityX[i] = ((velocityX[i] - displacementX[i] * 12f * dt) * damping).coerceIn(-600f, 600f)
            velocityY[i] = ((velocityY[i] - displacementY[i] * 12f * dt) * damping).coerceIn(-600f, 600f)
            displacementX[i] = (displacementX[i] + velocityX[i] * dt).coerceIn(-90f, 90f)
            displacementY[i] = (displacementY[i] + velocityY[i] * dt).coerceIn(-90f, 90f)
            x[i] = baseX + displacementX[i]
            y[i] = baseY + displacementY[i]
        }
        dragX = 0f
        dragY = 0f
    }

    // Reflect at the viewport edges instead of letting whole bands leave the screen.
    private fun reflect(position: Float, length: Float): Float {
        val period = length * 2f
        val wrapped = ((position % period) + period) % period
        return if (wrapped <= length) wrapped else period - wrapped
    }
}
