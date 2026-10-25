package com.masterofchessstrategy.games.lanzhou_fangqi

import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.games.lanzhou_fangqi.FangqiAction
import com.masterofchessstrategy.engine.games.lanzhou_fangqi.FangqiMoveResult
import com.masterofchessstrategy.engine.games.lanzhou_fangqi.FangqiPoint
import com.masterofchessstrategy.engine.games.lanzhou_fangqi.FangqiRejection
import com.masterofchessstrategy.engine.games.lanzhou_fangqi.FangqiSession
import com.masterofchessstrategy.engine.games.lanzhou_fangqi.FangqiSide
import com.masterofchessstrategy.engine.games.lanzhou_fangqi.FangqiSnapshot
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanzhouFangqiInteractionTest {
    @Test fun failedCheckpointBlocksNewActionsAndRetriesIdenticalNativeBytes() {
        val session = FixtureSession()
        val writes = mutableListOf<ByteArray>()
        val acknowledgements = mutableListOf<(Boolean) -> Unit>()
        val interaction = LanzhouFangqiInteraction(session, onPositionChanged = { _, bytes, done ->
            writes += bytes
            acknowledgements += done
        })
        interaction.tap(FangqiPoint(3, 3))
        interaction.tap(FangqiPoint(3, 4))
        assertTrue(interaction.state.value.savePending)
        interaction.undo()
        assertEquals(FangqiSide.BLACK, interaction.state.value.snapshot.currentPlayer)
        acknowledgements[0](false)
        interaction.reset()
        assertEquals(FangqiSide.BLACK, interaction.state.value.snapshot.currentPlayer)
        interaction.retrySave()
        assertTrue(writes[0].contentEquals(writes[1]))
        acknowledgements[0](false) // A late callback cannot finish the retry.
        assertTrue(interaction.state.value.savePending)
        acknowledgements[1](true)
        interaction.undo()
        assertEquals(FangqiSide.RED, interaction.state.value.snapshot.currentPlayer)
        assertEquals(3, writes.size)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun everyAiCaptureWaitsForItsOwnCheckpointConfirmation() = runTest {
        val black = listOf(FangqiPoint(6, 6))
        val session = ScriptedSession(
            listOf(
                position(FangqiSide.BLACK, 2, listOf(FangqiPoint(0, 0), FangqiPoint(1, 0), FangqiPoint(2, 0)), black),
                position(FangqiSide.BLACK, 1, listOf(FangqiPoint(1, 0), FangqiPoint(2, 0)), black),
                position(FangqiSide.RED, 0, listOf(FangqiPoint(2, 0)), black),
            ),
            listOf(FangqiAction.Capture(FangqiPoint(0, 0)), FangqiAction.Capture(FangqiPoint(1, 0))),
        )
        val writes = mutableListOf<FangqiSnapshot>()
        val acknowledgements = mutableListOf<(Boolean) -> Unit>()
        val interaction = LanzhouFangqiInteraction(
            session, FangqiSide.RED, StandardTestDispatcher(testScheduler),
            onPositionChanged = { snapshot, _, done -> writes += snapshot; acknowledgements += done },
        )
        val ai = launch { interaction.playBlackAi(Difficulty.EASY) }
        runCurrent()
        assertEquals(1, session.aiChoices)
        assertTrue(interaction.state.value.savePending)
        acknowledgements[0](true)
        runCurrent()
        assertEquals(2, session.aiChoices)
        acknowledgements[1](true)
        ai.join()
        assertEquals(listOf(1, 0), writes.map { it.pendingCaptures })
        assertEquals(listOf(FangqiSide.BLACK, FangqiSide.RED), writes.map { it.currentPlayer })
    }

    @Test fun boardGeometryMapsIntersectionsAndRejectsMargins() {
        val geometry = fangqiBoardGeometry(700f, 700f)
        assertEquals(FangqiPoint(0, 0), fangqiPointAt(geometry.originX, geometry.originY, 700f, 700f))
        assertEquals(FangqiPoint(6, 6), fangqiPointAt(
            geometry.originX + 6 * geometry.spacing,
            geometry.originY + 6 * geometry.spacing,
            700f, 700f,
        ))
        assertEquals(null, fangqiPointAt(0f, 0f, 700f, 700f))
        assertEquals(null, fangqiPointAt(350f, 350f, 0f, 700f))
    }

    @Test fun selectionUsesLegalDestinationsAndKeepsIllegalMoveAtomic() {
        val session = FixtureSession()
        val interaction = LanzhouFangqiInteraction(session)
        interaction.tap(FangqiPoint(2, 2))
        assertEquals(FangqiFeedback.SELECT_OWN, interaction.state.value.feedback)
        interaction.tap(FangqiPoint(3, 3))
        assertEquals(FangqiPoint(3, 3), interaction.state.value.selected)
        assertEquals(setOf(FangqiPoint(3, 4)), interaction.state.value.legalPoints)
        interaction.tap(FangqiPoint(4, 4))
        assertEquals(FangqiFeedback.ILLEGAL, interaction.state.value.feedback)
        assertEquals(FangqiPoint(3, 3), interaction.state.value.selected)
        assertEquals(FangqiSide.RED, interaction.state.value.snapshot.currentPlayer)
        interaction.tap(FangqiPoint(3, 4))
        assertEquals(null, interaction.state.value.selected)
        assertEquals(FangqiSide.BLACK, interaction.state.value.snapshot.currentPlayer)
        interaction.undo()
        assertEquals(FangqiSide.RED, interaction.state.value.snapshot.currentPlayer)
        assertFalse(interaction.state.value.snapshot.canUndo)
    }

    @Test fun pendingCaptureOnlyAcceptsListedOpponentAndRefreshesOnLoad() {
        val session = FixtureSession(pendingCapture = true)
        val interaction = LanzhouFangqiInteraction(session)
        assertEquals(setOf(FangqiPoint(6, 6)), interaction.state.value.legalPoints)
        interaction.tap(FangqiPoint(3, 3))
        assertEquals(FangqiFeedback.SELECT_CAPTURE, interaction.state.value.feedback)
        interaction.tap(FangqiPoint(6, 6))
        assertEquals(0, interaction.state.value.snapshot.pendingCaptures)
        assertEquals(GameResult.FIRST_PLAYER_WIN, interaction.state.value.snapshot.result)
        assertTrue(interaction.state.value.legalPoints.isEmpty())
    }

    @Test fun humanCannotTakeAiTurnAndUndoRestoresHumanDecision() = runTest {
        val redMove = FangqiAction.Move(FangqiPoint(3, 3), FangqiPoint(3, 4))
        val blackMove = FangqiAction.Move(FangqiPoint(6, 6), FangqiPoint(6, 5))
        val session = ScriptedSession(
            listOf(
                position(FangqiSide.RED, red = listOf(FangqiPoint(3, 3)), black = listOf(FangqiPoint(6, 6))),
                position(FangqiSide.BLACK, red = listOf(FangqiPoint(3, 4)), black = listOf(FangqiPoint(6, 6))),
                position(FangqiSide.RED, red = listOf(FangqiPoint(3, 4)), black = listOf(FangqiPoint(6, 5))),
            ),
            listOf(redMove, blackMove),
        )
        val interaction = LanzhouFangqiInteraction(session, FangqiSide.RED, StandardTestDispatcher(testScheduler))
        interaction.tap(redMove.from)
        interaction.tap(redMove.to)
        interaction.tap(blackMove.from)
        assertEquals(null, interaction.state.value.selected)
        assertEquals(FangqiFeedback.AI_THINKING, interaction.state.value.feedback)
        interaction.playBlackAi(Difficulty.EASY)
        assertEquals(FangqiSide.RED, interaction.state.value.snapshot.currentPlayer)
        interaction.undo()
        assertEquals(session.positions.first(), interaction.state.value.snapshot)
        assertEquals(2, session.undoCalls)
    }

    @Test fun aiSelectsAllPendingCapturesBeforeReturningControl() = runTest {
        val black = listOf(FangqiPoint(6, 6))
        val session = ScriptedSession(
            listOf(
                position(FangqiSide.BLACK, 2, listOf(FangqiPoint(0, 0), FangqiPoint(1, 0), FangqiPoint(2, 0)), black),
                position(FangqiSide.BLACK, 1, listOf(FangqiPoint(1, 0), FangqiPoint(2, 0)), black),
                position(FangqiSide.RED, 0, listOf(FangqiPoint(2, 0)), black),
            ),
            listOf(FangqiAction.Capture(FangqiPoint(0, 0)), FangqiAction.Capture(FangqiPoint(1, 0))),
        )
        val interaction = LanzhouFangqiInteraction(session, FangqiSide.RED, StandardTestDispatcher(testScheduler))
        interaction.playBlackAi(Difficulty.MEDIUM)
        assertEquals(2, session.aiChoices)
        assertEquals(0, interaction.state.value.snapshot.pendingCaptures)
        assertEquals(FangqiSide.RED, interaction.state.value.snapshot.currentPlayer)
        assertFalse(interaction.state.value.aiThinking)
    }

    private fun position(
        side: FangqiSide,
        pending: Int = 0,
        red: List<FangqiPoint>,
        black: List<FangqiPoint>,
    ): FangqiSnapshot {
        val board = MutableList(49) { 0 }
        red.forEach { board[it.index] = 1 }
        black.forEach { board[it.index] = 2 }
        return FangqiSnapshot(board.toList(), side, pending, GameResult.ONGOING, false)
    }

    private class ScriptedSession(
        val positions: List<FangqiSnapshot>,
        private val actions: List<FangqiAction>,
    ) : FangqiSession {
        private var index = 0
        var undoCalls = 0
        var aiChoices = 0
        override fun snapshot() = positions[index].copy(canUndo = index > 0)
        override fun legalActions() = actions.getOrNull(index)?.let { listOf(it) } ?: emptyList()
        override fun apply(action: FangqiAction): FangqiMoveResult {
            if (action != actions.getOrNull(index)) return FangqiMoveResult(false, FangqiRejection.INVALID_ACTION)
            index++
            return FangqiMoveResult(true, FangqiRejection.NONE)
        }
        override fun undo(): Boolean {
            if (index == 0) return false
            index--
            undoCalls++
            return true
        }
        override fun chooseAction(difficulty: Difficulty): FangqiAction? {
            aiChoices++
            return actions.getOrNull(index)
        }
        override fun reset() { index = 0 }
        override fun serialize() = byteArrayOf(index.toByte())
        override fun restore(data: ByteArray) = false
        override fun close() = Unit
    }

    private class FixtureSession(pendingCapture: Boolean = false) : FangqiSession {
        private val initialPending = pendingCapture
        private val board = MutableList(49) { 0 }.apply {
            this[FangqiPoint(3, 3).index] = 1
            this[FangqiPoint(6, 6).index] = 2
        }
        private var side = FangqiSide.RED
        private var pending = if (pendingCapture) 1 else 0
        private var canUndo = false
        private var result = GameResult.ONGOING

        override fun snapshot() = FangqiSnapshot(board.toList(), side, pending, result, canUndo)
        override fun legalActions(): List<FangqiAction> = when {
            result != GameResult.ONGOING -> emptyList()
            pending > 0 -> listOf(FangqiAction.Capture(FangqiPoint(6, 6)))
            side == FangqiSide.RED -> listOf(FangqiAction.Move(FangqiPoint(3, 3), FangqiPoint(3, 4)))
            else -> emptyList()
        }
        override fun apply(action: FangqiAction): FangqiMoveResult {
            if (action !in legalActions()) return FangqiMoveResult(false, FangqiRejection.NOT_ADJACENT)
            when (action) {
                is FangqiAction.Move -> {
                    board[action.from.index] = 0
                    board[action.to.index] = 1
                    side = FangqiSide.BLACK
                }
                is FangqiAction.Capture -> {
                    board[action.point.index] = 0
                    pending = 0
                    result = GameResult.FIRST_PLAYER_WIN
                }
            }
            canUndo = true
            return FangqiMoveResult(true, FangqiRejection.NONE)
        }
        override fun undo(): Boolean {
            if (!canUndo) return false
            reset()
            return true
        }
        override fun reset() {
            board.fill(0)
            board[FangqiPoint(3, 3).index] = 1
            board[FangqiPoint(6, 6).index] = 2
            side = FangqiSide.RED
            pending = if (initialPending) 1 else 0
            canUndo = false
            result = GameResult.ONGOING
        }
        override fun chooseAction(difficulty: Difficulty): FangqiAction? = legalActions().firstOrNull()
        override fun serialize(): ByteArray = board.map { it.toByte() }.toByteArray() + byteArrayOf(side.code.toByte(), pending.toByte())
        override fun restore(data: ByteArray): Boolean = false
        override fun close() = Unit
    }
}
