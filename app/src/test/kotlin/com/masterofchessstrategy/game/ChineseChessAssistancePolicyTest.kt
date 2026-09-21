package com.masterofchessstrategy.game

import com.masterofchessstrategy.data.StoredGameMode
import com.masterofchessstrategy.engine.Difficulty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChineseChessAssistancePolicyTest {
    @Test
    fun aiDifficultiesResolveDocumentedUndoAndHintBoundaries() {
        val easy = ChineseChessAssistancePolicy.resolve(
            StoredGameMode.HUMAN_VS_AI,
            Difficulty.EASY,
        )
        assertNull(easy.undoLimit)
        assertNull(easy.hintLimit)
        assertEquals(ChineseChessHintMode.ALL_LEGAL_MOVES, easy.hintMode)

        val medium = ChineseChessAssistancePolicy.resolve(
            StoredGameMode.HUMAN_VS_AI,
            Difficulty.MEDIUM,
        )
        assertEquals(3, medium.undoRemaining(0))
        assertEquals(1, medium.undoRemaining(2))
        assertEquals(0, medium.hintRemaining(3))
        assertEquals(ChineseChessHintMode.BEST_MOVE, medium.hintMode)

        val hard = ChineseChessAssistancePolicy.resolve(
            StoredGameMode.HUMAN_VS_AI,
            Difficulty.HARD,
        )
        assertEquals(1, hard.undoLimit)
        assertEquals(0, hard.hintLimit)
        assertEquals(ChineseChessHintMode.NONE, hard.hintMode)
    }

    @Test
    fun localPlayKeepsUndoUnlimitedAndDoesNotOfferAiHint() {
        val policy = ChineseChessAssistancePolicy.resolve(
            StoredGameMode.LOCAL_TWO_PLAYER,
            null,
        )

        assertNull(policy.undoRemaining(99))
        assertEquals(0, policy.hintRemaining(0))
        assertEquals(ChineseChessHintMode.NONE, policy.hintMode)
    }

    @Test
    fun blindChallengeDisablesUndoAndHintsAtEveryDifficulty() {
        listOf(
            StoredGameMode.BLIND_CHALLENGE,
            StoredGameMode.ASSESSMENT_CHALLENGE,
        ).forEach { mode ->
            Difficulty.entries.forEach { difficulty ->
                val policy = ChineseChessAssistancePolicy.resolve(mode, difficulty)

                assertEquals(0, policy.undoLimit)
                assertEquals(0, policy.hintLimit)
                assertEquals(ChineseChessHintMode.NONE, policy.hintMode)
            }
        }
    }
}
