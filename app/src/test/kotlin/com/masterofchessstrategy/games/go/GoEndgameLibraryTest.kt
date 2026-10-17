package com.masterofchessstrategy.games.go

import com.masterofchessstrategy.engine.*
import org.junit.Assert.*
import org.junit.Test

class GoEndgameLibraryTest {
    @Test fun catalogHasStableUniqueMetadataAndLinearDifficultyOrder() {
        val puzzles = GoEndgameLibrary.puzzles
        assertEquals(6, puzzles.size); assertEquals(puzzles.size, puzzles.map { it.id }.toSet().size)
        assertTrue(puzzles.all { it.boardSize == 9 && it.setup.size % 2 == 0 })
        assertEquals(listOf(GoEndgameLevel.SIMPLE, GoEndgameLevel.MEDIUM), puzzles.map { it.level }.distinct())
        assertEquals(setOf(GoEndgameTheme.CAPTURE, GoEndgameTheme.ESCAPE, GoEndgameTheme.CONNECTION),
            puzzles.map { it.theme }.toSet())
    }

    @Test fun everyAuthoredSetupAndAnswerPassThroughRulesEngine() {
        for (puzzle in GoEndgameLibrary.puzzles) {
            val plan = GoEndgameLibrary.open(puzzle, factory)
            val session = GoEndgameSession(plan, factory)
            val initial = session.state()
            val solved = session.answer(puzzle.answer)
            assertFalse(initial.completed); assertTrue(solved.completed); assertEquals(1, solved.attempts)
            assertEquals(GoStone.BLACK.code, solved.stones[puzzle.answer.y * puzzle.boardSize + puzzle.answer.x])
            puzzle.captured.forEach { assertEquals(GoStone.EMPTY.code, solved.stones[it.y * puzzle.boardSize + it.x]) }
        }
    }

    @Test fun wrongLegalMoveDoesNotMutatePositionAndCorrectRetryCompletes() {
        val puzzle = GoEndgameLibrary.puzzles.first()
        val session = GoEndgameSession(GoEndgameLibrary.open(puzzle, factory), factory)
        val initial = session.state()
        val wrong = session.answer(BoardPosition(4, 4))
        assertFalse(wrong.completed); assertEquals(1, wrong.attempts); assertEquals(initial.stones, wrong.stones)
        val solved = session.answer(puzzle.answer)
        assertTrue(solved.completed); assertEquals(2, solved.attempts)
    }

    @Test fun illegalMoveDoesNotMutatePositionOrConsumeAnswer() {
        val puzzle = GoEndgameLibrary.puzzles.first()
        val session = GoEndgameSession(GoEndgameLibrary.open(puzzle, factory), factory)
        val initial = session.state(); val occupied = puzzle.setup.first()
        val rejected = session.answer(occupied)
        assertFalse(rejected.completed); assertEquals(initial.stones, rejected.stones)
        assertTrue(rejected.feedback.contains("不符合")); assertTrue(session.answer(puzzle.answer).completed)
    }

    @Test fun resetRestoresImmutableInitialPositionAndAttemptCounter() {
        val puzzle = GoEndgameLibrary.puzzles[1]
        val session = GoEndgameSession(GoEndgameLibrary.open(puzzle, factory), factory)
        val initial = session.state().stones
        assertTrue(session.answer(puzzle.answer).completed)
        val reset = session.reset()
        assertFalse(reset.completed); assertEquals(0, reset.attempts); assertNull(reset.lastMoveIndex)
        assertEquals(initial, reset.stones)
    }

    @Test fun planBytesAndReturnedStateAreDefensiveCopies() {
        val plan = GoEndgameLibrary.open(GoEndgameLibrary.puzzles.first(), factory)
        val expected = plan.copyInitialBytes(); plan.copyInitialBytes()[0] = 99
        assertArrayEquals(expected, plan.copyInitialBytes())
        val session = GoEndgameSession(plan, factory); val expectedStones = session.state().stones
        val external = session.state().stones as MutableList<Int>; external[0] = 99
        assertEquals(expectedStones, session.state().stones)
    }

