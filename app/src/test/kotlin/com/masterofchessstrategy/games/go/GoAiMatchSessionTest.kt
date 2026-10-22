package com.masterofchessstrategy.games.go

import com.masterofchessstrategy.engine.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** Session transaction doubles; Native legality and actual NN search are separate gates. */
@OptIn(ExperimentalCoroutinesApi::class)
class GoAiMatchSessionTest {
    @Test fun initialCheckpointMustCommitBeforeHumanInputOrSearch() = runTest {
        val repository = Repository().apply { gate = CompletableDeferred() }
        val search = Search()
        val session = session(repository, search)
        runCurrent()
        assertFalse(session.state.value.game.canOperate)
        assertFalse(session.humanAction(place(0)))
        session.resume(); runCurrent()
        assertTrue(search.requests.isEmpty())
        repository.gate!!.complete(Unit); runCurrent()
        assertTrue(session.state.value.humanCanPlay)
        assertTrue(session.state.value.paused)
        session.closeAndJoin()
    }

    @Test fun humanAndAiMovesAreSavedInOrderWithImmutableSearchPosition() = runTest {
        val repository = Repository()
        val search = Search().apply { answers.trySend(move(place(1))) }
        val session = session(repository, search)
        runCurrent(); session.resume()
        assertTrue(session.humanAction(place(0))); runCurrent()
        assertEquals(1, search.requests.size)
        assertArrayEquals(byteArrayOf(9, 0, 0), search.requests.single().position.copyBytes())
        assertEquals(GoPlayer.BLACK, session.state.value.game.currentPlayer)
        assertEquals(1, session.state.value.game.stones[0])
        assertEquals(2, session.state.value.game.stones[1])
        assertEquals(3, repository.history.size)
        assertArrayEquals(byteArrayOf(9, 0, 0, 1), repository.saved!!.copyBytes())
        val bytes = repository.saved!!.copyBytes(); bytes[2] = 5
        assertEquals(0, repository.saved!!.copyBytes()[2].toInt())
        session.closeAndJoin()
    }

    @Test fun failedAiSaveRetainsHumanCheckpointAndCanRetry() = runTest {
        val repository = Repository()
        val search = Search()
        val session = session(repository, search)
        runCurrent(); session.resume()
        session.humanAction(place(0)); runCurrent()
        val before = repository.saved!!.copyBytes()
        repository.fail = true
        search.answers.trySend(move(place(1))); runCurrent()
        assertArrayEquals(before, repository.saved!!.copyBytes())
        assertEquals(0, session.state.value.game.stones[1])
        assertNotNull(session.state.value.game.error)
        assertFalse(session.state.value.game.saving)
        repository.fail = false
        search.answers.trySend(move(place(1)))
        session.retrySearch(); runCurrent()
        assertEquals(2, session.state.value.game.stones[1])
        assertEquals(2, search.requests.size)
        session.closeAndJoin()
    }

    @Test fun illegalAiSuggestionIsRejectedByRulesWithoutSave() = runTest {
        val repository = Repository()
        val search = Search().apply { answers.trySend(move(place(0))) }
        val session = session(repository, search)
        runCurrent(); session.resume(); session.humanAction(place(0)); runCurrent()
        assertEquals(2, repository.history.size)
        assertEquals(GoPlayer.WHITE, session.state.value.game.currentPlayer)
        assertNotNull(session.state.value.game.error)
        session.closeAndJoin()
    }

    @Test fun savingRejectsExtraHumanInputAndPausePersistsAcrossCommit() = runTest {
        val repository = Repository()
        val search = Search()
        val session = session(repository, search)
        runCurrent(); session.resume()
        repository.gate = CompletableDeferred()
        session.humanAction(place(0)); runCurrent()
        assertTrue(session.state.value.game.saving)
        assertFalse(session.humanAction(place(2)))
        session.pause(); session.setSpeed(4f)
        repository.gate!!.complete(Unit); runCurrent()
        assertTrue(session.state.value.paused)
        assertEquals(4f, session.state.value.speed)
        assertEquals(2, repository.history.size)
        assertTrue(search.requests.isEmpty())
        session.closeAndJoin()
    }

