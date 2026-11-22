package com.masterofchessstrategy.games.go

import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.GoPhase
import com.masterofchessstrategy.engine.GoPlayer
import com.masterofchessstrategy.games.go.data.admitGoRankedStart
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GoStreakChallengeTest {
    @Test fun changesDifficultyAtThresholdsAndRetainsBestAfterLossOrDraw() {
        val atPromotion = GoStreakProgress(2, 2, winsAtDifficulty = 2)
        val (medium, promoted) = atPromotion.after(GameResult.FIRST_PLAYER_WIN, GoAiDifficulty.SIMPLE)
        assertEquals(GoAiDifficulty.MEDIUM, medium)
        assertEquals(GoStreakProgress(3, 3), promoted)
        val (_, lost) = promoted.after(GameResult.SECOND_PLAYER_WIN, medium)
        assertEquals(GoStreakProgress(0, 3, lossesAtDifficulty = 1), lost)
        assertEquals(GoAiDifficulty.SIMPLE to GoStreakProgress(0, 3),
            lost.after(GameResult.SECOND_PLAYER_WIN, medium))
        assertEquals(medium to GoStreakProgress(0, 3), lost.after(GameResult.DRAW, medium))
        assertEquals(GoAiDifficulty.MASTER, atPromotion.after(GameResult.FIRST_PLAYER_WIN, GoAiDifficulty.MASTER).first)
        assertEquals(GoAiDifficulty.SIMPLE, lost.after(GameResult.SECOND_PLAYER_WIN, GoAiDifficulty.SIMPLE).first)
    }

    @Test fun streakEnvelopePreservesStartCountersAndRejectsRepairedInvalidHeaders() {
        val config = GoAiMatchConfig(9, streak = GoStreakProgress(1, 4, winsAtDifficulty = 1))
        val bytes = GoAiMatchFileCodec.encode(GoAiSavedMatch("series", config, byteArrayOf(9, 0)))
        assertEquals(10, GoAiMatchFileCodec.canonicalVersion(bytes))
        assertEquals(config, GoAiMatchFileCodec.decode(bytes)?.config)
        for ((offset, value) in listOf(7 to GoPlayer.WHITE.code, 9 to 2, 40 to 0xff, 44 to 0, 48 to 3, 52 to 2)) {
            val invalid = bytes.copyOf()
            invalid[offset] = value.toByte()
            val crc = CRC32().apply { update(invalid, 0, 56); update(invalid, 60, invalid.size - 60) }
            ByteBuffer.wrap(invalid).order(ByteOrder.LITTLE_ENDIAN).putInt(56, crc.value.toInt())
            assertNull("invalid field at $offset", GoAiMatchFileCodec.decode(invalid))
        }
        val plain = GoAiSavedMatch("regular", GoAiMatchConfig(9), byteArrayOf(9, 0))
        assertEquals(6, GoAiMatchFileCodec.canonicalVersion(GoAiMatchFileCodec.encode(plain)))
    }

    @Test fun resumeDerivesTerminalFromNativeResultAndAdvancesOnlyOnce() = runTest {
        val before = GoStreakProgress(2, 3, winsAtDifficulty = 2)
        val config = GoAiMatchConfig(9, streak = before)
        val repository = GoAiMatchSessionTest.Repository().apply {
            saved = GoAiSavedMatch("finished", config, byteArrayOf(9, 0, 81, 81, 82, 83))
        }
        val records = mutableListOf<GoAiSavedMatch>()
        val session = GoAiMatchSession(this, repository, GoAiMatchConfig(), GoAiMatchSessionTest.Search(),
            StandardTestDispatcher(testScheduler), completedSink = GoAiCompletedMatchSink(records::add)) { size, _, handicap ->
            GoAiMatchSessionTest.Rules(size, handicap)
        }
        try {
            runCurrent()
            assertEquals(GoPhase.FINISHED, session.state.value.game.phase)
            assertEquals(GoAiDifficulty.MEDIUM to GoStreakProgress(3, 3), session.state.value.streakResult)
            assertTrue(session.restart())
            runCurrent()
            assertEquals(config, records.single().config)
            assertEquals(GoAiDifficulty.MEDIUM, repository.saved!!.config.difficulty)
            assertEquals(GoStreakProgress(3, 3), repository.saved!!.config.streak)
            assertNotEquals("finished", repository.saved!!.matchId)
            assertFalse(session.restart())
            assertFalse(session.setAutomatic(true))
            assertEquals(1, records.size)
        } finally { session.closeAndJoin() }
    }

    @Test fun freshChallengeCannotCreateRankedProvenance() {
        val match = GoAiSavedMatch("36a05ce5-5769-4c15-ae31-52ec426ca63e",
            GoAiMatchConfig(9, streak = GoStreakProgress()), byteArrayOf(9, 0))
        assertThrows(IllegalArgumentException::class.java) {
            admitGoRankedStart(match, 0) { size, _, handicap -> GoAiMatchSessionTest.Rules(size, handicap) }
        }
    }
}
