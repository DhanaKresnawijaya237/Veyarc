package com.veyarc.automation.android

import com.veyarc.model.AssetId
import com.veyarc.model.ImageAbsentCondition
import com.veyarc.model.ImagePresentCondition
import com.veyarc.model.NormalizedCoordinate
import com.veyarc.model.NormalizedRect
import com.veyarc.model.PixelColorCondition
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualConditionEvaluatorTest {
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
    fun imagePresentSearchesOnlyInsideTheNormalizedRegion() {
        val source = templateSource(
            RgbaTemplate.fromRgba(
                widthPx = 2,
                heightPx = 2,
                rgbaBytes = rgba(
                    color(10, 20, 30),
                    color(40, 50, 60),
                    color(70, 80, 90),
                    color(100, 110, 120),
                ),
            ),
        )
        val insideFrame = frame(
            width = 4,
            height = 4,
            colors = listOf(
                color(0, 0, 0), color(0, 0, 0), color(0, 0, 0), color(0, 0, 0),
                color(0, 0, 0), color(10, 20, 30), color(40, 50, 60), color(0, 0, 0),
                color(0, 0, 0), color(70, 80, 90), color(100, 110, 120), color(0, 0, 0),
                color(0, 0, 0), color(0, 0, 0), color(0, 0, 0), color(0, 0, 0),
            ),
        )
        val region = NormalizedRect(0.25, 0.25, 0.5, 0.5)

        assertTrue(
            VisualConditionEvaluator({ insideFrame }, source).evaluate(
                ImagePresentCondition(ASSET_ID, region, matchThreshold = 1.0),
            ),
        )
        assertTrue(insideFrame.isClosed)

        val outsideFrame = frame(
            width = 4,
            height = 4,
            colors = listOf(
                color(10, 20, 30), color(40, 50, 60), color(0, 0, 0), color(0, 0, 0),
                color(70, 80, 90), color(100, 110, 120), color(0, 0, 0), color(0, 0, 0),
                color(0, 0, 0), color(0, 0, 0), color(0, 0, 0), color(0, 0, 0),
                color(0, 0, 0), color(0, 0, 0), color(0, 0, 0), color(0, 0, 0),
            ),
        )
        assertFalse(
            VisualConditionEvaluator({ outsideFrame }, source).evaluate(
                ImagePresentCondition(ASSET_ID, region, matchThreshold = 1.0),
            ),
        )
        assertTrue(outsideFrame.isClosed)
    }

    @Test
    fun imagePresentFindsAMatchAfterSlidingAcrossRowsAndColumns() {
        val template = RgbaTemplate.fromRgba(
            widthPx = 2,
            heightPx = 2,
            rgbaBytes = rgba(
                color(10, 20, 30),
                color(40, 50, 60),
                color(70, 80, 90),
                color(100, 110, 120),
            ),
        )
        val colors = MutableList(5 * 4) { color(0, 0, 0) }
        val matchOriginX = 2
        val matchOriginY = 1
        colors[matchOriginY * 5 + matchOriginX] = color(10, 20, 30)
        colors[matchOriginY * 5 + matchOriginX + 1] = color(40, 50, 60)
        colors[(matchOriginY + 1) * 5 + matchOriginX] = color(70, 80, 90)
        colors[(matchOriginY + 1) * 5 + matchOriginX + 1] = color(100, 110, 120)
        val frame = frame(5, 4, colors)

        val matched = VisualConditionEvaluator({ frame }, templateSource(template)).evaluate(
            ImagePresentCondition(
                assetId = ASSET_ID,
                region = NormalizedRect(0.0, 0.0, 1.0, 1.0),
                matchThreshold = 1.0,
            ),
        )

        assertTrue(matched)
        assertTrue(frame.isClosed)
    }

    @Test
    fun imageAbsentIsTheInverseOfAContainedMatch() {
        val template = RgbaTemplate.fromRgba(1, 1, rgba(color(100, 100, 100)))
        val matchingFrame = frame(2, 1, listOf(color(0, 0, 0), color(100, 100, 100)))
        assertFalse(
            VisualConditionEvaluator({ matchingFrame }, templateSource(template)).evaluate(
                ImageAbsentCondition(ASSET_ID, NormalizedRect(0.0, 0.0, 1.0, 1.0), 1.0),
            ),
        )

        val nonMatchingFrame = frame(2, 1, listOf(color(0, 0, 0), color(90, 90, 90)))
        assertTrue(
            VisualConditionEvaluator({ nonMatchingFrame }, templateSource(template)).evaluate(
                ImageAbsentCondition(ASSET_ID, NormalizedRect(0.0, 0.0, 1.0, 1.0), 1.0),
            ),
        )
    }

    @Test
    fun matchThresholdUsesDeterministicMeanRgbaSimilarity() {
        val template = RgbaTemplate.fromRgba(1, 1, rgba(color(100, 100, 100)))
        val frame = frame(1, 1, listOf(color(110, 100, 100)))

        assertTrue(
            VisualConditionEvaluator({ frame }, templateSource(template)).evaluate(
                ImagePresentCondition(ASSET_ID, NormalizedRect(0.0, 0.0, 1.0, 1.0), 0.9),
            ),
        )
        assertTrue(frame.isClosed)

        val stricterFrame = frame(1, 1, listOf(color(110, 100, 100)))
        assertFalse(
            VisualConditionEvaluator({ stricterFrame }, templateSource(template)).evaluate(
                ImagePresentCondition(ASSET_ID, NormalizedRect(0.0, 0.0, 1.0, 1.0), 0.995),
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
            rejected = error.message?.contains("unavailable") == true
        }
        assertTrue(rejected)
        assertFalse("No frame should be acquired when the template is unavailable", frame.isClosed)
    }

    @Test
    fun templateSearchStopsAtItsComparisonBudget() {
        val template = RgbaTemplate.fromRgba(1, 1, rgba(color(255, 255, 255)))
        val frame = frame(3, 3, List(9) { color(0, 0, 0) })
        var rejected = false
        try {
            VisualConditionEvaluator(
                frameSource = { frame },
                templateSource = templateSource(template),
                maxComparisons = 2,
            ).evaluate(
                ImagePresentCondition(ASSET_ID, NormalizedRect(0.0, 0.0, 1.0, 1.0), 1.0),
            )
        } catch (error: VisualConditionEvaluationException) {
            rejected = error.message?.contains("safety budget") == true
        }
        assertTrue(rejected)
        assertTrue(frame.isClosed)
    }

    @Test
    fun channelPrefilterKeepsAReasonableSearchWithinTheBudget() {
        val template = RgbaTemplate.fromRgba(
            widthPx = 8,
            heightPx = 8,
            rgbaBytes = rgba(*Array(64) { color(255, 255, 255) }),
        )
        val frame = frame(48, 48, List(48 * 48) { color(0, 0, 0) })

        val matched = VisualConditionEvaluator(
            frameSource = { frame },
            templateSource = templateSource(template),
            maxComparisons = 10_000,
        ).evaluate(
            ImagePresentCondition(
                assetId = ASSET_ID,
                region = NormalizedRect(0.0, 0.0, 1.0, 1.0),
                matchThreshold = 0.9,
            ),
        )

        assertFalse(matched)
        assertTrue(frame.isClosed)
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
