package com.masterofchessstrategy.games.go

import com.masterofchessstrategy.engine.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class GoAiAutoContinuationNativeInstrumentedTest {
    @Test fun confirmedNativeTerminalArchivesAndContinuesFromExactSpecifiedOpening() = runBlocking {
        val opening = NativeGoEngine(13, 0, 5).use {
            assertEquals(ActionResult.Accepted, it.apply(GoAction.Place(BoardPosition(0, 0))))
            assertEquals(ActionResult.Accepted, it.apply(GoAction.Place(BoardPosition(1, 0))))
            GoAiOpening.fromSnapshot(it.serialize())
        }
        val repository = Repository(); val archived = mutableListOf<GoAiSavedMatch>()
        val search = GoAiSearchEngine { GoAiSearchOutcome.Move(GoAiSuggestion(GoAction.Pass, 1, 0.0, 0.5, 0.0)) }
        val session = GoAiMatchSession(this, repository, opening.config(null, GoAiDifficulty.HARD), search,
            initialOpening = opening, completedSink = GoAiCompletedMatchSink { archived += it })
        try {
            await(session) { it.game.canOperate }; val firstId = repository.saved!!.matchId
            assertTrue(session.configureAutoContinuation(true, 2)); await(session) { it.autoContinueEnabled }
            session.resume(); await(session) { it.game.phase == GoPhase.SCORING }
            val revision = session.state.value.game.proposalRevision
            assertTrue(session.humanAction(GoAction.ConfirmScore(GoPlayer.BLACK, revision)))
            await(session) { it.game.blackConfirmed }
            assertTrue(session.humanAction(GoAction.ConfirmScore(GoPlayer.WHITE, revision)))
            await(session) { it.game.phase == GoPhase.PLAYING && it.autoContinueGameNumber == 2 }

            assertEquals(1, archived.size); assertEquals(firstId, archived.single().matchId)
            assertTrue(archived.single().completionReserved)
            val fresh = repository.saved!!
            assertNotEquals(firstId, fresh.matchId); assertArrayEquals(opening.copyBytes(), fresh.copyBytes())
            assertArrayEquals(opening.copyBytes(), fresh.copyOpeningBytes())
            assertEquals(2, fresh.autoContinueGameNumber); assertEquals(2, fresh.autoContinueMaxGames)
            assertTrue(session.state.value.hasSpecifiedOpening); assertFalse(session.state.value.canUndo)
        } finally { session.closeAndJoin() }
    }

    @Test fun restoredNativeTerminalAtMaximumArchivesLastGameWithoutCreatingAnotherGame() = runBlocking {
        val terminal = NativeGoEngine(9).use {
            it.apply(GoAction.Pass); it.apply(GoAction.Pass)
            val revision = it.scoringState().proposalRevision
            it.apply(GoAction.ConfirmScore(GoPlayer.BLACK, revision))
            it.apply(GoAction.ConfirmScore(GoPlayer.WHITE, revision)); it.serialize()
        }
        val original = GoAiSavedMatch("native-max", GoAiMatchConfig(9, humanPlayer = null), terminal,
            autoContinueEnabled = true, autoContinueMaxGames = 2, autoContinueGameNumber = 2)
        val repository = Repository(original); var stores = 0
        val session = GoAiMatchSession(this, repository, original.config, GoAiSearchEngine { error("No search expected") },
            completedSink = GoAiCompletedMatchSink { stores++ })
        try {
            await(session) { it.game.canOperate && it.completionReserved }
            assertEquals(GoPhase.FINISHED, session.state.value.game.phase)
            assertEquals("native-max", repository.saved!!.matchId); assertEquals(1, stores)
            assertTrue(repository.saved!!.completionReserved)
            assertFalse(session.continueAutomatically())
        } finally { session.closeAndJoin() }
    }

    private suspend fun await(session: GoAiMatchSession, predicate: (GoAiMatchUiState) -> Boolean) =
        withTimeout(20_000) { session.state.first(predicate) }
    private class Repository(@Volatile var saved: GoAiSavedMatch? = null) : GoAiMatchRepository {
        override suspend fun load() = saved
        override suspend fun save(match: GoAiSavedMatch) { saved = match }
    }
}
