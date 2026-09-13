package com.masterofchessstrategy.game

import com.masterofchessstrategy.data.StoredGameMode
import com.masterofchessstrategy.data.GameSessionRepository
import com.masterofchessstrategy.data.GameSessionSnapshot
import com.masterofchessstrategy.data.LoadGameSessionResult
import com.masterofchessstrategy.engine.ActionResult
import com.masterofchessstrategy.engine.BoardMove
import com.masterofchessstrategy.engine.BoardPosition
import com.masterofchessstrategy.engine.ChineseChessAiEngine
import com.masterofchessstrategy.engine.ChineseChessPiece
import com.masterofchessstrategy.engine.ChineseChessPieceType
import com.masterofchessstrategy.engine.ChineseChessSide
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.EngineError
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChineseChessAiGameViewModelTest {
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
    fun easyAiRepliesOffMainFlowAndUndoRestoresWholeTurn() = runTest(dispatcher) {
        val engine = FakeAiEngine()
        val repository = RecordingSessionRepository()
        val viewModel = ChineseChessGameViewModel(
            sessionRepository = repository,
            mode = StoredGameMode.HUMAN_VS_AI,
            difficulty = Difficulty.EASY,
            aiDispatcher = dispatcher,
            engineFactory = { engine },
        )
        advanceUntilIdle()

        viewModel.onSquareTap(engine.redFrom)
        viewModel.onSquareTap(engine.redTo)

        assertTrue(viewModel.uiState.isAiThinking)
        assertFalse(viewModel.uiState.isInteractionEnabled)
        advanceUntilIdle()

        assertEquals(1, engine.chooseCalls)
        assertEquals(ChineseChessSide.RED, viewModel.uiState.currentSide)
        assertNull(viewModel.uiState.pieceAt(engine.blackFrom))
        assertEquals(
            ChineseChessSide.BLACK,
            viewModel.uiState.pieceAt(engine.blackTo)?.side,
        )
        assertEquals(ChineseChessFeedback.AI_MOVED, viewModel.uiState.feedback)
        assertEquals(3, repository.saved.size)
        assertEquals(StoredGameMode.HUMAN_VS_AI, repository.saved.last().mode)
        assertEquals(Difficulty.EASY, repository.saved.last().difficulty)
        assertEquals(2, repository.saved.last().engineState.single().toInt())

        viewModel.undo()
        advanceUntilIdle()

        assertEquals(2, engine.undoCalls)
        assertEquals(ChineseChessSide.RED, viewModel.uiState.currentSide)
        assertEquals(ChineseChessSide.RED, viewModel.uiState.pieceAt(engine.redFrom)?.side)
        assertEquals(ChineseChessSide.BLACK, viewModel.uiState.pieceAt(engine.blackFrom)?.side)
        assertEquals(1, repository.saved.last().undoUseCount)
        assertEquals(0, repository.saved.last().acceptedMoveCount)
    }

    @Test
    fun terminalAiMatchEmitsOneStableSettlementAndLocksUndo() = runTest(dispatcher) {
        val engine = FakeAiEngine(
            humanMoveResult = GameResult.FIRST_PLAYER_WIN,
        )
        val outcomes = mutableListOf<com.masterofchessstrategy.data.MatchOutcome>()
        val viewModel = ChineseChessGameViewModel(
            mode = StoredGameMode.HUMAN_VS_AI,
            difficulty = Difficulty.EASY,
            aiDispatcher = dispatcher,
            matchIdFactory = { "match-final" },
            onMatchFinished = outcomes::add,
            engineFactory = { engine },
        )

        viewModel.onSquareTap(engine.redFrom)
        viewModel.onSquareTap(engine.redTo)
        viewModel.onSquareTap(engine.redTo)

        assertEquals(GameResult.FIRST_PLAYER_WIN, viewModel.uiState.result)
        assertFalse(viewModel.uiState.canUndo)
        assertEquals(1, outcomes.size)
        assertEquals("match-final", outcomes.single().matchId)
        assertTrue(outcomes.single().isWin)
    }

    @Test
    fun implementedHigherDifficultiesReachSearchUnchanged() = runTest(dispatcher) {
        listOf(
            Difficulty.MEDIUM,
            Difficulty.HARD,
            Difficulty.MASTER,
        ).forEach { difficulty ->
            val engine = FakeAiEngine()
            val viewModel = ChineseChessGameViewModel(
                mode = StoredGameMode.HUMAN_VS_AI,
                difficulty = difficulty,
                aiDispatcher = dispatcher,
                engineFactory = { engine },
            )

            viewModel.onSquareTap(engine.redFrom)
            viewModel.onSquareTap(engine.redTo)
            advanceUntilIdle()

            assertEquals(difficulty, engine.lastDifficulty)
            assertEquals(difficulty, viewModel.uiState.difficulty)
            assertEquals(ChineseChessSide.RED, viewModel.uiState.currentSide)
        }
    }

    @Test
    fun easyHintShowsEveryLegalOriginAndDestinationWithoutSearching() =
        runTest(dispatcher) {
            val engine = FakeAiEngine()
            val viewModel = ChineseChessGameViewModel(
                mode = StoredGameMode.HUMAN_VS_AI,
                difficulty = Difficulty.EASY,
                aiDispatcher = dispatcher,
                engineFactory = { engine },
            )

            viewModel.requestHint()

            assertEquals(setOf(engine.redFrom), viewModel.uiState.hintedOrigins)
            assertEquals(setOf(engine.redTo), viewModel.uiState.hintedDestinations)
            assertEquals(0, engine.chooseCalls)
            assertNull(viewModel.uiState.hintRemaining)
            assertTrue(viewModel.uiState.canRequestHint)
        }

    @Test
    fun mediumBestMoveHintConsumesThreePersistedUses() = runTest(dispatcher) {
        val engine = FakeAiEngine()
        val repository = RecordingSessionRepository()
        val viewModel = ChineseChessGameViewModel(
            sessionRepository = repository,
            mode = StoredGameMode.HUMAN_VS_AI,
            difficulty = Difficulty.MEDIUM,
            aiDispatcher = dispatcher,
            engineFactory = { engine },
        )
        advanceUntilIdle()

        repeat(3) {
            viewModel.requestHint()
            assertTrue(viewModel.uiState.isHintThinking)
            advanceUntilIdle()
        }

        assertEquals(3, engine.chooseCalls)
        assertEquals(setOf(engine.redFrom), viewModel.uiState.hintedOrigins)
        assertEquals(setOf(engine.redTo), viewModel.uiState.hintedDestinations)
        assertFalse(viewModel.uiState.canRequestHint)
        assertEquals(0, viewModel.uiState.hintRemaining)
        assertEquals(3, repository.saved.last().hintUseCount)

        viewModel.requestHint()
        assertEquals(3, engine.chooseCalls)
        assertEquals(ChineseChessFeedback.HINT_LIMIT_REACHED, viewModel.uiState.feedback)
    }

    @Test
    fun resignSettlesOnceAndPersistsTerminalOverride() = runTest(dispatcher) {
        val engine = FakeAiEngine()
        val repository = RecordingSessionRepository()
        val outcomes = mutableListOf<com.masterofchessstrategy.data.MatchOutcome>()
        val viewModel = ChineseChessGameViewModel(
            sessionRepository = repository,
            mode = StoredGameMode.HUMAN_VS_AI,
            difficulty = Difficulty.HARD,
            aiDispatcher = dispatcher,
            matchIdFactory = { "match-resign" },
            onMatchFinished = outcomes::add,
            engineFactory = { engine },
        )
        advanceUntilIdle()

        viewModel.resign()
        advanceUntilIdle()
        viewModel.resign()

        assertEquals(GameResult.SECOND_PLAYER_WIN, viewModel.uiState.result)
        assertFalse(viewModel.uiState.canUndo)
        assertEquals(1, outcomes.size)
        assertEquals(GameResult.SECOND_PLAYER_WIN, outcomes.single().result)
        assertEquals(
            GameResult.SECOND_PLAYER_WIN,
            repository.saved.last().resultOverride,
        )
    }

    @Test
    fun aiDrawOfferCannotForceAResultAndIsExplicitlyDeclined() = runTest(dispatcher) {
        val engine = FakeAiEngine()
        val viewModel = ChineseChessGameViewModel(
            mode = StoredGameMode.HUMAN_VS_AI,
            difficulty = Difficulty.EASY,
            aiDispatcher = dispatcher,
            engineFactory = { engine },
        )

        viewModel.offerOrAcceptDraw()
        viewModel.onSquareTap(engine.redFrom)
        viewModel.onSquareTap(engine.redTo)
        advanceUntilIdle()

        assertEquals(GameResult.ONGOING, viewModel.uiState.result)
        assertNull(viewModel.uiState.pendingDrawOfferSide)
        assertEquals(ChineseChessFeedback.DRAW_DECLINED, viewModel.uiState.feedback)
    }

    @Test
    fun restoreKeepsMoveAndAssistanceCounters() = runTest(dispatcher) {
        val snapshot = GameSessionSnapshot(
            gameType = GameType.CHINESE_CHESS,
            mode = StoredGameMode.HUMAN_VS_AI,
            difficulty = Difficulty.MEDIUM,
            engineState = byteArrayOf(2),
            updatedAtEpochMillis = 9L,
            sessionId = "match-restored-controls",
            acceptedMoveCount = 2,
            undoUseCount = 1,
            hintUseCount = 2,
        )
        val repository = RecordingSessionRepository(
            LoadGameSessionResult.Loaded(snapshot),
        )
        val viewModel = ChineseChessGameViewModel(
            sessionRepository = repository,
            mode = StoredGameMode.HUMAN_VS_AI,
            difficulty = Difficulty.MEDIUM,
            aiDispatcher = dispatcher,
            engineFactory = { FakeAiEngine() },
        )

        advanceUntilIdle()

        assertTrue(viewModel.uiState.canUndo)
        assertEquals(2, viewModel.uiState.undoRemaining)
        assertTrue(viewModel.uiState.canRequestHint)
        assertEquals(1, viewModel.uiState.hintRemaining)
        assertEquals(ChineseChessFeedback.GAME_RESTORED, viewModel.uiState.feedback)
    }

    @Test
    fun restoreChargesElapsedBackgroundTimeAndPersistsTimeout() = runTest(dispatcher) {
        var now = 121_000L
        val snapshot = GameSessionSnapshot(
            gameType = GameType.CHINESE_CHESS,
            mode = StoredGameMode.HUMAN_VS_AI,
            difficulty = Difficulty.EASY,
            engineState = byteArrayOf(0),
            updatedAtEpochMillis = 1_000L,
            sessionId = "match-restored-clock",
            timeControlMinutes = 5,
            redRemainingMillis = 300_000L,
            blackRemainingMillis = 300_000L,
            turnStartedAtEpochMillis = 1_000L,
        )
        val repository = RecordingSessionRepository(
            LoadGameSessionResult.Loaded(snapshot),
        )
        val outcomes = mutableListOf<com.masterofchessstrategy.data.MatchOutcome>()
        val viewModel = ChineseChessGameViewModel(
            sessionRepository = repository,
            nowEpochMillis = { now },
            mode = StoredGameMode.HUMAN_VS_AI,
            difficulty = Difficulty.EASY,
            aiDispatcher = dispatcher,
            clockTickIntervalMillis = null,
            onMatchFinished = outcomes::add,
            engineFactory = { FakeAiEngine() },
        )

        advanceUntilIdle()
        assertEquals(180_000L, viewModel.uiState.redRemainingMillis)

        now = 301_001L
        viewModel.synchronizeClock()
        viewModel.synchronizeClock()
        advanceUntilIdle()

        assertEquals(GameResult.SECOND_PLAYER_WIN, viewModel.uiState.result)
        assertEquals(0L, viewModel.uiState.redRemainingMillis)
        assertEquals(
            GameResult.SECOND_PLAYER_WIN,
            repository.saved.last().resultOverride,
        )
        assertEquals(1, outcomes.size)
        assertFalse(outcomes.single().isWin)
    }

    @Test
    fun autoPlayRunsBothSidesAndStopsAtConfiguredGameLimit() = runTest(dispatcher) {
        val engine = FakeAiEngine(
            humanMoveResult = GameResult.FIRST_PLAYER_WIN,
        )
        val outcomes = mutableListOf<com.masterofchessstrategy.data.MatchOutcome>()
        val viewModel = ChineseChessGameViewModel(
            mode = StoredGameMode.AI_AUTO_PLAY,
            difficulty = Difficulty.EASY,
            aiDispatcher = dispatcher,
            autoContinueEnabled = true,
            autoContinueGameLimit = 2,
            clockTickIntervalMillis = null,
            onMatchFinished = outcomes::add,
            engineFactory = { engine },
        )

        advanceUntilIdle()

        assertEquals(2, engine.chooseCalls)
        assertEquals(1, engine.resetCalls)
        assertEquals(2, viewModel.uiState.completedAutoGames)
        assertTrue(viewModel.uiState.isAutoPlayPaused)
        assertFalse(viewModel.uiState.isInteractionEnabled)
        assertTrue(outcomes.isEmpty())

        viewModel.restart()

        assertEquals(0, viewModel.uiState.completedAutoGames)
        assertFalse(viewModel.uiState.isAutoPlayPaused)
    }

    @Test
    fun restoredAutoPlayTerminalDoesNotIncrementCompletedGamesTwice() =
        runTest(dispatcher) {
            val snapshot = GameSessionSnapshot(
                gameType = GameType.CHINESE_CHESS,
                mode = StoredGameMode.AI_AUTO_PLAY,
                difficulty = Difficulty.EASY,
                engineState = byteArrayOf(1),
                updatedAtEpochMillis = 1L,
                sessionId = "match-restored-auto-terminal",
                resultOverride = GameResult.DRAW,
                autoPlayPaused = true,
                completedAutoGames = 3,
            )
            val viewModel = ChineseChessGameViewModel(
                sessionRepository = RecordingSessionRepository(
                    LoadGameSessionResult.Loaded(snapshot),
                ),
                mode = StoredGameMode.AI_AUTO_PLAY,
                difficulty = Difficulty.EASY,
                aiDispatcher = dispatcher,
                clockTickIntervalMillis = null,
                engineFactory = { FakeAiEngine() },
            )

            advanceUntilIdle()

            assertEquals(GameResult.DRAW, viewModel.uiState.result)
            assertEquals(3, viewModel.uiState.completedAutoGames)
            assertTrue(viewModel.uiState.isAutoPlayPaused)
        }

    @Test
    fun pausedAutoPlayFreezesBeforeSearchAndAcceptsContinuousSpeed() = runTest(dispatcher) {
        val engine = FakeAiEngine()
        val viewModel = ChineseChessGameViewModel(
            mode = StoredGameMode.AI_AUTO_PLAY,
            difficulty = Difficulty.EASY,
            aiDispatcher = dispatcher,
            clockTickIntervalMillis = null,
            engineFactory = { engine },
        )

        viewModel.toggleAutoPlayPaused()
        viewModel.setAutoPlaySpeed(1.75f)
        advanceUntilIdle()

        assertEquals(0, engine.chooseCalls)
        assertTrue(viewModel.uiState.isAutoPlayPaused)
        assertEquals(1.75f, viewModel.uiState.autoPlaySpeed)
    }

    private class RecordingSessionRepository(
        private val loadResult: LoadGameSessionResult = LoadGameSessionResult.NotFound,
    ) : GameSessionRepository {
        val saved = mutableListOf<GameSessionSnapshot>()

        override suspend fun load(gameType: GameType): LoadGameSessionResult =
            loadResult

        override suspend fun save(snapshot: GameSessionSnapshot) {
            saved += snapshot.defensiveCopy()
        }

        override suspend fun clear(gameType: GameType) = Unit
    }

    private class FakeAiEngine(
        private val humanMoveResult: GameResult = GameResult.ONGOING,
    ) : ChineseChessAiEngine {
        val redFrom = BoardPosition(0, 9)
        val redTo = BoardPosition(0, 8)
        val blackFrom = BoardPosition(0, 0)
        val blackTo = BoardPosition(0, 1)
        override val gameType = GameType.CHINESE_CHESS

        private data class Snapshot(
            val pieces: Map<BoardPosition, ChineseChessPiece>,
            val player: PlayerId,
        )

        private val history = mutableListOf<Snapshot>()
        private val pieces = mutableMapOf(
            redFrom to ChineseChessPiece(
                ChineseChessPieceType.CHARIOT,
                ChineseChessSide.RED,
            ),
            blackFrom to ChineseChessPiece(
                ChineseChessPieceType.CHARIOT,
                ChineseChessSide.BLACK,
            ),
        )
        private var player = PlayerId(ChineseChessSide.RED.code)
        var chooseCalls = 0
            private set
        var undoCalls = 0
            private set
        var lastDifficulty: Difficulty? = null
            private set
        var resetCalls = 0
            private set

        override val currentPlayer: PlayerId
            get() = player

        override fun reset() {
            resetCalls++
            history.clear()
            pieces.clear()
            pieces[redFrom] = ChineseChessPiece(
                ChineseChessPieceType.CHARIOT,
                ChineseChessSide.RED,
            )
            pieces[blackFrom] = ChineseChessPiece(
                ChineseChessPieceType.CHARIOT,
                ChineseChessSide.BLACK,
            )
            player = PlayerId(ChineseChessSide.RED.code)
        }

        override fun apply(action: BoardMove): ActionResult {
            if (action !in legalActions()) {
                return ActionResult.Rejected(EngineError.ILLEGAL_ACTION)
            }
            history += Snapshot(pieces.toMap(), player)
            pieces[action.to] = requireNotNull(pieces.remove(action.from))
            player = PlayerId(
                if (player.value == ChineseChessSide.RED.code) {
                    ChineseChessSide.BLACK.code
                } else {
                    ChineseChessSide.RED.code
                },
            )
            return ActionResult.Accepted
        }

        override fun undo(): Boolean {
            val snapshot = history.removeLastOrNull() ?: return false
            pieces.clear()
            pieces.putAll(snapshot.pieces)
            player = snapshot.player
            undoCalls++
            return true
        }

        override fun legalActions(): List<BoardMove> =
            if (player.value == ChineseChessSide.RED.code) {
                listOf(BoardMove(redFrom, redTo))
            } else {
                listOf(BoardMove(blackFrom, blackTo))
            }

        override fun chooseMove(difficulty: Difficulty): BoardMove? {
            lastDifficulty = difficulty
            chooseCalls++
            return legalActions().single()
        }

        override fun gameResult(): GameResult =
            if (history.size == 1) humanMoveResult else GameResult.ONGOING

        override fun serialize(): ByteArray = byteArrayOf(history.size.toByte())

        override fun restore(data: ByteArray): RestoreResult = RestoreResult.Restored

        override fun pieceAt(position: BoardPosition): ChineseChessPiece? = pieces[position]

        override fun close() = Unit
    }
}
