package com.veyarc.automation.android

import org.junit.Assert.assertEquals
import org.junit.Test

class EdgeLatchSideTest {
    @Test
    fun releaseSelectsOnlyLeftOrRightEvenNearGestureEdges() {
        assertEquals(
            EdgeLatchSide.LEFT,
            nearestEdgeLatchSide(4f, 1080, EdgeLatchSide.RIGHT),
        )
        assertEquals(
            EdgeLatchSide.RIGHT,
            nearestEdgeLatchSide(1075f, 1080, EdgeLatchSide.LEFT),
        )
        assertEquals(
            EdgeLatchSide.LEFT,
            nearestEdgeLatchSide(500f, 1080, EdgeLatchSide.RIGHT),
        )
        assertEquals(
            EdgeLatchSide.LEFT,
            nearestEdgeLatchSide(500f, 1080, EdgeLatchSide.RIGHT),
        )
    }

    @Test
    fun invalidDisplayDimensionsKeepExistingSafePlacement() {
        assertEquals(
            EdgeLatchSide.RIGHT,
            nearestEdgeLatchSide(0f, 0, EdgeLatchSide.RIGHT),
        )
    }

    @Test
    fun dragReleaseStoresAContinuousPositionAlongTheSelectedEdge() {
        assertEquals(
            0.5f,
            edgeLatchOffsetForRelease(
                rawY = 500f,
                displayHeightPx = 1000,
                latchLengthPx = 100,
                fallback = 0f,
            ),
            0.001f,
        )
        assertEquals(
            1f,
            edgeLatchOffsetForRelease(
                rawY = 1080f,
                displayHeightPx = 1080,
                latchLengthPx = 100,
                fallback = 0f,
            ),
            0.001f,
        )
    }

    @Test
    fun latchRoundsOnlyTheContentFacingSide() {
        assertEquals(listOf(0f, 0f, 4f, 4f, 4f, 4f, 0f, 0f).map(Float::toDouble), edgeLatchCornerRadii(EdgeLatchSide.LEFT, 4f).map { it.toDouble() })
        assertEquals(listOf(4f, 4f, 0f, 0f, 0f, 0f, 4f, 4f).map(Float::toDouble), edgeLatchCornerRadii(EdgeLatchSide.RIGHT, 4f).map { it.toDouble() })
    }

    @Test
    fun connectedDrawerParksOutsideTheCorrectEdge() {
        assertEquals(-196f, edgeDrawerTranslationPx(EdgeLatchSide.LEFT, 184, 20, 8))
        assertEquals(196f, edgeDrawerTranslationPx(EdgeLatchSide.RIGHT, 184, 20, 8))
    }

    @Test
    fun playTargetKeepsTheRevisionBelongingToItsSelectedWorkflow() {
        val lastSaved = ReplayControllerWorkflow("saved", 8, "Saved", updatedAtMs = 30)
        val older = ReplayControllerWorkflow("older", 11, "Older", updatedAtMs = 10)
        val lastPlayed = ReplayControllerWorkflow("played", 3, "Played")
        val lastFavorited = ReplayControllerWorkflow("favorite", 6, "Favorite", isFavorite = true)
        val workflows = listOf(older, lastSaved)

        assertEquals(
            lastPlayed,
            selectReplayWorkflow(EdgeLatchPlayTarget.MOST_RECENTLY_PLAYED, workflows, lastPlayed, lastFavorited),
        )
        assertEquals(
            lastSaved,
            selectReplayWorkflow(EdgeLatchPlayTarget.LAST_SAVED, workflows, lastPlayed, lastFavorited),
        )
        assertEquals(
            lastFavorited,
            selectReplayWorkflow(EdgeLatchPlayTarget.LATEST_FAVORITED, workflows, lastPlayed, lastFavorited),
        )
    }
}
