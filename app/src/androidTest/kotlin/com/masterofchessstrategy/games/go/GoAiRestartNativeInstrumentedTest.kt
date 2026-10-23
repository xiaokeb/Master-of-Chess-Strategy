package com.masterofchessstrategy.games.go

import com.masterofchessstrategy.engine.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** Real Native resets/terminal validation; completed-store double is not Room or rewards evidence. */
class GoAiRestartNativeInstrumentedTest {
    @Test fun threeSizesAndHandicapRestartPreserveSetupPolicyAndResetAllowances() = runBlocking {
        for (size in listOf(9, 13, 19)) for (handicap in listOf(0, 5)) {
            val config = GoAiMatchConfig(size, handicap, GoPlayer.WHITE, GoAiDifficulty.HARD)
            val bytes = NativeGoEngine(size, if (handicap == 0) 15 else 0, handicap).use {
                for (point in listOf(BoardPosition(0, 0), BoardPosition(size - 1, size - 1)))
                    assertEquals(ActionResult.Accepted, it.apply(GoAction.Place(point)))
                it.serialize()
            }
            val repository = Repository(GoAiSavedMatch("old-$size-$handicap", config, bytes, 1, 1))
            val session = GoAiMatchSession(this, repository, config, noSearch)
            try {
                await(session) { it.game.canOperate }
                assertTrue(session.restart())
                await(session) { it.game.canOperate && it.eventCount == 0 }
                val fresh = checkNotNull(repository.saved)
                assertNotEquals("old-$size-$handicap", fresh.matchId)
                assertEquals(config, fresh.config)
                assertEquals(0, fresh.hintUsed); assertEquals(0, fresh.undoUsed)
                assertEquals(handicap, session.state.value.game.stones.count { it != 0 })
                assertTrue(session.state.value.paused); assertFalse(session.undo())
                NativeGoEngine(size).use {
                    assertEquals(RestoreResult.Restored, it.restore(fresh.copyBytes()))
                    assertEquals(if (handicap == 0) 15 else 0, it.blackKomiQuarters)
                    assertEquals(handicap, it.handicapStones)
                    assertEquals(if (handicap == 0) 0 else 1, it.currentPlayer.value)
                    assertTrue(it.eventHistory().isEmpty())
                }
            } finally { session.closeAndJoin() }
        }
    }

    @Test fun realFinishedResultIsSealedBeforeStoreFailureAndCanRetryAfterRestore() = runBlocking {
        val config = GoAiMatchConfig(9, difficulty = GoAiDifficulty.MEDIUM)
        val bytes = NativeGoEngine(9).use {
            assertEquals(ActionResult.Accepted, it.apply(GoAction.Pass))
            assertEquals(ActionResult.Accepted, it.apply(GoAction.Pass))
            val revision = it.scoringState().proposalRevision
            for (actor in GoPlayer.entries) assertEquals(ActionResult.Accepted, it.apply(GoAction.ConfirmScore(actor, revision)))
            it.serialize()
        }
        val repository = Repository(GoAiSavedMatch("terminal-native", config, bytes, hintUsed = 1))
        val session = GoAiMatchSession(this, repository, config, noSearch)
        try {
            await(session) { it.game.canOperate }
            assertTrue(session.state.value.canUndo)
            assertTrue(session.restart(GoAiCompletedMatchSink { throw IOException("Store unavailable") }))
            await(session) { it.game.canOperate && it.game.error != null }
            assertTrue(checkNotNull(repository.saved).completionReserved)
            assertFalse(session.undo())
            assertEquals(GameResult.SECOND_PLAYER_WIN, session.state.value.game.result)
        } finally { session.closeAndJoin() }
        val restored = GoAiMatchSession(this, repository, config, noSearch)
        try {
            await(restored) { it.game.canOperate }
            assertTrue(restored.state.value.completionReserved)
            assertFalse(restored.undo())
            var stored = false
            assertTrue(restored.restart(GoAiCompletedMatchSink { match ->
                assertEquals("terminal-native", match.matchId)
                assertTrue(checkNotNull(repository.saved).completionReserved)
                NativeGoEngine(9).use {
                    assertEquals(RestoreResult.Restored, it.restore(match.copyBytes()))
                    assertEquals(GoPhase.FINISHED, it.phase)
                    assertNotNull(it.finalScore())
                }
                stored = true
            }))
            await(restored) { it.game.canOperate && it.game.phase == GoPhase.PLAYING }
            assertTrue(stored)
            assertNotEquals("terminal-native", checkNotNull(repository.saved).matchId)
            assertFalse(checkNotNull(repository.saved).completionReserved)
        } finally { restored.closeAndJoin() }
    }

    @Test fun completionFlagCannotSealAnOngoingNativeRecord() = runBlocking {
        val config = GoAiMatchConfig(9)
        val bytes = NativeGoEngine(9).use { it.serialize() }
        val original = GoAiSavedMatch("invalid-reservation", config, bytes, completionReserved = true)
        val repository = Repository(original)
        val session = GoAiMatchSession(this, repository, config, noSearch)
        try {
            await(session) { !it.game.loading }
            assertFalse(session.state.value.game.canOperate)
            assertNotNull(session.state.value.game.error)
            assertSame(original, repository.saved)
            assertFalse(session.restart())
        } finally { session.closeAndJoin() }
    }

    private val noSearch = GoAiSearchEngine { error("Restart must remain paused") }
    private suspend fun await(session: GoAiMatchSession, predicate: (GoAiMatchUiState) -> Boolean) =
        withTimeout(15_000) { session.state.first(predicate) }
    private class Repository(@Volatile var saved: GoAiSavedMatch) : GoAiMatchRepository {
        override suspend fun load() = saved
        override suspend fun save(match: GoAiSavedMatch) { saved = match }
    }
}