    @Test fun pauseCancelsInFlightSearchAndNoStaleMoveIsCommitted() = runTest {
        val repository = Repository()
        val search = Search()
        val session = session(repository, search)
        runCurrent(); session.resume(); session.humanAction(place(0)); runCurrent()
        assertEquals(1, search.requests.size)
        session.pause(); runCurrent()
        assertTrue(search.requests.single().cancellation.isCancelled)
        assertEquals(2, repository.history.size)
        assertEquals(0, session.state.value.game.stones[1])
        session.closeAndJoin()
    }

    @Test fun repeatedResumeDoesNotRestartOrCancelAnActiveSearch() = runTest {
        val search = Search()
        val session = session(Repository(), search)
        try {
            runCurrent(); session.resume(); session.humanAction(place(0)); runCurrent()
            assertEquals(1, search.requests.size)
            repeat(3) { session.resume() }
            runCurrent()
            assertEquals(1, search.requests.size)
            assertFalse(search.requests.single().cancellation.isCancelled)
        } finally { session.closeAndJoin() }
    }

    @Test fun automaticDoublePassStopsForAgreementWithoutInventingResult() = runTest {
        val repository = Repository()
        val search = Search().apply { answers.trySend(move(GoAction.Pass)); answers.trySend(move(GoAction.Pass)) }
        val session = session(repository, search, GoAiMatchConfig(9, humanPlayer = null))
        runCurrent(); session.setSpeed(4f); session.resume(); runCurrent()
        advanceTimeBy(249); runCurrent()
        assertTrue(search.requests.isEmpty())
        advanceTimeBy(1); runCurrent()
        assertEquals(1, search.requests.size)
        advanceTimeBy(250); runCurrent()
        assertEquals(GoPhase.SCORING, session.state.value.game.phase)
        assertEquals(GameResult.ONGOING, session.state.value.game.result)
        assertTrue(session.state.value.paused)
        assertTrue(session.state.value.canReviewScore)
        advanceTimeBy(10_000); runCurrent()
        assertEquals(2, search.requests.size)
        assertTrue(session.humanAction(GoAction.ConfirmScore(GoPlayer.BLACK, 1))); runCurrent()
        assertEquals(GameResult.ONGOING, session.state.value.game.result)
        assertTrue(session.humanAction(GoAction.ConfirmScore(GoPlayer.WHITE, 1))); runCurrent()
        assertEquals(GoPhase.FINISHED, session.state.value.game.phase)
        assertEquals(GameResult.FIRST_PLAYER_WIN, session.state.value.game.result)
        session.closeAndJoin()
    }

    @Test fun undoRestoresHumanTurnAndDurableDifficultyAllowance() = runTest {
        val repository = Repository()
        val search = Search().apply { answers.trySend(move(place(1))) }
        val config = GoAiMatchConfig(9, difficulty = GoAiDifficulty.HARD)
        val session = session(repository, search, config)
        runCurrent(); session.resume(); session.humanAction(place(0)); runCurrent()
        assertTrue(session.undo()); runCurrent()
        assertEquals(GoPlayer.BLACK, session.state.value.game.currentPlayer)
        assertTrue(session.state.value.game.stones.all { it == 0 })
        assertEquals(1, repository.saved!!.undoUsed)
        assertFalse(session.undo())
        session.closeAndJoin()
        val restored = session(repository, Search())
        runCurrent()
        assertEquals(config, restored.state.value.config)
        assertEquals(1, restored.state.value.undoUsed)
        assertFalse(restored.undo())
        assertTrue(restored.state.value.paused)
        restored.closeAndJoin()
    }

