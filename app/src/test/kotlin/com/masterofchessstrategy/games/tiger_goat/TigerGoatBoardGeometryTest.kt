package com.masterofchessstrategy.games.tiger_goat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class TigerGoatBoardGeometryTest {
    private val spec = TigerGoatBoardSpec(
        points = listOf(
            TigerGoatPoint(0, -2, 0),
            TigerGoatPoint(1, 0, 2),
            TigerGoatPoint(2, 6, 4),
        ),
        edges = listOf(TigerGoatEdge(0, 1), TigerGoatEdge(1, 2)),
    )

    @Test
    fun mountainTipsFitLandscapeAndMapToCorrectPoints() {
        val viewport = tigerGoatViewport(920f, 520f, spec)
        assertEquals(60f, viewport.centers[0].x, 0.01f)
        assertEquals(60f, viewport.centers[0].y, 0.01f)
        assertEquals(860f, viewport.centers[2].x, 0.01f)
        assertEquals(460f, viewport.centers[2].y, 0.01f)
        assertEquals(0, viewport.hitTest(TigerGoatPixelPoint(60f, 60f)))
        assertEquals(1, viewport.hitTest(TigerGoatPixelPoint(260f, 260f)))
        assertEquals(2, viewport.hitTest(TigerGoatPixelPoint(860f, 460f)))
        assertNull(viewport.hitTest(TigerGoatPixelPoint(460f, 260f)))
    }

    @Test
    fun repeatedPrintedEdgeIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            TigerGoatBoardSpec(spec.points, spec.edges + TigerGoatEdge(2, 1))
        }
    }
}
