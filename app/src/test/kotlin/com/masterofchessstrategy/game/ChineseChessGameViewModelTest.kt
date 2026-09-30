package com.masterofchessstrategy.game

import com.masterofchessstrategy.engine.ActionResult
import com.masterofchessstrategy.engine.BoardMove
import com.masterofchessstrategy.engine.BoardPosition
import com.masterofchessstrategy.engine.ChineseChessPiece
import com.masterofchessstrategy.engine.ChineseChessPieceType
import com.masterofchessstrategy.engine.ChineseChessNaturalLimitEngine
import com.masterofchessstrategy.engine.ChineseChessNaturalLimitReview
import com.masterofchessstrategy.engine.ChineseChessRuleEngine
import com.masterofchessstrategy.engine.ChineseChessSide
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.engine.PlayerId
import com.masterofchessstrategy.engine.NaturalLimitClaimOutcome
import com.masterofchessstrategy.engine.RestoreResult
import com.masterofchessstrategy.data.StoredGameMode
import com.masterofchessstrategy.data.GameRecord
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChineseChessGameViewModelTest {
    private val redRook = BoardPosition(0, 9)
    private val redRookDestination = BoardPosition(0, 8)
    private val blackRook = BoardPosition(0, 0)

    @Test
    fun initialSnapshotUsesEngineBoardAndRedStarts() {
        val viewModel = ChineseChessGameViewModel { FakeChineseChessEngine() }

        assertEquals(ChineseChessSide.RED, viewModel.uiState.currentSide)
        assertEquals(ChineseChessSide.RED, viewModel.uiState.pieceAt(redRook)?.side)
        assertEquals(ChineseChessSide.BLACK, viewModel.uiState.pieceAt(blackRook)?.side)
        assertFalse(viewModel.uiState.canUndo)
    }

    @Test
    fun checkedSideFollowsCurrentPlayerAndClearsAfterEscape() {
        val engine = FakeChineseChessEngine().apply {
            checkedSide = ChineseChessSide.RED
        }
        val viewModel = ChineseChessGameViewModel { engine }
        assertEquals(ChineseChessSide.RED, viewModel.uiState.checkedSide)

        engine.checkedSide = null
        viewModel.onSquareTap(redRook)
        viewModel.onSquareTap(redRookDestination)
        assertNull(viewModel.uiState.checkedSide)
    }

    @Test
    fun selectingOwnPieceShowsOnlyItsLegalDestinations() {
        val viewModel = ChineseChessGameViewModel { FakeChineseChessEngine() }

        viewModel.onSquareTap(redRook)

        assertEquals(redRook, viewModel.uiState.selectedPosition)
        assertEquals(setOf(redRookDestination), viewModel.uiState.legalDestinations)
        assertNull(viewModel.uiState.feedback)
    }

    @Test
    fun legalDestinationAppliesMoveAndSwitchesSide() = runTest {
        val viewModel = ChineseChessGameViewModel { FakeChineseChessEngine() }
        val soundEvent = async(start = CoroutineStart.UNDISPATCHED) {
            viewModel.soundEvents.first()
        }

        viewModel.onSquareTap(redRook)
        viewModel.onSquareTap(redRookDestination)

        assertEquals(ChineseChessSoundCue.MOVE, soundEvent.await())
        assertNull(viewModel.uiState.pieceAt(redRook))
        assertEquals(ChineseChessSide.RED, viewModel.uiState.pieceAt(redRookDestination)?.side)
        assertEquals(ChineseChessSide.BLACK, viewModel.uiState.currentSide)
        assertTrue(viewModel.uiState.canUndo)
        assertNull(viewModel.uiState.selectedPosition)
    }

    @Test
    fun wrongSideAndEmptyIntersectionReturnSpecificFeedback() {
        val viewModel = ChineseChessGameViewModel { FakeChineseChessEngine() }

        viewModel.onSquareTap(blackRook)
        assertEquals(ChineseChessFeedback.WRONG_SIDE, viewModel.uiState.feedback)

        viewModel.onSquareTap(BoardPosition(4, 4))
        assertEquals(ChineseChessFeedback.SELECT_OWN_PIECE, viewModel.uiState.feedback)
    }

    @Test
    fun undoAndRestartRestoreStableSessionState() {
        val engine = FakeChineseChessEngine()
        val viewModel = ChineseChessGameViewModel { engine }
        viewModel.onSquareTap(redRook)
        viewModel.onSquareTap(redRookDestination)

        viewModel.undo()
        assertEquals(ChineseChessSide.RED, viewModel.uiState.pieceAt(redRook)?.side)
        assertFalse(viewModel.uiState.canUndo)
        assertEquals(ChineseChessFeedback.MOVE_UNDONE, viewModel.uiState.feedback)

        viewModel.restart()
        assertEquals(1, engine.resetCalls)
        assertEquals(ChineseChessSide.RED, viewModel.uiState.currentSide)
        assertEquals(ChineseChessFeedback.GAME_RESTARTED, viewModel.uiState.feedback)
    }

    @Test
    fun linkageFailureProducesDisabledStateWithoutLeakingDetails() {
        val viewModel = ChineseChessGameViewModel {
            throw UnsatisfiedLinkError("C:\\private\\native.dll")
        }

        assertFalse(viewModel.uiState.isEngineAvailable)
        assertFalse(viewModel.uiState.canUndo)
        assertEquals(ChineseChessFeedback.ENGINE_UNAVAILABLE, viewModel.uiState.feedback)
    }

    @Test
    fun captureEmitsCaptureSound() = runTest {
        val viewModel = ChineseChessGameViewModel {
            FakeChineseChessEngine(captureAtDestination = true)
        }
        val soundEvent = async(start = CoroutineStart.UNDISPATCHED) {
            viewModel.soundEvents.first()
        }

        viewModel.onSquareTap(redRook)
        viewModel.onSquareTap(redRookDestination)

        assertEquals(ChineseChessSoundCue.CAPTURE, soundEvent.await())
    }

    @Test
    fun timedGameChargesOnlyActiveSideAndTimeoutLoses() = runTest {
        var now = 1_000L
        val records = mutableListOf<GameRecord>()
        val viewModel = ChineseChessGameViewModel(
            nowEpochMillis = { now },
            mode = StoredGameMode.LOCAL_TWO_PLAYER,
            onGameRecorded = records::add,
            initialTimeControlMinutes = 5,
            clockTickIntervalMillis = null,
            engineFactory = { FakeChineseChessEngine() },
        )

        now += 2_000L
        viewModel.onSquareTap(redRook)
        viewModel.onSquareTap(redRookDestination)

        assertEquals(298_000L, viewModel.uiState.redRemainingMillis)
        assertEquals(300_000L, viewModel.uiState.blackRemainingMillis)

        val soundEvent = async(start = CoroutineStart.UNDISPATCHED) {
            viewModel.soundEvents.first()
        }
        now += 300_001L
        viewModel.synchronizeClock()

        assertEquals(ChineseChessSoundCue.VICTORY, soundEvent.await())
        assertEquals(0L, viewModel.uiState.blackRemainingMillis)
        assertEquals(GameResult.FIRST_PLAYER_WIN, viewModel.uiState.result)
        assertEquals(ChineseChessFeedback.TIME_EXPIRED, viewModel.uiState.feedback)
        assertEquals(1, records.size)
        assertEquals(GameResult.FIRST_PLAYER_WIN, records.single().result)
        assertEquals(1, records.single().moveCount)

        viewModel.synchronizeClock()
        assertEquals(1, records.size)
    }

    @Test
    fun drawRequiresTheOtherSideToAccept() = runTest {
        val viewModel = ChineseChessGameViewModel { FakeChineseChessEngine() }

        viewModel.offerOrAcceptDraw()
        assertEquals(ChineseChessSide.RED, viewModel.uiState.pendingDrawOfferSide)
        assertEquals(GameResult.ONGOING, viewModel.uiState.result)

        viewModel.onSquareTap(redRook)
        viewModel.onSquareTap(redRookDestination)
        val soundEvent = async(start = CoroutineStart.UNDISPATCHED) {
            viewModel.soundEvents.first()
        }
        viewModel.offerOrAcceptDraw()

        assertEquals(ChineseChessSoundCue.DRAW, soundEvent.await())
        assertEquals(GameResult.DRAW, viewModel.uiState.result)
        assertNull(viewModel.uiState.pendingDrawOfferSide)
        assertEquals(ChineseChessFeedback.DRAW_ACCEPTED, viewModel.uiState.feedback)
    }

    @Test
    fun naturalLimitClaimIsDistinctFromMutualOfferAndDeductsFiveMinutes() {
        var now = 1_000L
        val engine = FakeChineseChessEngine()
        val viewModel = ChineseChessGameViewModel(
            nowEpochMillis = { now },
            initialTimeControlMinutes = 10,
            clockTickIntervalMillis = null,
            engineFactory = { engine },
        )
        assertTrue(viewModel.uiState.canClaimNaturalLimit)
        now += 10_000L
        viewModel.claimNaturalLimit()
        assertEquals(290_000L, viewModel.uiState.redRemainingMillis)
        assertEquals(GameResult.ONGOING, viewModel.uiState.result)
        assertNull(viewModel.uiState.pendingDrawOfferSide)
        assertEquals(ChineseChessFeedback.NATURAL_LIMIT_FALSE_CLAIM, viewModel.uiState.feedback)

        engine.naturalClaimOutcome = NaturalLimitClaimOutcome.SECOND_FALSE_CLAIM_LOSS
        viewModel.claimNaturalLimit()
        assertEquals(GameResult.SECOND_PLAYER_WIN, viewModel.uiState.result)
        assertEquals(ChineseChessFeedback.NATURAL_LIMIT_SECOND_FALSE_CLAIM, viewModel.uiState.feedback)
    }

    @Test
    fun naturalLimitFalseClaimCanCauseImmediateClockLoss() {
        var now = 1_000L
        val viewModel = ChineseChessGameViewModel(
            nowEpochMillis = { now },
            initialTimeControlMinutes = 5,
            clockTickIntervalMillis = null,
            engineFactory = { FakeChineseChessEngine() },
        )
        now += 1L
        viewModel.claimNaturalLimit()
        assertEquals(0L, viewModel.uiState.redRemainingMillis)
        assertEquals(GameResult.SECOND_PLAYER_WIN, viewModel.uiState.result)
        assertEquals(ChineseChessFeedback.TIME_EXPIRED, viewModel.uiState.feedback)
    }

    @Test
    fun successfulNaturalLimitClaimSettlesDrawWithoutOpponentConsent() = runTest {
        val engine = FakeChineseChessEngine().apply {
            naturalClaimOutcome = NaturalLimitClaimOutcome.DRAW
        }
        val viewModel = ChineseChessGameViewModel { engine }
        val soundEvent = async(start = CoroutineStart.UNDISPATCHED) {
            viewModel.soundEvents.first()
        }
        viewModel.claimNaturalLimit()
        assertEquals(ChineseChessSoundCue.DRAW, soundEvent.await())
        assertEquals(GameResult.DRAW, viewModel.uiState.result)
        assertEquals(ChineseChessFeedback.NATURAL_LIMIT_DRAW, viewModel.uiState.feedback)
    }

    @Test
    fun incompleteNaturalLimitRecordDisablesApplicationWithoutClockPenalty() {
        val engine = FakeChineseChessEngine().apply { naturalRecordComplete = false }
        val viewModel = ChineseChessGameViewModel(
            initialTimeControlMinutes = 10,
            clockTickIntervalMillis = null,
            engineFactory = { engine },
        )
        assertFalse(viewModel.uiState.canClaimNaturalLimit)
        viewModel.claimNaturalLimit()
        assertEquals(600_000L, viewModel.uiState.redRemainingMillis)
        assertEquals(GameResult.ONGOING, viewModel.uiState.result)
    }

    private inner class FakeChineseChessEngine(
        private val captureAtDestination: Boolean = false,
    ) : ChineseChessNaturalLimitEngine {
        override val gameType = GameType.CHINESE_CHESS
        private val positions = mutableMapOf(
            redRook to ChineseChessPiece(ChineseChessPieceType.CHARIOT, ChineseChessSide.RED),
            blackRook to ChineseChessPiece(ChineseChessPieceType.CHARIOT, ChineseChessSide.BLACK),
        ).apply {
            if (captureAtDestination) {
                this[redRookDestination] = ChineseChessPiece(
                    ChineseChessPieceType.SOLDIER,
                    ChineseChessSide.BLACK,
                )
            }
        }
        private var player = PlayerId(ChineseChessSide.RED.code)
        private var canUndo = false
        var resetCalls = 0
            private set
        var checkedSide: ChineseChessSide? = null
        var naturalClaimOutcome = NaturalLimitClaimOutcome.FIRST_FALSE_CLAIM
        var naturalRecordComplete = true
        private var naturalResult = GameResult.ONGOING

        override val currentPlayer: PlayerId
            get() = player

        override fun reset() {
            resetCalls++
            positions.clear()
            positions[redRook] =
                ChineseChessPiece(ChineseChessPieceType.CHARIOT, ChineseChessSide.RED)
            positions[blackRook] =
                ChineseChessPiece(ChineseChessPieceType.CHARIOT, ChineseChessSide.BLACK)
            if (captureAtDestination) {
                positions[redRookDestination] = ChineseChessPiece(
                    ChineseChessPieceType.SOLDIER,
                    ChineseChessSide.BLACK,
                )
            }
            player = PlayerId(ChineseChessSide.RED.code)
            canUndo = false
            naturalResult = GameResult.ONGOING
        }

        override fun apply(action: BoardMove): ActionResult {
            if (action != BoardMove(redRook, redRookDestination)) {
                return ActionResult.Rejected(
                    com.masterofchessstrategy.engine.EngineError.ILLEGAL_ACTION,
                )
            }
            positions[redRookDestination] = positions.remove(redRook)!!
            player = PlayerId(ChineseChessSide.BLACK.code)
            canUndo = true
            return ActionResult.Accepted
        }

        override fun undo(): Boolean {
            if (!canUndo) return false
            positions[redRook] = positions.remove(redRookDestination)!!
            player = PlayerId(ChineseChessSide.RED.code)
            canUndo = false
            return true
        }

        override fun legalActions(): List<BoardMove> =
            if (positions.containsKey(redRook) && player.value == ChineseChessSide.RED.code) {
                listOf(BoardMove(redRook, redRookDestination))
            } else {
                emptyList()
            }

        override fun gameResult(): GameResult = naturalResult

        override fun naturalLimitReview(side: ChineseChessSide): ChineseChessNaturalLimitReview =
            ChineseChessNaturalLimitReview(0, 0, 0, 0, naturalRecordComplete, false)

        override fun claimNaturalLimit(): NaturalLimitClaimOutcome = naturalClaimOutcome.also {
            naturalResult = when (it) {
                NaturalLimitClaimOutcome.DRAW -> GameResult.DRAW
                NaturalLimitClaimOutcome.SECOND_FALSE_CLAIM_LOSS ->
                    if (currentPlayer.value == ChineseChessSide.RED.code) GameResult.SECOND_PLAYER_WIN else GameResult.FIRST_PLAYER_WIN
                else -> GameResult.ONGOING
            }
        }

        override fun serialize(): ByteArray = byteArrayOf(1)

        override fun restore(data: ByteArray): RestoreResult = RestoreResult.Restored

        override fun pieceAt(position: BoardPosition): ChineseChessPiece? = positions[position]
        override fun isInCheck(side: ChineseChessSide): Boolean = checkedSide == side

        override fun close() = Unit
    }
}
