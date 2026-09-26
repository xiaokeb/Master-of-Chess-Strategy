package com.masterofchessstrategy.game

import androidx.lifecycle.ViewModelStore
import com.masterofchessstrategy.data.GameRecord
import com.masterofchessstrategy.data.StoredGameMode
import com.masterofchessstrategy.engine.ActionResult
import com.masterofchessstrategy.engine.BoardMove
import com.masterofchessstrategy.engine.BoardPosition
import com.masterofchessstrategy.engine.ChineseChessAiEngine
import com.masterofchessstrategy.engine.ChineseChessPiece
import com.masterofchessstrategy.engine.ChineseChessPieceType
import com.masterofchessstrategy.engine.ChineseChessSide
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.engine.PlayerId
import com.masterofchessstrategy.engine.RestoreResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChineseChessBackgroundRuntimeTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    @Before fun prepare() = Dispatchers.setMain(dispatcher)
    @After fun cleanup() { store.clear(); Dispatchers.resetMain() }

    @Test fun backgroundPolicyNeverSpeedsUpSlowViewingOrChangesForegroundIntervals() {
        assertEquals(250L, ChineseChessRuntimePolicy.clockInterval(250L, true))
        assertEquals(1_000L, ChineseChessRuntimePolicy.clockInterval(250L, false))
        assertEquals(3_000L, ChineseChessRuntimePolicy.clockInterval(3_000L, false))
        assertEquals(250L, ChineseChessRuntimePolicy.autoPlayDelay(250L, true))
        assertEquals(2_000L, ChineseChessRuntimePolicy.autoPlayDelay(250L, false))
        assertEquals(4_000L, ChineseChessRuntimePolicy.autoPlayDelay(4_000L, false))
    }

    @Test fun backgroundRefreshSlowsAndForegroundSynchronizesImmediatelyWithoutResettingClock() = runTest(dispatcher) {
        val game = keep(ChineseChessGameViewModel(
            initialTimeControlMinutes = 5, nowEpochMillis = { 1_000L + testScheduler.currentTime },
            engineFactory = { Engine() },
        ))
        game.setRuntimeForeground(false)
        runCurrent()
        advanceTimeBy(999L); runCurrent()
        assertEquals(300_000L, game.uiState.redRemainingMillis)
        advanceTimeBy(1L); runCurrent()
        assertEquals(299_000L, game.uiState.redRemainingMillis)
        advanceTimeBy(250L)
        game.setRuntimeForeground(true)
        assertEquals(298_750L, game.uiState.redRemainingMillis)
        repeat(3) { game.setRuntimeForeground(true) }
        runCurrent()
        advanceTimeBy(250L); runCurrent()
        assertEquals(298_500L, game.uiState.redRemainingMillis)
        assertEquals(300_000L, game.uiState.blackRemainingMillis)
    }

    @Test fun coarseTickStillExpiresAtExactDeadlineAndLeavesNoTerminalTicker() = runTest(dispatcher) {
        val records = mutableListOf<GameRecord>()
        val game = keep(ChineseChessGameViewModel(
            initialTimeControlMinutes = 5, nowEpochMillis = { 1_000L + testScheduler.currentTime },
            onGameRecorded = records::add, engineFactory = { Engine() },
        ))
        runCurrent()
        advanceTimeBy(125L)
        game.setRuntimeForeground(false) // Background refresh would otherwise miss the 5 min deadline by 125 ms.
        runCurrent()
        advanceTimeBy(299_874L); runCurrent()
        assertEquals(GameResult.ONGOING, game.uiState.result)
        advanceTimeBy(1L); runCurrent()
        assertEquals(GameResult.SECOND_PLAYER_WIN, game.uiState.result)
        assertEquals(0L, game.uiState.redRemainingMillis)
        advanceUntilIdle()
        assertEquals(300_000L, testScheduler.currentTime)
        assertEquals(1, records.size)
        game.restart()
        runCurrent()
        advanceTimeBy(1_000L); runCurrent()
        assertEquals(GameResult.ONGOING, game.uiState.result)
        assertEquals(299_000L, game.uiState.redRemainingMillis)
    }

    @Test fun returnAfterSuspensionExpiresOnceInsteadOfGrantingMoreThinkingTime() = runTest(dispatcher) {
        var wallTime = 1_000L
        val records = mutableListOf<GameRecord>()
        val game = keep(ChineseChessGameViewModel(
            initialTimeControlMinutes = 5, nowEpochMillis = { wallTime },
            onGameRecorded = records::add, engineFactory = { Engine() },
        ))
        game.setRuntimeForeground(false)
        wallTime += 305_000L // No timer ran while the process was not scheduled.
        game.setRuntimeForeground(true)
        repeat(3) { game.setRuntimeForeground(false); game.setRuntimeForeground(true) }
        advanceUntilIdle()
        assertEquals(GameResult.SECOND_PLAYER_WIN, game.uiState.result)
        assertEquals(1, records.size)
    }

    @Test fun backgroundAutoPlayUsesSameDifficultyAndReturnsToUserSpeedForFutureTurns() = runTest(dispatcher) {
        val engine = Engine(terminalAt = 2)
        val game = keep(ChineseChessGameViewModel(
            mode = StoredGameMode.AI_AUTO_PLAY, difficulty = Difficulty.HARD,
            aiDispatcher = dispatcher, engineFactory = { engine },
        ))
        game.setAutoPlaySpeed(4f)
        game.setRuntimeForeground(false)
        runCurrent()
        advanceTimeBy(1_999L); runCurrent()
        assertEquals(0, engine.searches.size)
        advanceTimeBy(1L); runCurrent()
        assertEquals(listOf(Difficulty.HARD), engine.searches)
        game.setRuntimeForeground(true)
        advanceUntilIdle() // The already-scheduled viewing delay is allowed to finish once.
        assertEquals(listOf(Difficulty.HARD, Difficulty.HARD), engine.searches)
        assertEquals(4f, game.uiState.autoPlaySpeed)
        game.restart()
        runCurrent()
        advanceTimeBy(249L); runCurrent()
        assertEquals(2, engine.searches.size)
        advanceTimeBy(1L); runCurrent()
        assertEquals(3, engine.searches.size)
        game.toggleAutoPlayPaused()
        advanceUntilIdle()
        assertTrue(game.uiState.isAutoPlayPaused)
        assertEquals(3, engine.searches.size)
    }

    @Test fun pausedTimedAutoPlayHasNoIdleTickerAndResumeRestartsItsDeadline() = runTest(dispatcher) {
        val engine = Engine(terminalAt = 2)
        val game = keep(ChineseChessGameViewModel(
            mode = StoredGameMode.AI_AUTO_PLAY, difficulty = Difficulty.EASY,
            initialTimeControlMinutes = 5, aiDispatcher = dispatcher,
            nowEpochMillis = { 1_000L + testScheduler.currentTime }, engineFactory = { engine },
        ))
        game.setRuntimeForeground(false)
        game.toggleAutoPlayPaused()
        advanceUntilIdle()
        val pausedAt = testScheduler.currentTime
        assertTrue(pausedAt <= 2_000L)
        assertTrue(engine.searches.isEmpty())
        advanceTimeBy(310_000L)
        game.setRuntimeForeground(true)
        assertEquals(300_000L, game.uiState.redRemainingMillis)
        assertEquals(GameResult.ONGOING, game.uiState.result)
        game.toggleAutoPlayPaused()
        runCurrent()
        advanceTimeBy(250L); runCurrent()
        assertEquals(299_750L, game.uiState.redRemainingMillis)
        assertEquals(GameResult.ONGOING, game.uiState.result)
    }

    @Test fun repeatedVisibilityChangesDoNotDuplicateHumanAiReply() = runTest(dispatcher) {
        val engine = Engine()
        val game = keep(ChineseChessGameViewModel(
            mode = StoredGameMode.HUMAN_VS_AI, difficulty = Difficulty.MEDIUM,
            aiDispatcher = dispatcher, engineFactory = { engine },
        ))
        game.onSquareTap(engine.red.from)
        game.onSquareTap(engine.red.to)
        repeat(5) { game.setRuntimeForeground(false); game.setRuntimeForeground(true) }
        assertTrue(game.uiState.isAiThinking)
        advanceUntilIdle()
        assertEquals(listOf(Difficulty.MEDIUM), engine.searches)
        assertEquals(2, engine.moves)
        assertTrue(game.uiState.isInteractionEnabled)
    }

    @Test fun clearedSessionCancelsClockAndPendingSearchAndIgnoresLateVisibility() = runTest(dispatcher) {
        val engine = Engine()
        val game = keep(ChineseChessGameViewModel(
            mode = StoredGameMode.AI_AUTO_PLAY, difficulty = Difficulty.EASY,
            initialTimeControlMinutes = 5, aiDispatcher = dispatcher,
            nowEpochMillis = { testScheduler.currentTime }, engineFactory = { engine },
        ))
        game.setRuntimeForeground(false)
        runCurrent()
        store.clear()
        game.setRuntimeForeground(true)
        advanceUntilIdle()
        assertFalse(game.isRuntimeForeground)
        assertEquals(1, engine.closeCalls)
        assertTrue(engine.searches.isEmpty())
        assertEquals(0L, testScheduler.currentTime)
    }

    private fun keep(game: ChineseChessGameViewModel) = game.also {
        assertTrue("Fixture must create a valid engine", it.uiState.isEngineAvailable)
        store.put("game", it)
    }

    /** Deterministic two-rook engine: scheduling tests must not depend on NNUE timing. */
    private class Engine(private val terminalAt: Int = Int.MAX_VALUE) : ChineseChessAiEngine {
        val red = BoardMove(BoardPosition(0, 9), BoardPosition(0, 8))
        private val black = BoardMove(BoardPosition(0, 0), BoardPosition(0, 1))
        var moves = 0
        var closeCalls = 0
        val searches = mutableListOf<Difficulty>()
        override val gameType = GameType.CHINESE_CHESS
        override val currentPlayer get() = PlayerId(moves % 2)
        override fun reset() { moves = 0 }
        override fun apply(action: BoardMove): ActionResult { moves++; return ActionResult.Accepted }
        override fun undo(): Boolean = (moves > 0).also { if (it) moves-- }
        override fun legalActions() = listOf(if (moves % 2 == 0) red else black)
        override fun chooseMove(difficulty: Difficulty): BoardMove { searches += difficulty; return legalActions().single() }
        override fun gameResult() = if (moves >= terminalAt) GameResult.DRAW else GameResult.ONGOING
        override fun serialize() = byteArrayOf(moves.toByte())
        override fun restore(data: ByteArray): RestoreResult { moves = data.single().toInt(); return RestoreResult.Restored }
        override fun pieceAt(position: BoardPosition): ChineseChessPiece? = when (position) {
            if (moves == 0) red.from else red.to -> ChineseChessPiece(ChineseChessPieceType.CHARIOT, ChineseChessSide.RED)
            if (moves < 2) black.from else black.to -> ChineseChessPiece(ChineseChessPieceType.CHARIOT, ChineseChessSide.BLACK)
            else -> null
        }
        override fun isInCheck(side: ChineseChessSide) = false
        override fun close() { closeCalls++ }
    }
}
