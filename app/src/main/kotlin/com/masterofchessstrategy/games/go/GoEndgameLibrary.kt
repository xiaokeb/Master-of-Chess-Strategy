package com.masterofchessstrategy.games.go

import com.masterofchessstrategy.engine.*
import com.masterofchessstrategy.progress.EndgameRewardTotals

enum class GoEndgameTheme(val label: String) { CAPTURE("提子"), ESCAPE("逃出打吃"), CONNECTION("连接") }
enum class GoEndgameLevel(val label: String, internal val firstClearScore: Int) {
    SIMPLE("简单", 10), MEDIUM("中等", 20), HARD("困难", 40),
}

class GoEndgamePuzzle(
    val id: String,
    val title: String,
    val theme: GoEndgameTheme,
    val level: GoEndgameLevel,
    val instruction: String,
    val success: String,
    val boardSize: Int,
    setup: List<BoardPosition>,
    val answer: BoardPosition,
    captured: Set<BoardPosition> = emptySet(),
    targetStones: Set<BoardPosition> = emptySet(),
    val maxBlackMoves: Int = 1,
) {
    val setup: List<BoardPosition> = java.util.Collections.unmodifiableList(setup.toList())
    val captured: Set<BoardPosition> = java.util.Collections.unmodifiableSet(captured.toSet())
    val targetStones: Set<BoardPosition> = java.util.Collections.unmodifiableSet(targetStones.toSet())
    init {
        require(id.matches(Regex("[a-z0-9-]{1,48}")) && title.isNotBlank())
        require(boardSize in setOf(9, 13, 19) && setup.size in 2..128 && setup.size % 2 == 0)
        require(instruction.isNotBlank() && success.isNotBlank())
        require((setup + answer + captured + targetStones).all { it.x in 0 until boardSize && it.y in 0 until boardSize })
        require(setup.distinct().size == setup.size && answer !in setup && captured.all(setup::contains))
        require(targetStones.all(setup::contains))
        require(maxBlackMoves in 1..4 && (maxBlackMoves == 1 || theme == GoEndgameTheme.CAPTURE))
        require(when (theme) {
            GoEndgameTheme.CAPTURE -> captured.isNotEmpty() && targetStones.isEmpty()
            GoEndgameTheme.ESCAPE -> captured.isEmpty() && targetStones.size == 1
            GoEndgameTheme.CONNECTION -> captured.isEmpty() && targetStones.size >= 2
        })
    }
}

class GoEndgamePlan internal constructor(
    val puzzle: GoEndgamePuzzle,
    bytes: ByteArray,
    internal val tacticalCapture: GoTacticalCapturePlan? = null,
) {
    private val initial = bytes.copyOf()
    fun copyInitialBytes() = initial.copyOf()
}

data class GoEndgameState(
    val puzzle: GoEndgamePuzzle,
    val stones: List<Int>,
    val completed: Boolean = false,
    val attempts: Int = 0,
    val lastMoveIndex: Int? = null,
    val feedback: String = puzzle.instruction,
)

/** Project-authored seed positions. They are rules-verified exercises, not a licensed classic problem set. */
object GoEndgameLibrary {
    const val CONTENT_VERSION = 1
    private fun p(x: Int, y: Int) = BoardPosition(x, y)
    private fun puzzle(id: String, title: String, theme: GoEndgameTheme, level: GoEndgameLevel,
        instruction: String, success: String, vararg setup: Pair<Int, Int>, answer: Pair<Int, Int>,
        captured: Set<Pair<Int, Int>> = emptySet(), targets: Set<Pair<Int, Int>> = emptySet()) = GoEndgamePuzzle(id, title, theme, level,
        instruction, success, 9, setup.map { p(it.first, it.second) }, p(answer.first, answer.second),
        captured.map { p(it.first, it.second) }.toSet(), targets.map { p(it.first, it.second) }.toSet())

