package com.masterofchessstrategy.games.go

import com.masterofchessstrategy.engine.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GoAiMatchHintTest {
    @Test fun simpleHighlightsAllLegalPointsAfterDurableSaveWithoutSearchingOrMoving() = runTest {
        val repository = GoAiMatchSessionTest.Repository()
        val search = GoAiMatchSessionTest.Search()
        val session = session(repository, search)
        try {
            runCurrent()
            val before = repository.saved!!.copyBytes()
            repository.gate = CompletableDeferred()
            assertTrue(session.requestHint()); runCurrent()
            assertTrue(session.state.value.game.saving)
            assertNull(session.state.value.hint)
            assertEquals(0, session.state.value.hintUsed)
            assertFalse(session.requestHint())
            assertFalse(session.humanAction(place(0)))
            repository.gate!!.complete(Unit); runCurrent()
            assertEquals((0..80).toSet(), session.state.value.hint!!.highlightedPoints)
            assertTrue(session.state.value.hint!!.allLegal)
            assertTrue(session.state.value.hint!!.passSuggested)
            assertTrue(search.requests.isEmpty())
            assertArrayEquals(before, repository.saved!!.copyBytes())
            assertEquals(1, repository.saved!!.hintUsed)
            assertEquals(GoPlayer.BLACK, session.state.value.game.currentPlayer)
        } finally { session.closeAndJoin() }
    }

    @Test fun mediumSuggestsWithoutPlayingAndStopsAfterThreePersistedHints() = runTest {
        val repository = GoAiMatchSessionTest.Repository()
        val search = GoAiMatchSessionTest.Search()
        val session = session(repository, search, GoAiDifficulty.MEDIUM)
        try {
            runCurrent()
            val before = repository.saved!!.copyBytes()
            repeat(3) { count ->
                search.answers.trySend(move(place(count)))
                assertTrue(session.requestHint()); runCurrent()
                assertEquals(setOf(count), session.state.value.hint!!.highlightedPoints)
                assertFalse(session.state.value.hint!!.allLegal)
                assertEquals(count + 1, repository.saved!!.hintUsed)
                assertEquals(0, repository.saved!!.undoUsed)
                assertArrayEquals(before, repository.saved!!.copyBytes())
            }
            assertFalse(session.requestHint())
            assertEquals(3, search.requests.size)
        } finally { session.closeAndJoin() }
    }

    @Test fun hardPassSuggestionDoesNotPassOrStartScoringAndLimitIsOne() = runTest {
        val repository = GoAiMatchSessionTest.Repository()
        val search = GoAiMatchSessionTest.Search().apply { answers.trySend(move(GoAction.Pass)) }
        val session = session(repository, search, GoAiDifficulty.HARD)
        try {
            runCurrent(); assertTrue(session.requestHint()); runCurrent()
            assertTrue(session.state.value.hint!!.passSuggested)
            assertTrue(session.state.value.hint!!.highlightedPoints.isEmpty())
            assertEquals(GoPhase.PLAYING, session.state.value.game.phase)
            assertEquals(GoPlayer.BLACK, session.state.value.game.currentPlayer)
            assertFalse(session.requestHint())
            assertArrayEquals(byteArrayOf(9, 0), repository.saved!!.copyBytes())
        } finally { session.closeAndJoin() }
    }

    @Test fun illegalSuggestionDoesNotChangeCheckpointOrChargeQuota() = runTest {
        val repository = GoAiMatchSessionTest.Repository().apply {
            saved = GoAiSavedMatch("occupied", GoAiMatchConfig(9, difficulty = GoAiDifficulty.MEDIUM), byteArrayOf(9, 0, 0, 1))
        }
        val search = GoAiMatchSessionTest.Search().apply { answers.trySend(move(place(0))) }
        val session = session(repository, search)
        try {
            runCurrent(); val before = repository.saved!!.copyBytes()
            assertTrue(session.requestHint()); runCurrent()
            assertNotNull(session.state.value.game.error)
            assertNull(session.state.value.hint)
            assertEquals(0, session.state.value.hintUsed)
            assertTrue(repository.history.isEmpty())
            assertArrayEquals(before, repository.saved!!.copyBytes())
        } finally { session.closeAndJoin() }
    }

    @Test fun failedHintSavePreservesAllowanceAndCanRetry() = runTest {
        val repository = GoAiMatchSessionTest.Repository()
        val search = GoAiMatchSessionTest.Search()
        val session = session(repository, search)
        try {
            runCurrent(); repository.fail = true
            assertTrue(session.requestHint()); runCurrent()
            assertNull(session.state.value.hint)
            assertNotNull(session.state.value.game.error)
            assertEquals(0, repository.saved!!.hintUsed)
            repository.fail = false
            assertTrue(session.requestHint()); runCurrent()
            assertEquals(1, session.state.value.hintUsed)
            assertNotNull(session.state.value.hint)
        } finally { session.closeAndJoin() }
    }

    @Test fun humanMoveCancelsPendingHintAndNeverConsumesLateSuggestion() = runTest {
        val repository = GoAiMatchSessionTest.Repository()
        val search = GoAiMatchSessionTest.Search()
        val session = session(repository, search, GoAiDifficulty.MEDIUM)
        try {
            runCurrent(); assertTrue(session.requestHint()); runCurrent()
            assertTrue(session.state.value.hintThinking)
            assertTrue(session.humanAction(place(0))); runCurrent()
            assertTrue(search.requests.single().cancellation.isCancelled)
            search.answers.trySend(move(place(2))); runCurrent()
            assertNull(session.state.value.hint)
            assertEquals(0, repository.saved!!.hintUsed)
            assertEquals(1, session.state.value.game.stones[0])
            assertEquals(0, session.state.value.game.stones[2])
        } finally { session.closeAndJoin() }
    }

    @Test fun undoAndRestoringDoNotRefundUsedHintsOrRestoreStaleHighlight() = runTest {
        val repository = GoAiMatchSessionTest.Repository()
        val search = GoAiMatchSessionTest.Search().apply { answers.trySend(move(place(2))) }
        val session = session(repository, search, GoAiDifficulty.MEDIUM)
        runCurrent(); session.requestHint(); runCurrent()
        session.humanAction(place(0)); runCurrent(); session.undo(); runCurrent()
        assertEquals(1, repository.saved!!.hintUsed)
        assertEquals(1, repository.saved!!.undoUsed)
        assertNull(session.state.value.hint)
        session.closeAndJoin()
        val restored = session(repository, search)
        try {
            runCurrent()
            assertEquals(1, restored.state.value.hintUsed)
            assertEquals(1, restored.state.value.undoUsed)
            assertNull(restored.state.value.hint)
            assertTrue(restored.state.value.paused)
        } finally { restored.closeAndJoin() }
    }

    @Test fun pauseDuringHintSaveRetainsDurableChargeButHidesLateHighlight() = runTest {
        val repository = GoAiMatchSessionTest.Repository()
        val session = session(repository, GoAiMatchSessionTest.Search())
        try {
            runCurrent(); repository.gate = CompletableDeferred()
            session.requestHint(); runCurrent(); session.pause()
            repository.gate!!.complete(Unit); runCurrent()
            assertEquals(1, repository.saved!!.hintUsed)
            assertEquals(1, session.state.value.hintUsed)
            assertNull(session.state.value.hint)
            assertTrue(session.state.value.paused)
            assertFalse(session.state.value.game.saving)
        } finally { session.closeAndJoin() }
    }

    @Test fun masterAndAutomaticNeverRequestHintsOrSearch() = runTest {
        for (config in listOf(GoAiMatchConfig(9, difficulty = GoAiDifficulty.MASTER), GoAiMatchConfig(9, humanPlayer = null))) {
            val repository = GoAiMatchSessionTest.Repository()
            val search = GoAiMatchSessionTest.Search()
            val session = GoAiMatchSession(this, repository, config, search, StandardTestDispatcher(testScheduler)) { size, _, handicap ->
                GoAiMatchSessionTest.Rules(size, handicap)
            }
            try {
                runCurrent(); assertFalse(session.requestHint()); runCurrent()
                assertTrue(search.requests.isEmpty())
                assertEquals(1, repository.history.size)
            } finally { session.closeAndJoin() }
        }
    }

    @Test fun closingWaitsForHintWriteAndClosesValidationCandidates() = runTest {
        val repository = GoAiMatchSessionTest.Repository()
        val engines = mutableListOf<GoAiMatchSessionTest.Rules>()
        val session = GoAiMatchSession(this, repository, GoAiMatchConfig(9), GoAiMatchSessionTest.Search(),
            StandardTestDispatcher(testScheduler)) { size, _, handicap -> GoAiMatchSessionTest.Rules(size, handicap).also(engines::add) }
        runCurrent(); repository.gate = CompletableDeferred()
        session.requestHint(); runCurrent()
        val closing = async { session.closeAndJoin() }; runCurrent()
        assertFalse(closing.isCompleted)
        repository.gate!!.complete(Unit); runCurrent(); closing.await()
        assertEquals(1, repository.saved!!.hintUsed)
        assertArrayEquals(byteArrayOf(9, 0), repository.saved!!.copyBytes())
        assertTrue(engines.all { it.closed })
        assertTrue(session.state.value.closed)
        assertNull(session.state.value.hint)
    }

    private fun TestScope.session(repository: GoAiMatchSessionTest.Repository, search: GoAiMatchSessionTest.Search,
        difficulty: GoAiDifficulty = GoAiDifficulty.SIMPLE) =
        GoAiMatchSession(this, repository, GoAiMatchConfig(9, difficulty = difficulty), search, StandardTestDispatcher(testScheduler)) { size, _, handicap ->
            GoAiMatchSessionTest.Rules(size, handicap)
        }
    private fun place(x: Int) = GoAction.Place(BoardPosition(x, 0))
    private fun move(action: GoAction) = GoAiSearchOutcome.Move(GoAiSuggestion(action, 1, 1.0, 0.5, 0.0))
}
