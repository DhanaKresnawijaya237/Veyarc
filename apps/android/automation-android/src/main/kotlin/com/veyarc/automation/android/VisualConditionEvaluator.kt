package com.veyarc.automation.android

import com.veyarc.engine.ConditionObserver
import com.veyarc.model.AssetId
import com.veyarc.model.Condition
import com.veyarc.model.ImageAbsentCondition
import com.veyarc.model.ImagePresentCondition
import com.veyarc.model.NormalizedCoordinate
import com.veyarc.model.NormalizedRect
import com.veyarc.model.PixelColorCondition
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Supplies a decoded, user-selected template without exposing how it is stored or decrypted. */
fun interface ImageTemplateSource {
    fun templateFor(assetId: AssetId): RgbaTemplate?
}

/**
 * A decoded RGBA template held in memory for the duration of condition evaluation.
 *
 * The template is deliberately separate from [EphemeralScreenFrame]: templates are user-selected
 * assets and will be supplied by the encrypted asset layer, while captured screen frames must be
 * released immediately after each observation.
 */
class RgbaTemplate private constructor(
    val widthPx: Int,
    val heightPx: Int,
    private val rgbaBytes: ByteArray,
) {
    init {
        require(widthPx > 0) { "Template width must be positive" }
        require(heightPx > 0) { "Template height must be positive" }
        require(widthPx.toLong() * heightPx <= MAX_TEMPLATE_PIXELS) {
            "Template is too large to evaluate safely"
        }
        require(rgbaBytes.size == widthPx * heightPx * BYTES_PER_PIXEL) {
            "Template RGBA bytes do not match its dimensions"
        }
    }

    /** Returns a defensive copy so callers cannot mutate the evaluator's template. */
    fun copyRgbaBytes(): ByteArray = rgbaBytes.copyOf()

    /** Clears the decoded template after the run releases its condition assets. */
    fun clear() {
        rgbaBytes.fill(0)
    }

    internal fun <T> withRgbaBytes(block: (ByteArray) -> T): T = block(rgbaBytes)

    companion object {
        private const val BYTES_PER_PIXEL = 4
        const val MAX_TEMPLATE_PIXELS = 2_000_000L

        /** Creates a template from tightly packed RGBA bytes and takes ownership of a copy. */
        fun fromRgba(widthPx: Int, heightPx: Int, rgbaBytes: ByteArray): RgbaTemplate = RgbaTemplate(widthPx, heightPx, rgbaBytes.copyOf())
    }
}

/**
 * Evaluates pixel and image conditions against one temporary MediaProjection frame.
 *
 * The evaluator is intentionally platform-adapter code rather than engine code. It has no
 * persistence and no image decoding policy; [ImageTemplateSource] supplies already-decoded
 * templates after the later encrypted-asset layer has authenticated and decrypted them.
 */
