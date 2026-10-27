package com.veyarc.automation.android

import com.veyarc.model.AssetId
import com.veyarc.model.ImageAbsentCondition
import com.veyarc.model.ImageMatchMode
import com.veyarc.model.ImagePresentCondition
import com.veyarc.model.NormalizedCoordinate
import com.veyarc.model.NormalizedRect
import com.veyarc.model.PixelColorCondition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualConditionEvaluatorTest {
    @Test fun colorAreaUsesOnlyTheRequestedRegionAndAlwaysReleasesItsFrame() {
        val region = NormalizedRect(0.4, 0.25, 0.6, 0.75)
        val bytes = ByteArray(3 * 3 * 4) { if (it % 4 == 3) -1 else 0 }
        val index = (1 * 3 + 1) * 4
        bytes[index] = 22
        bytes[index + 1] = 33
        bytes[index + 2] = 44
        fun evaluator(): Pair<VisualConditionEvaluator, EphemeralScreenFrame> {
            val frame = EphemeralScreenFrame(5, 4, 0, bytes.copyOf(), FramePixelBounds(2, 1, 5, 4))
            return VisualConditionEvaluator({ area ->
                assertEquals(ScreenCaptureArea.Region(region), area)
                frame
            }, ImageTemplateSource { null }) to frame
        }
        val condition = PixelColorCondition(NormalizedCoordinate(0.0, 0.0), 0xFF16212C, 0, region)
        val (present, frame) = evaluator()
        assertTrue(present.evaluate(condition))
        assertTrue(frame.isClosed)
        val (absent, missingFrame) = evaluator()
        assertFalse(absent.evaluate(condition.copy(expectedArgb = 0xFF203040)))
        assertTrue(missingFrame.isClosed)
        val (tolerant, tolerantFrame) = evaluator()
        assertTrue(tolerant.evaluate(condition.copy(expectedArgb = 0xFF17222D, tolerance = 1)))
        assertTrue(tolerantFrame.isClosed)
    }

    @Test fun cancellingAnAreaScanReleasesTheCapturedPixels() {
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        val frame = EphemeralScreenFrame(2, 2, 0, ByteArray(16))
        val evaluator = VisualConditionEvaluator({
            cancelled.set(true)
            frame
        }, ImageTemplateSource { null }, cancellation = com.veyarc.engine.CancellationSignal { cancelled.get() })
        assertThrows(java.util.concurrent.CancellationException::class.java) { evaluator.evaluate(PixelColorCondition(NormalizedCoordinate(0.0, 0.0), 0, 0, NormalizedRect(0.0, 0.0, 1.0, 1.0))) }
        assertTrue(frame.isClosed)
    }

    @Test
    fun missingCaptureNeverBecomesAFalseImageOrPixelCondition() {
        val template = RgbaTemplate.fromRgba(1, 1, byteArrayOf(10, 20, 30, -1))
        try {
            val evaluator = VisualConditionEvaluator({ null }, ImageTemplateSource { template })
            val asset = AssetId("missing-frame")
            val region = NormalizedRect(0.0, 0.0, 1.0, 1.0)
            val conditions = listOf(
                ImagePresentCondition(asset, region, 1.0),
                ImageAbsentCondition(asset, region, 1.0),
                ImagePresentCondition(asset, region, 1.0, ImageMatchMode.SCREEN_STATE),
                PixelColorCondition(NormalizedCoordinate(0.5, 0.5), 0xFF0A141E, 0),
            )
            conditions.forEach { condition ->
                val error = assertThrows(VisualConditionEvaluationException::class.java) { evaluator.observe(condition) }
                assertTrue(error.message.orEmpty().contains("No screen capture frame arrived"))
            }
        } finally {
            template.clear()
        }
    }

    @Test
    fun pixelColorUsesNormalizedDisplayCoordinatesAndReleasesTheFrame() {
        val frame = frame(
            width = 2,
            height = 2,
            colors = listOf(
                color(0, 0, 0),
                color(10, 20, 30),
                color(40, 50, 60),
                color(70, 80, 90),
            ),
        )
        val evaluator = evaluator(frame)

        assertTrue(
            evaluator.evaluate(
                PixelColorCondition(
                    point = NormalizedCoordinate(1.0, 0.0),
                    expectedArgb = argb(10, 20, 30),
                    tolerance = 0,
                ),
            ),
        )
        assertTrue(frame.isClosed)
    }

    @Test
    fun pixelColorToleranceAppliesToAllRgbaChannels() {
        val frame = frame(1, 1, listOf(RgbaColor(10, 20, 30, 200)))
        val evaluator = evaluator(frame)

        assertTrue(
            evaluator.evaluate(
                PixelColorCondition(
                    point = NormalizedCoordinate(0.5, 0.5),
                    expectedArgb = argb(14, 24, 34, 204),
                    tolerance = 4,
                ),
            ),
        )

        val secondFrame = frame(1, 1, listOf(RgbaColor(10, 20, 30, 200)))
        assertFalse(
            evaluator(secondFrame).evaluate(
                PixelColorCondition(
                    point = NormalizedCoordinate(0.0, 0.0),
                    expectedArgb = argb(14, 24, 34, 205),
                    tolerance = 4,
                ),
            ),
        )
    }

    @Test
    fun missingTemplateFailsClosedInsteadOfMatchingAnAbsentCondition() {
        val frame = frame(1, 1, listOf(color(0, 0, 0)))
        var rejected = false
        try {
            VisualConditionEvaluator({ frame }, ImageTemplateSource { null }).evaluate(
                ImageAbsentCondition(ASSET_ID, NormalizedRect(0.0, 0.0, 1.0, 1.0), 1.0),
            )
        } catch (error: VisualConditionEvaluationException) {
            rejected = error.message == "Choose a saved or captured target image."
        }
        assertTrue(rejected)
        assertFalse("No frame should be acquired when the template is unavailable", frame.isClosed)
    }

    private fun evaluator(frame: EphemeralScreenFrame): VisualConditionEvaluator = VisualConditionEvaluator({ frame }, ImageTemplateSource { null })

    private fun templateSource(template: RgbaTemplate): ImageTemplateSource = ImageTemplateSource { assetId -> if (assetId == ASSET_ID) template else null }

    private fun frame(width: Int, height: Int, colors: List<RgbaColor>): EphemeralScreenFrame = EphemeralScreenFrame(
        widthPx = width,
        heightPx = height,
        timestampNanos = 1L,
        rgbaBytes = rgba(*colors.toTypedArray()),
    )

    private fun rgba(vararg colors: RgbaColor): ByteArray = ByteArray(colors.size * 4).also { bytes ->
        colors.forEachIndexed { index, color ->
            val offset = index * 4
            bytes[offset] = color.red.toByte()
            bytes[offset + 1] = color.green.toByte()
            bytes[offset + 2] = color.blue.toByte()
            bytes[offset + 3] = color.alpha.toByte()
        }
    }

    private fun color(red: Int, green: Int, blue: Int, alpha: Int = 255): RgbaColor = RgbaColor(red, green, blue, alpha)

    private fun argb(red: Int, green: Int, blue: Int, alpha: Int = 255): Long = (alpha.toLong() shl 24) or
        (red.toLong() shl 16) or
        (green.toLong() shl 8) or
        blue.toLong()

    private companion object {
        val ASSET_ID = AssetId("template-main")
    }
}
