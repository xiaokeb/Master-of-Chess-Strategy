package com.masterofchessstrategy.games.go

import com.masterofchessstrategy.engine.*
import com.masterofchessstrategy.games.go.data.admitGoRankedStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** Policy tests use disposable deterministic rules, not Native legality or playing-strength evidence. */
@OptIn(ExperimentalCoroutinesApi::class)
class GoAiPerMovePolicyTest {
    private val config = GoAiMatchConfig(9, perMoveLimitSeconds = 30)

    @Test fun moveAtTheDeadlineWritesTimeoutBeforeTheAttemptedPlacement() = runTest {
        var now = 0L
        val repository = GoAiMatchSessionTest.Repository()
        val session = session(repository, now = { now })
        try {
            runCurrent()
            now = 30_000
            assertTrue(session.humanAction(GoAction.Place(BoardPosition(0, 0))))
            runCurrent()
            val saved = checkNotNull(repository.saved)
            assertArrayEquals(byteArrayOf(9, 0, 84), saved.copyBytes())
            assertEquals(GoPhase.FINISHED, session.state.value.game.phase)
            assertEquals(GoPlayer.BLACK, session.state.value.game.timedOutBy)
            assertEquals(0L, saved.clock!!.blackMillis)
        } finally { session.closeAndJoin() }
    }

    @Test fun undoReturningToTheSameHumanActorDoesNotRefillTime() = runTest {
        var now = 0L
        val repository = GoAiMatchSessionTest.Repository().apply {
            saved = GoAiSavedMatch("undo", config, byteArrayOf(9, 0, 0, 1),
                clock = GoTurnClock.start(30_000, GoPlayer.BLACK, 0, GoClockMode.PER_MOVE))
        }
        val session = session(repository, now = { now })
        try {
            runCurrent()
            now = 5_000
            assertTrue(session.undo()); runCurrent()
            assertArrayEquals(byteArrayOf(9, 0), repository.saved!!.copyBytes())
            assertEquals(25_000L, repository.saved!!.clock!!.blackMillis)
            assertEquals(1, repository.saved!!.undoUsed)
        } finally { session.closeAndJoin() }
    }

    @Test fun aNewTurnRefillsAndDoublePassPausesTheDurableClock() = runTest {
        var now = 0L
        val repository = GoAiMatchSessionTest.Repository()
        val search = GoAiMatchSessionTest.Search()
        val session = session(repository, search = search, now = { now })
        try {
            runCurrent()
            session.resume(); runCurrent()
            now = 5_000
            assertTrue(session.humanAction(GoAction.Pass)); runCurrent()
            assertEquals(GoPlayer.WHITE, repository.saved!!.clock!!.active)
            assertEquals(30_000L, repository.saved!!.clock!!.whiteMillis)
            now = 9_000
            search.answers.trySend(GoAiSearchOutcome.Move(GoAiSuggestion(GoAction.Pass, 1, 1.0, 0.5, 0.0)))
            runCurrent()
            assertEquals(GoPhase.SCORING, session.state.value.game.phase)
            assertNull(repository.saved!!.clock!!.active)
            assertEquals(26_000L, repository.saved!!.clock!!.whiteMillis)
            now = 900_000
            assertEquals(26_000L, repository.saved!!.clock!!.remaining(GoPlayer.WHITE, now))
        } finally { session.closeAndJoin() }
    }

    @Test fun restoredAutomaticSeriesPreservesModeAndRefillsTheNextGame() = runTest {
        val automatic = config.copy(humanPlayer = null)
        val repository = GoAiMatchSessionTest.Repository().apply {
            saved = GoAiSavedMatch("per-series", automatic, byteArrayOf(9, 0, 81, 81, 82, 83),
                autoContinueEnabled = true, autoContinueMaxGames = 2,
                clock = GoTurnClock(30_000, 20_000, 25_000, null, 0, GoClockMode.PER_MOVE))
        }
        val archived = mutableListOf<GoAiSavedMatch>()
        val session = session(repository, sink = GoAiCompletedMatchSink(archived::add), now = { 10L })
        try {
            runCurrent()
            assertEquals("per-series", archived.single().matchId)
            assertEquals(automatic, archived.single().config)
            val next = checkNotNull(repository.saved)
            assertNotEquals("per-series", next.matchId)
            assertEquals(automatic, next.config)
            assertEquals(2, next.autoContinueGameNumber)
            assertEquals(GoClockMode.PER_MOVE, next.clock!!.mode)
            assertEquals(30_000L, next.clock!!.blackMillis)
        } finally { session.closeAndJoin() }
    }

    @Test fun restartReplacementAndControlChangesPreserveThePerMoveSpecification() = runTest {
        val repository = GoAiMatchSessionTest.Repository()
        val session = session(repository, now = { 0L })
        try {
            runCurrent()
            assertTrue(session.setAutomatic(true)); runCurrent()
            assertEquals(config.perMoveLimitSeconds, repository.saved!!.config.perMoveLimitSeconds)
            assertEquals(GoClockMode.PER_MOVE, repository.saved!!.clock!!.mode)
            assertTrue(session.setAutomatic(false)); runCurrent()
            val opening = GoAiOpening.fromSnapshot(byteArrayOf(13, 5, 0, 1)) { size, _, handicap ->
                GoAiMatchSessionTest.Rules(size, handicap)
            }
            assertTrue(session.replaceOpening(opening)); runCurrent()
            assertEquals(30, repository.saved!!.config.perMoveLimitSeconds)
            assertEquals(30_000L, repository.saved!!.clock!!.limitMillis)
            val replaced = repository.saved!!.matchId
            assertTrue(session.restart()); runCurrent()
            assertNotEquals(replaced, repository.saved!!.matchId)
            assertEquals(GoClockMode.PER_MOVE, repository.saved!!.clock!!.mode)
            assertArrayEquals(opening.copyBytes(), repository.saved!!.copyBytes())
        } finally { session.closeAndJoin() }
    }

    @Test fun perMoveGameCannotObtainAStandardRankedCreationFact() {
        val saved = GoAiSavedMatch("9d18308c-c5fe-4660-8118-b2d740d7519a", config, byteArrayOf(9, 0),
            clock = GoTurnClock.start(30_000, GoPlayer.BLACK, 0, GoClockMode.PER_MOVE))
        assertThrows(IllegalArgumentException::class.java) {
            admitGoRankedStart(saved, 0) { size, _, handicap -> GoAiMatchSessionTest.Rules(size, handicap) }
        }
    }

    private fun TestScope.session(repository: GoAiMatchSessionTest.Repository,
        initial: GoAiMatchConfig = config, search: GoAiSearchEngine = GoAiMatchSessionTest.Search(),
        sink: GoAiCompletedMatchSink? = null, now: () -> Long) = GoAiMatchSession(this, repository, initial,
        search, StandardTestDispatcher(testScheduler), completedSink = sink, nowMillis = now) { size, _, handicap ->
        GoAiMatchSessionTest.Rules(size, handicap)
    }
}
