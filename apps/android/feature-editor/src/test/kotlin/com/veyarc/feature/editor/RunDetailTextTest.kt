package com.veyarc.feature.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class RunDetailTextTest {
    @Test fun identifiersAreHiddenWithoutRemovingCoordinatesOrImageNames() {
        assertEquals("Tap at (25, 149 px recorded) started", readableRunMessage("Tap at (25, 149 px recorded) started"))
        assertEquals("Step completed", readableRunMessage("Step 00000000-0000-4000-8000-000000000501 completed"))
        assertEquals("Image Login screen matched", readableRunMessage("Image Login screen ${"a".repeat(64)} matched"))
    }

    @Test fun eventGapsCannotShowNegativeTime() {
        assertEquals(formatEventGap(0), formatEventGap(-100))
    }
}
