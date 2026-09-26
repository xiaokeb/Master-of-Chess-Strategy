package com.masterofchessstrategy.game

import com.masterofchessstrategy.challenge.AssessmentChallengeState
import com.masterofchessstrategy.challenge.AssessmentChallengeStateCodec
import com.masterofchessstrategy.challenge.StreakChallengeState
import com.masterofchessstrategy.challenge.StreakChallengeStateCodec
import com.masterofchessstrategy.challenge.TimedChallengeConfig
import com.masterofchessstrategy.data.*
import com.masterofchessstrategy.engine.*
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChineseChessAiFirstTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun prepare() = Dispatchers.setMain(dispatcher)
    @After fun cleanup() = Dispatchers.resetMain()

    @Test fun openingIsAutomaticAndUndoPreservesAiOpening() = runTest(dispatcher) {
        val engine = AlternatingEngine()
        val repository = MemorySessions()
        val vm = game(engine, repository)
        assertFalse(vm.uiState.isInteractionEnabled)
        vm.onSquareTap(BoardPosition(0, 9))
        assertNull(vm.uiState.selectedPosition)
        advanceUntilIdle()
        assertEquals(1, engine.plies)
        assertEquals(ChineseChessSide.BLACK, vm.uiState.playerSide)
        assertTrue(vm.uiState.isInteractionEnabled)
        assertFalse(vm.uiState.canUndo)
        vm.undo()
        assertEquals(1, engine.plies)
        vm.requestHint()
        advanceUntilIdle()
        assertEquals(setOf(engine.legalActions().single().from), vm.uiState.hintedOrigins)
        vm.offerOrAcceptDraw()
        advanceUntilIdle()
        play(vm, engine)
        assertFalse(vm.uiState.isInteractionEnabled)
        advanceUntilIdle()
        assertEquals(3, engine.plies)
        assertEquals(ChineseChessFeedback.DRAW_DECLINED, vm.uiState.feedback)
        assertTrue(vm.uiState.canUndo)
        vm.undo()
        advanceUntilIdle()
        assertEquals(1, engine.plies)
        assertEquals(2, engine.searches)
        assertEquals(1, repository.saved.last().playerIndex)
        assertEquals(1, repository.saved.last().acceptedMoveCount)
        assertFalse(vm.uiState.canUndo)
    }

    @Test fun restoreRetainsOwnerAndResumesExactlyOnePendingAiMove() = runTest(dispatcher) {
        val source = MemorySessions()
        val original = AlternatingEngine()
        val vm = game(original, source)
        advanceUntilIdle()
        play(vm, original)
        advanceUntilIdle()
        val pending = source.saved.first { it.acceptedMoveCount == 2 }
        val restoredEngine = AlternatingEngine()
        val restored = game(restoredEngine, MemorySessions(pending), aiFirst = false)
        advanceUntilIdle()
        assertEquals(ChineseChessSide.BLACK, restored.uiState.playerSide)
        assertEquals(3, restoredEngine.plies)
        assertEquals(1, restoredEngine.searches)
        assertTrue(restored.uiState.isInteractionEnabled)

        val oldRedSave = pending.copy(playerIndex = 0, engineState = byteArrayOf(0), acceptedMoveCount = 0)
        val oldEngine = AlternatingEngine()
        val old = game(oldEngine, MemorySessions(oldRedSave), aiFirst = true)
        advanceUntilIdle()
        assertEquals(ChineseChessSide.RED, old.uiState.playerSide)
        assertEquals(0, oldEngine.searches)
        old.restartWithAiFirst(true)
        advanceUntilIdle()
        assertEquals(ChineseChessSide.BLACK, old.uiState.playerSide)
        assertEquals(1, oldEngine.plies)
    }

    @Test fun blackVictoryEmitsVictoryAndRecordsAbsoluteResultWithHumanIdentity() = runTest(dispatcher) {
        val engine = AlternatingEngine(2, GameResult.SECOND_PLAYER_WIN)
        val outcomes = mutableListOf<MatchOutcome>()
        val records = mutableListOf<GameRecord>()
        val vm = game(engine, outcomes = outcomes, records = records)
        advanceUntilIdle()
        val cue = async(start = CoroutineStart.UNDISPATCHED) { vm.soundEvents.first() }
        play(vm, engine)
        advanceUntilIdle()
        assertEquals(ChineseChessSoundCue.VICTORY, cue.await())
        assertTrue(outcomes.single().isWin)
        assertEquals(1, outcomes.single().playerIndex)
        assertEquals(GameResult.SECOND_PLAYER_WIN, records.single().result)
        assertEquals(1, records.single().playerIndex)
        vm.onSquareTap(BoardPosition(0, 0))
        assertEquals(1, records.size)
        assertFalse(vm.uiState.canUndo)
    }

    @Test fun aiRedVictoryAndBlackResignationProduceHumanLosses() = runTest(dispatcher) {
        for (resign in listOf(false, true)) {
            val engine = AlternatingEngine(3, GameResult.FIRST_PLAYER_WIN)
            val outcomes = mutableListOf<MatchOutcome>()
            val vm = game(engine, outcomes = outcomes)
            advanceUntilIdle()
            val cue = async(start = CoroutineStart.UNDISPATCHED) {
                vm.soundEvents.first { it == ChineseChessSoundCue.DEFEAT }
            }
            if (resign) vm.resign() else play(vm, engine)
            advanceUntilIdle()
            assertEquals(ChineseChessSoundCue.DEFEAT, cue.await())
            assertTrue(outcomes.single().isLoss)
            assertEquals(GameResult.FIRST_PLAYER_WIN, vm.uiState.result)
        }
    }

    @Test fun blackClockExpiresAsHumanLossWhileExpiredRedSearchIsHumanWin() = runTest(dispatcher) {
        for (expireAi in listOf(false, true)) {
            var now = 100L
            val outcomes = mutableListOf<MatchOutcome>()
            val engine = AlternatingEngine(onSearch = { if (expireAi) now += 300_001L })
            val vm = ChineseChessGameViewModel(
                mode = StoredGameMode.HUMAN_VS_AI, difficulty = Difficulty.EASY,
                aiFirstEnabled = true, aiDispatcher = dispatcher, engineFactory = { engine },
                nowEpochMillis = { now }, initialTimeControlMinutes = 5,
                clockTickIntervalMillis = null, onMatchFinished = outcomes::add,
            )
            val cue = async(start = CoroutineStart.UNDISPATCHED) { vm.soundEvents.first {
                it == ChineseChessSoundCue.VICTORY || it == ChineseChessSoundCue.DEFEAT
            } }
            advanceUntilIdle()
            if (!expireAi) {
                now += 300_001L
                vm.synchronizeClock()
            }
            assertEquals(expireAi, outcomes.single().isWin)
            assertEquals(if (expireAi) ChineseChessSoundCue.VICTORY else ChineseChessSoundCue.DEFEAT, cue.await())
            assertEquals(if (expireAi) 0 else 1, engine.plies)
        }
    }

    @Test fun challengeWinsUseHumanPerspectiveAndSeriesKeepsSide() = runTest(dispatcher) {
        for (mode in listOf(StoredGameMode.STREAK_CHALLENGE, StoredGameMode.ASSESSMENT_CHALLENGE,
            StoredGameMode.TIMED_CHALLENGE, StoredGameMode.BLIND_CHALLENGE)) {
            val engine = AlternatingEngine(2, GameResult.SECOND_PLAYER_WIN)
            val repository = MemorySessions()
            val streak = StreakChallengeState().takeIf { mode == StoredGameMode.STREAK_CHALLENGE }
            val assessment = AssessmentChallengeState().takeIf { mode == StoredGameMode.ASSESSMENT_CHALLENGE }
            val timed = mode == StoredGameMode.TIMED_CHALLENGE
            val vm = ChineseChessGameViewModel(
                sessionRepository = repository, mode = mode, difficulty = Difficulty.MEDIUM,
                aiFirstEnabled = true, aiDispatcher = dispatcher, engineFactory = { engine },
                initialStreakState = streak, initialAssessmentState = assessment,
                perMoveTimeLimitSeconds = if (timed) 10 else null, clockTickIntervalMillis = null,
                sessionVariantId = when {
                    streak != null -> StreakChallengeStateCodec.encode(streak)
                    assessment != null -> AssessmentChallengeStateCodec.encode(assessment)
                    timed -> TimedChallengeConfig.sessionVariant(10)
                    else -> ""
                },
            )
            advanceUntilIdle()
            assertEquals(ChineseChessSide.BLACK, vm.uiState.currentSide)
            play(vm, engine)
            advanceUntilIdle()
            assertEquals(1, repository.saved.last().playerIndex)
            if (streak != null) assertEquals(1, vm.uiState.currentStreak)
            if (assessment != null) assertEquals(1, vm.uiState.assessmentWins)
            if (streak != null) vm.continueStreakChallenge() else if (assessment != null) vm.continueAssessmentChallenge()
            advanceUntilIdle()
            assertEquals(ChineseChessSide.BLACK, vm.uiState.playerSide)
        }
    }

    @Test fun unsupportedModesCannotAcquireBlackPlayerIdentity() {
        for (mode in StoredGameMode.entries.filterNot { it.supportsAiFirst }) {
            assertTrue(mode.acceptsPlayerIndex(0))
            assertFalse(mode.acceptsPlayerIndex(1))
        }
        assertFalse(StoredGameMode.HUMAN_VS_AI.acceptsPlayerIndex(2))
    }

    private fun game(
        engine: AlternatingEngine,
        repository: MemorySessions? = null,
        aiFirst: Boolean = true,
        outcomes: MutableList<MatchOutcome> = mutableListOf(),
        records: MutableList<GameRecord> = mutableListOf(),
    ) = ChineseChessGameViewModel(
        sessionRepository = repository, mode = StoredGameMode.HUMAN_VS_AI,
        difficulty = Difficulty.EASY, aiFirstEnabled = aiFirst, aiDispatcher = dispatcher,
        engineFactory = { engine }, onMatchFinished = outcomes::add, onGameRecorded = records::add,
    )

    private fun play(vm: ChineseChessGameViewModel, engine: AlternatingEngine) {
        val move = engine.legalActions().single()
        vm.onSquareTap(move.from)
        vm.onSquareTap(move.to)
    }

    private class MemorySessions(val savedSession: GameSessionSnapshot? = null) : GameSessionRepository {
        val saved = mutableListOf<GameSessionSnapshot>()
        override suspend fun load(gameType: GameType) = savedSession?.let { LoadGameSessionResult.Loaded(it) }
            ?: LoadGameSessionResult.NotFound
        override suspend fun save(snapshot: GameSessionSnapshot) { saved += snapshot.defensiveCopy() }
        override suspend fun clear(gameType: GameType) = Unit
    }

    /** Deterministic alternating rooks; real legality/search is tested separately on Android. */
    private class AlternatingEngine(
        val terminalPly: Int = Int.MAX_VALUE,
        val terminal: GameResult = GameResult.ONGOING,
        val onSearch: () -> Unit = {},
    ) : ChineseChessAiEngine {
        var plies = 0
        var searches = 0
        override val gameType = GameType.CHINESE_CHESS
        override val currentPlayer get() = PlayerId(plies % 2)
        private fun position(side: Int): BoardPosition {
            val moves = if (side == 0) (plies + 1) / 2 else plies / 2
            return BoardPosition(0, if (side == 0) 9 - moves % 2 else moves % 2)
        }
        override fun legalActions(): List<BoardMove> {
            val from = position(currentPlayer.value)
            val to = if (currentPlayer.value == 0) BoardPosition(0, 17 - from.y) else BoardPosition(0, 1 - from.y)
            return listOf(BoardMove(from, to))
        }
        override fun chooseMove(difficulty: Difficulty): BoardMove {
            searches++
            onSearch()
            return legalActions().single()
        }
        override fun apply(action: BoardMove): ActionResult {
            if (action !in legalActions()) return ActionResult.Rejected(EngineError.ILLEGAL_ACTION)
            plies++
            return ActionResult.Accepted
        }
        override fun undo(): Boolean = (plies > 0).also { if (it) plies-- }
        override fun pieceAt(position: BoardPosition): ChineseChessPiece? = ChineseChessSide.entries
            .firstOrNull { position == position(it.code) }?.let { ChineseChessPiece(ChineseChessPieceType.CHARIOT, it) }
        override fun gameResult() = if (plies >= terminalPly) terminal else GameResult.ONGOING
        override fun isInCheck(side: ChineseChessSide) = false
        override fun reset() { plies = 0 }
        override fun serialize() = byteArrayOf(plies.toByte())
        override fun restore(data: ByteArray): RestoreResult {
            plies = data.single().toInt()
            return RestoreResult.Restored
        }
        override fun close() = Unit
    }
}
