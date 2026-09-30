package com.masterofchessstrategy.ui

import com.masterofchessstrategy.engine.BoardPosition
import com.masterofchessstrategy.engine.ChineseChessPiece
import com.masterofchessstrategy.engine.ChineseChessPieceType
import com.masterofchessstrategy.engine.ChineseChessSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChineseChessTutorialDiagramTest {
    @Test
    fun horseDiagramShowsBlockedLegAndOnlySixRemainingDestinations() {
        val state = tutorialMoveDiagramState(TutorialMoveDiagram.HORSE)

        assertFalse(state.isInteractionEnabled)
        assertEquals(BoardPosition(4, 5), state.selectedPosition)
        assertEquals(
            ChineseChessPiece(ChineseChessPieceType.SOLDIER, ChineseChessSide.RED),
            state.pieceAt(BoardPosition(4, 4)),
        )
        assertEquals(6, state.legalDestinations.size)
        assertFalse(BoardPosition(3, 3) in state.legalDestinations)
        assertFalse(BoardPosition(5, 3) in state.legalDestinations)
        assertTrue(BoardPosition(2, 4) in state.legalDestinations)
    }

    @Test
    fun cannonDiagramShowsOneScreenAndTheCaptureTarget() {
        val state = tutorialMoveDiagramState(TutorialMoveDiagram.CANNON)

        assertFalse(state.isInteractionEnabled)
        assertEquals(BoardPosition(1, 7), state.selectedPosition)
        assertEquals(
            ChineseChessPiece(ChineseChessPieceType.SOLDIER, ChineseChessSide.RED),
            state.pieceAt(BoardPosition(1, 5)),
        )
        assertEquals(
            ChineseChessPiece(ChineseChessPieceType.CHARIOT, ChineseChessSide.BLACK),
            state.pieceAt(BoardPosition(1, 3)),
        )
        assertEquals(setOf(BoardPosition(1, 3)), state.legalDestinations)
    }
}
