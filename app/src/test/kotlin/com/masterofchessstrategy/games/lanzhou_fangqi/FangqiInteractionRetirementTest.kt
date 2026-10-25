package com.masterofchessstrategy.games.lanzhou_fangqi

import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.games.lanzhou_fangqi.FangqiPoint
import com.masterofchessstrategy.engine.games.lanzhou_fangqi.FangqiSide
import com.masterofchessstrategy.engine.games.lanzhou_fangqi.FangqiRejection
import com.masterofchessstrategy.engine.GameResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FangqiInteractionRetirementTest {
    @Test fun immediateTurnObserverCannotSeeAnUnacknowledgedHumanMoveAsReady() = runTest {
        val session = FangqiOwnerTestSession()
        var done: ((Boolean) -> Unit)? = null
        val interaction = LanzhouFangqiInteraction(session,
            onPositionChanged = { _, _, callback -> done = callback })
        val blackPendingStates = mutableListOf<Boolean>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            interaction.state.collect { ui ->
                if (ui.snapshot.currentPlayer == FangqiSide.BLACK) blackPendingStates += ui.savePending
            }
        }
        interaction.tap(FangqiPoint(0, 0)); interaction.tap(FangqiPoint(1, 0))
        assertTrue(blackPendingStates.isNotEmpty())
        assertTrue("The new turn must be fenced before any synchronous observer sees it", blackPendingStates.all { it })
        assertNotNull(done)
        done!!(true)
        assertFalse(interaction.state.value.savePending)
        assertFalse(blackPendingStates.last())
    }

    @Test fun historyLimitRejectsAHumanMoveWithoutSavingOrChangingTheGameResult() {
        val session = FangqiOwnerTestSession().apply { applyRejection = FangqiRejection.RESOURCE_LIMIT }
        val original = session.serialize()
        var saves = 0
        val interaction = LanzhouFangqiInteraction(session, onPositionChanged = { _, _, _ -> saves++ })
        interaction.tap(FangqiPoint(0, 0)); interaction.tap(FangqiPoint(1, 0))
        assertEquals(FangqiFeedback.HISTORY_LIMIT, interaction.state.value.feedback)
        assertEquals(GameResult.ONGOING, interaction.state.value.snapshot.result)
        assertArrayEquals(original, session.serialize())
        assertEquals(0, saves)
        assertFalse(interaction.state.value.savePending)
        assertFalse(interaction.state.value.saveFailed)
    }

    @Test fun sealingDuringChooseRejectsTheLateResultBeforeItCanMutateOrSave() = runTest {
        val session = FangqiOwnerTestSession()
        lateinit var interaction: LanzhouFangqiInteraction
        var saves = 0
        session.onChoose = { interaction.seal() }
        interaction = LanzhouFangqiInteraction(session, aiDispatcher = StandardTestDispatcher(testScheduler),
            onPositionChanged = { _, _, _ -> saves++ })
        interaction.playAiTurn(Difficulty.EASY, FangqiSide.RED)
        assertEquals(1, session.chooseCalls)
        assertEquals(0, session.applyCalls)
        assertEquals(0, saves)
    }

    @Test fun retiredCheckpointCallbackCannotUnlockOrScheduleMoreWork() {
        val session = FangqiOwnerTestSession()
        var done: ((Boolean) -> Unit)? = null
        val interaction = LanzhouFangqiInteraction(session, onPositionChanged = { _, _, callback -> done = callback })
        interaction.tap(FangqiPoint(0, 0)); interaction.tap(FangqiPoint(1, 0))
        assertTrue(interaction.state.value.savePending)
        interaction.seal()
        done!!(true)
        assertTrue(interaction.state.value.savePending)
        interaction.retrySave(); interaction.undo(); interaction.reset(); interaction.tap(FangqiPoint(6, 6))
        assertEquals(1, session.applyCalls)
        assertEquals(0, session.undoCalls)
    }

    @Test fun automaticModeRejectsHumanTapsAndUndoGuardRejectsDirectCalls() {
        val session = FangqiOwnerTestSession()
        val automatic = LanzhouFangqiInteraction(session, automatic = true)
        automatic.tap(FangqiPoint(0, 0)); automatic.tap(FangqiPoint(1, 0))
        assertEquals(0, session.applyCalls)
        session.restore(FangqiOwnerTestSession.saved(count = 2).engineState)
        val guarded = LanzhouFangqiInteraction(session, undoAllowed = { false })
        guarded.undo()
        assertEquals(0, session.undoCalls)
    }
}