    val puzzles: List<GoEndgamePuzzle> = listOf(
        puzzle("corner-capture", "角上一口气", GoEndgameTheme.CAPTURE, GoEndgameLevel.SIMPLE,
            "黑先：找到角上白子的最后一口气。", "白子被提走，角部重新成为空点。",
            1 to 0, 0 to 0, answer = 0 to 1, captured = setOf(0 to 0)),
        puzzle("side-capture", "边上一口气", GoEndgameTheme.CAPTURE, GoEndgameLevel.SIMPLE,
            "黑先：封住边上白子的唯一出口。", "边上白子已经没有气并被提走。",
            0 to 1, 1 to 1, 1 to 0, 8 to 8, 2 to 1, 7 to 7,
            answer = 1 to 2, captured = setOf(1 to 1)),
        puzzle("escape-atari", "逃出打吃", GoEndgameTheme.ESCAPE, GoEndgameLevel.SIMPLE,
            "黑先：中央黑子只剩一口气，把它连向外侧。", "黑子获得了新的气，已经逃出打吃。",
            1 to 1, 0 to 1, 8 to 8, 1 to 0, 7 to 7, 2 to 1, answer = 1 to 2, targets = setOf(1 to 1)),
        puzzle("bridge-groups", "连接两子", GoEndgameTheme.CONNECTION, GoEndgameLevel.SIMPLE,
            "黑先：在两颗黑子之间落子，把它们连接起来。", "三颗黑子已正交相连。",
            0 to 1, 8 to 8, 2 to 1, 7 to 7, answer = 1 to 1, targets = setOf(0 to 1, 2 to 1)),
        puzzle("capture-pair", "提走两子", GoEndgameTheme.CAPTURE, GoEndgameLevel.MEDIUM,
            "黑先：白方两子相连，封住它们共同的最后一口气。", "两颗白子作为同一棋组被一起提走。",
            0 to 1, 1 to 1, 1 to 0, 2 to 1, 2 to 0, 8 to 8, 3 to 1, 7 to 7,
            1 to 2, 6 to 6, answer = 2 to 2, captured = setOf(1 to 1, 2 to 1)),
        puzzle("connect-under-pressure", "受压连接", GoEndgameTheme.CONNECTION, GoEndgameLevel.MEDIUM,
            "黑先：连接两颗黑子，同时扩大这组棋的气。", "连接完成，局部棋组可以整体计算气。",
            2 to 2, 1 to 2, 2 to 4, 3 to 2, 8 to 8, 7 to 7, answer = 2 to 3, targets = setOf(2 to 2, 2 to 4)),
        GoTacticalCaptureLibrary.twoCorners.let { puzzle ->
            GoEndgamePuzzle(puzzle.id, puzzle.title, GoEndgameTheme.CAPTURE, GoEndgameLevel.HARD,
                "黑先：两手内提走两个角上的目标白子。", "目标白子已全部提走。", puzzle.boardSize,
                puzzle.setup, puzzle.firstMove, captured = puzzle.targets, maxBlackMoves = puzzle.maxBlackMoves)
        },
        GoEndgamePuzzle("corner-counter-capture", "反提救角", GoEndgameTheme.ESCAPE, GoEndgameLevel.HARD,
            "黑先：左上边上的黑子只剩一口气。找到一手棋，让它至少有两口气。",
            "角上的白子被提走，受打吃的黑子获得第二口气。", 13,
            listOf(p(1, 0), p(0, 0), p(10, 10), p(2, 0), p(10, 9), p(2, 1), p(9, 10), p(1, 2)),
            p(0, 1), targetStones = setOf(p(1, 0))),
        GoEndgamePuzzle("connect-between-white-groups", "夹缝双路", GoEndgameTheme.CONNECTION, GoEndgameLevel.HARD,
            "黑先：在白棋围住的中央，把左右两组黑子连成一组。",
            "左右黑棋已经连成一组。另一处空点也能完成连接。", 19,
            listOf(p(8, 8), p(7, 8), p(8, 9), p(7, 9), p(10, 8), p(11, 8), p(10, 9), p(11, 9),
                p(8, 10), p(9, 10), p(10, 7), p(9, 7)), p(9, 8), targetStones = setOf(p(8, 8), p(10, 8))),
    )

