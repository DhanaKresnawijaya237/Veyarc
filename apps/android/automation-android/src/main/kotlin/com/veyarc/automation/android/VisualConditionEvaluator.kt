package com.veyarc.automation.android

import com.veyarc.engine.ConditionObservation
import com.veyarc.engine.ConditionObserver
import com.veyarc.model.AssetId
import com.veyarc.model.Condition
import com.veyarc.model.ImageAbsentCondition
import com.veyarc.model.ImageMatchMode
import com.veyarc.model.ImagePresentCondition
import com.veyarc.model.NormalizedRect
import com.veyarc.model.PixelColorCondition
import java.io.Closeable
import kotlin.math.abs

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

    internal val prefilterData = TemplatePrefilterData.create(widthPx, heightPx, rgbaBytes)

    /** Returns a defensive copy so callers cannot mutate the evaluator's template. */
    fun copyRgbaBytes(): ByteArray = rgbaBytes.copyOf()

    /** Clears the decoded template after the run releases its condition assets. */
    fun clear() {
        rgbaBytes.fill(0)
        prefilterData.cellSums.fill(0)
        prefilterData.bandSums.fill(0)
    }

    internal fun <T> withRgbaBytes(block: (ByteArray) -> T): T = block(rgbaBytes)

    companion object {
        private const val BYTES_PER_PIXEL = 4
        const val MAX_TEMPLATE_PIXELS = 3_000_000L

        /** Creates a template from tightly packed RGBA bytes and takes ownership of a copy. */
        fun fromRgba(widthPx: Int, heightPx: Int, rgbaBytes: ByteArray): RgbaTemplate = RgbaTemplate(widthPx, heightPx, rgbaBytes.copyOf())

        /** Takes ownership of a caller-released pixel buffer. The caller must not reuse it. */
        fun fromOwnedRgba(widthPx: Int, heightPx: Int, rgbaBytes: ByteArray): RgbaTemplate = RgbaTemplate(widthPx, heightPx, rgbaBytes)
    }
}