    @Test fun invalidExpectedCaptureIsRejectedAndAllCandidatesClose() {
        val puzzle = GoEndgamePuzzle("bad-capture", "坏提子", GoEndgameTheme.CAPTURE, GoEndgameLevel.SIMPLE,
            "验证", "验证", 9, listOf(BoardPosition(0, 0), BoardPosition(8, 8)), BoardPosition(1, 0),
            setOf(BoardPosition(8, 8)))
        val engines = mutableListOf<GridRules>()
        try {
            GoEndgameLibrary.open(puzzle) { size, _, _ -> GridRules(size).also(engines::add) }
            fail("Invalid capture expectation accepted")
        } catch (_: IllegalArgumentException) { }
        assertEquals(2, engines.size); assertTrue(engines.all { it.closed })
    }

    @Test fun uiUnlockPolicyRequiresImmediatelyPreviousPuzzle() {
        val first = GoEndgameLibrary.puzzles[0]; val second = GoEndgameLibrary.puzzles[1]
        val third = GoEndgameLibrary.puzzles[2]
        assertTrue(GoEndgameUiState(loading = false).isUnlocked(first.id))
        assertFalse(GoEndgameUiState(loading = false).isUnlocked(second.id))
        assertTrue(GoEndgameUiState(loading = false, completedIds = setOf(first.id)).isUnlocked(second.id))
        assertFalse(GoEndgameUiState(loading = false, completedIds = setOf(first.id)).isUnlocked(third.id))
    }

    @Test fun alternateLegalConnectionThatMeetsGoalIsAcceptedWithoutMatchingReferencePoint() {
        val targets = setOf(BoardPosition(0, 1), BoardPosition(2, 1))
        val puzzle = GoEndgamePuzzle("two-bridges", "两种连接", GoEndgameTheme.CONNECTION, GoEndgameLevel.SIMPLE,
            "连接两组", "已连接", 9, listOf(BoardPosition(0, 0), BoardPosition(8, 8),
                BoardPosition(0, 1), BoardPosition(7, 7), BoardPosition(2, 0), BoardPosition(6, 6),
                BoardPosition(2, 1), BoardPosition(5, 5)), BoardPosition(1, 0), targetStones = targets)
        val plan = GoEndgameLibrary.open(puzzle, factory)
        val result = GoEndgameSession(plan, factory).answer(BoardPosition(1, 1))
        assertTrue(result.completed); assertEquals(10, result.lastMoveIndex)
    }