    init { require(puzzles.map { it.id }.toSet().size == puzzles.size) }
    fun puzzle(id: String): GoEndgamePuzzle? = puzzles.firstOrNull { it.id == id }

    internal fun rewards(completedIds: Set<String>): EndgameRewardTotals {
        val completed = puzzles.filter { it.id in completedIds }
        return EndgameRewardTotals(completed.size, completed.size, completed.sumOf { it.level.firstClearScore })
    }

    fun open(puzzle: GoEndgamePuzzle,
        engineFactory: (Int, Int, Int) -> GoRecordEngine = { size, komi, handicap -> NativeGoEngine(size, komi, handicap) },
    ): GoEndgamePlan {
        if (puzzle.maxBlackMoves > 1) {
            val capture = GoTacticalCapturePuzzle(puzzle.id, puzzle.title, puzzle.boardSize, puzzle.setup,
                puzzle.captured, puzzle.answer, puzzle.maxBlackMoves)
            val plan = GoTacticalCaptureLibrary.open(capture, engineFactory)
            return GoEndgamePlan(puzzle, plan.copyInitialBytes(), plan)
        }
        val initial = engineFactory(puzzle.boardSize, 15, 0).use { engine ->
            require(engine.boardSize == puzzle.boardSize && engine.handicapStones == 0 && engine.blackKomiQuarters == 15)
            puzzle.setup.forEach { require(engine.apply(GoAction.Place(it)) == ActionResult.Accepted) {
                "残局 ${puzzle.id} 初始着手不合法。" } }
            require(engine.phase == GoPhase.PLAYING && engine.gameResult() == GameResult.ONGOING)
            require(engine.currentPlayer.value == GoPlayer.BLACK.code && engine.stoneAt(puzzle.answer) == GoStone.EMPTY)
            require(engine.eventHistory() == puzzle.setup.map { GoAction.Place(it) })
            puzzle.captured.forEach { require(engine.stoneAt(it) == GoStone.WHITE) }
            puzzle.targetStones.forEach { require(engine.stoneAt(it) == GoStone.BLACK) }
            if (puzzle.theme == GoEndgameTheme.ESCAPE) require(
                goEndgameLiberties(engine, goEndgameGroup(engine, puzzle.targetStones.single())).size == 1)
            if (puzzle.theme == GoEndgameTheme.CONNECTION) require(
                puzzle.targetStones.map { goEndgameGroup(engine, it) }.distinct().size >= 2)
            engine.serialize()
        }
        engineFactory(puzzle.boardSize, 15, 0).use { solution ->
            require(solution.restore(initial) == RestoreResult.Restored)
            require(solution.apply(GoAction.Place(puzzle.answer)) == ActionResult.Accepted) {
                "残局 ${puzzle.id} 答案不合法。" }
            require(solution.stoneAt(puzzle.answer) == GoStone.BLACK)
            require(goEndgameGoalReached(puzzle, solution)) { "残局 ${puzzle.id} 未达到题目目标。" }
            require(solution.phase == GoPhase.PLAYING && solution.gameResult() == GameResult.ONGOING)
        }
        return GoEndgamePlan(puzzle, initial)
    }
}