internal data class TemplatePrefilterData(
    val xBandCount: Int,
    val yBandCount: Int,
    val cellSums: IntArray,
    val bandSums: IntArray,
) {
    companion object {
        fun create(widthPx: Int, heightPx: Int, bytes: ByteArray): TemplatePrefilterData {
            val xBandCount = minOf(PREFILTER_BANDS, widthPx)
            val yBandCount = minOf(PREFILTER_BANDS, heightPx)
            val xStarts = partitionStarts(widthPx, xBandCount)
            val xEnds = partitionEnds(widthPx, xBandCount)
            val yStarts = partitionStarts(heightPx, yBandCount)
            val yEnds = partitionEnds(heightPx, yBandCount)
            val sums = IntArray(xBandCount * yBandCount * CHANNEL_COUNT)
            val bands = IntArray(yBandCount * CHANNEL_COUNT)
            for (yBand in 0 until yBandCount) {
                for (xBand in 0 until xBandCount) {
                    val cellOffset = (yBand * xBandCount + xBand) * CHANNEL_COUNT
                    for (y in yStarts[yBand] until yEnds[yBand]) {
                        var offset = rgbaOffset(xStarts[xBand], y, widthPx)
                        for (x in xStarts[xBand] until xEnds[xBand]) {
                            for (channel in 0 until CHANNEL_COUNT) sums[cellOffset + channel] += channelValue(bytes, offset + channel)
                            offset += CHANNEL_COUNT
                        }
                    }
                    for (channel in 0 until CHANNEL_COUNT) bands[yBand * CHANNEL_COUNT + channel] += sums[cellOffset + channel]
                }
            }
            return TemplatePrefilterData(xBandCount, yBandCount, sums, bands)
        }
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
    private val frameSource: (ScreenCaptureArea) -> EphemeralScreenFrame?,
    private val templateSource: ImageTemplateSource,
    private val maxComparisons: Long = DEFAULT_MAX_COMPARISONS,
    private val maxSearchOrigins: Long = DEFAULT_MAX_SEARCH_ORIGINS,
) : ConditionObserver {
    init {
        require(maxComparisons > 0L) { "Visual comparison budget must be positive" }
        require(maxSearchOrigins > 0L) { "Visual search-origin budget must be positive" }
    }

    /** Evaluates only visual conditions; other condition types are owned by the engine/driver. */
    fun evaluate(condition: Condition): Boolean = observe(condition).result

    override fun observe(condition: Condition): ConditionObservation = when (condition) {
        is PixelColorCondition -> ConditionObservation(evaluatePixelColor(condition))

        is ImagePresentCondition -> evaluateImage(
            assetId = condition.assetId,
            region = condition.region,
            threshold = condition.matchThreshold,
            present = true,
            matchMode = condition.matchMode,
        )

        is ImageAbsentCondition -> evaluateImage(
            assetId = condition.assetId,
            region = condition.region,
            threshold = condition.matchThreshold,
            present = false,
            matchMode = ImageMatchMode.APPEARS_WITHIN_REGION,
        )

        else -> throw AndroidAutomationException(
            "Visual evaluator does not support ${condition::class.simpleName} conditions",
        )
    }

    private fun evaluatePixelColor(condition: PixelColorCondition): Boolean {
        val frame = frameSource.invoke(ScreenCaptureArea.Pixel(condition.point)) ?: return false
        return try {
            val point = FramePixelBounds.pixel(condition.point, frame.widthPx, frame.heightPx)
            withinTolerance(frame.pixelAt(point.left, point.top), argbColor(condition.expectedArgb), condition.tolerance)
        } finally {
            frame.close()
        }
    }

    private fun evaluateImage(
        assetId: AssetId,
        region: NormalizedRect,
        threshold: Double,
        present: Boolean,
        matchMode: ImageMatchMode,
    ): ConditionObservation {
        val template = templateSource.templateFor(assetId)
            ?: throw VisualConditionEvaluationException(
                "Image template asset ${assetId.value} is unavailable",
            )
        val area = if (matchMode == ImageMatchMode.SCREEN_STATE) ScreenCaptureArea.FullScreen else ScreenCaptureArea.Region(region)
        val frame = frameSource.invoke(area) ?: return ConditionObservation(
            result = false,
            threshold = threshold,
        )
        return try {
            val match = template.withRgbaBytes { templateBytes ->
                frame.withRgbaBytes { frameBytes ->
                    when (matchMode) {
                        ImageMatchMode.APPEARS_WITHIN_REGION -> containsTemplate(
                            frame = frame,
                            frameBytes = frameBytes,
                            template = template,
                            templateBytes = templateBytes,
                            region = region,
                            threshold = threshold,
                        )

                        ImageMatchMode.SCREEN_STATE -> matchesScreenState(
                            frame = frame,
                            frameBytes = frameBytes,
                            template = template,
                            templateBytes = templateBytes,
                            threshold = threshold,
                        )
                    }
                }
            }
            ConditionObservation(
                result = if (present) match.matched else !match.matched,
                matchScore = match.score,
                threshold = threshold,
            )
        } finally {
            frame.close()
        }
    }

    /** Measures the full fixed-position screen similarity so diagnostics never substitute the threshold. */
    private fun matchesScreenState(
        frame: EphemeralScreenFrame,
        frameBytes: ByteArray,
        template: RgbaTemplate,
        templateBytes: ByteArray,
        threshold: Double,
    ): ImageMatchResult {
        if (template.widthPx != frame.widthPx || template.heightPx != frame.heightPx || frame.capturedBounds != FramePixelBounds(0, 0, frame.widthPx, frame.heightPx)) {
            return ImageMatchResult.Unmatched
        }
        val maxError = (1.0 - threshold) * MAX_CHANNEL_ERROR * template.widthPx.toLong() * template.heightPx
        return scoreAt(
            frameWidth = frame.capturedBounds.width,
            frameBytes = frameBytes,
            template = template,
            templateBytes = templateBytes,
            originX = 0,
            originY = 0,
            maxError = maxError,
            budget = ComparisonBudget(
                limit = maxComparisons,
                context = "fixed ${template.widthPx}x${template.heightPx} screen state",
            ),
        )
    }

    /** Fully compares one fixed candidate and returns its actual normalized RGBA similarity. */
    private fun scoreAt(
        frameWidth: Int,
        frameBytes: ByteArray,
        template: RgbaTemplate,
        templateBytes: ByteArray,
        originX: Int,
        originY: Int,
        maxError: Double,
        budget: ComparisonBudget,
    ): ImageMatchResult {
        var error = 0L
        template.withRgbaBytes {
            for (templateY in 0 until template.heightPx) {
                for (templateX in 0 until template.widthPx) {
                    budget.consume()
                    val frameOffset = rgbaOffset(originX + templateX, originY + templateY, frameWidth)
                    val templateOffset = rgbaOffset(templateX, templateY, template.widthPx)
                    error += channelError(frameBytes, frameOffset, templateBytes, templateOffset)
                }
            }
        }
        val maximumError = MAX_CHANNEL_ERROR.toDouble() * template.widthPx * template.heightPx
        return ImageMatchResult(
            matched = error.toDouble() <= maxError,
            score = (1.0 - error / maximumError).coerceIn(0.0, 1.0),
        )
    }

    private fun containsTemplate(
        frame: EphemeralScreenFrame,
        frameBytes: ByteArray,
        template: RgbaTemplate,
        templateBytes: ByteArray,
        region: NormalizedRect,
        threshold: Double,
    ): ImageMatchResult {
        val bounds = FramePixelBounds.from(region, frame.widthPx, frame.heightPx).relativeTo(frame.capturedBounds)
        if (template.widthPx > bounds.width || template.heightPx > bounds.height) {
            return ImageMatchResult.Unmatched
        }

        val maxOriginX = bounds.rightExclusive - template.widthPx
        val maxOriginY = bounds.bottomExclusive - template.heightPx
        val originCount = (maxOriginX - bounds.left + 1).toLong() *
            (maxOriginY - bounds.top + 1).toLong()
        if (originCount > maxSearchOrigins) {
            throw VisualConditionEvaluationException(
                "Image template search covers $originCount positions, exceeding the safety limit of $maxSearchOrigins. " +
                    "Narrow the screen search region or use a larger, correctly scaled template.",
            )
        }
        val maxError = (1.0 - threshold) * MAX_CHANNEL_ERROR * template.widthPx.toLong() * template.heightPx
        val budget = ComparisonBudget(
            limit = maxComparisons,
            context = "template ${template.widthPx}x${template.heightPx} in ${bounds.width}x${bounds.height} region",
        )
        val preferredOriginX = bounds.left + (bounds.width - template.widthPx) / 2
        val preferredOriginY = bounds.top + (bounds.height - template.heightPx) / 2
        // The preferred candidate needs only its own window, not a second region-wide buffer.
        TemplateWindowPrefilter(
            frameWidth = frame.capturedBounds.width,
            frameBytes = frameBytes,
            template = template,
            bounds = FramePixelBounds(preferredOriginX, preferredOriginY, preferredOriginX + template.widthPx, preferredOriginY + template.heightPx),
        ).use { preferred ->
            if (preferred.mayMatch(maxError)) {
                val match = matchesAt(frame.capturedBounds.width, frameBytes, template, templateBytes, preferredOriginX, preferredOriginY, maxError, budget)
                if (match.matched) return match
            }
        }
        TemplateWindowPrefilter(frame.capturedBounds.width, frameBytes, template, bounds).use { prefilter ->
            for (originY in bounds.top..maxOriginY) {
                if (originY > bounds.top) prefilter.moveDown()
                for (originX in bounds.left..maxOriginX) {
                    val isPreferredOrigin = originX == preferredOriginX && originY == preferredOriginY
                    if (!isPreferredOrigin && prefilter.mayMatch(maxError)) {
                        val match = matchesAt(frame.capturedBounds.width, frameBytes, template, templateBytes, originX, originY, maxError, budget)
                        if (match.matched) return match
                    }
                    if (originX < maxOriginX) prefilter.moveRight()
                }
            }
        }
        return ImageMatchResult.Unmatched
    }

    /**
     * Each cell's channel-sum difference is a lower bound on its exact pixel error. Prefix sums
     * across rolling vertical bands provide those same bounds without updating every horizontal
     * cell for every search position. Memory depends on region width, not screen pixel count.
     */
    private class TemplateWindowPrefilter(
        private val frameWidth: Int,
        private val frameBytes: ByteArray,
        template: RgbaTemplate,
        private val bounds: FramePixelBounds,
    ) : Closeable {
        private val xBandCount = template.prefilterData.xBandCount
        private val yBandCount = template.prefilterData.yBandCount
        private val xStarts = partitionStarts(template.widthPx, xBandCount)
        private val xEnds = partitionEnds(template.widthPx, xBandCount)
        private val yStarts = partitionStarts(template.heightPx, yBandCount)
        private val yEnds = partitionEnds(template.heightPx, yBandCount)
        private val templateCellSums = template.prefilterData.cellSums
        private val templateBandSums = template.prefilterData.bandSums
        private val templateWidth = template.widthPx
        private val prefixStride = (bounds.width + 1) * CHANNEL_COUNT

        // Capture dimensions cap a band's per-channel prefix below Int.MAX_VALUE.
        private val prefixes = IntArray(yBandCount * prefixStride)
        private var originY = bounds.top
        private var originX = bounds.left

        init {
            for (yBand in 0 until yBandCount) {
                for (y in yStarts[yBand] until yEnds[yBand]) {
                    var frameOffset = rgbaOffset(bounds.left, originY + y, frameWidth)
                    var prefixOffset = yBand * prefixStride + CHANNEL_COUNT
                    for (x in bounds.left until bounds.rightExclusive) {
                        for (channel in 0 until CHANNEL_COUNT) prefixes[prefixOffset + channel] += channelValue(frameBytes, frameOffset + channel)
                        prefixOffset += CHANNEL_COUNT
                        frameOffset += CHANNEL_COUNT
                    }
                }
                val end = (yBand + 1) * prefixStride
                for (offset in yBand * prefixStride + CHANNEL_COUNT until end) prefixes[offset] += prefixes[offset - CHANNEL_COUNT]
            }
        }

        fun mayMatch(maxError: Double): Boolean {
            var lowerBound = 0L
            val relativeX = originX - bounds.left
            // Whole-band bounds cheaply reject unrelated backgrounds before the finer cells.
            if (xBandCount > 1) {
                for (yBand in 0 until yBandCount) {
                    val left = yBand * prefixStride + relativeX * CHANNEL_COUNT
                    val right = left + templateWidth * CHANNEL_COUNT
                    for (channel in 0 until CHANNEL_COUNT) lowerBound += abs(prefixes[right + channel] - prefixes[left + channel] - templateBandSums[yBand * CHANNEL_COUNT + channel]).toLong()
                    if (lowerBound.toDouble() > maxError) return false
                }
                lowerBound = 0L
            }
            for (yBand in 0 until yBandCount) {
                for (xBand in 0 until xBandCount) {
                    val left = yBand * prefixStride + (relativeX + xStarts[xBand]) * CHANNEL_COUNT
                    val right = yBand * prefixStride + (relativeX + xEnds[xBand]) * CHANNEL_COUNT
                    val cell = (yBand * xBandCount + xBand) * CHANNEL_COUNT
                    for (channel in 0 until CHANNEL_COUNT) lowerBound += abs(prefixes[right + channel] - prefixes[left + channel] - templateCellSums[cell + channel]).toLong()
                    if (lowerBound.toDouble() > maxError) return false
                }
            }
            return true
        }

        fun moveRight() {
            originX++
        }

        fun moveDown() {
            val previousY = originY++
            for (yBand in 0 until yBandCount) {
                var removeOffset = rgbaOffset(bounds.left, previousY + yStarts[yBand], frameWidth)
                var addOffset = rgbaOffset(bounds.left, previousY + yEnds[yBand], frameWidth)
                var prefixOffset = yBand * prefixStride + CHANNEL_COUNT
                var redDelta = 0
                var greenDelta = 0
                var blueDelta = 0
                var alphaDelta = 0
                for (x in bounds.left until bounds.rightExclusive) {
                    redDelta += channelValue(frameBytes, addOffset) - channelValue(frameBytes, removeOffset)
                    greenDelta += channelValue(frameBytes, addOffset + 1) - channelValue(frameBytes, removeOffset + 1)
                    blueDelta += channelValue(frameBytes, addOffset + 2) - channelValue(frameBytes, removeOffset + 2)
                    alphaDelta += channelValue(frameBytes, addOffset + 3) - channelValue(frameBytes, removeOffset + 3)
                    prefixes[prefixOffset] += redDelta
                    prefixes[prefixOffset + 1] += greenDelta
                    prefixes[prefixOffset + 2] += blueDelta
                    prefixes[prefixOffset + 3] += alphaDelta
                    prefixOffset += CHANNEL_COUNT
                    removeOffset += CHANNEL_COUNT
                    addOffset += CHANNEL_COUNT
                }
            }
            originX = bounds.left
        }

        override fun close() {
            prefixes.fill(0)
        }
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
    ): ImageMatchResult {
        var error = 0L
        return template.withRgbaBytes {
            for (templateY in 0 until template.heightPx) {
                for (templateX in 0 until template.widthPx) {
                    budget.consume()
                    val frameOffset = rgbaOffset(originX + templateX, originY + templateY, frameWidth)
                    val templateOffset = rgbaOffset(templateX, templateY, template.widthPx)
                    error += channelError(frameBytes, frameOffset, templateBytes, templateOffset)
                    if (error.toDouble() > maxError) return@withRgbaBytes ImageMatchResult.Unmatched
                }
            }
            val maximumError = MAX_CHANNEL_ERROR.toDouble() * template.widthPx * template.heightPx
            ImageMatchResult(
                matched = true,
                score = (1.0 - error / maximumError).coerceIn(0.0, 1.0),
            )
        }
    }

    private data class ImageMatchResult(
        val matched: Boolean,
        val score: Double?,
    ) {
        companion object {
            val Unmatched = ImageMatchResult(matched = false, score = null)
        }
    }

    private class ComparisonBudget(
        private val limit: Long,
        private val context: String,
    ) {
        private var comparisons = 0L

        fun consume() {
            comparisons++
            if (comparisons > limit) {
                throw VisualConditionEvaluationException(
                    "Image template exact comparison exceeded its safety budget. " +
                        "Narrow the screen search region or use a smaller crop ($context).",
                )
            }
        }
    }

    companion object {
        /** Limits worst-case CPU work for a single image-condition observation. */
        const val DEFAULT_MAX_COMPARISONS = 12_000_000L
        const val DEFAULT_MAX_SEARCH_ORIGINS = 1_000_000L
        private const val MAX_CHANNEL_ERROR = 255L * 4L
        private const val BYTES_PER_PIXEL = 4

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
        ): Long {
            var error = 0L
            for (component in 0 until BYTES_PER_PIXEL) {
                error += abs((first[firstOffset + component].toInt() and 0xFF) - (second[secondOffset + component].toInt() and 0xFF))
            }
            return error
        }
    }
}

private const val CHANNEL_COUNT = 4
private const val PREFILTER_BANDS = 16

private fun partitionStarts(size: Int, bands: Int): IntArray = IntArray(bands) { band ->
    band * size / bands
}

private fun partitionEnds(size: Int, bands: Int): IntArray = IntArray(bands) { band ->
    (band + 1) * size / bands
}

private fun channelValue(bytes: ByteArray, offset: Int): Int = bytes[offset].toInt() and 0xFF

private fun rgbaOffset(x: Int, y: Int, width: Int): Int = (y * width + x) * 4

class VisualConditionEvaluationException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
