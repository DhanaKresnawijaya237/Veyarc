package com.veyarc.feature.assistant

import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

internal class WelcomeParticleField {
    private val random = Random(29)
    private val originsX = FloatArray(560) { random.nextFloat() }
    private val originsY = FloatArray(560) { (it % 6 + 0.5f) / 6f + (random.nextFloat() - 0.5f) * 0.1f }
    val depth = FloatArray(560) { random.nextFloat() }
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
        val amplitude = (height * 0.09f).coerceAtMost(56f)
        val pullX = dragX.coerceIn(-48f, 48f) * 18f
        val pullY = dragY.coerceIn(-48f, 48f) * 18f
        for (i in x.indices) {
            val along = (originsX[i] + time * (0.014f + depth[i] * 0.009f)) % 1f
            val baseX = along * (width + 96f) - 48f
            val baseY = originsY[i] * height + amplitude * (
                sin(along * 6f + originsY[i] * 3f - time * 0.42f) +
                    sin(along * 11f - originsY[i] * 4f + time * 0.27f) * 0.32f
                )
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
}
