package com.masterofchessstrategy.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.masterofchessstrategy.engine.BoardPosition
import com.masterofchessstrategy.engine.ChineseChessPiece
import com.masterofchessstrategy.engine.ChineseChessPieceType
import com.masterofchessstrategy.engine.ChineseChessSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChineseChessBoardViewportTest {
    @Test
    fun blackPerspectiveRoundTripsEveryVisibleSquareWithZoomAndPan() {
        for (size in listOf(Size(600f, 600f), Size(1100f, 350f), Size(350f, 700f))) {
            val geometry = calculateBoardGeometry(size.width, size.height).copy(reversed = true)
            assertTrue(geometry.center(BoardPosition(0, 0)).y > geometry.center(BoardPosition(0, 9)).y)
            for (scale in listOf(1f, 1.5f, 2.5f)) {
                val view = ChineseChessBoardViewport().transform(size, Offset(100f, 200f), Offset(80f, -130f), scale)
                for (y in 0..9) for (x in 0..8) {
                    val position = BoardPosition(x, y)
                    val screen = view.screenPoint(geometry.center(position), size)
                    if (screen.x in 0f..size.width && screen.y in 0f..size.height) {
                        assertEquals(position, view.positionAt(screen, size)?.fromPlayerView(true))
                    }
                }
            }
        }
    }

    @Test
    fun visibleIntersectionsRoundTripAcrossZoomPanAndAspectRatios() {
        for (size in listOf(Size(600f, 600f), Size(1100f, 350f), Size(350f, 700f))) {
            val geometry = calculateBoardGeometry(size.width, size.height)
            for (scale in listOf(1f, 1.5f, 2.5f)) {
                val viewport = ChineseChessBoardViewport().transform(size, Offset(100f, 200f), Offset(80f, -130f), scale)
                for (y in 0..9) for (x in 0..8) {
                    val position = BoardPosition(x, y)
                    val screen = viewport.screenPoint(geometry.center(position), size)
                    if (screen.x in 0f..size.width && screen.y in 0f..size.height) {
                        assertEquals(position, viewport.positionAt(screen, size))
                    } else assertNull(viewport.positionAt(screen, size))
                }
            }
        }
    }

    @Test
    fun zoomPreservesFingerAnchorUntilAnEdgeIsReached() {
        val size = Size(600f, 600f)
        val centroid = Offset(340f, 280f)
        val view = ChineseChessBoardViewport().transform(size, centroid, Offset.Zero, 1.5f)
        val screen = view.screenPoint(centroid, size)
        assertEquals(centroid.x, screen.x, 0.001f)
        assertEquals(centroid.y, screen.y, 0.001f)
        assertEquals(ChineseChessBoardViewport(), view.transform(size, centroid, Offset.Zero, 0.1f))
    }

    @Test
    fun boundsPreventPanningIntoEmptyLetterboxAndCapScale() {
        val size = Size(1100f, 350f)
        val view = ChineseChessBoardViewport().transform(size, Offset.Zero, Offset(9999f, 9999f), 100f)
        assertEquals(2.5f, view.scale, 0f)
        assertEquals(0f, view.translation.x, 0.001f)
        assertEquals(262.5f, view.translation.y, 0.001f)
        assertEquals(ChineseChessBoardViewport(), view.transform(size, Offset.Zero, Offset(9999f, 9999f), 0.01f))
    }

    @Test
    fun invalidGeometryAndEventsDoNotPoisonTheViewport() {
        val view = ChineseChessBoardViewport()
        val size = Size(500f, 500f)
        assertEquals(view, view.transform(size, Offset.Zero, Offset.Zero, Float.NaN))
        assertEquals(view, view.transform(size, Offset.Zero, Offset.Zero, -1f))
        assertEquals(view, view.transform(size, Offset.Zero, Offset(Float.POSITIVE_INFINITY, 0f), 2f))
        assertEquals(view, view.transform(Size.Zero, Offset.Zero, Offset.Zero, 2f))
        assertNull(view.positionAt(Offset(Float.NaN, 0f), size))
        assertNull(view.positionAt(Offset.Zero, Size.Zero))
        val geometry = calculateBoardGeometry(size.width, size.height)
        val between = geometry.center(BoardPosition(4, 5)) + Offset(geometry.cellSize / 2, 0f)
        assertNull(view.positionAt(between, size))
    }

    @Test
    fun onlySinglePieceRelocationsProduceMoveOrCaptureFeedback() {
        val red = ChineseChessPiece(ChineseChessPieceType.CHARIOT, ChineseChessSide.RED)
        val black = ChineseChessPiece(ChineseChessPieceType.HORSE, ChineseChessSide.BLACK)
        val before = MutableList<ChineseChessPiece?>(90) { null }.apply { this[9] = red }
        val moved = before.toMutableList().apply { this[9] = null; this[10] = red }
        assertEquals(BoardPosition(1, 1), boardMoveFeedback(before, moved)?.destination)
        assertFalse(boardMoveFeedback(before, moved)!!.isCapture)
        before[10] = black
        assertTrue(boardMoveFeedback(before, moved)!!.isCapture)
        assertNull(boardMoveFeedback(moved, before)) // Undoing a capture restores two pieces.
        assertNull(boardMoveFeedback(before, before))
        assertNull(boardMoveFeedback(emptyList(), moved))
        assertNull(boardMoveFeedback(List(90) { null }, before))
        before[10] = red
        assertNull(boardMoveFeedback(before, moved))
    }
}
