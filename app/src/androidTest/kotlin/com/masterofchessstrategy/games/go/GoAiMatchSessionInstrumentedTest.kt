package com.masterofchessstrategy.games.go

import com.masterofchessstrategy.engine.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

/** Actual Native rules, scripted search responses. This does not measure NN search or strength. */
class GoAiMatchSessionInstrumentedTest {
    @Test fun threeSizesHandicapHumanWhiteAndAiReplyPersistAndUndo() = runBlocking {
        for (size in listOf(9, 13, 19)) {
            val repository = Repository()
            val answers = Channel<GoAiSearchOutcome>(Channel.UNLIMITED)
            val requests = mutableListOf<GoAiSearchRequest>()
            val driver = GoAiSearchEngine { request -> synchronized(requests) { requests += request }; answers.receive() }
            val config = GoAiMatchConfig(size, 5, GoPlayer.WHITE, GoAiDifficulty.HARD)
            val session = GoAiMatchSession(this, repository, config, driver)
            try {
                await(session) { it.game.canOperate }
                assertEquals(GoPlayer.WHITE, session.state.value.game.currentPlayer)
                session.resume()
                answers.trySend(move(GoAction.Place(BoardPosition(size - 1, size - 1))))
                assertTrue(session.humanAction(GoAction.Place(BoardPosition(0, 0))))
                await(session) { it.humanCanPlay && it.game.stones.last() == GoStone.BLACK.code }
                assertEquals(GoStone.WHITE.code, session.state.value.game.stones[0])
                assertEquals(1, synchronized(requests) { requests.size })
                NativeGoEngine(size).use { verifier ->
                    assertEquals(RestoreResult.Restored, verifier.restore(checkNotNull(repository.saved).copyBytes()))
                    assertEquals(0, verifier.blackKomiQuarters)
                    assertEquals(5, verifier.handicapStones)
                    assertEquals(2, verifier.eventHistory().size)
                }
                assertTrue(session.undo())
                await(session) { it.game.canOperate && it.undoUsed == 1 }
                assertEquals(GoPlayer.WHITE, session.state.value.game.currentPlayer)
                assertEquals(5, session.state.value.game.stones.count { it != 0 })
                assertFalse(session.undo())
                assertEquals(1, checkNotNull(repository.saved).undoUsed)
            } finally { session.closeAndJoin() }
        }
    }

    @Test fun actualDoublePassProposalAndBothConfirmationsRequireExplicitReview() = runBlocking {
        val repository = Repository()
        val answers = Channel<GoAiSearchOutcome>(Channel.UNLIMITED)
        answers.trySend(move(GoAction.Pass)); answers.trySend(move(GoAction.Pass))
        val session = GoAiMatchSession(this, repository, GoAiMatchConfig(9, humanPlayer = null), GoAiSearchEngine { answers.receive() })
        try {
            await(session) { it.game.canOperate }
            session.setSpeed(4f); session.resume()
            await(session) { it.canReviewScore }
            assertEquals(GameResult.ONGOING, session.state.value.game.result)
            assertFalse(session.state.value.game.blackConfirmed)
            assertFalse(session.state.value.game.whiteConfirmed)
            assertTrue(session.state.value.paused)
            val revision = session.state.value.game.proposalRevision
            assertTrue(session.humanAction(GoAction.ConfirmScore(GoPlayer.BLACK, revision)))
            await(session) { it.game.canOperate && it.game.blackConfirmed }
            assertEquals(GameResult.ONGOING, session.state.value.game.result)
            assertTrue(session.humanAction(GoAction.ConfirmScore(GoPlayer.WHITE, revision)))
            await(session) { it.game.canOperate && it.game.phase == GoPhase.FINISHED }
            NativeGoEngine(9).use { verifier ->
                assertEquals(RestoreResult.Restored, verifier.restore(checkNotNull(repository.saved).copyBytes()))
                assertEquals(verifier.gameResult(), session.state.value.game.result)
                assertNotNull(verifier.finalScore())
            }
        } finally { session.closeAndJoin() }
    }

    @Test fun occupiedAiCandidateKeepsNativeAndCheckpointUnchanged() = runBlocking {
        val repository = Repository()
        val answers = Channel<GoAiSearchOutcome>(Channel.UNLIMITED)
        val session = GoAiMatchSession(this, repository, GoAiMatchConfig(9), GoAiSearchEngine { answers.receive() })
        try {
            await(session) { it.game.canOperate }
            session.resume()
            assertTrue(session.humanAction(GoAction.Place(BoardPosition(0, 0))))
            await(session) { it.game.canOperate && it.game.currentPlayer == GoPlayer.WHITE }
            val before = checkNotNull(repository.saved).copyBytes()
            answers.trySend(move(GoAction.Place(BoardPosition(0, 0))))
            await(session) { it.game.canOperate && it.game.error != null }
            assertArrayEquals(before, checkNotNull(repository.saved).copyBytes())
            assertEquals(GoStone.BLACK.code, session.state.value.game.stones[0])
            assertEquals(GoPlayer.WHITE, session.state.value.game.currentPlayer)
        } finally { session.closeAndJoin() }
    }

    private suspend fun await(session: GoAiMatchSession, predicate: (GoAiMatchUiState) -> Boolean) =
        withTimeout(15_000) { session.state.first(predicate) }
    private fun move(action: GoAction) = GoAiSearchOutcome.Move(GoAiSuggestion(action, 1, 1.0, 0.5, 0.0))
    private class Repository : GoAiMatchRepository {
        @Volatile var saved: GoAiSavedMatch? = null
        override suspend fun load() = saved
        override suspend fun save(match: GoAiSavedMatch) { saved = match }
    }
}
