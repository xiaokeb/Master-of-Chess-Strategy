package com.masterofchessstrategy.games.go

import com.masterofchessstrategy.engine.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** Session policy uses deterministic rules; production Native/Room is checked on the emulator. */
@OptIn(ExperimentalCoroutinesApi::class)
class GoAiBlindPlaySessionTest {
    @Test fun restartReplacementAndModeChangesKeepBlindPlay() = runTest {
        val repository = GoAiMatchSessionTest.Repository()
        val session = session(repository, GoAiMatchConfig(9, blind = true))
        try {
            runCurrent()
            val original = checkNotNull(repository.saved)
            assertTrue(session.setAutomatic(true)); runCurrent()
            assertTrue(repository.saved!!.config.blind)
            assertTrue(session.setAutomatic(false)); runCurrent()
            assertEquals(original.config, repository.saved!!.config)
            val replacement = GoAiOpening.fromSnapshot(byteArrayOf(13, 5, 0, 1)) { size, _, handicap ->
                GoAiMatchSessionTest.Rules(size, handicap)
            }
            assertTrue(session.replaceOpening(replacement)); runCurrent()
            val replaced = checkNotNull(repository.saved)
            assertTrue(replaced.config.blind)
            assertNotEquals(original.matchId, replaced.matchId)
            assertEquals(13, replaced.config.boardSize)
            assertArrayEquals(replacement.copyBytes(), replaced.copyOpeningBytes())
            assertTrue(session.restart()); runCurrent()
            val restarted = checkNotNull(repository.saved)
            assertEquals(replaced.config, restarted.config)
            assertNotEquals(replaced.matchId, restarted.matchId)
            assertArrayEquals(replacement.copyBytes(), restarted.copyBytes())
        } finally { session.closeAndJoin() }
    }

    @Test fun terminalAutomaticContinuationKeepsBlindInArchiveAndNextGame() = runTest {
        val config = GoAiMatchConfig(9, humanPlayer = null, blind = true)
        val repository = GoAiMatchSessionTest.Repository().apply {
            saved = GoAiSavedMatch("blind-series", config, byteArrayOf(9, 0, 81, 81, 82, 83),
                autoContinueEnabled = true, autoContinueMaxGames = 3, autoContinueGameNumber = 2)
        }
        val archived = mutableListOf<GoAiSavedMatch>()
        val session = session(repository, GoAiMatchConfig(19), GoAiCompletedMatchSink(archived::add))
        try {
            runCurrent()
            assertEquals(config, archived.single().config)
            assertEquals("blind-series", archived.single().matchId)
            val fresh = checkNotNull(repository.saved)
            assertEquals(config, fresh.config)
            assertNotEquals("blind-series", fresh.matchId)
            assertEquals(3, fresh.autoContinueGameNumber)
            assertEquals(GoPhase.PLAYING, session.state.value.game.phase)
        } finally { session.closeAndJoin() }
    }

    private fun TestScope.session(repository: GoAiMatchSessionTest.Repository, config: GoAiMatchConfig,
        sink: GoAiCompletedMatchSink? = null) = GoAiMatchSession(this, repository, config,
        GoAiMatchSessionTest.Search(), StandardTestDispatcher(testScheduler), completedSink = sink) { size, _, handicap ->
        GoAiMatchSessionTest.Rules(size, handicap)
    }
}