    @Test fun legalReferenceMoveThatDoesNotConnectTargetsIsRejected() {
        val puzzle = GoEndgamePuzzle("false-bridge", "错误连接", GoEndgameTheme.CONNECTION, GoEndgameLevel.SIMPLE,
            "连接两组", "已连接", 9, listOf(BoardPosition(0, 1), BoardPosition(8, 8),
                BoardPosition(2, 1), BoardPosition(7, 7)), BoardPosition(4, 4),
            targetStones = setOf(BoardPosition(0, 1), BoardPosition(2, 1)))
        try { GoEndgameLibrary.open(puzzle, factory); fail("Disconnected answer accepted") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun escapeFixtureMustStartInAtariBeforeClaimingToEscape() {
        val puzzle = GoEndgamePuzzle("false-atari", "错误打吃", GoEndgameTheme.ESCAPE, GoEndgameLevel.SIMPLE,
            "逃出打吃", "已逃出", 9, listOf(BoardPosition(4, 4), BoardPosition(8, 8)),
            BoardPosition(4, 5), targetStones = setOf(BoardPosition(4, 4)))
        try { GoEndgameLibrary.open(puzzle, factory); fail("Non-atari fixture accepted") }
        catch (_: IllegalArgumentException) { }
    }

    private val factory: (Int, Int, Int) -> GoRecordEngine = { size, _, _ -> GridRules(size) }

    internal class GridRules(override val boardSize: Int) : GoRecordEngine {
        private var board = IntArray(boardSize * boardSize)
        private val events = mutableListOf<GoAction>()
        var closed = false
        override val gameType = GameType.GO
        override val currentPlayer get() = PlayerId(events.size % 2)
        override val blackKomiQuarters = 15
        override val handicapStones = 0
        override val phase get() = if (events.takeLast(2) == listOf(GoAction.Pass, GoAction.Pass)) GoPhase.SCORING else GoPhase.PLAYING
        override fun stoneAt(point: BoardPosition) = GoStone.entries[board[index(point)]]
        override fun apply(action: GoAction): ActionResult {
            if (phase != GoPhase.PLAYING) return ActionResult.Rejected(EngineError.ILLEGAL_ACTION)
            if (action == GoAction.Pass) { events += action; return ActionResult.Accepted }
            if (action !is GoAction.Place || action.point.x !in 0 until boardSize || action.point.y !in 0 until boardSize ||
                board[index(action.point)] != 0) return ActionResult.Rejected(EngineError.ILLEGAL_ACTION)
            val old = board.copyOf(); val color = events.size % 2 + 1; board[index(action.point)] = color
            neighbors(action.point).filter { board[index(it)] != 0 && board[index(it)] != color }
                .map(::group).distinctBy { it.toSet() }.filter { liberties(it).isEmpty() }
                .flatten().forEach { board[index(it)] = 0 }
            if (liberties(group(action.point)).isEmpty()) { board = old; return ActionResult.Rejected(EngineError.ILLEGAL_ACTION) }
            events += action; return ActionResult.Accepted
        }
        override fun undo() = false
        override fun serialize(): ByteArray = ByteArray(2 + events.size * 2).also { out ->
            out[0] = boardSize.toByte(); out[1] = events.size.toByte()
            events.forEachIndexed { i, event -> val p = if (event is GoAction.Place) event.point.y * boardSize + event.point.x else boardSize * boardSize
                out[2 + i * 2] = p.toByte(); out[3 + i * 2] = (p ushr 8).toByte() }
        }
        override fun restore(data: ByteArray): RestoreResult {
            if (data.size < 2 || data.size != 2 + (data[1].toInt() and 255) * 2 || data[0].toInt() != boardSize)
                return RestoreResult.Rejected(EngineError.CORRUPTED_DATA)
            board.fill(0); events.clear()
            for (o in 2 until data.size step 2) { val n = (data[o].toInt() and 255) or ((data[o + 1].toInt() and 255) shl 8)
                val action = if (n == boardSize * boardSize) GoAction.Pass else GoAction.Place(BoardPosition(n % boardSize, n / boardSize))
                if (n !in 0..board.size || apply(action) != ActionResult.Accepted)
                    return RestoreResult.Rejected(EngineError.CORRUPTED_DATA) }
            return RestoreResult.Restored
        }
        override fun reset() { board.fill(0); events.clear() }
        override fun legalActions(): List<GoAction> {
            if (phase != GoPhase.PLAYING) return emptyList()
            val saved = board.copyOf(); val history = events.toList()
            val moves = board.indices.filter { saved[it] == 0 }.mapNotNull { index ->
                val action = GoAction.Place(BoardPosition(index % boardSize, index / boardSize))
                val legal = apply(action) == ActionResult.Accepted
                board = saved.copyOf(); events.clear(); events.addAll(history)
                action.takeIf { legal }
            }
            return moves + GoAction.Pass
        }
        override fun gameResult() = GameResult.ONGOING
        override fun scoringState() = GoScoringState(phase, 0, false, false, emptyList())
        override fun previewScore(): GoAreaScore? = null
        override fun finalScore(): GoAreaScore? = null
        override fun exportSgf() = "(;GM[1]SZ[$boardSize])"
        override fun restoreSgf(text: String) = RestoreResult.Rejected(EngineError.UNSUPPORTED)
        override fun eventHistory() = events.toList()
        override fun close() { closed = true }
        private fun index(p: BoardPosition) = p.y * boardSize + p.x
        private fun neighbors(p: BoardPosition) = listOf(p.x - 1 to p.y, p.x + 1 to p.y, p.x to p.y - 1, p.x to p.y + 1)
            .filter { it.first in 0 until boardSize && it.second in 0 until boardSize }.map { BoardPosition(it.first, it.second) }
        private fun group(start: BoardPosition): List<BoardPosition> {
            val color = board[index(start)]; val seen = mutableSetOf(start); val queue = ArrayDeque<BoardPosition>(); queue.add(start)
            while (queue.isNotEmpty()) neighbors(queue.removeFirst()).filter { board[index(it)] == color && seen.add(it) }.forEach(queue::add)
            return seen.toList()
        }
        private fun liberties(group: List<BoardPosition>) = group.flatMap(::neighbors).filter { board[index(it)] == 0 }.toSet()
    }
}
