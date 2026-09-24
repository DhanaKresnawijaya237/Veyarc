package com.veyarc.feature.editor

import com.veyarc.model.AndCondition
import com.veyarc.model.ImageAbsentCondition
import com.veyarc.model.ImagePresentCondition
import com.veyarc.model.NotCondition
import com.veyarc.model.OrCondition
import com.veyarc.model.OrientationCondition
import com.veyarc.model.PixelColorCondition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConditionEditorDefaultsTest {
    @Test
    fun composedConditionKindsCreateFiniteEditableChildren() {
        val all = EditableConditionKind.AND.defaultCondition(DEFAULT_PACKAGE) as AndCondition
        val any = EditableConditionKind.OR.defaultCondition(DEFAULT_PACKAGE) as OrCondition
        val not = EditableConditionKind.NOT.defaultCondition(DEFAULT_PACKAGE) as NotCondition

        assertEquals(2, all.conditions.size)
        assertTrue(all.conditions.first() is PixelColorCondition)
        assertEquals(2, any.conditions.size)
        assertTrue(any.conditions.first() is PixelColorCondition)
        assertTrue(not.condition is PixelColorCondition)
    }

    @Test
    fun imageConditionsStartWithoutSelectingAnExistingTemplate() {
        val screen = EditableConditionKind.SCREEN_STATE.defaultCondition(DEFAULT_PACKAGE) as ImagePresentCondition
        val present = EditableConditionKind.IMAGE_PRESENT.defaultCondition(DEFAULT_PACKAGE) as ImagePresentCondition
        val absent = EditableConditionKind.IMAGE_ABSENT.defaultCondition(DEFAULT_PACKAGE) as ImageAbsentCondition

        assertEquals("template-missing", screen.assetId.value)
        assertEquals("template-missing", present.assetId.value)
        assertEquals("template-missing", absent.assetId.value)
    }

    @Test
    fun onlyShortConditionEditorsUseCenteredSheets() {
        assertTrue(OrientationCondition(com.veyarc.model.DisplayOrientation.PORTRAIT).usesCompactEditorSheet())
        assertTrue(!EditableConditionKind.PIXEL.defaultCondition(DEFAULT_PACKAGE).usesCompactEditorSheet())
        assertTrue(!EditableConditionKind.AND.defaultCondition(DEFAULT_PACKAGE).usesCompactEditorSheet())
    }

    private companion object {
        const val DEFAULT_PACKAGE = "com.example.target"
    }
}
