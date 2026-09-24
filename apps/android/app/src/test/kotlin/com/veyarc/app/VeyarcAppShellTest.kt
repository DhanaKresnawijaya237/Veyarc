package com.veyarc.app

import com.veyarc.automation.android.ReplayFloatingController
import com.veyarc.data.storage.StoredRunStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VeyarcAppShellTest {
    @Test
    fun navigationRailIsUsedForLandscapeAndLargeWindows() {
        assertFalse(usesNavigationRail(widthDp = 360f, heightDp = 800f))
        assertTrue(usesNavigationRail(widthDp = 800f, heightDp = 360f))
        assertTrue(usesNavigationRail(widthDp = 840f, heightDp = 479f))
        assertTrue(usesNavigationRail(widthDp = 839f, heightDp = 600f))
        assertFalse(usesNavigationRail(widthDp = 600f, heightDp = 839f))
        assertTrue(usesNavigationRail(widthDp = 840f, heightDp = 600f))
        assertTrue(usesNavigationRail(widthDp = 1280f, heightDp = 800f))
    }

    @Test
    fun freshControllerRequestIsHandledAfterActivityRestoration() {
        val action = ReplayFloatingController.ACTION_OPEN_WORKFLOW

        assertTrue(shouldHandleControllerRequest(action))
        // Handled intents have their action cleared, so rotation cannot replay them.
        assertFalse(shouldHandleControllerRequest(null))
    }

    @Test
    fun runFilterHighlightOnlyAppearsForActiveFilters() {
        assertFalse(hasActiveRunFilters(null, null, RunDateFilter.ANY, ""))
        assertTrue(hasActiveRunFilters(StoredRunStatus.FAILED, null, RunDateFilter.ANY, ""))
        assertTrue(hasActiveRunFilters(null, "workflow", RunDateFilter.ANY, ""))
        assertTrue(hasActiveRunFilters(null, null, RunDateFilter.WEEK, ""))
        assertTrue(hasActiveRunFilters(null, null, RunDateFilter.ANY, "2"))
    }

    @Test
    fun folderOrderKeepsBuiltInAndCustomFoldersTogether() {
        val available = listOf("all", "favorites", "custom", "unfiled")

        assertEquals(
            listOf("all", "custom", "favorites", "unfiled"),
            reconcileFolderOrder(listOf("all", "custom", "favorites"), available),
        )
    }

    @Test
    fun workflowCopyNamesAreUniqueAcrossAllFolders() {
        val names = setOf("Morning", "Morning copy", "Morning copy 2")

        assertEquals("Morning copy 3", uniqueWorkflowCopyName("Morning", names))
        assertEquals("Evening copy", uniqueWorkflowCopyName("Evening", names))
    }
}
