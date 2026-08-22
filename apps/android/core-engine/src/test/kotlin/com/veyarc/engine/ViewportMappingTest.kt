package com.veyarc.engine

import com.veyarc.model.DisplayOrientation
import com.veyarc.model.DurationMillis
import com.veyarc.model.NormalizedCoordinate
import com.veyarc.model.TimedPoint
import org.junit.Assert.assertEquals
import org.junit.Test

class ViewportMappingTest {
    @Test
    fun scalesAxesIndependentlyToTheCurrentDisplay() {
        val viewport = ActiveViewport(
            widthPx = 1080,
            heightPx = 2392,
            orientation = DisplayOrientation.PORTRAIT,
        )

        val mapped = ViewportMapper.map(NormalizedCoordinate(0.1, 0.2), viewport)

        assertEquals(107.9, mapped.x, 0.000001)
        assertEquals(478.2, mapped.y, 0.000001)
        assertEquals(
            DisplayCoordinate(1079.0, 2391.0),
            ViewportMapper.map(NormalizedCoordinate(1.0, 1.0), viewport),
        )
    }

    @Test
    fun orientationRemainsRelativeToTheCurrentScreen() {
        val point = NormalizedCoordinate(0.1, 0.2)
        val portrait = ViewportMapper.map(
            point,
            ActiveViewport(1080, 2392, DisplayOrientation.PORTRAIT),
        )
        val landscape = ViewportMapper.map(
            point,
            ActiveViewport(2392, 1080, DisplayOrientation.LANDSCAPE),
        )

        assertEquals(107.9, portrait.x, 0.000001)
        assertEquals(478.2, portrait.y, 0.000001)
        assertEquals(239.1, landscape.x, 0.000001)
        assertEquals(215.8, landscape.y, 0.000001)
    }

    @Test
    fun mapsSwipePointsWhilePreservingOffsets() {
        val points = listOf(
            TimedPoint(NormalizedCoordinate(0.0, 0.5), DurationMillis(0)),
            TimedPoint(NormalizedCoordinate(1.0, 0.5), DurationMillis(250)),
        )

        val mapped = ViewportMapper.map(
            points,
            ActiveViewport(101, 201, DisplayOrientation.PORTRAIT),
        )

        assertEquals(DisplayTimedPoint(DisplayCoordinate(0.0, 100.0), 0L), mapped[0])
        assertEquals(DisplayTimedPoint(DisplayCoordinate(100.0, 100.0), 250L), mapped[1])
    }

    @Test(expected = UnsupportedViewportException::class)
    fun rejectsSplitScreen() {
        ViewportMapper.map(
            NormalizedCoordinate(0.5, 0.5),
            ActiveViewport(
                widthPx = 1080,
                heightPx = 1200,
                orientation = DisplayOrientation.PORTRAIT,
                windowMode = ActiveWindowMode.SPLIT_SCREEN,
            ),
        )
    }

    @Test(expected = UnsupportedViewportException::class)
    fun rejectsFreeformWindows() {
        ViewportMapper.map(
            NormalizedCoordinate(0.5, 0.5),
            ActiveViewport(
                widthPx = 1080,
                heightPx = 1200,
                orientation = DisplayOrientation.PORTRAIT,
                windowMode = ActiveWindowMode.FREEFORM,
            ),
        )
    }
}
