package com.veyarc.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VeyarcAppShellTest {
    @Test
    fun navigationRailIsReservedForLargeWindows() {
        assertFalse(usesNavigationRail(widthDp = 840f, heightDp = 479f))
        assertFalse(usesNavigationRail(widthDp = 839f, heightDp = 600f))
        assertTrue(usesNavigationRail(widthDp = 840f, heightDp = 600f))
    }
}