    @Test fun corruptRestoreNeverExposesPlayableStateAndClosesCandidate() = runTest {
        val repository = Repository().apply { saved = GoAiSavedMatch("id", GoAiMatchConfig(13), byteArrayOf(9, 0)) }
        val engines = mutableListOf<Rules>()
        val session = session(repository, Search(), engines = engines)
        runCurrent()
        assertFalse(session.state.value.game.canOperate)
        assertFalse(session.state.value.game.loading)
        assertNotNull(session.state.value.game.error)
        assertTrue(engines.all { it.closed })
        session.closeAndJoin()
    }

    @Test fun closeDuringDurableSaveWaitsAndClosesAllCandidates() = runTest {
        val repository = Repository()
        val engines = mutableListOf<Rules>()
        val session = session(repository, Search(), engines = engines)
        runCurrent()
        repository.gate = CompletableDeferred()
        session.humanAction(place(0)); runCurrent()
        val closing = async { session.closeAndJoin() }; runCurrent()
        assertFalse(closing.isCompleted)
        repository.gate!!.complete(Unit); runCurrent()
        closing.await()
        assertArrayEquals(byteArrayOf(9, 0, 0), repository.saved!!.copyBytes())
        assertTrue(engines.all { it.closed })
        assertTrue(session.state.value.closed)
        assertFalse(session.humanAction(place(1)))
        session.closeAndJoin()
    }

    @Test fun restoredAutoStartsPausedAndDoesNotSearchUntilResume() = runTest {
        val repository = Repository().apply {
            saved = GoAiSavedMatch("fixed-id", GoAiMatchConfig(9, humanPlayer = null, difficulty = GoAiDifficulty.MASTER), byteArrayOf(9, 0, 0))
        }
        val search = Search()
        val session = session(repository, search)
        runCurrent(); advanceTimeBy(10_000); runCurrent()
        assertTrue(search.requests.isEmpty())
        assertFalse(session.humanAction(place(2)))
        assertFalse(session.undo())
        session.resume(); runCurrent(); advanceTimeBy(1_000); runCurrent()
        assertEquals(GoAiDifficulty.MASTER.budget, search.requests.single().budget)
        assertArrayEquals(byteArrayOf(9, 0, 0), search.requests.single().position.copyBytes())
        session.closeAndJoin()
    }

    @Test fun synchronousRetirementSealsInputWhileAnAlreadyEnteredCheckpointDrains() = runTest {
        val repository = Repository()
        val search = Search()
        val engines = mutableListOf<Rules>()
        val session = session(repository, search, engines = engines)
        runCurrent()
        repository.gate = CompletableDeferred()
        assertTrue(session.humanAction(place(0))); runCurrent()
        session.beginRetirement(); session.beginRetirement()
        assertTrue(session.state.value.closed)
        assertFalse(session.state.value.game.sessionReady)
        assertFalse(session.humanAction(place(1)))
        assertFalse(session.undo()); assertFalse(session.requestHint()); assertFalse(session.restart())
        assertFalse(session.continueAutomatically()); assertFalse(session.retryAutoContinuation())
        assertFalse(session.setAutomatic(true)); assertFalse(session.configureAutoContinuation(true, 3))
        session.resume(); session.retrySearch(); session.pause(); session.setSpeed(Float.NaN)
        val closing = async { session.closeAndJoin() }; runCurrent()
        assertFalse(closing.isCompleted)
        repository.gate!!.complete(Unit); runCurrent()
        closing.await()
        assertArrayEquals(byteArrayOf(9, 0, 0), repository.saved!!.copyBytes())
        assertTrue(engines.all { it.closed })
        assertTrue(search.requests.isEmpty())
        assertTrue(session.state.value.closed)
        session.closeAndJoin()
    }

