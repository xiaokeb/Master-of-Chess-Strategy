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
    fun everyPieceTypeHasAReadOnlyIllustrationWithCorrectOrigin() {
        val origins = mapOf(
            TutorialMoveDiagram.CHARIOT to BoardPosition(0, 6),
            TutorialMoveDiagram.HORSE to BoardPosition(4, 5),
            TutorialMoveDiagram.CANNON to BoardPosition(1, 7),
            TutorialMoveDiagram.ELEPHANT to BoardPosition(2, 9),
            TutorialMoveDiagram.ADVISOR to BoardPosition(3, 9),
            TutorialMoveDiagram.GENERAL to BoardPosition(5, 9),
            TutorialMoveDiagram.SOLDIER to BoardPosition(4, 4),
        )
        assertEquals(TutorialMoveDiagram.entries.size, origins.size)
        origins.forEach { (diagram, origin) ->
            val state = tutorialMoveDiagramState(diagram)
            assertFalse(state.isInteractionEnabled)
            assertEquals(origin, state.selectedPosition)
            assertEquals(ChineseChessSide.RED, state.pieceAt(origin)?.side)
            assertTrue(state.legalDestinations.isNotEmpty())
        }
    }

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

    @Test
    fun rookElephantAdvisorGeneralAndSoldierShowTheirDistinctBoundaries() {
        val rook = tutorialMoveDiagramState(TutorialMoveDiagram.CHARIOT)
        assertEquals(17, rook.legalDestinations.size)
        assertTrue(BoardPosition(8, 6) in rook.legalDestinations)
        assertTrue(BoardPosition(0, 0) in rook.legalDestinations)

        val elephant = tutorialMoveDiagramState(TutorialMoveDiagram.ELEPHANT)
        assertEquals(setOf(BoardPosition(4, 7)), elephant.legalDestinations)
        assertEquals(
            ChineseChessPiece(ChineseChessPieceType.SOLDIER, ChineseChessSide.RED),
            elephant.pieceAt(BoardPosition(1, 8)),
        )

        val advisor = tutorialMoveDiagramState(TutorialMoveDiagram.ADVISOR)
        assertEquals(setOf(BoardPosition(4, 8)), advisor.legalDestinations)

        val general = tutorialMoveDiagramState(TutorialMoveDiagram.GENERAL)
        assertEquals(setOf(BoardPosition(4, 9), BoardPosition(5, 8)), general.legalDestinations)
        assertEquals(
            ChineseChessPiece(ChineseChessPieceType.SOLDIER, ChineseChessSide.RED),
            general.pieceAt(BoardPosition(4, 5)),
        )

        val soldier = tutorialMoveDiagramState(TutorialMoveDiagram.SOLDIER)
        assertEquals(
            setOf(BoardPosition(4, 3), BoardPosition(3, 4), BoardPosition(5, 4)),
            soldier.legalDestinations,
        )
        assertFalse(BoardPosition(4, 5) in soldier.legalDestinations)
    }
}
