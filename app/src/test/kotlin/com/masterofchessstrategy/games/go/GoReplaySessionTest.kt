package com.masterofchessstrategy.games.go

import com.masterofchessstrategy.engine.*
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

/** Controlled record adapter for cursor/ownership transactions; actual rules have JNI tests. */
class GoReplaySessionTest {
    private val actions = listOf(GoAction.Place(BoardPosition(0, 0)), GoAction.Pass, GoAction.Pass,
        GoAction.ToggleDeadGroup(BoardPosition(0, 0), GoPlayer.BLACK), GoAction.ConfirmScore(GoPlayer.WHITE, 1),
        GoAction.ResumePlay(GoPlayer.BLACK), GoAction.Place(BoardPosition(1, 0)))
    private val created = mutableListOf<RecordDouble>()
    private var failRestore = false
    private var undoHook: (() -> Unit)? = null
    private fun factory(size: Int) = RecordDouble(size, failRestore, undoHook).also(created::add)
    private fun open() = GoReplaySession.openSnapshot(byteArrayOf(9, 7), ::factory)

    @Test fun startsAtSetupAndSeparatesMovesFromAgreementEvents() {
        open().use { replay ->
            val initial = replay.frame()
            assertEquals(0, initial.eventIndex)
            assertEquals(0, initial.moveIndex)
            assertEquals(4, initial.moveCount)
            assertEquals(7, initial.eventCount)
            assertEquals(2, initial.handicapStones)
            assertNull(initial.lastMoveIndex)
            val proposal = replay.seek(4)
            assertEquals(3, proposal.moveIndex)
            assertEquals(GoPhase.SCORING, proposal.phase)
            assertEquals(setOf(0), proposal.deadStoneIndices)
            assertEquals(0, proposal.lastMoveIndex)
            val resumed = replay.seek(6)
            assertEquals(GoPhase.PLAYING, resumed.phase)
            assertEquals(GoPlayer.BLACK, resumed.currentPlayer)
            assertEquals(0, resumed.lastMoveIndex)
            val end = replay.seek(7)
            assertEquals(4, end.moveIndex)
            assertEquals(1, end.lastMoveIndex)
            assertFalse(end.canNext)
        }
        assertTrue(created.all { it.closed })
    }

    @Test fun inputAndRecordCopyMutationCannotChangeFutureSeeks() {
        val source = byteArrayOf(9, 7)
        GoReplaySession.openSnapshot(source, ::factory).use { replay ->
            source[1] = 0
            replay.copyRecordBytes()[1] = 0
            assertEquals(7, replay.seek(7).eventIndex)
            assertEquals(1, replay.frame().lastMoveIndex)
            assertArrayEquals(byteArrayOf(9, 7), replay.copyRecordBytes())
        }
    }

    @Test fun cancelledCandidateClosesAndKeepsCurrentFrame() {
        open().use { replay ->
            replay.seek(7)
            val before = replay.frame()
            var cancelled = false
            undoHook = { cancelled = true }
            try { replay.seek(3) { cancelled }; fail("Cancelled seek accepted") }
            catch (_: CancellationException) { }
            assertTrue(created.last().closed)
            assertEquals(before, replay.frame())
        }
    }

    @Test fun rejectedRestoreAndInvalidCursorKeepPreviousEngine() {
        open().use { replay ->
            replay.seek(7)
            val before = replay.frame()
            val count = created.size
            for (cursor in listOf(-1, 8)) {
                try { replay.seek(cursor); fail("Invalid cursor accepted") }
                catch (_: IllegalArgumentException) { }
            }
            assertEquals(count, created.size)
            failRestore = true
            try { replay.seek(3); fail("Bad restore accepted") }
            catch (_: IllegalStateException) { }
            assertEquals(before, replay.frame())
            assertTrue(created.last().closed)
        }
    }

    @Test fun exportAlwaysUsesEntireRecordAndDoesNotChangeCursor() {
        open().use { replay ->
            replay.seek(3)
            val before = replay.frame()
            assertEquals("whole-record:7", replay.exportSgf())
            assertEquals(before, replay.frame())
            assertTrue(created.last().closed)
        }
    }

    @Test fun rejectedSizeCandidatesCloseAndAcceptedSessionClosesOnce() {
        val replay = GoReplaySession.openSnapshot(byteArrayOf(13, 7), ::factory)
        assertEquals(13, replay.frame().boardSize)
        assertTrue(created.first().closed)
        assertFalse(created.last().closed)
        replay.close()
        replay.close()
        assertEquals(1, created.last().closeCount)
        try { replay.frame(); fail("Closed replay accessible") }
        catch (_: IllegalStateException) { }
    }

    private inner class RecordDouble(
        override val boardSize: Int, private val rejected: Boolean, private val onUndo: (() -> Unit)?,
    ) : GoRecordEngine {
        var current = emptyList<GoAction>()
        var closed = false
        var closeCount = 0
        override val gameType = GameType.GO
        override val blackKomiQuarters = 0
        override val handicapStones = 2
        override val currentPlayer: PlayerId get() {
            var player = 1
            for (action in current) when (action) {
                is GoAction.Place, GoAction.Pass -> player = 1 - player
                is GoAction.ResumePlay -> player = action.firstPlayer.code
                else -> Unit
            }
            return PlayerId(player)
        }
        override val phase: GoPhase get() = when (current.lastOrNull()) {
            is GoAction.ToggleDeadGroup, is GoAction.ConfirmScore -> GoPhase.SCORING
            GoAction.Pass -> if (current.takeLast(2) == listOf(GoAction.Pass, GoAction.Pass)) GoPhase.SCORING else GoPhase.PLAYING
            else -> GoPhase.PLAYING
        }
        override fun restore(data: ByteArray): RestoreResult {
            if (rejected || data.size != 2 || data[0].toInt() != boardSize) return RestoreResult.Rejected(EngineError.CORRUPTED_DATA)
            current = actions.take(data[1].toInt())
            return RestoreResult.Restored
        }
        override fun serialize() = byteArrayOf(boardSize.toByte(), current.size.toByte())
        override fun undo(): Boolean {
            if (current.isEmpty()) return false
            current = current.dropLast(1)
            onUndo?.invoke()
            return true
        }
        override fun eventHistory() = current.toList()
        override fun exportSgf() = "whole-record:${current.size}"
        override fun restoreSgf(text: String) = RestoreResult.Rejected(EngineError.UNSUPPORTED)
        override fun scoringState() = GoScoringState(phase, 1, false,
            phase == GoPhase.SCORING && current.lastOrNull() is GoAction.ConfirmScore,
            if (phase == GoPhase.SCORING && current.any { it is GoAction.ToggleDeadGroup }) listOf(BoardPosition(0, 0)) else emptyList())
        override fun stoneAt(point: BoardPosition): GoStone =
            if (current.any { it is GoAction.Place && it.point == point }) GoStone.BLACK else GoStone.EMPTY
        override fun gameResult() = GameResult.ONGOING
        override fun previewScore(): GoAreaScore? = null
        override fun finalScore(): GoAreaScore? = null
        override fun legalActions() = emptyList<GoAction>()
        override fun apply(action: GoAction) = ActionResult.Rejected(EngineError.UNSUPPORTED)
        override fun reset() { current = emptyList() }
        override fun close() { if (!closed) { closed = true; closeCount++ } }
    }
}
