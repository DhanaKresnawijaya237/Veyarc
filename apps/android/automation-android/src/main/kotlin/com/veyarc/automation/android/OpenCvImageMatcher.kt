package com.veyarc.automation.android

import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import org.opencv.core.CvException
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import java.io.Closeable
import kotlin.math.roundToLong
import kotlin.math.sqrt

/** Session-owned native storage. Captured pixels are wiped after every observation. */
internal class OpenCvImageMatcher : Closeable {
    init {
        try {
            check(initialized) { "OpenCV initialization failed" }
        } catch (error: LinkageError) {
            throw VisualConditionEvaluationException("Image matching could not start on this device.", error)
        } catch (error: IllegalStateException) {
            throw VisualConditionEvaluationException("Image matching could not start on this device.", error)
        }
    }

    private val rgba = Mat()
    private val rgb = Mat()
    private val targetRgba = Mat()
    private val target = Mat()
    private val result = Mat()
    private val storage = listOf(rgba, rgb, targetRgba, target, result)

    data class ImageMatch(val score: Double, val left: Int, val top: Int)

    fun score(bytes: ByteArray, width: Int, height: Int, bounds: FramePixelBounds, template: RgbaTemplate, threshold: Double, checkCancellation: () -> Unit): Double = match(bytes, width, height, bounds, template, threshold, checkCancellation, false).score

    fun find(bytes: ByteArray, width: Int, height: Int, bounds: FramePixelBounds, template: RgbaTemplate, threshold: Double, checkCancellation: () -> Unit): ImageMatch = match(bytes, width, height, bounds, template, threshold, checkCancellation, true)

    private fun match(bytes: ByteArray, width: Int, height: Int, bounds: FramePixelBounds, template: RgbaTemplate, threshold: Double, checkCancellation: () -> Unit, fullSearch: Boolean): ImageMatch {
        try {
            checkCancellation()
            rgba.create(height, width, CvType.CV_8UC4)
            check(rgba.put(0, 0, bytes) == bytes.size)
            val region = rgba.submat(Rect(bounds.left, bounds.top, bounds.width, bounds.height))
            try {
                Imgproc.cvtColor(region, rgb, Imgproc.COLOR_RGBA2RGB)
            } finally {
                region.release()
            }
            checkCancellation()
            targetRgba.create(template.heightPx, template.widthPx, CvType.CV_8UC4)
            template.withRgbaBytes { check(targetRgba.put(0, 0, it) == it.size) }
            Imgproc.cvtColor(targetRgba, target, Imgproc.COLOR_RGBA2RGB)
            checkCancellation()
            val maximumError = 3.0 * 255 * 255 * template.widthPx * template.heightPx
            val allowedError = (1.0 - threshold) * (1.0 - threshold) * maximumError
            var bestError = Double.POSITIVE_INFINITY
            var bestX = 0
            var bestY = 0
            fun record(error: Double, x: Int, y: Int) {
                if (error < bestError || (error == bestError && (y < bestY || (y == bestY && x < bestX)))) {
                    bestError = error
                    bestX = x
                    bestY = y
                }
            }
            fun found() = ImageMatch(similarity(bestError, maximumError), bounds.left + bestX, bounds.top + bestY)
            val originRows = rgb.rows() - target.rows() + 1
            var row = 0
            while (row < originRows) {
                checkCancellation()
                // One fixed-position comparison needs no correlation map or integral buffers.
                val count = minOf(ORIGIN_ROWS_PER_STRIP, originRows - row)
                val strip = rgb.rowRange(row, row + count + target.rows() - 1)
                try {
                    if (count == 1 && rgb.cols() == target.cols()) {
                        record(Core.norm(strip, target, Core.NORM_L2SQR).roundToLong().toDouble(), 0, row)
                    } else {
                        Imgproc.matchTemplate(strip, target, result, Imgproc.TM_SQDIFF)
                        checkCancellation()
                        val minimum = Core.minMaxLoc(result)
                        val x = minimum.minLoc.x.toInt()
                        val y = minimum.minLoc.y.toInt()
                        fun exactError(x: Int, y: Int): Double {
                            checkCancellation()
                            val candidate = strip.submat(Rect(x, y, target.cols(), target.rows()))
                            return try {
                                Core.norm(candidate, target, Core.NORM_L2SQR).roundToLong().toDouble()
                            } finally {
                                candidate.release()
                            }
                        }
                        // Eight-bit squared differences are integers; remove native norm rounding at cutoffs.
                        record(exactError(x, y), x, row + y)
                        if (bestError == 0.0) return found()
                        if (!fullSearch && similarity(bestError, maximumError) >= threshold) return found()

                        // FFT rounding can reorder nearly equal candidates. Verify those near the cutoff.
                        val roundingMargin = maximumError * 0.00001
                        if (fullSearch || minimum.minVal <= allowedError + roundingMargin) {
                            val scores = FloatArray(result.cols())
                            try {
                                for (resultY in 0 until result.rows()) {
                                    checkCancellation()
                                    result.get(resultY, 0, scores)
                                    for (resultX in scores.indices) {
                                        if (resultX == x && resultY == y) continue
                                        if (scores[resultX] > (if (fullSearch) minimum.minVal else allowedError) + roundingMargin) continue
                                        record(exactError(resultX, resultY), resultX, row + resultY)
                                        if (bestError == 0.0) return found()
                                        if (!fullSearch && similarity(bestError, maximumError) >= threshold) return found()
                                    }
                                }
                            } finally {
                                scores.fill(0f)
                            }
                        }
                    }
                    checkCancellation()
                    if (bestError == 0.0) break
                } finally {
                    strip.release()
                }
                row += count
            }
            checkCancellation()
            return found()
        } catch (error: CvException) {
            throw VisualConditionEvaluationException("Image matching failed. Try the image check again.", error)
        } finally {
            storage.forEach { if (!it.empty()) it.setTo(Scalar.all(0.0)) }
        }
    }

    override fun close() {
        storage.forEach { it.release() }
    }

    private companion object {
        const val ORIGIN_ROWS_PER_STRIP = 256
        val initialized: Boolean by lazy {
            OpenCVLoader.initLocal().also { if (it) Core.setNumThreads(1) }
        }

        fun similarity(error: Double, maximumError: Double): Double = (1.0 - sqrt(error / maximumError)).coerceIn(0.0, 1.0)
    }
}
