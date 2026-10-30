package com.masterofchessstrategy.games.lanzhou_fangqi.data

import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.games.lanzhou_fangqi.*
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

/** Synthetic action text tests transitions only, never claims a real legal Native game. */
class FangqiRankedCheckpointTransitionTest {
    @Test fun emptySlotCreationDoesNotImplicitlyAbandonAnExistingGame() {
        validateFangqiRankedReplacement(null, null, emptySet())
        rejected { validateFangqiRankedReplacement(session(), null, setOf("id")) }
    }
    @Test fun explicitExactSettledActivityCanBeReplacedButAnUnsettledOrLegacyGameCannot() {
        val current = session()
        validateFangqiRankedReplacement(current, session(), setOf("id"))
        rejected { validateFangqiRankedReplacement(current, current, emptySet()) }
        rejected { validateFangqiRankedReplacement(current, session(time = 11), setOf("id")) }
        rejected { validateFangqiRankedReplacement(null, current, setOf("id")) }
    }
    @Test fun exactRetryAndTimestampOnlyCheckpointAreAllowed() {
        val before = session()
        validateFangqiCheckpointTransition(before, before)
        validateFangqiCheckpointTransition(before, session(time = 11))
    }
    @Test fun forwardBatchMustExtendEveryOriginalAction() {
        validateFangqiCheckpointTransition(session(listOf(A)), session(listOf(A, B)))
        rejected { validateFangqiCheckpointTransition(session(listOf(A)), session(listOf(B, A))) }
    }
    @Test fun rewritingEqualLengthHistoryIsNotForwardPlay() {
        rejected { validateFangqiCheckpointTransition(session(listOf(A)), session(listOf(B))) }
    }
    @Test fun undoRequiresOneCreditAndTheExactHistoryPrefix() {
        validateFangqiCheckpointTransition(session(listOf(A, B)), session(listOf(A), undo = 1))
        rejected { validateFangqiCheckpointTransition(session(listOf(A, B)), session(listOf(A))) }
        rejected { validateFangqiCheckpointTransition(session(listOf(A, B)), session(listOf(B), undo = 1)) }
        rejected { validateFangqiCheckpointTransition(session(listOf(A, B)), session(listOf(A), undo = 2)) }
    }
    @Test fun undoCanReturnToTheStandardRootWithoutRewritingIt() {
        validateFangqiCheckpointTransition(session(listOf(A)), session(undo = 1))
    }
    @Test fun hintConsumesExactlyOneCreditWithoutChangingHistory() {
        validateFangqiCheckpointTransition(session(listOf(A)), session(listOf(A), hint = 1))
        rejected { validateFangqiCheckpointTransition(session(listOf(A)), session(listOf(A), hint = 2)) }
        rejected { validateFangqiCheckpointTransition(session(listOf(A)), session(listOf(A, B), hint = 1)) }
    }
    @Test fun countersAndTimeCannotDecrease() {
        rejected { validateFangqiCheckpointTransition(session(time = 11), session(time = 10)) }
        rejected { validateFangqiCheckpointTransition(session(hint = 1), session()) }
        rejected { validateFangqiCheckpointTransition(session(undo = 1), session()) }
    }
    @Test fun aForwardMoveCannotAlsoConsumeAnUndo() {
        rejected { validateFangqiCheckpointTransition(session(), session(listOf(A), undo = 1)) }
    }
    @Test fun sessionDifficultyAndModeAreImmutable() {
        rejected { validateFangqiCheckpointTransition(session(), session(id = "other")) }
        rejected { validateFangqiCheckpointTransition(session(), session(difficulty = Difficulty.MEDIUM)) }
        val local = FangqiStoredSession(FangqiStoredMode.LOCAL_TWO_PLAYER, null, root, "id", 10)
        rejected { validateFangqiCheckpointTransition(local, session()) }
    }
    @Test fun declaredActionCountMustEqualTheEntireHistory() {
        val wrong = FangqiStoredSession(FangqiStoredMode.HUMAN_VS_AI, Difficulty.EASY, root, "id", 10, 1)
        rejected { validateFangqiCheckpointTransition(session(), wrong) }
    }
    @Test fun masterHasNoHintOrUndoAllowance() {
        rejected { validateFangqiCheckpointTransition(session(difficulty = Difficulty.MASTER), session(hint = 1, difficulty = Difficulty.MASTER)) }
        rejected { validateFangqiCheckpointTransition(session(listOf(A), difficulty = Difficulty.MASTER), session(undo = 1, difficulty = Difficulty.MASTER)) }
    }
    @Test fun mediumAndHardUseTheirOwnLimitsAndEasyDoesNotOverflowCreditMath() {
        validateFangqiCheckpointTransition(session(hint = 2, difficulty = Difficulty.MEDIUM), session(hint = 3, difficulty = Difficulty.MEDIUM))
        rejected { validateFangqiCheckpointTransition(session(hint = 3, difficulty = Difficulty.MEDIUM), session(hint = 4, difficulty = Difficulty.MEDIUM)) }
        rejected { validateFangqiCheckpointTransition(session(hint = 1, difficulty = Difficulty.HARD), session(hint = 2, difficulty = Difficulty.HARD)) }
        validateFangqiCheckpointTransition(session(hint = Int.MAX_VALUE), session(hint = Int.MAX_VALUE))
    }

    private fun session(actions: List<String> = emptyList(), time: Long = 10, undo: Int = 0, hint: Int = 0,
        id: String = "id", difficulty: Difficulty = Difficulty.EASY): FangqiStoredSession {
        val text = root.toString(Charsets.US_ASCII).removeSuffix("|0|") + "|${actions.size}|" + actions.joinToString(";")
        return FangqiStoredSession(FangqiStoredMode.HUMAN_VS_AI, difficulty, text.toByteArray(Charsets.US_ASCII),
            id, time, actions.size, undo, hint)
    }
    private fun rejected(operation: () -> Unit) {
        try { operation(); fail("Invalid transition accepted") } catch (_: IOException) { }
    }
    companion object {
        private const val A = "m,1,35,28"
        private const val B = "m,2,7,14"
        private val root = FangqiRankedLedger.standardInitialHistory().bytes
    }
}
