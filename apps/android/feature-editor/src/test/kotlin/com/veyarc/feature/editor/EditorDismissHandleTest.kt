package com.veyarc.feature.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorDismissHandleTest {
    @Test
    fun `large sheets consume only upward overscroll`() {
        assertEquals(-24f, upwardOverscrollConsumption(-24f), 0f)
        assertEquals(0f, upwardOverscrollConsumption(0f), 0f)
        assertEquals(0f, upwardOverscrollConsumption(24f), 0f)
    }

    @Test
    fun `large sheet mode follows measured content height`() {
        assertEquals(LargeEditorSheetMode.CAPPED_SCROLLABLE, largeEditorSheetMode(0, 1_000))
        assertEquals(LargeEditorSheetMode.WRAPPED, largeEditorSheetMode(799, 1_000))
        assertEquals(LargeEditorSheetMode.CAPPED_SCROLLABLE, largeEditorSheetMode(800, 1_000))
        assertEquals(LargeEditorSheetMode.CAPPED_SCROLLABLE, largeEditorSheetMode(999, 1_000))
        assertEquals(LargeEditorSheetMode.FULL_SCROLLABLE, largeEditorSheetMode(1_000, 1_000))
        assertEquals(LargeEditorSheetMode.FULL_SCROLLABLE, largeEditorSheetMode(1_200, 1_000))
    }

    @Test
    fun `sheet requires a fresh gesture after content returns to the top`() {
        assertFalse(nextEditorSheetGesturesEnabled(true, canScrollBackward = true, scrolling = true))
        assertFalse(nextEditorSheetGesturesEnabled(false, canScrollBackward = false, scrolling = true))
        assertTrue(nextEditorSheetGesturesEnabled(false, canScrollBackward = false, scrolling = false))
    }
}