/** Each attempt restores an independent candidate, so a wrong move never mutates the exercise. */
class GoEndgameSession(
    private val plan: GoEndgamePlan,
    private val engineFactory: (Int, Int, Int) -> GoRecordEngine = { size, komi, handicap -> NativeGoEngine(size, komi, handicap) },
) {
    private val tacticalCapture = plan.tacticalCapture?.let { GoTacticalCaptureSession(it, engineFactory) }
    private var state = initialState()
    fun state() = state.copy(stones = state.stones.toList())
    fun reset(): GoEndgameState {
        state = tacticalCapture?.reset()?.toEndgameState() ?: initialState()
        return state()
    }

    fun answer(point: BoardPosition): GoEndgameState {
        if (state.completed) return state()
        tacticalCapture?.let {
            state = it.answer(point).toEndgameState()
            return state()
        }
        val puzzle = plan.puzzle
        var stones: List<Int>? = null
        var accepted = false
        engineFactory(puzzle.boardSize, 15, 0).use { candidate ->
            check(candidate.restore(plan.copyInitialBytes()) == RestoreResult.Restored)
            accepted = candidate.apply(GoAction.Place(point)) == ActionResult.Accepted
            if (accepted && goEndgameGoalReached(puzzle, candidate, point)) {
                stones = snapshot(candidate)
            }
        }
        state = when {
            !accepted -> state.copy(attempts = state.attempts + 1, feedback = "这手不符合当前围棋规则，题面保持不变。")
            stones == null -> state.copy(attempts = state.attempts + 1, feedback = "这手合法，但没有完成本题目标；请继续观察局部的气。")
            else -> state.copy(stones = checkNotNull(stones), completed = true,
                attempts = state.attempts + 1, lastMoveIndex = point.y * puzzle.boardSize + point.x,
                feedback = puzzle.success)
        }
        return state()
    }

    private fun initialState(): GoEndgameState = tacticalCapture?.state()?.toEndgameState()
        ?: engineFactory(plan.puzzle.boardSize, 15, 0).use { engine ->
            check(engine.restore(plan.copyInitialBytes()) == RestoreResult.Restored)
            GoEndgameState(plan.puzzle, snapshot(engine))
        }

    private fun GoTacticalCaptureState.toEndgameState(): GoEndgameState {
        val feedbackText = when {
            completed -> plan.puzzle.success
            attempts == 0 -> plan.puzzle.instruction
            else -> "$feedback 剩余 ${puzzle.maxBlackMoves - blackMoves} 手。"
        }
        return GoEndgameState(plan.puzzle, stones, completed, attempts,
            lastMoveIndex.takeIf { blackMoves > 0 }, feedbackText)
    }
    private fun snapshot(engine: GoRuleEngine) = List(plan.puzzle.boardSize * plan.puzzle.boardSize) { index ->
        engine.stoneAt(BoardPosition(index % plan.puzzle.boardSize, index / plan.puzzle.boardSize)).code
    }
}

internal fun goEndgameGoalReached(puzzle: GoEndgamePuzzle, engine: GoRuleEngine,
    move: BoardPosition = puzzle.answer,
): Boolean = when (puzzle.theme) {
    GoEndgameTheme.CAPTURE -> puzzle.captured.all { engine.stoneAt(it) == GoStone.EMPTY }
    GoEndgameTheme.ESCAPE -> {
        val target = puzzle.targetStones.single()
        // A capture outside the group can also give the endangered stones a new liberty.
        engine.stoneAt(target) == GoStone.BLACK && goEndgameLiberties(engine, goEndgameGroup(engine, target)).size >= 2
    }
    GoEndgameTheme.CONNECTION -> {
        val group = goEndgameGroup(engine, move)
        engine.stoneAt(move) == GoStone.BLACK && group.containsAll(puzzle.targetStones)
    }
}

private fun goEndgameNeighbors(point: BoardPosition, size: Int): List<BoardPosition> =
    listOf(point.x - 1 to point.y, point.x + 1 to point.y, point.x to point.y - 1, point.x to point.y + 1)
        .filter { it.first in 0 until size && it.second in 0 until size }.map { BoardPosition(it.first, it.second) }

internal fun goEndgameGroup(engine: GoRuleEngine, start: BoardPosition): Set<BoardPosition> {
    val color = engine.stoneAt(start)
    if (color == GoStone.EMPTY) return emptySet()
    val group = mutableSetOf(start)
    val pending = ArrayDeque<BoardPosition>().apply { add(start) }
    while (pending.isNotEmpty()) goEndgameNeighbors(pending.removeFirst(), engine.boardSize)
        .filter { engine.stoneAt(it) == color && group.add(it) }.forEach(pending::add)
    return group
}

internal fun goEndgameLiberties(engine: GoRuleEngine, group: Set<BoardPosition>): Set<BoardPosition> =
    group.flatMap { goEndgameNeighbors(it, engine.boardSize) }.filter { engine.stoneAt(it) == GoStone.EMPTY }.toSet()
