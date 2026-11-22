package com.masterofchessstrategy.games.go

import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.GoPhase
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

class GoAssessmentChallengeTest {
    @Test fun fiveRoundsDeriveDifficultyAndFinalRatingFromActualOpponent() {
        var progress = GoAssessmentProgress()
        var difficulty = GoAiDifficulty.MEDIUM
        fun commit(result: GameResult, next: GoAiDifficulty, rating: Int) {
            val derived = checkNotNull(progress.next(result, difficulty))
            difficulty = derived.first
            progress = derived.second
            assertEquals(next, difficulty)
            assertEquals(rating, progress.rating)
        }
        commit(GameResult.FIRST_PLAYER_WIN, GoAiDifficulty.HARD, 1400)
        commit(GameResult.DRAW, GoAiDifficulty.HARD, 1400)
        commit(GameResult.SECOND_PLAYER_WIN, GoAiDifficulty.MEDIUM, 1200)
        commit(GameResult.FIRST_PLAYER_WIN, GoAiDifficulty.HARD, 1400)
        assertNull(progress.next(GameResult.FIRST_PLAYER_WIN, difficulty))
        val final = progress.after(GameResult.FIRST_PLAYER_WIN, difficulty)
        assertTrue(final.isFinished)
        assertEquals(5, final.completedGames)
        assertEquals(1700, final.rating)
        assertEquals(listOf(3, 1, 1), listOf(final.wins, final.draws, final.losses))
    }

    @Test fun assessmentRequiresCanonicalCountersAndRejectsAssistanceOnRestore() {
        val initial = GoAiMatchConfig(9, difficulty = GoAiDifficulty.MEDIUM, assessment = GoAssessmentProgress())
        val bytes = GoAiMatchFileCodec.encode(GoAiSavedMatch("placement", initial, byteArrayOf(9, 0)))
        assertEquals(11, GoAiMatchFileCodec.canonicalVersion(bytes))
        assertEquals(initial, GoAiMatchFileCodec.decode(bytes)?.config)
        for ((offset, value) in listOf(8 to 0, 12 to 1, 24 to 1, 40 to 0, 44 to 5)) {
            val invalid = bytes.copyOf()
            invalid[offset] = value.toByte()
            val checksum = CRC32().apply { update(invalid, 0, 56); update(invalid, 60, invalid.size - 60) }
            ByteBuffer.wrap(invalid).order(ByteOrder.LITTLE_ENDIAN).putInt(56, checksum.value.toInt())
            assertNull("invalid field at $offset", GoAiMatchFileCodec.decode(invalid))
        }
    }

    @Test fun assessmentUiPolicyRejectsAssistanceModeChangesAndSixthRound() {
        val config = GoAiMatchConfig(9, difficulty = GoAiDifficulty.SIMPLE,
            assessment = GoAssessmentProgress(400, losses = 4))
        val playing = GoAiMatchUiState(config, game = GoGameUiState(boardSize = 9, sessionReady = true), eventCount = 2)
        assertFalse(playing.canUndo)
        assertFalse(playing.canRequestHint)
        assertFalse(playing.canSwitchMode)
        assertNull(playing.seriesNextConfig)
        val ended = playing.copy(game = playing.game.copy(phase = GoPhase.FINISHED, result = GameResult.SECOND_PLAYER_WIN))
        assertNull(ended.seriesNextConfig)
        assertEquals(5, ended.assessmentResult?.completedGames)
        assertEquals(400, ended.assessmentResult?.rating)
    }
}
