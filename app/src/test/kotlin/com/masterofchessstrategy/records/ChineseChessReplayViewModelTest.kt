package com.masterofchessstrategy.records

import com.masterofchessstrategy.data.GameRecord
import com.masterofchessstrategy.data.GameRecordCategory
import com.masterofchessstrategy.data.GameRecordRepository
import com.masterofchessstrategy.data.LoadGameRecordsResult
import com.masterofchessstrategy.data.StoredGameMode
import com.masterofchessstrategy.engine.ActionResult
import com.masterofchessstrategy.engine.BoardMove
import com.masterofchessstrategy.engine.BoardPosition
import com.masterofchessstrategy.engine.ChineseChessPiece
import com.masterofchessstrategy.engine.ChineseChessPieceType
import com.masterofchessstrategy.engine.ChineseChessRuleEngine
import com.masterofchessstrategy.engine.ChineseChessSide
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.engine.PlayerId
import com.masterofchessstrategy.engine.RestoreResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChineseChessReplayViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun finalEngineHistoryIsReversedIntoForwardReplayFrames() = runTest(dispatcher) {
        val engine = ReplayEngine()
        val viewModel = ChineseChessReplayViewModel(
            recordId = "record",
            repository = SingleRecordRepository(record(moveCount = 1)),
            dispatcher = dispatcher,
            engineFactory = { engine },
        )
        testScheduler.advanceUntilIdle()

        assertFalse(viewModel.uiState.isLoading)
        assertFalse(viewModel.uiState.isIncompatible)
        assertEquals(2, viewModel.uiState.frames.size)
        assertEquals(
            ChineseChessPieceType.CHARIOT,
            viewModel.uiState.frames[0].board[9 * 9]?.type,
        )
        assertEquals(
            ChineseChessPieceType.CHARIOT,
            viewModel.uiState.frames[1].board[8 * 9]?.type,
        )
        assertTrue(engine.wasClosed)
    }

    @Test
    fun moveCountMismatchRejectsCorruptedReplay() = runTest(dispatcher) {
        val viewModel = ChineseChessReplayViewModel(
            recordId = "record",
            repository = SingleRecordRepository(record(moveCount = 2)),
            dispatcher = dispatcher,
            engineFactory = { ReplayEngine() },
        )
        testScheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.isIncompatible)
        assertTrue(viewModel.uiState.frames.isEmpty())
    }

    private fun record(moveCount: Int) = GameRecord(
        recordId = "record",
        gameType = GameType.CHINESE_CHESS,
        mode = StoredGameMode.HUMAN_VS_AI,
        difficulty = Difficulty.EASY,
        result = GameResult.FIRST_PLAYER_WIN,
        engineState = byteArrayOf(1),
        moveCount = moveCount,
        completedAtEpochMillis = 1L,
    )

    private class SingleRecordRepository(
        private val record: GameRecord,
    ) : GameRecordRepository {
        override suspend fun saveCompleted(record: GameRecord): Boolean = false

        override suspend fun list(category: GameRecordCategory): LoadGameRecordsResult =
            LoadGameRecordsResult.Loaded(listOf(record))

        override suspend fun load(recordId: String): GameRecord? =
            record.takeIf { it.recordId == recordId }

        override suspend fun setFavorite(recordId: String, favorite: Boolean): Boolean = false
    }

    private class ReplayEngine : ChineseChessRuleEngine {
        private val from = BoardPosition(0, 9)
        private val to = BoardPosition(0, 8)
        private var index = 1
        var wasClosed = false

        override val gameType = GameType.CHINESE_CHESS
        override val currentPlayer: PlayerId
            get() = PlayerId(if (index == 0) ChineseChessSide.RED.code else ChineseChessSide.BLACK.code)

        override fun reset() {
            index = 0
        }

        override fun apply(action: BoardMove): ActionResult = ActionResult.Rejected(
            com.masterofchessstrategy.engine.EngineError.UNSUPPORTED,
        )

        override fun undo(): Boolean {
            if (index == 0) return false
            index--
            return true
        }

        override fun legalActions(): List<BoardMove> = emptyList()

        override fun gameResult(): GameResult =
            if (index == 1) GameResult.FIRST_PLAYER_WIN else GameResult.ONGOING

        override fun serialize(): ByteArray = byteArrayOf(index.toByte())

        override fun restore(data: ByteArray): RestoreResult {
            index = 1
            return RestoreResult.Restored
        }

        override fun pieceAt(position: BoardPosition): ChineseChessPiece? =
            if (position == if (index == 0) from else to) {
                ChineseChessPiece(ChineseChessPieceType.CHARIOT, ChineseChessSide.RED)
            } else {
                null
            }

        override fun close() {
            wasClosed = true
        }
    }
}