    @Test fun retirementDuringTerminalReservationCannotArchiveOrCreateTheNextAutomaticGame() = runTest {
        val terminal = GoAiSavedMatch("retiring-series", GoAiMatchConfig(9, humanPlayer = null),
            byteArrayOf(9, 0, 81, 81, 82, 83), undoUsed = 2, hintUsed = 3,
            autoContinueEnabled = true, autoContinueMaxGames = 3)
        val repository = Repository().apply { saved = terminal; gate = CompletableDeferred() }
        val engines = mutableListOf<Rules>()
        val archives = mutableListOf<GoAiSavedMatch>()
        val session = GoAiMatchSession(this, repository, terminal.config, Search(),
            StandardTestDispatcher(testScheduler), completedSink = GoAiCompletedMatchSink(archives::add),
            engineFactory = { size, _, handicap -> Rules(size, handicap).also(engines::add) })
        runCurrent() // The initial terminal is loaded; continuation is waiting inside reservation save.
        assertTrue(session.state.value.game.saving)
        session.beginRetirement()
        val closing = async { session.closeAndJoin() }; runCurrent()
        assertFalse(closing.isCompleted)
        repository.gate!!.complete(Unit); runCurrent()
        closing.await()
        assertEquals("retiring-series", repository.saved!!.matchId)
        assertTrue(repository.saved!!.completionReserved)
        assertEquals(2, repository.saved!!.undoUsed); assertEquals(3, repository.saved!!.hintUsed)
        assertEquals(1, repository.saved!!.autoContinueGameNumber)
        assertEquals(1, repository.history.size)
        assertTrue(archives.isEmpty())
        assertTrue(engines.all { it.closed })
        assertTrue(session.state.value.closed)
    }

    @Test fun retirementDuringAnEnteredCompletionSinkDoesNotWriteAFreshCheckpointAfterItReturns() = runTest {
        val terminal = GoAiSavedMatch("retiring-sink", GoAiMatchConfig(9, humanPlayer = null),
            byteArrayOf(9, 0, 81, 81, 82, 83), autoContinueEnabled = true, autoContinueMaxGames = 3)
        val repository = Repository().apply { saved = terminal }
        val release = CompletableDeferred<Unit>()
        var sinkCalls = 0
        val session = GoAiMatchSession(this, repository, terminal.config, Search(),
            StandardTestDispatcher(testScheduler), completedSink = GoAiCompletedMatchSink {
                sinkCalls++; release.await()
            }, engineFactory = { size, _, handicap -> Rules(size, handicap) })
        runCurrent()
        assertEquals(1, sinkCalls)
        session.beginRetirement()
        val closing = async { session.closeAndJoin() }; runCurrent()
        assertFalse(closing.isCompleted)
        release.complete(Unit); runCurrent()
        closing.await()
        assertEquals("retiring-sink", repository.saved!!.matchId)
        assertTrue(repository.saved!!.completionReserved)
        assertEquals(1, repository.history.size)
        assertEquals(1, sinkCalls)
    }

    private fun TestScope.session(repository: Repository, search: Search,
        config: GoAiMatchConfig = GoAiMatchConfig(9), engines: MutableList<Rules> = mutableListOf()) =
        GoAiMatchSession(this, repository, config, search, StandardTestDispatcher(testScheduler)) { size, _, handicap ->
            Rules(size, handicap).also(engines::add)
        }
    private fun place(x: Int) = GoAction.Place(BoardPosition(x, 0))
    private fun move(action: GoAction) = GoAiSearchOutcome.Move(GoAiSuggestion(action, 1, 1.0, 0.5, 0.0))

