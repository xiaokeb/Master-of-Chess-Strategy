package com.masterofchessstrategy.game

import com.masterofchessstrategy.data.GameSessionRepository
import com.masterofchessstrategy.data.GameSessionSnapshot
import com.masterofchessstrategy.data.LoadGameSessionResult
import com.masterofchessstrategy.data.StoredGameMode
import com.masterofchessstrategy.engine.ActionResult
import com.masterofchessstrategy.engine.BoardMove
import com.masterofchessstrategy.engine.BoardPosition
import com.masterofchessstrategy.engine.ChineseChessPiece
import com.masterofchessstrategy.engine.ChineseChessPieceType
import com.masterofchessstrategy.engine.ChineseChessRuleEngine
import com.masterofchessstrategy.engine.ChineseChessSide
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.engine.PlayerId
import com.masterofchessstrategy.engine.RestoreResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChineseChessGamePersistenceTest {
    private val dispatcher = StandardTestDispatcher()
    private val from = BoardPosition(0, 9)
    private val to = BoardPosition(0, 8)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun compatibleSnapshotRestoresBeforeInteraction() = runTest(dispatcher) {
        val engine = PersistenceFakeEngine()
        val repository = FakeSessionRepository(
            loadResult = LoadGameSessionResult.Loaded(
                snapshot(byteArrayOf(4, 5, 6)),
            ),
        )

        val viewModel = ChineseChessGameViewModel(
            sessionRepository = repository,
            engineFactory = { engine },
        )
        assertTrue(viewModel.uiState.isRestoring)
        advanceUntilIdle()

        assertArrayEquals(byteArrayOf(4, 5, 6), engine.restoredBytes)
        assertFalse(viewModel.uiState.isRestoring)
        assertFalse(viewModel.uiState.canUndo)
        assertEquals(ChineseChessFeedback.GAME_RESTORED, viewModel.uiState.feedback)
    }

    @Test
    fun incompatibleSnapshotIsClearedAndFreshGameRemainsUsable() = runTest(dispatcher) {
        val engine = PersistenceFakeEngine()
        val repository = FakeSessionRepository(LoadGameSessionResult.Incompatible)

        val viewModel = ChineseChessGameViewModel(
            sessionRepository = repository,
            engineFactory = { engine },
        )
        advanceUntilIdle()

        assertEquals(1, repository.clearCalls)
        assertEquals(1, engine.resetCalls)
        assertTrue(viewModel.uiState.isInteractionEnabled)
        assertEquals(ChineseChessFeedback.RESTORE_REJECTED, viewModel.uiState.feedback)
    }

    @Test
    fun acceptedMoveIsSavedBeforeMoreInteractionIsEnabled() = runTest(dispatcher) {
        val engine = PersistenceFakeEngine()
        val repository = FakeSessionRepository(LoadGameSessionResult.NotFound)
        val viewModel = ChineseChessGameViewModel(
            sessionRepository = repository,
            nowEpochMillis = { 1234L },
            engineFactory = { engine },
        )
        advanceUntilIdle()

        viewModel.onSquareTap(from)
        viewModel.onSquareTap(to)
        assertTrue(viewModel.uiState.isPersisting)
        advanceUntilIdle()

        val saved = requireNotNull(repository.saved)
        assertEquals(StoredGameMode.LOCAL_TWO_PLAYER, saved.mode)
        assertEquals(1234L, saved.updatedAtEpochMillis)
        assertArrayEquals(engine.serializedBytes, saved.engineState)
        assertTrue(viewModel.uiState.isInteractionEnabled)
    }

    @Test
    fun saveFailureLeavesGamePlayableWithStableFeedback() = runTest(dispatcher) {
        val repository = FakeSessionRepository(
            loadResult = LoadGameSessionResult.NotFound,
            failSave = true,
        )
        val viewModel = ChineseChessGameViewModel(
            sessionRepository = repository,
            engineFactory = { PersistenceFakeEngine() },
        )
        advanceUntilIdle()

        viewModel.onSquareTap(from)
        viewModel.onSquareTap(to)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.isInteractionEnabled)
        assertEquals(ChineseChessFeedback.SAVE_FAILED, viewModel.uiState.feedback)
    }

    private fun snapshot(bytes: ByteArray) = GameSessionSnapshot(
        gameType = GameType.CHINESE_CHESS,
        mode = StoredGameMode.LOCAL_TWO_PLAYER,
        difficulty = null,
        engineState = bytes,
        updatedAtEpochMillis = 1L,
        sessionId = "restored-match",
    )

    private class FakeSessionRepository(
        private val loadResult: LoadGameSessionResult,
        private val failSave: Boolean = false,
    ) : GameSessionRepository {
        var saved: GameSessionSnapshot? = null
        var clearCalls = 0

        override suspend fun load(gameType: GameType): LoadGameSessionResult = loadResult

        override suspend fun save(snapshot: GameSessionSnapshot) {
            if (failSave) error("disk unavailable")
            saved = snapshot.defensiveCopy()
        }

        override suspend fun clear(gameType: GameType) {
            clearCalls++
        }
    }

    private inner class PersistenceFakeEngine : ChineseChessRuleEngine {
        override val gameType = GameType.CHINESE_CHESS
        private val pieces = mutableMapOf(
            from to ChineseChessPiece(ChineseChessPieceType.CHARIOT, ChineseChessSide.RED),
        )
        private var player = PlayerId(ChineseChessSide.RED.code)
        val serializedBytes = byteArrayOf(10, 11, 12)
        var restoredBytes: ByteArray? = null
        var resetCalls = 0

        override val currentPlayer: PlayerId
            get() = player

        override fun reset() {
            resetCalls++
            pieces.clear()
            pieces[from] = ChineseChessPiece(
                ChineseChessPieceType.CHARIOT,
                ChineseChessSide.RED,
            )
            player = PlayerId(ChineseChessSide.RED.code)
        }

        override fun apply(action: BoardMove): ActionResult {
            pieces[to] = pieces.remove(from) ?: return ActionResult.Rejected(
                com.masterofchessstrategy.engine.EngineError.ILLEGAL_ACTION,
            )
            player = PlayerId(ChineseChessSide.BLACK.code)
            return ActionResult.Accepted
        }

        override fun undo(): Boolean = false

        override fun legalActions(): List<BoardMove> = listOf(BoardMove(from, to))

        override fun gameResult(): GameResult = GameResult.ONGOING

        override fun serialize(): ByteArray = serializedBytes.copyOf()

        override fun restore(data: ByteArray): RestoreResult {
            restoredBytes = data.copyOf()
            return RestoreResult.Restored
        }

        override fun pieceAt(position: BoardPosition): ChineseChessPiece? = pieces[position]

        override fun close() = Unit
    }
}
