package com.masterofchessstrategy.games.tiger_goat

import org.junit.Assert.*
import org.junit.Test

class TigerGoatForcedPassTest {
    @Test fun blockedGoatsRequireAdvertisedPassAndCanUndoIt() {
        val session = TigerGoatPassFixture()
        val controller = TigerGoatGameController(session)
        assertTrue(controller.uiState.value.canPassGoat)
        assertTrue(controller.uiState.value.message().contains("无合法走步"))
        controller.passGoat()
        assertEquals(1, session.actionCount)
        assertFalse(controller.uiState.value.canPassGoat)
        controller.undo()
        assertTrue(controller.uiState.value.canPassGoat)
        assertEquals(0, session.actionCount)
        session.omitPass = true
        controller.passGoat()
        assertEquals(0, session.actionCount)
    }

    @Test fun mobileGoatsPlacementTigersAndCaptureChainCannotPass() {
        for (adjust in listOf<(TigerGoatPassFixture) -> Unit>(
            { it.mobile = true }, { it.reserve = 1 }, { it.side = 1 },
            { it.phase = TigerGoatPhase.CAPTURE_CHAIN },
        )) {
            val session = TigerGoatPassFixture().also(adjust)
            val controller = TigerGoatGameController(session)
            assertFalse(controller.uiState.value.canPassGoat)
            controller.passGoat()
            assertEquals(0, session.actionCount)
        }
    }
}
