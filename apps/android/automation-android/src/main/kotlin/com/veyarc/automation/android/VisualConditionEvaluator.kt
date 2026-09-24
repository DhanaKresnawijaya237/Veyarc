package com.veyarc.automation.android

import com.veyarc.engine.ConditionObservation
import com.veyarc.engine.ConditionObserver
import com.veyarc.model.AssetId
import com.veyarc.model.Condition
import com.veyarc.model.ImageAbsentCondition
import com.veyarc.model.ImageMatchMode
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
    internal val prefilterData = TemplatePrefilterData.create(widthPx, heightPx, rgbaBytes)

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
        prefilterData.cellSums.fill(0)
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
            repeat(heightPx) { y ->
                val yBand = bandFor(y, yStarts, yEnds)
                repeat(widthPx) { x ->
                    val xBand = bandFor(x, xStarts, xEnds)
                    val cellOffset = (yBand * xBandCount + xBand) * CHANNEL_COUNT
                    val pixelOffset = rgbaOffset(x, y, widthPx)
                    repeat(CHANNEL_COUNT) { channel ->
                        sums[cellOffset + channel] += channelValue(bytes, pixelOffset + channel)
                    }
                }
            }
            return TemplatePrefilterData(xBandCount, yBandCount, sums)
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
    private val frameSource: () -> EphemeralScreenFrame?,
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
        matchMode: ImageMatchMode,
    ): ConditionObservation {
        val template = templateSource.templateFor(assetId)
            ?: throw VisualConditionEvaluationException(
                "Image template asset ${assetId.value} is unavailable",
            )
        val frame = frameSource.invoke() ?: return ConditionObservation(
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
        if (template.widthPx != frame.widthPx || template.heightPx != frame.heightPx) {
            return ImageMatchResult.Unmatched
        }
        val maxError = (1.0 - threshold) * MAX_CHANNEL_ERROR * template.widthPx.toLong() * template.heightPx
        return scoreAt(
            frameWidth = frame.widthPx,
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
        val bounds = PixelBounds.from(region, frame.widthPx, frame.heightPx)
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
        val preferredPrefilter = TemplateWindowPrefilter(
            frameWidth = frame.widthPx,
            frameBytes = frameBytes,
            template = template,
            bounds = bounds,
            initialOriginX = preferredOriginX,
            initialOriginY = preferredOriginY,
        )
        if (preferredPrefilter.mayMatch(maxError)) {
            val preferredMatch = matchesAt(
                frameWidth = frame.widthPx,
                frameBytes = frameBytes,
                template = template,
                templateBytes = templateBytes,
                originX = preferredOriginX,
                originY = preferredOriginY,
                maxError = maxError,
                budget = budget,
            )
            if (preferredMatch.matched) return preferredMatch
        }
        val prefilter = TemplateWindowPrefilter(
            frameWidth = frame.widthPx,
            frameBytes = frameBytes,
            template = template,
            bounds = bounds,
        )

        for (originY in bounds.top until maxOriginY + 1) {
            if (originY > bounds.top) prefilter.moveDown()
            for (originX in bounds.left until maxOriginX + 1) {
                val isPreferredOrigin = originX == preferredOriginX && originY == preferredOriginY
                if (!isPreferredOrigin && prefilter.mayMatch(maxError)) {
                    val match = matchesAt(
                        frameWidth = frame.widthPx,
                        frameBytes = frameBytes,
                        template = template,
                        templateBytes = templateBytes,
                        originX = originX,
                        originY = originY,
                        maxError = maxError,
                        budget = budget,
                    )
                    if (match.matched) return match
                }
                if (originX < maxOriginX) prefilter.moveRight()
            }
        }
        return ImageMatchResult.Unmatched
    }

    /**
     * Rejects impossible origins using channel sums over up to sixteen spatial cells before the expensive
     * pixel-by-pixel comparison. The triangle inequality makes this a safe lower bound: a cell's
     * absolute channel-sum difference cannot exceed its total per-pixel channel error, so a lower
     * bound above [maxError] can never be a match. Rolling sums keep the prefilter bounded without
     * allocating a full-frame integral-image buffer.
     */
    private class TemplateWindowPrefilter(
        private val frameWidth: Int,
        private val frameBytes: ByteArray,
        template: RgbaTemplate,
        private val bounds: PixelBounds,
        initialOriginX: Int = bounds.left,
        initialOriginY: Int = bounds.top,
    ) {
        private val xBandCount = template.prefilterData.xBandCount
        private val yBandCount = template.prefilterData.yBandCount
        private val xStarts = partitionStarts(template.widthPx, xBandCount)
        private val xEnds = partitionEnds(template.widthPx, xBandCount)
        private val yStarts = partitionStarts(template.heightPx, yBandCount)
        private val yEnds = partitionEnds(template.heightPx, yBandCount)
        private val templateCellSums = template.prefilterData.cellSums
        private val columnSums = IntArray(yBandCount * frameWidth * CHANNEL_COUNT)
        private val windowSums = IntArray(yBandCount * xBandCount * CHANNEL_COUNT)
        private var originY = initialOriginY
        private var originX = initialOriginX

        init {
            require(originX in bounds.left..bounds.rightExclusive - template.widthPx)
            require(originY in bounds.top..bounds.bottomExclusive - template.heightPx)
            initializeColumns()
            rebuildWindowSums()
        }

        fun mayMatch(maxError: Double): Boolean {
            var lowerBound = 0L
            for (yBand in 0 until yBandCount) {
                for (xBand in 0 until xBandCount) {
                    val cellOffset = cellOffset(yBand, xBand)
                    for (channel in 0 until CHANNEL_COUNT) {
                        val difference = windowSums[cellOffset + channel] - templateCellSums[cellOffset + channel]
                        lowerBound += abs(difference).toLong()
                    }
                    if (lowerBound.toDouble() > maxError) return false
                }
            }
            return true
        }

        fun moveRight() {
            check(originX < bounds.rightExclusive) { "Template prefilter moved beyond its region" }
            val previousOriginX = originX
            originX++
            for (yBand in 0 until yBandCount) {
                for (xBand in 0 until xBandCount) {
                    val removeX = previousOriginX + xStarts[xBand]
                    val addX = previousOriginX + xEnds[xBand]
                    val windowOffset = cellOffset(yBand, xBand)
                    val columnOffset = columnOffset(yBand, removeX)
                    val addedColumnOffset = columnOffset(yBand, addX)
                    for (channel in 0 until CHANNEL_COUNT) {
                        windowSums[windowOffset + channel] +=
                            columnSums[addedColumnOffset + channel] - columnSums[columnOffset + channel]
                    }
                }
            }
        }

        fun moveDown() {
            check(originY < bounds.bottomExclusive) { "Template prefilter moved beyond its region" }
            val previousOriginY = originY
            originY++
            for (yBand in 0 until yBandCount) {
                val removeY = previousOriginY + yStarts[yBand]
                val addY = previousOriginY + yEnds[yBand]
                for (x in bounds.left until bounds.rightExclusive) {
                    val removeOffset = rgbaOffset(x, removeY, frameWidth)
                    val addOffset = rgbaOffset(x, addY, frameWidth)
                    val columnOffset = columnOffset(yBand, x)
                    for (channel in 0 until CHANNEL_COUNT) {
                        columnSums[columnOffset + channel] +=
                            channelValue(frameBytes, addOffset + channel) - channelValue(frameBytes, removeOffset + channel)
                    }
                }
            }
            originX = bounds.left
            rebuildWindowSums()
        }

        private fun initializeColumns() {
            for (yBand in 0 until yBandCount) {
                for (y in yStarts[yBand] until yEnds[yBand]) {
                    val frameY = originY + y
                    for (x in bounds.left until bounds.rightExclusive) {
                        val frameOffset = rgbaOffset(x, frameY, frameWidth)
                        val columnOffset = columnOffset(yBand, x)
                        for (channel in 0 until CHANNEL_COUNT) {
                            columnSums[columnOffset + channel] += channelValue(frameBytes, frameOffset + channel)
                        }
                    }
                }
            }
        }

        private fun rebuildWindowSums() {
            windowSums.fill(0)
            for (yBand in 0 until yBandCount) {
                for (xBand in 0 until xBandCount) {
                    val windowOffset = cellOffset(yBand, xBand)
                    for (x in xStarts[xBand] until xEnds[xBand]) {
                        val columnOffset = columnOffset(yBand, originX + x)
                        for (channel in 0 until CHANNEL_COUNT) {
                            windowSums[windowOffset + channel] += columnSums[columnOffset + channel]
                        }
                    }
                }
            }
        }

        private fun columnOffset(yBand: Int, x: Int): Int = (yBand * frameWidth + x) * CHANNEL_COUNT

        private fun cellOffset(yBand: Int, xBand: Int): Int = (yBand * xBandCount + xBand) * CHANNEL_COUNT
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
        const val DEFAULT_MAX_SEARCH_ORIGINS = 1_000_000L
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

private fun bandFor(value: Int, starts: IntArray, ends: IntArray): Int = starts.indices.first { band ->
    value in starts[band] until ends[band]
}

private fun channelValue(bytes: ByteArray, offset: Int): Int = bytes[offset].toInt() and 0xFF

private fun rgbaOffset(x: Int, y: Int, width: Int): Int = (y * width + x) * 4

class VisualConditionEvaluationException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
