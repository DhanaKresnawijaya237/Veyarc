package com.veyarc.automation.android

import com.veyarc.engine.CancellationSignal
import com.veyarc.engine.CancellationSignals
import com.veyarc.engine.ColorMatchObservation
import com.veyarc.engine.ConditionObservation
import com.veyarc.engine.ConditionObserver
import com.veyarc.engine.RuntimeImageCapture
import com.veyarc.model.AssetId
import com.veyarc.model.CapturedImageCondition
import com.veyarc.model.Condition
import com.veyarc.model.ImageAbsentCondition
import com.veyarc.model.ImageMatchMode
import com.veyarc.model.ImagePresentCondition
import com.veyarc.model.NormalizedRect
import com.veyarc.model.PixelColorCondition
import com.veyarc.model.VariableName
import java.io.Closeable
import java.util.concurrent.CancellationException
import kotlin.math.abs

/** Supplies a decoded, user-selected template without exposing how it is stored or decrypted. */
fun interface ImageTemplateSource {
    fun templateFor(assetId: AssetId): RgbaTemplate?
}

/**
 * A decoded RGBA template held in memory for the duration of condition evaluation.
 *
 * Saved templates come from encrypted assets. Runtime captures belong to one evaluator and are
 * wiped on replacement or close. Both are independent of the immediately released screen frame.
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
        const val MAX_TEMPLATE_PIXELS = 3_000_000L

        /** Creates a template from tightly packed RGBA bytes and takes ownership of a copy. */
        fun fromRgba(widthPx: Int, heightPx: Int, rgbaBytes: ByteArray): RgbaTemplate = RgbaTemplate(widthPx, heightPx, rgbaBytes.copyOf())

        /** Takes ownership of a caller-released pixel buffer. The caller must not reuse it. */
        fun fromOwnedRgba(widthPx: Int, heightPx: Int, rgbaBytes: ByteArray): RgbaTemplate = RgbaTemplate(widthPx, heightPx, rgbaBytes)
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
    private val maxSearchOrigins: Long = DEFAULT_MAX_SEARCH_ORIGINS,
    private val cancellation: CancellationSignal = CancellationSignals.NONE,
    private val maxRuntimeImageBytes: Long = 32L * 1024 * 1024,
) : ConditionObserver,
    RuntimeImageCapture,
    Closeable {
    private val imageMatcher = lazy { OpenCvImageMatcher() }
    private val runtimeImages = mutableMapOf<VariableName, RgbaTemplate>()
    private var runtimeImageBytes = 0L

    @Volatile private var closed = false

    init {
        require(maxSearchOrigins > 0L) { "Visual search-origin budget must be positive" }
        require(maxRuntimeImageBytes > 0L) { "Runtime image memory must be positive" }
    }

    /** Evaluates only visual conditions; other condition types are owned by the engine/driver. */
    fun evaluate(condition: Condition): Boolean = observe(condition).result

    @Synchronized
    override fun observe(condition: Condition): ConditionObservation {
        checkCancellation()
        check(!closed) { "Visual evaluator is closed" }
        return when (condition) {
            is PixelColorCondition -> evaluatePixelColor(condition)

            is ImagePresentCondition -> evaluateImage(
                template = requireTemplate(condition.assetId, condition.capturedImage),
                region = condition.region,
                threshold = condition.matchThreshold,
                present = true,
                matchMode = condition.matchMode,
                capturedBackground = condition.capturedBackground,
                requireFit = condition.capturedImage != null || condition.capturedBackground != null,
            )

            is ImageAbsentCondition -> evaluateImage(
                template = requireTemplate(condition.assetId, condition.capturedImage),
                region = condition.region,
                threshold = condition.matchThreshold,
                present = false,
                matchMode = ImageMatchMode.APPEARS_WITHIN_REGION,
                capturedBackground = condition.capturedBackground,
                requireFit = condition.capturedImage != null || condition.capturedBackground != null,
            )

            is CapturedImageCondition -> evaluateImage(
                template = requireCapture(condition.name),
                region = condition.region,
                threshold = condition.matchThreshold,
                present = condition.present,
                matchMode = ImageMatchMode.APPEARS_WITHIN_REGION,
                requireFit = true,
            )

            else -> throw AndroidAutomationException(
                "Visual evaluator does not support ${condition::class.simpleName} conditions",
            )
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        runtimeImages.values.forEach(RgbaTemplate::clear)
        runtimeImages.clear()
        runtimeImageBytes = 0L
        if (imageMatcher.isInitialized()) imageMatcher.value.close()
    }

    @Synchronized
    override fun captureImage(name: VariableName, region: NormalizedRect) {
        checkCancellation()
        check(!closed) { "Visual evaluator is closed" }
        val frame = requireFrame(ScreenCaptureArea.Region(region))
        var replacement: RgbaTemplate? = null
        try {
            val bounds = FramePixelBounds.from(region, frame.widthPx, frame.heightPx)
            val previous = runtimeImages[name]
            val previousBytes = previous?.let { it.widthPx.toLong() * it.heightPx * 4 } ?: 0L
            val nextBytes = runtimeImageBytes - previousBytes + bounds.width.toLong() * bounds.height * 4
            if (nextBytes > maxRuntimeImageBytes) throw VisualConditionEvaluationException("Captured images do not fit in available memory. Reuse an image name or capture a smaller area.")
            replacement = frame.copyRegion(region)
            checkCancellation()
            runtimeImages.put(name, replacement)?.clear()
            runtimeImageBytes = nextBytes
            replacement = null
        } finally {
            replacement?.clear()
            frame.close()
        }
    }

    private fun requireCapture(name: VariableName): RgbaTemplate = runtimeImages[name]
        ?: throw VisualConditionEvaluationException("Image ${name.value} has not been captured in this run. Run its Capture image block first.")

    private fun requireTemplate(assetId: AssetId?, captured: VariableName?): RgbaTemplate = captured?.let(::requireCapture)
        ?: assetId?.let(templateSource::templateFor)
        ?: throw VisualConditionEvaluationException("Choose a saved or captured target image.")

    private fun withSearchPixels(captured: VariableName?, area: ScreenCaptureArea, block: (Int, Int, FramePixelBounds, ByteArray) -> ConditionObservation): ConditionObservation {
        if (captured != null) {
            val image = requireCapture(captured)
            return image.withRgbaBytes { block(image.widthPx, image.heightPx, FramePixelBounds(0, 0, image.widthPx, image.heightPx), it) }
        }
        val frame = requireFrame(area)
        return try {
            frame.withRgbaBytes { block(frame.widthPx, frame.heightPx, frame.capturedBounds, it) }
        } finally {
            frame.close()
        }
    }

    private fun checkCancellation() {
        if (cancellation.isCancellationRequested() || Thread.currentThread().isInterrupted) {
            throw CancellationException("Image check cancelled")
        }
    }

    private fun evaluatePixelColor(condition: PixelColorCondition): ConditionObservation {
        val area = condition.region?.let { ScreenCaptureArea.Region(it) } ?: ScreenCaptureArea.Pixel(condition.point)
        return withSearchPixels(condition.capturedBackground, area) { width, height, capturedBounds, bytes ->
            val bounds = (
                condition.region?.let { FramePixelBounds.from(it, width, height) }
                    ?: FramePixelBounds.pixel(condition.point, width, height)
                ).relativeTo(capturedBounds)
            val expected = argbColor(condition.expectedArgb)
            val match = run {
                var bestDifference = 256
                var bestOffset = 0
                search@ for (y in bounds.top until bounds.bottomExclusive) {
                    checkCancellation()
                    var nextOffset = rgbaOffset(bounds.left, y, capturedBounds.width)
                    val end = rgbaOffset(bounds.rightExclusive, y, capturedBounds.width)
                    while (nextOffset < end) {
                        val offset = nextOffset
                        nextOffset += BYTES_PER_PIXEL
                        var difference = abs(channelValue(bytes, offset) - expected.red)
                        if (difference >= bestDifference) continue
                        difference = maxOf(difference, abs(channelValue(bytes, offset + 1) - expected.green))
                        if (difference >= bestDifference) continue
                        difference = maxOf(difference, abs(channelValue(bytes, offset + 2) - expected.blue))
                        if (difference >= bestDifference) continue
                        difference = maxOf(difference, abs(channelValue(bytes, offset + 3) - expected.alpha))
                        if (difference >= bestDifference) continue
                        bestDifference = difference
                        bestOffset = offset
                        // Only an exact colour can prove there is no better pixel later in the area.
                        if (difference == 0) break@search
                    }
                }
                val closestArgb = (channelValue(bytes, bestOffset + 3).toLong() shl 24) or
                    (channelValue(bytes, bestOffset).toLong() shl 16) or
                    (channelValue(bytes, bestOffset + 1).toLong() shl 8) or
                    channelValue(bytes, bestOffset + 2).toLong()
                ColorMatchObservation(condition.expectedArgb, closestArgb, bestDifference, condition.tolerance)
            }
            ConditionObservation(match.difference <= match.tolerance, colorMatch = match)
        }
    }

    private fun evaluateImage(
        template: RgbaTemplate,
        region: NormalizedRect,
        threshold: Double,
        present: Boolean,
        matchMode: ImageMatchMode,
        requireFit: Boolean = false,
        capturedBackground: VariableName? = null,
    ): ConditionObservation {
        val area = if (matchMode == ImageMatchMode.SCREEN_STATE) ScreenCaptureArea.FullScreen else ScreenCaptureArea.Region(region)
        return withSearchPixels(capturedBackground, area) { width, height, capturedBounds, frameBytes ->
            val bounds = if (matchMode == ImageMatchMode.SCREEN_STATE) {
                if (template.widthPx != width || template.heightPx != height || capturedBounds != FramePixelBounds(0, 0, width, height)) {
                    return@withSearchPixels ConditionObservation(!present, threshold = threshold)
                }
                FramePixelBounds(0, 0, width, height)
            } else {
                FramePixelBounds.from(region, width, height).relativeTo(capturedBounds)
            }
            if (template.widthPx > bounds.width || template.heightPx > bounds.height) {
                if (requireFit) throw VisualConditionEvaluationException("The captured image does not fit the search area. Enlarge the area or capture a smaller crop.")
                return@withSearchPixels ConditionObservation(!present, threshold = threshold)
            }
            val origins = (bounds.width - template.widthPx + 1).toLong() * (bounds.height - template.heightPx + 1)
            if (origins > maxSearchOrigins) throw VisualConditionEvaluationException("Image template search covers $origins positions, exceeding $maxSearchOrigins. Narrow the search area.")
            val score = imageMatcher.value.score(frameBytes, capturedBounds.width, capturedBounds.height, bounds, template, threshold, ::checkCancellation)
            checkCancellation()
            ConditionObservation(
                result = if (present) score >= threshold else score < threshold,
                matchScore = score,
                threshold = threshold,
            )
        }
    }

    private fun requireFrame(area: ScreenCaptureArea): EphemeralScreenFrame = frameSource.invoke(area)
        ?: throw VisualConditionEvaluationException("No screen capture frame arrived. Restart the run and allow screen capture.")

    companion object {
        /** Covers every origin in a supported capture; native work is cancellable between strips. */
        const val DEFAULT_MAX_SEARCH_ORIGINS = 16_777_216L
        private const val BYTES_PER_PIXEL = 4

        private fun argbColor(value: Long): RgbaColor = RgbaColor(
            red = ((value shr 16) and 0xFF).toInt(),
            green = ((value shr 8) and 0xFF).toInt(),
            blue = (value and 0xFF).toInt(),
            alpha = ((value shr 24) and 0xFF).toInt(),
        )
    }
}

private fun channelValue(bytes: ByteArray, offset: Int): Int = bytes[offset].toInt() and 0xFF

private fun rgbaOffset(x: Int, y: Int, width: Int): Int = (y * width + x) * 4

class VisualConditionEvaluationException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