class VisualConditionEvaluator(
    private val frameSource: () -> EphemeralScreenFrame?,
    private val templateSource: ImageTemplateSource,
    private val maxComparisons: Long = DEFAULT_MAX_COMPARISONS,
) : ConditionObserver {
    init {
        require(maxComparisons > 0L) { "Visual comparison budget must be positive" }
    }

    /** Evaluates only visual conditions; other condition types are owned by the engine/driver. */
    fun evaluate(condition: Condition): Boolean = when (condition) {
        is PixelColorCondition -> evaluatePixelColor(condition)

        is ImagePresentCondition -> evaluateImage(condition.assetId, condition.region, condition.matchThreshold, present = true)

        is ImageAbsentCondition -> evaluateImage(condition.assetId, condition.region, condition.matchThreshold, present = false)

        else -> throw AndroidAutomationException(
            "Visual evaluator does not support ${condition::class.simpleName} conditions",
        )
    }

    override fun observe(condition: Condition): Boolean = evaluate(condition)

    private fun evaluatePixelColor(condition: PixelColorCondition): Boolean {
        val frame = frameSource.invoke() ?: return false
        return try {
            frame.withRgbaBytes { bytes ->
                val (x, y) = mapPoint(condition.point, frame.widthPx, frame.heightPx)
                val offset = rgbaOffset(x, y, frame.widthPx)
                val actual = RgbaColor(
                    red = bytes[offset].toInt() and 0xFF,
                    green = bytes[offset + 1].toInt() and 0xFF,
                    blue = bytes[offset + 2].toInt() and 0xFF,
                    alpha = bytes[offset + 3].toInt() and 0xFF,
                )
                val expected = argbColor(condition.expectedArgb)
                withinTolerance(actual, expected, condition.tolerance)
            }
        } finally {
            frame.close()
        }
    }

    private fun evaluateImage(
        assetId: AssetId,
        region: NormalizedRect,
        threshold: Double,
        present: Boolean,
    ): Boolean {
        val template = templateSource.templateFor(assetId)
            ?: throw VisualConditionEvaluationException(
                "Image template asset ${assetId.value} is unavailable",
            )
        val frame = frameSource.invoke() ?: return false
        return try {
            val matched = template.withRgbaBytes { templateBytes ->
                frame.withRgbaBytes { frameBytes ->
                    containsTemplate(
                        frame = frame,
                        frameBytes = frameBytes,
                        template = template,
                        templateBytes = templateBytes,
                        region = region,
                        threshold = threshold,
                    )
                }
            }
            if (present) matched else !matched
        } finally {
            frame.close()
        }
    }

    private fun containsTemplate(
        frame: EphemeralScreenFrame,
        frameBytes: ByteArray,
        template: RgbaTemplate,
        templateBytes: ByteArray,
        region: NormalizedRect,
        threshold: Double,
    ): Boolean {
        val bounds = PixelBounds.from(region, frame.widthPx, frame.heightPx)
        if (template.widthPx > bounds.width || template.heightPx > bounds.height) return false

        val maxOriginX = bounds.rightExclusive - template.widthPx
        val maxOriginY = bounds.bottomExclusive - template.heightPx
        val maxError = (1.0 - threshold) * MAX_CHANNEL_ERROR * template.widthPx.toLong() * template.heightPx
        val budget = ComparisonBudget(maxComparisons)

        for (originY in bounds.top until maxOriginY + 1) {
            for (originX in bounds.left until maxOriginX + 1) {
                if (matchesAt(
                        frameWidth = frame.widthPx,
                        frameBytes = frameBytes,
                        template = template,
                        templateBytes = templateBytes,
                        originX = originX,
                        originY = originY,
                        maxError = maxError,
                        budget = budget,
                    )
                ) {
                    return true
                }
            }
        }
        return false
    }

    /** Compares a candidate exactly until it either meets the threshold or cannot meet it. */
    private fun matchesAt(
        frameWidth: Int,
        frameBytes: ByteArray,
        template: RgbaTemplate,
        templateBytes: ByteArray,
        originX: Int,
        originY: Int,
        maxError: Double,
        budget: ComparisonBudget,
    ): Boolean {
        var error = 0L
        return template.withRgbaBytes {
            for (templateY in 0 until template.heightPx) {
                for (templateX in 0 until template.widthPx) {
                    budget.consume()
                    val frameOffset = rgbaOffset(originX + templateX, originY + templateY, frameWidth)
                    val templateOffset = rgbaOffset(templateX, templateY, template.widthPx)
                    error += channelError(frameBytes, frameOffset, templateBytes, templateOffset)
                    if (error.toDouble() > maxError) return@withRgbaBytes false
                }
            }
            true
        }
    }

    private class ComparisonBudget(private val limit: Long) {
        private var comparisons = 0L

        fun consume() {
            comparisons++
            if (comparisons > limit) {
                throw VisualConditionEvaluationException(
                    "Image template search exceeded its comparison safety budget",
                )
            }
        }
    }

    private data class PixelBounds(
        val left: Int,
        val top: Int,
        val rightExclusive: Int,
        val bottomExclusive: Int,
    ) {
        val width: Int get() = rightExclusive - left
        val height: Int get() = bottomExclusive - top

        companion object {
            fun from(region: NormalizedRect, frameWidth: Int, frameHeight: Int): PixelBounds {
                val left = floor(region.x * frameWidth).toInt().coerceIn(0, frameWidth - 1)
                val top = floor(region.y * frameHeight).toInt().coerceIn(0, frameHeight - 1)
                val right = ceil((region.x + region.width) * frameWidth).toInt().coerceIn(left + 1, frameWidth)
                val bottom = ceil((region.y + region.height) * frameHeight).toInt().coerceIn(top + 1, frameHeight)
                return PixelBounds(left, top, right, bottom)
            }
        }
    }

    companion object {
        /** Limits worst-case CPU work for a single image-condition observation. */
        const val DEFAULT_MAX_COMPARISONS = 12_000_000L
        private const val MAX_CHANNEL_ERROR = 255L * 4L
        private const val BYTES_PER_PIXEL = 4

        private fun mapPoint(point: NormalizedCoordinate, widthPx: Int, heightPx: Int): Pair<Int, Int> = (point.x * (widthPx - 1)).roundToInt().coerceIn(0, widthPx - 1) to
            (point.y * (heightPx - 1)).roundToInt().coerceIn(0, heightPx - 1)

        private fun argbColor(value: Long): RgbaColor = RgbaColor(
            red = ((value shr 16) and 0xFF).toInt(),
            green = ((value shr 8) and 0xFF).toInt(),
            blue = (value and 0xFF).toInt(),
            alpha = ((value shr 24) and 0xFF).toInt(),
        )

        private fun withinTolerance(actual: RgbaColor, expected: RgbaColor, tolerance: Int): Boolean = abs(actual.red - expected.red) <= tolerance &&
            abs(actual.green - expected.green) <= tolerance &&
            abs(actual.blue - expected.blue) <= tolerance &&
            abs(actual.alpha - expected.alpha) <= tolerance

        private fun channelError(
            first: ByteArray,
            firstOffset: Int,
            second: ByteArray,
            secondOffset: Int,
        ): Long = (0 until BYTES_PER_PIXEL).sumOf { component ->
            abs(
                (first[firstOffset + component].toInt() and 0xFF) -
                    (second[secondOffset + component].toInt() and 0xFF),
            ).toLong()
        }

        private fun rgbaOffset(x: Int, y: Int, width: Int): Int = (y * width + x) * BYTES_PER_PIXEL
    }
}

class VisualConditionEvaluationException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
