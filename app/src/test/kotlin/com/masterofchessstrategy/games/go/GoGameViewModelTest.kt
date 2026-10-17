package com.masterofchessstrategy.games.go

import com.masterofchessstrategy.engine.ActionResult
import com.masterofchessstrategy.engine.BoardPosition
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.engine.GoAction
import com.masterofchessstrategy.engine.GoAreaScore
import com.masterofchessstrategy.engine.GoPhase
import com.masterofchessstrategy.engine.GoRuleEngine
import com.masterofchessstrategy.engine.GoScoringState
import com.masterofchessstrategy.engine.GoStone
import com.masterofchessstrategy.engine.PlayerId
import com.masterofchessstrategy.engine.RestoreResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.toList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class GoGameViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun failedSaveBlocksNewActionsUntilRetrySucceeds() = runTest(dispatcher) {
        val repository = MemoryRepository()
        val engine = SessionDouble(9)
        val game = GoGameViewModel(repository, 9, engineFactory = { _, _, _ -> engine })
        advanceUntilIdle()
        repository.failSave = true
        game.play(0, 0)
        advanceUntilIdle()
        assertTrue(game.state.value.needsSave)
        assertFalse(game.state.value.canAct)
        assertEquals(1, game.state.value.stones[0])
        game.play(1, 0)
        advanceUntilIdle()
        assertEquals(1, engine.acceptedActions)
        repository.failSave = false
        game.retrySave()
        advanceUntilIdle()
        assertFalse(game.state.value.needsSave)
        assertTrue(game.state.value.canAct)
        assertEquals(1, repository.saved!!.engineState[0].toInt())
    }

    @Test fun initialPlayableStateIsPublishedOnlyAfterCheckpointCommits() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val repository = MemoryRepository().apply { saveGate = gate }
        val game = GoGameViewModel(repository, 9, engineFactory = { size, _, _ -> SessionDouble(size) })
        val observed = mutableListOf<GoGameUiState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { game.state.toList(observed) }
        runCurrent()
        assertTrue(game.state.value.saving)
        assertFalse(observed.any { it.canOperate })
        game.play(0, 0)
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(game.state.value.canOperate)
        assertEquals(0, game.state.value.stones[0])
        assertEquals(1, repository.saveCalls)
    }

    @Test fun corruptLoadDoesNotExposeAnEmptyPlayableGame() = runTest(dispatcher) {
        val repository = MemoryRepository().apply { failLoad = true }
        var created = false
        val game = GoGameViewModel(repository, 9, engineFactory = { size, _, _ ->
            created = true
            SessionDouble(size)
        })
        advanceUntilIdle()
        assertFalse(created)
        assertFalse(game.state.value.loading)
        assertFalse(game.state.value.sessionReady)
        assertFalse(game.state.value.canOperate)
        assertTrue(game.state.value.error != null)
    }

    @Test fun touchesDuringSaveAreNotQueuedForLaterMoves() = runTest(dispatcher) {
        val repository = MemoryRepository()
        val engine = SessionDouble(9)
        val game = GoGameViewModel(repository, 9, engineFactory = { _, _, _ -> engine })
        advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        repository.saveGate = gate
        game.play(0, 0)
        runCurrent()
        assertTrue(game.state.value.saving)
        game.play(1, 0)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, engine.acceptedActions)
        assertEquals(0, game.state.value.stones[1])
    }

    @Test fun finalScoreCanBeUndoneAndSavedAsPendingAgreement() = runTest(dispatcher) {
        val repository = MemoryRepository()
        val engine = SessionDouble(9).apply {
            phase = GoPhase.FINISHED
            result = GameResult.FIRST_PLAYER_WIN
        }
        val game = GoGameViewModel(repository, 9, engineFactory = { _, _, _ -> engine })
        advanceUntilIdle()
        assertFalse(game.state.value.canAct)
        assertTrue(game.state.value.canOperate)
        game.undo()
        advanceUntilIdle()
        assertEquals(GoPhase.SCORING, game.state.value.phase)
        assertEquals(GameResult.ONGOING, game.state.value.result)
        assertEquals(1, engine.undoCalls)
        assertEquals(2, repository.saveCalls)
    }

    @Test fun failedNewGameSaveKeepsPreviousSessionAndClosesCandidate() = runTest(dispatcher) {
        val repository = MemoryRepository()
        val engines = mutableListOf<SessionDouble>()
        val game = GoGameViewModel(repository, 9, engineFactory = { size, _, _ ->
            SessionDouble(size).also(engines::add)
        })
        advanceUntilIdle()
        val identity = repository.saved!!.identity
        repository.failSave = true
        game.newGame(13, 2)
        advanceUntilIdle()
        assertEquals(9, game.state.value.boardSize)
        assertFalse(engines[0].closed)
        assertTrue(engines[1].closed)
        assertEquals(9, repository.saved!!.boardSize)
        assertEquals(identity, repository.saved!!.identity)
        repository.failSave = false
        game.play(0, 0)
        advanceUntilIdle()
        assertEquals(identity, repository.saved!!.identity)
    }

    @Test fun ordinaryActionsAndSaveRetryKeepIdentity() = runTest(dispatcher) {
        val repository = MemoryRepository()
        val game = GoGameViewModel(repository, 9, engineFactory = { size, _, _ -> SessionDouble(size) })
        advanceUntilIdle()
        val identity = repository.saved!!.identity
        assertEquals(GoSessionOrigin.LOCAL, identity.origin)
        game.play(0, 0)
        advanceUntilIdle()
        game.toggleScoreMode()
        advanceUntilIdle()
        assertEquals(identity, repository.saved!!.identity)
        repository.failSave = true
        game.play(1, 0)
        advanceUntilIdle()
        assertTrue(game.state.value.needsSave)
        repository.failSave = false
        game.retrySave()
        advanceUntilIdle()
        assertEquals(identity, repository.saved!!.identity)
    }

    @Test fun restoredSourceAndIdentityAreNotPromotedOrRegenerated() = runTest(dispatcher) {
        for (source in listOf(GoSessionOrigin.LEGACY_UNKNOWN, GoSessionOrigin.SGF_IMPORT)) {
            val identity = GoSessionIdentity.create(source).copy(archiveRevision = 3)
            val repository = MemoryRepository().apply {
                saved = GoSavedSession(9, byteArrayOf(0), GoScoreDisplayMode.POINTS, identity = identity)
            }
            val game = GoGameViewModel(repository, 9, engineFactory = { size, _, _ -> SessionDouble(size) })
            advanceUntilIdle()
            assertEquals(0, repository.saveCalls)
            game.play(0, 0)
            advanceUntilIdle()
            assertEquals(identity, repository.saved!!.identity)
        }
    }

    @Test fun successfulNewGameUsesNewLocalIdentityOnlyAfterSave() = runTest(dispatcher) {
        val repository = MemoryRepository()
        val game = GoGameViewModel(repository, 9, engineFactory = { size, _, _ -> SessionDouble(size) })
        advanceUntilIdle()
        val previous = repository.saved!!.identity
        game.newGame(13, 2)
        advanceUntilIdle()
        assertTrue(previous.sessionId != repository.saved!!.identity.sessionId)
        assertEquals(GoSessionOrigin.LOCAL, repository.saved!!.identity.origin)
        assertEquals(0, repository.saved!!.identity.archiveRevision)
        game.play(0, 0)
        advanceUntilIdle()
        assertTrue(previous.sessionId != repository.saved!!.identity.sessionId)
    }

    @Test fun terminalUndoSaveFailureRetriesTheSameBranchExactlyOnce() = runTest(dispatcher) {
        val repository = MemoryRepository()
        val engine = SessionDouble(9).apply {
            phase = GoPhase.FINISHED
            result = GameResult.FIRST_PLAYER_WIN
        }
        val game = GoGameViewModel(repository, 9, engineFactory = { _, _, _ -> engine })
        advanceUntilIdle()
        val previous = repository.saved!!.identity
        repository.failSave = true
        game.undo()
        advanceUntilIdle()
        assertTrue(game.state.value.needsSave)
        assertEquals(previous, repository.saved!!.identity)
        game.undo()
        advanceUntilIdle()
        assertEquals(1, engine.undoCalls)
        repository.failSave = false
        game.retrySave()
        advanceUntilIdle()
        assertEquals(previous.afterTerminalUndo(), repository.saved!!.identity)
        game.undo()
        advanceUntilIdle()
        assertEquals(previous.afterTerminalUndo(), repository.saved!!.identity)
        engine.phase = GoPhase.FINISHED
        engine.result = GameResult.DRAW
        game.retrySave()
        advanceUntilIdle()
        assertEquals(previous.afterTerminalUndo(), repository.saved!!.identity)
        game.undo()
        advanceUntilIdle()
        assertEquals(previous.afterTerminalUndo().afterTerminalUndo(), repository.saved!!.identity)
    }

    @Test fun unsuccessfulTerminalUndoDoesNotCreateABranch() = runTest(dispatcher) {
        val repository = MemoryRepository()
        val engine = SessionDouble(9).apply {
            phase = GoPhase.FINISHED
            result = GameResult.DRAW
            undoSucceeds = false
        }
        val game = GoGameViewModel(repository, 9, engineFactory = { _, _, _ -> engine })
        advanceUntilIdle()
        val original = repository.saved!!.identity
        game.undo()
        advanceUntilIdle()
        assertEquals(original, repository.saved!!.identity)
        assertEquals(1, repository.saveCalls)
        assertEquals(GoPhase.FINISHED, game.state.value.phase)
    }

    @Test fun branchExhaustionRejectsBeforeMutatingNativeState() = runTest(dispatcher) {
        val identity = GoSessionIdentity.create().copy(archiveRevision = Int.MAX_VALUE)
        val repository = MemoryRepository().apply {
            saved = GoSavedSession(9, byteArrayOf(0), GoScoreDisplayMode.STONES, identity = identity)
        }
        val engine = SessionDouble(9).apply { phase = GoPhase.FINISHED; result = GameResult.DRAW }
        val game = GoGameViewModel(repository, 9, engineFactory = { _, _, _ -> engine })
        advanceUntilIdle()
        game.undo()
        advanceUntilIdle()
        assertEquals(0, engine.undoCalls)
        assertEquals(0, repository.saveCalls)
        assertEquals(identity, repository.saved!!.identity)
        assertTrue(game.state.value.error != null)
    }

    private class MemoryRepository : GoSessionRepository {
        var saved: GoSavedSession? = null
        var failSave = false
        var failLoad = false
        var saveCalls = 0
        var saveGate: CompletableDeferred<Unit>? = null
        override suspend fun load(): GoSavedSession? {
            if (failLoad) throw IOException("损坏存档")
            return saved
        }
        override suspend fun save(session: GoSavedSession) {
            saveGate?.await()
            if (failSave) throw IOException("写入失败")
            saveCalls++
            saved = session
        }
        override suspend fun clear() { saved = null }
    }

    /** Exercises session handling only; production rule behavior is tested natively. */
    private class SessionDouble(override val boardSize: Int) : GoRuleEngine {
        override val gameType = GameType.GO
        override val currentPlayer = PlayerId(0)
        override val blackKomiQuarters = 15
        override val handicapStones = 0
        override var phase = GoPhase.PLAYING
        var result = GameResult.ONGOING
        var acceptedActions = 0
        var undoCalls = 0
        var undoSucceeds = true
        var closed = false
        private val stones = MutableList(boardSize * boardSize) { GoStone.EMPTY }
        override fun apply(action: GoAction): ActionResult {
            acceptedActions++
            if (action is GoAction.Place) stones[action.point.y * boardSize + action.point.x] = GoStone.BLACK
            return ActionResult.Accepted
        }
        override fun undo(): Boolean {
            undoCalls++
            if (!undoSucceeds) return false
            phase = GoPhase.SCORING
            result = GameResult.ONGOING
            return true
        }
        override fun scoringState() = GoScoringState(phase, 1, false, false, emptyList())
        override fun stoneAt(point: BoardPosition) = stones[point.y * boardSize + point.x]
        override fun legalActions(): List<GoAction> = emptyList()
        override fun gameResult() = result
        override fun serialize() = byteArrayOf(acceptedActions.toByte())
        override fun restore(data: ByteArray) = RestoreResult.Restored
        override fun reset() = Unit
        override fun previewScore(): GoAreaScore? = null
        override fun finalScore(): GoAreaScore? = null
        override fun close() { closed = true }
    }
}