    internal class Repository : GoAiMatchRepository {
        var saved: GoAiSavedMatch? = null
        val history = mutableListOf<GoAiSavedMatch>()
        var gate: CompletableDeferred<Unit>? = null
        var fail = false
        override suspend fun load() = saved
        override suspend fun save(match: GoAiSavedMatch) {
            gate?.await()
            if (fail) throw IOException("save failed")
            saved = match; history += match
        }
    }
    internal class Search : GoAiSearchEngine {
        val requests = mutableListOf<GoAiSearchRequest>()
        val answers = Channel<GoAiSearchOutcome>(Channel.UNLIMITED)
        override suspend fun search(request: GoAiSearchRequest): GoAiSearchOutcome {
            requests += request
            return answers.receive()
        }
    }
    internal class Rules(override val boardSize: Int, override var handicapStones: Int) : GoRecordEngine {
        private val events = mutableListOf<GoAction>()
        var closed = false
        override val gameType = GameType.GO
        override val blackKomiQuarters get() = if (handicapStones == 0) 15 else 0
        override val currentPlayer: PlayerId get() = PlayerId((if (handicapStones > 0) 1 else 0) xor (events.count { it is GoAction.Place || it == GoAction.Pass } % 2))
        override val phase: GoPhase get() = when {
            events.count { it is GoAction.ConfirmScore } == 2 -> GoPhase.FINISHED
            events.takeLast(2) == listOf(GoAction.Pass, GoAction.Pass) || events.lastOrNull() is GoAction.ConfirmScore -> GoPhase.SCORING
            else -> GoPhase.PLAYING
        }
        override fun apply(action: GoAction): ActionResult {
            if (action is GoAction.Place && stoneAt(action.point) != GoStone.EMPTY) return ActionResult.Rejected(EngineError.ILLEGAL_ACTION)
            events += action
            return ActionResult.Accepted
        }
        override fun undo(): Boolean = if (events.isEmpty()) false else { events.removeAt(events.lastIndex); true }
        override fun stoneAt(point: BoardPosition): GoStone {
            val index = events.indexOfFirst { it is GoAction.Place && it.point == point }
            if (index < 0) return GoStone.EMPTY
            val turn = events.take(index).count { it is GoAction.Place || it == GoAction.Pass }
            return if (turn % 2 == 0) GoStone.BLACK else GoStone.WHITE
        }
        override fun serialize() = byteArrayOf(boardSize.toByte(), handicapStones.toByte()) + events.map {
            when (it) {
                is GoAction.Place -> (it.point.y * boardSize + it.point.x).toByte()
                GoAction.Pass -> 81.toByte()
                is GoAction.ConfirmScore -> (82 + it.actor.code).toByte()
                else -> error("Unsupported transaction fixture")
            }
        }.toByteArray()
        override fun restore(data: ByteArray): RestoreResult {
            if (data.size < 2 || data[0].toInt() != boardSize) return RestoreResult.Rejected(EngineError.CORRUPTED_DATA)
            handicapStones = data[1].toInt()
            events.clear()
            data.drop(2).forEach { events += when (val code = it.toInt()) {
                81 -> GoAction.Pass
                82, 83 -> GoAction.ConfirmScore(GoPlayer.entries[code - 82], 1)
                else -> GoAction.Place(BoardPosition(code % boardSize, code / boardSize))
            } }
            return RestoreResult.Restored
        }
        override fun scoringState() = GoScoringState(phase, 1, events.any { it is GoAction.ConfirmScore && it.actor == GoPlayer.BLACK },
            events.any { it is GoAction.ConfirmScore && it.actor == GoPlayer.WHITE }, emptyList())
        override fun eventHistory() = events.toList()
        override fun gameResult() = if (phase == GoPhase.FINISHED) GameResult.FIRST_PLAYER_WIN else GameResult.ONGOING
        override fun reset() { events.clear() }
        override fun legalActions(): List<GoAction> = (0 until boardSize * boardSize).map {
            GoAction.Place(BoardPosition(it % boardSize, it / boardSize))
        }.filter { stoneAt(it.point) == GoStone.EMPTY } + GoAction.Pass
        override fun previewScore(): GoAreaScore? = null
        override fun finalScore(): GoAreaScore? = null
        override fun exportSgf() = "(;GM[1]SZ[$boardSize])"
        override fun restoreSgf(text: String) = RestoreResult.Rejected(EngineError.UNSUPPORTED)
        override fun close() { check(!closed); closed = true }
    }
}
