package com.masterofchessstrategy.custom

import com.masterofchessstrategy.data.GameSessionRepository
import com.masterofchessstrategy.data.GameSessionSnapshot
import com.masterofchessstrategy.data.LoadGameSessionResult
import com.masterofchessstrategy.data.StoredGameMode
import com.masterofchessstrategy.data.acceptsSessionVariant
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
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChineseChessHandicapTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun prepare() = Dispatchers.setMain(dispatcher)
    @After fun cleanup() = Dispatchers.resetMain()

    @Test fun canonicalVariantRoundTripsBothSidesAndCannotRemoveGenerals() {
        val variant = ChineseChessHandicapConfig.sessionVariant(setOf(0, 1, 82), ID)
        assertEquals("handicap:1:000152:$ID", variant)
        assertEquals(setOf(0, 1, 82), ChineseChessHandicapConfig.removedSquares(variant))
        val board = ChineseChessHandicapConfig.board(setOf(0, 1, 82))
        assertEquals(29, board.count { it != null })
        assertEquals(ChineseChessPieceType.GENERAL, board[4]?.type)
        assertEquals(ChineseChessPieceType.GENERAL, board[85]?.type)
        assertTrue(StoredGameMode.HANDICAP.acceptsSessionVariant(variant))
        for (bad in listOf(4, 85, 40, -1, 90)) {
            assertThrows(IllegalArgumentException::class.java) { ChineseChessHandicapConfig.sessionVariant(setOf(bad), ID) }
        }
    }

    @Test fun alternateSpellingsDuplicatesEmptyAndFacingGeneralsAreRejected() {
        for (value in listOf(
            "handicap:1:0000:$ID", "handicap:1:0100:$ID",
            "handicap:1:FF:$ID", "handicap:2:00:$ID", "handicap:1:00:BAD", "handicap:1::$ID",
        )) {
            assertFalse(value, StoredGameMode.HANDICAP.acceptsSessionVariant(value))
        }
        assertThrows(IllegalArgumentException::class.java) { ChineseChessHandicapConfig.sessionVariant(emptySet(), ID) }
        val facing = ChineseChessHandicapConfig.sessionVariant(setOf(31, 58), ID)
        assertFalse(StoredGameMode.HANDICAP.acceptsSessionVariant(facing))
    }

    @Test fun togglesProtectGeneralsRestorePiecesAndPresetsNeverMoveOtherPieces() {
        val vm = viewModel()
        vm.toggleSquare(BoardPosition(4, 0))
        assertEquals(HandicapFeedback.GENERAL_PROTECTED, vm.uiState.feedback)
        assertTrue(vm.uiState.removed.isEmpty())
        vm.toggleSquare(BoardPosition(1, 0))
        assertNull(vm.uiState.board[1])
        vm.toggleSquare(BoardPosition(1, 0))
        assertEquals(standardBoard(), vm.uiState.board)
        repeat(3) { vm.addPreset(ChineseChessPieceType.HORSE) }
        assertEquals(setOf(1, 7), vm.uiState.removed)
        vm.selectPresetSide(ChineseChessSide.RED)
        vm.addPreset(ChineseChessPieceType.CHARIOT)
        assertEquals(setOf(1, 7, 81), vm.uiState.removed)
        vm.reset()
        assertEquals(standardBoard(), vm.uiState.board)
    }

    @Test fun validNewGamesGetDistinctIdsAndRapidDoubleStartIsBlocked() = runTest(dispatcher) {
        var generated = 0
        val vm = viewModel(unlocked = setOf(Difficulty.EASY, Difficulty.MEDIUM),
            idFactory = { (++generated).toString().padStart(32, '0') })
        vm.addPreset(ChineseChessPieceType.HORSE)
        val first = requireNotNull(vm.prepareNewGame())
        assertNull(vm.prepareNewGame())
        val launching = vm.uiState
        vm.reset()
        vm.selectPresetSide(ChineseChessSide.RED)
        vm.addPreset(ChineseChessPieceType.CHARIOT)
        vm.selectDifficulty(Difficulty.MEDIUM)
        vm.toggleSquare(BoardPosition(0, 0))
        assertEquals(launching, vm.uiState)
        vm.refreshSavedGame()
        advanceUntilIdle()
        val second = requireNotNull(vm.prepareNewGame())
        assertNotEquals(first.variant, second.variant)
        assertArrayEquals(ChineseChessHandicapConfig.initialState(first.variant), ChineseChessHandicapConfig.initialState(second.variant))
    }

    @Test fun lockedDifficultyInvalidPositionAndEngineFailureCannotStart() {
        val locked = viewModel(unlocked = emptySet())
        locked.addPreset(ChineseChessPieceType.HORSE)
        assertNull(locked.prepareNewGame())
        assertEquals(HandicapFeedback.DIFFICULTY_LOCKED, locked.uiState.feedback)
        val vm = viewModel()
        vm.selectDifficulty(Difficulty.MASTER)
        assertEquals(Difficulty.EASY, vm.uiState.difficulty)
        vm.toggleSquare(BoardPosition(4, 3))
        vm.toggleSquare(BoardPosition(4, 6))
        assertNull(vm.prepareNewGame())
        assertEquals(HandicapFeedback.INVALID_POSITION, vm.uiState.feedback)
        val unavailable = viewModel(factory = { error("unavailable") })
        unavailable.addPreset(ChineseChessPieceType.HORSE)
        assertNull(unavailable.prepareNewGame())
        assertEquals(HandicapFeedback.ENGINE_UNAVAILABLE, unavailable.uiState.feedback)
    }

    @Test fun terminalOrNoMoveNativeValidationFailsAndAlwaysClosesEngine() {
        for (terminal in listOf(false, true)) {
            val engine = ValidationEngine(terminal = terminal, legal = terminal)
            val vm = viewModel(factory = { engine })
            vm.addPreset(ChineseChessPieceType.HORSE)
            assertNull(vm.prepareNewGame())
            assertEquals(HandicapFeedback.INVALID_POSITION, vm.uiState.feedback)
            assertTrue(engine.closed)
        }
    }

    @Test fun savedVariantRefreshesWithoutOverwritingNewSelections() = runTest(dispatcher) {
        val variant = ChineseChessHandicapConfig.sessionVariant(setOf(1), ID)
        val sessions = Sessions()
        val vm = viewModel(sessions)
        vm.toggleSquare(BoardPosition(0, 9))
        sessions.saved = GameSessionSnapshot(
            gameType = GameType.CHINESE_CHESS, mode = StoredGameMode.HANDICAP, difficulty = Difficulty.EASY,
            engineState = byteArrayOf(1), updatedAtEpochMillis = 1L, sessionVariantId = variant, playerIndex = 1,
        )
        vm.refreshSavedGame()
        advanceUntilIdle()
        assertEquals(setOf(81), vm.uiState.removed)
        assertEquals(variant, vm.continueSavedGame()?.variant)
        sessions.saved = sessions.saved!!.copy(mode = StoredGameMode.CUSTOM_POSITION)
        vm.refreshSavedGame()
        advanceUntilIdle()
        assertNull(vm.continueSavedGame())
    }

    private fun viewModel(
        repository: Sessions = Sessions(),
        unlocked: Set<Difficulty> = setOf(Difficulty.EASY),
        factory: () -> ChineseChessRuleEngine = { ValidationEngine() },
        idFactory: () -> String = { ID },
    ) = ChineseChessHandicapViewModel(repository, unlocked, factory, idFactory)

    private class Sessions : GameSessionRepository {
        var saved: GameSessionSnapshot? = null
        override suspend fun load(gameType: GameType) = saved?.let { LoadGameSessionResult.Loaded(it) } ?: LoadGameSessionResult.NotFound
        override suspend fun save(snapshot: GameSessionSnapshot) { saved = snapshot }
        override suspend fun clear(gameType: GameType) { saved = null }
    }

    private class ValidationEngine(val terminal: Boolean = false, val legal: Boolean = true) : ChineseChessRuleEngine {
        var closed = false
        override val gameType = GameType.CHINESE_CHESS
        override val currentPlayer = PlayerId(0)
        override fun reset() = Unit
        override fun apply(action: BoardMove) = ActionResult.Accepted
        override fun undo() = false
        override fun legalActions() = if (legal) listOf(BoardMove(BoardPosition(0, 9), BoardPosition(0, 8))) else emptyList()
        override fun gameResult() = if (terminal) GameResult.DRAW else GameResult.ONGOING
        override fun serialize() = byteArrayOf(1)
        override fun restore(data: ByteArray) = RestoreResult.Restored
        override fun pieceAt(position: BoardPosition): ChineseChessPiece? = null
        override fun isInCheck(side: ChineseChessSide) = false
        override fun close() { closed = true }
    }

    private companion object { const val ID = "00000000000000000000000000000001" }
}
