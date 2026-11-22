package com.masterofchessstrategy.games.go

import com.masterofchessstrategy.engine.*
import com.masterofchessstrategy.progress.EndgameRewardTotals

enum class GoEndgameTheme(val label: String) { CAPTURE("提子"), ESCAPE("逃出打吃"), CONNECTION("连接"), LIFE("做活") }
enum class GoEndgameLevel(val label: String, internal val firstClearScore: Int) {
    SIMPLE("简单", 10), MEDIUM("中等", 20), HARD("困难", 40), MASTER("大师", 80),
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
    val requiredEyes: Int = 2,
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
        require(maxBlackMoves in 1..4 && (maxBlackMoves == 1 || theme == GoEndgameTheme.CAPTURE ||
            theme == GoEndgameTheme.LIFE && maxBlackMoves == 2))
        require(requiredEyes == 2 || theme == GoEndgameTheme.LIFE && requiredEyes == 3)
        require(when (theme) {
            GoEndgameTheme.CAPTURE -> captured.isNotEmpty() && targetStones.isEmpty()
            GoEndgameTheme.ESCAPE -> captured.isEmpty() && targetStones.size == 1
            GoEndgameTheme.CONNECTION -> captured.isEmpty() && targetStones.size >= 2
            GoEndgameTheme.LIFE -> captured.isEmpty() && targetStones.isNotEmpty()
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
    val trueEyes: Set<BoardPosition> = emptySet(),
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
        GoTacticalCaptureLibrary.cornerLibertyRace.let { puzzle ->
            GoEndgamePuzzle(puzzle.id, puzzle.title, GoEndgameTheme.CAPTURE, GoEndgameLevel.MASTER,
                "黑先：三手内提走标记的两颗白子。注意右下角白棋的两口气。", "目标白子已全部提走。",
                puzzle.boardSize, puzzle.setup, puzzle.firstMove, captured = puzzle.targets, maxBlackMoves = puzzle.maxBlackMoves)
        },
        puzzle("corner-separate-eyes", "三空分双眼", GoEndgameTheme.LIFE, GoEndgameLevel.MEDIUM,
            "黑先：边角黑棋只剩三个相连的空点。下一手把它们分成两只真眼。",
            "同一个黑棋棋组围住两只互不相连的真眼，已做活。",
            0 to 1, 0 to 2, 1 to 1, 1 to 2, 2 to 1, 2 to 2,
            3 to 1, 3 to 2, 3 to 0, 4 to 1, 4 to 0, 5 to 0,
            answer = 1 to 0, targets = setOf(1 to 1)),
        puzzle("link-one-eye-groups", "两组借眼", GoEndgameTheme.LIFE, GoEndgameLevel.HARD,
            "黑先：标记处是两组各有一只眼的黑棋。找一手棋让两组共用双眼。",
            "两组相连，保留原来的两只真眼，已做活。",
            0 to 1, 0 to 2, 1 to 1, 1 to 2, 1 to 0, 2 to 1, 3 to 1, 2 to 2,
            3 to 0, 3 to 2, 4 to 1, 4 to 2, 5 to 1, 5 to 2, 5 to 0, 6 to 1,
            answer = 2 to 0, targets = setOf(0 to 1, 4 to 1)),
        GoEndgamePuzzle("corner-life-counter-capture", "护眼反提", GoEndgameTheme.LIFE, GoEndgameLevel.MASTER,
            "黑先：在两手内做出两只真眼。白方可以点入眼形，注意提掉侵入的白子。",
            "黑棋提走侵入棋子，保住两只真眼。", 9,
            listOf(p(0, 1), p(0, 2), p(1, 1), p(1, 2), p(2, 1), p(2, 2), p(3, 1), p(3, 2),
                p(4, 1), p(4, 2), p(4, 0), p(5, 2), p(8, 8), p(5, 1), p(7, 8), p(5, 0)),
            p(1, 0), targetStones = setOf(p(0, 1), p(3, 1)), maxBlackMoves = 2),
        run {
            val empty = setOf(p(5, 5), p(6, 5), p(7, 5), p(6, 6))
            val black = (4..7).flatMap { y -> (4..8).map { x -> p(x, y) } }.filterNot { it in empty }
            val white = (0..3).flatMap { y -> (0..3).map { x -> p(x, y) } }
            GoEndgamePuzzle("interior-three-eyes", "四空分三眼", GoEndgameTheme.LIFE, GoEndgameLevel.HARD,
                "黑先。在中央黑棋围住的四个空点中下一手，把相连的空点分成三只真眼。",
                "同一棋组围住三只互不相连的真眼，白方无法填入其中任何一只。", 13,
                black.zip(white).flatMap { (own, other) -> listOf(own, other) },
                p(6, 5), targetStones = setOf(p(5, 4), p(6, 7)), requiredEyes = 3)
        },
        puzzle("shared-liberty-double-capture", "一气双提", GoEndgameTheme.CAPTURE, GoEndgameLevel.HARD,
            "黑先。两颗白子各只剩一口气，找出它们共有的气，一手提掉两子。",
            "两颗白子虽不相连，却因共同的最后一口气被填，同时被提走。",
            2 to 4, 3 to 4, 3 to 3, 5 to 4, 3 to 5, 0 to 0,
            6 to 4, 1 to 0, 5 to 3, 2 to 0, 5 to 5, 3 to 0,
            answer = 4 to 4, captured = setOf(3 to 4, 5 to 4)),
        GoEndgamePuzzle("join-three-groups", "一点接三组", GoEndgameTheme.CONNECTION, GoEndgameLevel.HARD,
            "黑先。把标出的三组黑棋用一手连成同一组。",
            "三组棋共用同一连接点，现在可以合在一起计算气。", 13,
            listOf(p(5, 6), p(5, 5), p(7, 6), p(7, 5), p(6, 5), p(6, 7)),
            p(6, 6), targetStones = setOf(p(5, 6), p(7, 6), p(6, 5))),
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
        if (puzzle.maxBlackMoves > 1 && puzzle.theme == GoEndgameTheme.CAPTURE) {
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
            if (puzzle.theme == GoEndgameTheme.LIFE) require(!goEndgameGoalReached(puzzle, engine))
            engine.serialize()
        }
        engineFactory(puzzle.boardSize, 15, 0).use { solution ->
            require(solution.restore(initial) == RestoreResult.Restored)
            require(solution.apply(GoAction.Place(puzzle.answer)) == ActionResult.Accepted) {
                "残局 ${puzzle.id} 答案不合法。" }
            require(solution.stoneAt(puzzle.answer) == GoStone.BLACK)
            require(solution.phase == GoPhase.PLAYING && solution.gameResult() == GameResult.ONGOING)
            if (puzzle.maxBlackMoves == 2) {
                require(!goEndgameGoalReached(puzzle, solution))
                require(goEndgameLifeDefense(puzzle, solution, engineFactory) != null) {
                    "残局 ${puzzle.id} 无法应对全部合法防守。" }
            } else require(goEndgameGoalReached(puzzle, solution)) { "残局 ${puzzle.id} 未达到题目目标。" }
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
    private var lifeContinuation: ByteArray? = null
    private var state = initialState()
    fun state() = state.copy(stones = state.stones.toList(), trueEyes = state.trueEyes.toSet())
    fun reset(): GoEndgameState {
        lifeContinuation = null
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
        var eyes: Set<BoardPosition> = emptySet()
        var defense: ByteArray? = null
        var lastMove = point.y * puzzle.boardSize + point.x
        engineFactory(puzzle.boardSize, 15, 0).use { candidate ->
            check(candidate.restore(lifeContinuation ?: plan.copyInitialBytes()) == RestoreResult.Restored)
            accepted = candidate.apply(GoAction.Place(point)) == ActionResult.Accepted
            if (accepted) {
                val reached = if (puzzle.theme == GoEndgameTheme.LIFE) {
                    eyes = goEndgameTrueEyes(puzzle, candidate)
                    eyes.size >= puzzle.requiredEyes
                } else goEndgameGoalReached(puzzle, candidate, point)
                if (reached) {
                    stones = snapshot(candidate)
                } else if (puzzle.maxBlackMoves == 2 && lifeContinuation == null) {
                    defense = goEndgameLifeDefense(puzzle, candidate, engineFactory)
                    defense?.let {
                        check(candidate.restore(it) == RestoreResult.Restored)
                        stones = snapshot(candidate)
                        val reply = candidate.eventHistory().lastOrNull() as? GoAction.Place
                        if (reply != null) lastMove = reply.point.y * puzzle.boardSize + reply.point.x
                    }
                }
            }
        }
        state = when {
            !accepted -> state.copy(attempts = state.attempts + 1, feedback = "这手不符合当前围棋规则，题面保持不变。")
            defense != null -> {
                lifeContinuation = defense
                state.copy(stones = checkNotNull(stones), attempts = state.attempts + 1, lastMoveIndex = lastMove,
                    feedback = "白方已应手。剩余 1 手，请完成双眼。")
            }
            stones == null -> state.copy(attempts = state.attempts + 1, feedback = when {
                puzzle.theme != GoEndgameTheme.LIFE -> "这手合法，但没有完成本题目标；请继续观察局部的气。"
                puzzle.maxBlackMoves == 1 -> {
                    val eyeCount = if (puzzle.requiredEyes == 3) "三" else "两"
                    "这手合法，还需要让同一棋组围住${eyeCount}只真眼。"
                }
                else -> "这手合法，但无法在剩余手数内完成双眼。"
            })
            else -> state.copy(stones = checkNotNull(stones), completed = true,
                attempts = state.attempts + 1, lastMoveIndex = lastMove,
                feedback = puzzle.success, trueEyes = eyes)
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
    GoEndgameTheme.LIFE -> goEndgameTrueEyes(puzzle, engine).size >= puzzle.requiredEyes
}

private fun goEndgameTrueEyes(puzzle: GoEndgamePuzzle, engine: GoRuleEngine): Set<BoardPosition> {
    val target = puzzle.targetStones.first()
    if (engine.stoneAt(target) != GoStone.BLACK) return emptySet()
    val group = goEndgameGroup(engine, target)
    if (!group.containsAll(puzzle.targetStones)) return emptySet()
    // Two isolated empty points surrounded by the same connected group are unfillable by White.
    return goEndgameLiberties(engine, group).filterTo(mutableSetOf()) { eye ->
        goEndgameNeighbors(eye, engine.boardSize).all { it in group }
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

/** Returns a local white reply only after every legal reply has a black solution. */
private fun goEndgameLifeDefense(
    puzzle: GoEndgamePuzzle,
    afterBlack: GoRecordEngine,
    engineFactory: (Int, Int, Int) -> GoRecordEngine,
): ByteArray? {
    check(puzzle.theme == GoEndgameTheme.LIFE && puzzle.maxBlackMoves == 2)
    check(afterBlack.currentPlayer.value == GoPlayer.WHITE.code && afterBlack.phase == GoPhase.PLAYING)
    val record = afterBlack.serialize()
    val nearTargets = compareBy<GoAction> { action ->
        if (action is GoAction.Place) puzzle.targetStones.minOf {
            kotlin.math.abs(it.x - action.point.x) + kotlin.math.abs(it.y - action.point.y)
        } else Int.MAX_VALUE
    }.thenBy { action ->
        if (action is GoAction.Place) action.point.y * puzzle.boardSize + action.point.x else Int.MAX_VALUE
    }
    val replies = afterBlack.legalActions().filter { it is GoAction.Place || it == GoAction.Pass }.sortedWith(nearTargets)
    var selectedReply: ByteArray? = null
    engineFactory(puzzle.boardSize, 15, 0).use { defender ->
        engineFactory(puzzle.boardSize, 15, 0).use { solver ->
            for (reply in replies) {
                check(defender.restore(record) == RestoreResult.Restored)
                check(defender.apply(reply) == ActionResult.Accepted)
                val afterWhite = defender.serialize()
                val solved = defender.legalActions().filterIsInstance<GoAction.Place>().sortedWith(nearTargets).any { move ->
                    check(solver.restore(afterWhite) == RestoreResult.Restored)
                    check(solver.apply(move) == ActionResult.Accepted)
                    goEndgameGoalReached(puzzle, solver)
                }
                if (!solved) return null
                if (selectedReply == null) selectedReply = afterWhite
            }
        }
    }
    return selectedReply
}
