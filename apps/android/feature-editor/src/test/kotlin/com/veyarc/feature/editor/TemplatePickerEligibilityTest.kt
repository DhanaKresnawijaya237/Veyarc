package com.veyarc.feature.editor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplatePickerEligibilityTest {
    @Test
    fun fullScreenImagesCannotBeUsedAsCroppedTargetsInEitherOrientation() {
        assertFalse(templateFitsPicker(1080, 2392, 1080, 2392, fullScreenOnly = false))
        assertFalse(templateFitsPicker(2392, 1080, 1080, 2392, fullScreenOnly = false))
        assertTrue(templateFitsPicker(420, 360, 1080, 2392, fullScreenOnly = false))
        assertFalse(templateFitsPicker(1200, 360, 1080, 2392, fullScreenOnly = false))
        assertFalse(templateFitsPicker(420, 2500, 1080, 2392, fullScreenOnly = false))
    }

    @Test
    fun backgroundsMustMatchTheWorkflowOrientation() {
        val landscapeWorkflowWidth = 2392
        val landscapeWorkflowHeight = 1080

        assertTrue(templateFitsPicker(2392, 1080, landscapeWorkflowWidth, landscapeWorkflowHeight, fullScreenOnly = true))
        assertFalse(templateFitsPicker(1080, 2392, landscapeWorkflowWidth, landscapeWorkflowHeight, fullScreenOnly = true))
        assertFalse(templateFitsPicker(420, 360, landscapeWorkflowWidth, landscapeWorkflowHeight, fullScreenOnly = true))
    }

    @Test
    fun storedDimensionsKeepDownscaledSavedBackgroundsSelectable() {
        assertTrue(
            templateFitsPicker(
                descriptorWidthPx = 2392,
                descriptorHeightPx = 1080,
                previewWidthPx = 720,
                previewHeightPx = 325,
                displayWidthPx = 2392,
                displayHeightPx = 1080,
                fullScreenOnly = true,
            ) == true,
        )
    }

    @Test
    fun oversizedCropsRemainVisibleSoTheyCanBeRotatedButCannotBeSelected() {
        assertTrue(templateCanAppearInPicker(1600, 400, 1080, 2392, fullScreenOnly = false))
        assertFalse(templateFitsPicker(1600, 400, 1080, 2392, fullScreenOnly = false))
        assertTrue(templateFitsPicker(400, 1600, 1080, 2392, fullScreenOnly = false))
    }
}
