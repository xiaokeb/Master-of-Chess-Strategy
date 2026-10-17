package com.masterofchessstrategy.games.go

import com.masterofchessstrategy.engine.*
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException

/** Tests the durable replacement transaction; native SGF rules have separate JNI tests. */
@OptIn(ExperimentalCoroutinesApi::class)
class GoGameRecordViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = MemoryRepository()
    private val engines = mutableListOf<RecordDouble>()
    private fun game() = GoGameViewModel(repository, 9,
        engineFactory = { size, _, _ -> RecordDouble(size).also(engines::add) },
        recordDispatcher = dispatcher)

    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { Dispatchers.resetMain() }

    @Test fun savesImportedCandidateBeforeReplacingAndClosesRejectedSizes() = runTest(dispatcher) {
        val game = game()
        advanceUntilIdle()
        val originalIdentity = repository.saved!!.identity
        repository.beforeSave = {
            assertFalse(engines[0].closed)
            assertEquals(9, game.state.value.boardSize)
        }
        game.importSgf("(;SZ[13];B[aa])")
        advanceUntilIdle()
        assertEquals(13, game.state.value.boardSize)
        assertEquals(13, repository.saved!!.boardSize)
        assertEquals(0, game.state.value.lastMoveIndex)
        assertEquals(0, repository.saved!!.lastMoveIndex)
        assertTrue(engines[0].closed)
        assertTrue(engines[1].closed)
        assertFalse(engines[2].closed)
        assertTrue(game.state.value.canAct)
        val importedIdentity = repository.saved!!.identity
        assertNotEquals(originalIdentity.sessionId, importedIdentity.sessionId)
        assertEquals(GoSessionOrigin.SGF_IMPORT, importedIdentity.origin)
        repository.beforeSave = null
        game.play(1, 0)
        advanceUntilIdle()
        game.toggleScoreMode()
        advanceUntilIdle()
        assertEquals(importedIdentity, repository.saved!!.identity)
    }

    @Test fun invalidSgfClosesEveryCandidateAndPreservesCurrentSession() = runTest(dispatcher) {
        val game = game()
        advanceUntilIdle()
        game.play(0, 1)
        advanceUntilIdle()
        val saved = repository.saved!!
        game.importSgf("(;SZ[11];B[aa])")
        advanceUntilIdle()
        assertEquals(9, game.state.value.boardSize)
        assertEquals(9, game.state.value.lastMoveIndex)
        assertSame(saved, repository.saved)
        assertFalse(engines[0].closed)
        assertEquals(4, engines.size)
        assertTrue(engines.drop(1).all { it.closed })
        assertNotNull(game.state.value.error)
        assertFalse(game.state.value.importing)
    }

    @Test fun failedCandidateSavePreservesOldBoardAndDurableRecord() = runTest(dispatcher) {
        val game = game()
        advanceUntilIdle()
        game.play(0, 1)
        advanceUntilIdle()
        val saved = repository.saved!!
        repository.failSave = true
        game.importSgf("(;SZ[19];B[aa])")
        advanceUntilIdle()
        assertSame(saved, repository.saved)
        assertEquals(9, game.state.value.boardSize)
        assertEquals(1, game.state.value.stones[9])
        assertEquals(9, game.state.value.lastMoveIndex)
        assertFalse(engines[0].closed)
        assertTrue(engines.drop(1).all { it.closed })
        assertFalse(game.state.value.needsSave)
        assertTrue(game.state.value.canAct)
        repository.failSave = false
        game.play(1, 1)
        advanceUntilIdle()
        assertEquals(saved.identity, repository.saved!!.identity)
    }

    @Test fun operationsDuringCandidateSaveAreDiscarded() = runTest(dispatcher) {
        val game = game()
        advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        repository.saveGate = gate
        game.importSgf("(;SZ[13];B[aa])")
        runCurrent()
        assertTrue(game.state.value.importing)
        assertFalse(game.state.value.canOperate)
        game.play(1, 1)
        game.undo()
        game.newGame(19, 0)
        game.importSgf("(;SZ[19];B[aa])")
        game.retrySave()
        try { game.exportSgf(); fail("busy export should fail") } catch (_: IllegalStateException) { }
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(3, engines.size)
        assertEquals(13, game.state.value.boardSize)
        assertEquals(1, engines.last().actions.size)
        assertEquals(2, repository.saves)
    }

    @Test fun exportDoesNotMutateSaveOrRewardAndUndoRestoresPreviousMarker() = runTest(dispatcher) {
        val game = game()
        advanceUntilIdle()
        game.play(0, 1)
        advanceUntilIdle()
        game.play(1, 1)
        advanceUntilIdle()
        val before = repository.saved!!
        assertEquals("(;SZ[9]C[2])", game.exportSgf())
        assertSame(before, repository.saved)
        game.undo()
        advanceUntilIdle()
        assertEquals(9, game.state.value.lastMoveIndex)
        assertEquals(9, repository.saved!!.lastMoveIndex)
    }

    @Test fun oversizedUnicodeIsRejectedBeforeCreatingCandidate() = runTest(dispatcher) {
        val game = game()
        advanceUntilIdle()
        game.importSgf("棋".repeat(333_334))
        advanceUntilIdle()
        assertEquals(1, engines.size)
        assertFalse(engines[0].closed)
        assertNotNull(game.state.value.error)
    }

    @Test fun closingPageDuringCandidateSaveFinishesCheckpointAndClosesBothSessions() = runTest(dispatcher) {
        val game = game()
        val store = ViewModelStore()
        store.put("record-test", game)
        advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        repository.saveGate = gate
        game.importSgf("(;SZ[13];B[aa])")
        runCurrent()
        assertTrue(game.state.value.importing)
        store.clear()
        assertTrue(engines[0].closed)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(13, repository.saved!!.boardSize)
        assertEquals(0, repository.saved!!.lastMoveIndex)
        assertEquals(GoSessionOrigin.SGF_IMPORT, repository.saved!!.identity.origin)
        assertTrue(engines.all { it.closed })
    }

    private class MemoryRepository : GoSessionRepository {
        var saved: GoSavedSession? = null
        var failSave = false
        var saveGate: CompletableDeferred<Unit>? = null
        var beforeSave: (() -> Unit)? = null
        var saves = 0
        override suspend fun load() = saved
        override suspend fun save(session: GoSavedSession) {
            beforeSave?.invoke()
            saveGate?.await()
            if (failSave) throw IOException("写盘失败")
            saved = session
            saves++
        }
        override suspend fun clear() { saved = null }
    }

    private class RecordDouble(override val boardSize: Int) : GoRecordEngine {
        var closed = false
        val actions = mutableListOf<GoAction>()
        override val gameType = GameType.GO
        override val currentPlayer = PlayerId(0)
        override val blackKomiQuarters = 15
        override val handicapStones = 0
        override val phase = GoPhase.PLAYING
        override fun exportSgf() = "(;SZ[$boardSize]C[${actions.size}])"
        override fun restoreSgf(text: String): RestoreResult {
            if (text != "(;SZ[$boardSize];B[aa])") return RestoreResult.Rejected(EngineError.INVALID_STATE)
            actions.add(GoAction.Place(BoardPosition(0, 0)))
            return RestoreResult.Restored
        }
        override fun eventHistory() = actions.toList()
        override fun apply(action: GoAction): ActionResult { actions.add(action); return ActionResult.Accepted }
        override fun undo(): Boolean = if (actions.isEmpty()) false else { actions.removeAt(actions.lastIndex); true }
        override fun stoneAt(point: BoardPosition) = if (actions.filterIsInstance<GoAction.Place>().any {
                it.point == point }) GoStone.BLACK else GoStone.EMPTY
        override fun scoringState() = GoScoringState(phase, 1, false, false, emptyList())
        override fun legalActions() = emptyList<GoAction>()
        override fun gameResult() = GameResult.ONGOING
        override fun serialize() = byteArrayOf(boardSize.toByte(), actions.size.toByte())
        override fun restore(data: ByteArray) = RestoreResult.Restored
        override fun reset() { actions.clear() }
        override fun previewScore(): GoAreaScore? = null
        override fun finalScore(): GoAreaScore? = null
        override fun close() { closed = true }
    }
}
