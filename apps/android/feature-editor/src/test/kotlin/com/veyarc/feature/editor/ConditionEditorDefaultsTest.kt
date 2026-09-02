package com.veyarc.feature.editor

import com.veyarc.model.AndCondition
import com.veyarc.model.NotCondition
import com.veyarc.model.OrCondition
import com.veyarc.model.PixelColorCondition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConditionEditorDefaultsTest {
    @Test
    fun composedConditionKindsCreateFiniteEditableChildren() {
        val all = EditableConditionKind.AND.defaultCondition(DEFAULT_PACKAGE, emptyList()) as AndCondition
        val any = EditableConditionKind.OR.defaultCondition(DEFAULT_PACKAGE, emptyList()) as OrCondition
        val not = EditableConditionKind.NOT.defaultCondition(DEFAULT_PACKAGE, emptyList()) as NotCondition

        assertEquals(2, all.conditions.size)
        assertTrue(all.conditions.first() is PixelColorCondition)
        assertEquals(2, any.conditions.size)
        assertTrue(any.conditions.first() is PixelColorCondition)
        assertTrue(not.condition is PixelColorCondition)
    }

    private companion object {
        const val DEFAULT_PACKAGE = "com.example.target"
    }
}
