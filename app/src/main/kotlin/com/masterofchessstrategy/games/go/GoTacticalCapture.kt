package com.masterofchessstrategy.games.go

import com.masterofchessstrategy.engine.*

class GoTacticalCapturePuzzle(
    val id: String,
    val title: String,
    val boardSize: Int,
    setup: List<BoardPosition>,
    targets: Set<BoardPosition>,
    val firstMove: BoardPosition,
    val maxBlackMoves: Int,
) {
    val setup: List<BoardPosition> = java.util.Collections.unmodifiableList(setup.toList())
    val targets: Set<BoardPosition> = java.util.Collections.unmodifiableSet(targets.toSet())
    init {
        require(id.matches(Regex("[a-z0-9-]{1,48}")) && title.isNotBlank())
        require(boardSize in setOf(9, 13, 19) && maxBlackMoves in 2..4)
        require(setup.size in 2..128 && setup.size % 2 == 0 && setup.distinct().size == setup.size)
        require(targets.isNotEmpty() && targets.all(setup::contains) && firstMove !in setup)
        require((setup + targets + firstMove).all { it.x in 0 until boardSize && it.y in 0 until boardSize })
    }
}

enum class GoCaptureProofResult { PROVEN, REFUTED, UNRESOLVED }

class GoCaptureAnalysis internal constructor(
    val result: GoCaptureProofResult,
    val nodes: Int,
    val defenses: Int,
    val defense: GoAction?,
    bytes: ByteArray? = null,
) {
    private val continuation = bytes?.copyOf()
    fun copyContinuationBytes() = continuation?.copyOf()
}

/** Bounded proof over full MOCG histories. No evaluation or scripted response adjudicates the goal. */
class GoCaptureSolver(
    private val puzzle: GoTacticalCapturePuzzle,
    private val engineFactory: (Int, Int, Int) -> GoRecordEngine = { size, komi, handicap -> NativeGoEngine(size, komi, handicap) },
    private val nodeLimit: Int = 10_000,
) {
    init { require(nodeLimit in 1..100_000) }
    private class LimitReached : RuntimeException()
    private var nodes = 0

    /** Input is the position just after Black's accepted move; all legal White replies are considered. */
    fun afterBlackMove(bytes: ByteArray, remainingBlackMoves: Int): GoCaptureAnalysis {
        require(remainingBlackMoves in 0 until puzzle.maxBlackMoves)
        nodes = 0
        return try { whiteReplies(bytes, remainingBlackMoves) }
        catch (_: LimitReached) { GoCaptureAnalysis(GoCaptureProofResult.UNRESOLVED, nodes, 0, null) }
    }

    private fun whiteReplies(bytes: ByteArray, remaining: Int): GoCaptureAnalysis = restored(bytes) { engine ->
        if (goal(engine)) return@restored GoCaptureAnalysis(GoCaptureProofResult.PROVEN, nodes, 0, null, bytes)
        if (remaining == 0 || engine.phase != GoPhase.PLAYING || engine.currentPlayer.value != GoPlayer.WHITE.code)
            return@restored GoCaptureAnalysis(GoCaptureProofResult.REFUTED, nodes, 0, null)
        val replies = moves(engine)
        if (replies.isEmpty()) return@restored GoCaptureAnalysis(GoCaptureProofResult.REFUTED, nodes, 0, null)
        var chosen: GoAction? = null
        var continuation: ByteArray? = null
        var count = 0
        for (reply in replies) {
            val next = restored(bytes) { candidate ->
                check(candidate.apply(reply) == ActionResult.Accepted) { "引擎列出的防守着不合法。" }
                candidate.serialize()
            }
            count++
            if (!blackCanWin(next, remaining)) return@restored GoCaptureAnalysis(
                GoCaptureProofResult.REFUTED, nodes, count, reply)
            if (chosen == null) { chosen = reply; continuation = next }
        }
        GoCaptureAnalysis(GoCaptureProofResult.PROVEN, nodes, count, chosen, continuation)
    }

    private fun blackCanWin(bytes: ByteArray, remaining: Int): Boolean = restored(bytes) { engine ->
        if (goal(engine)) return@restored true
        if (remaining <= 0 || engine.phase != GoPhase.PLAYING || engine.currentPlayer.value != GoPlayer.BLACK.code)
            return@restored false
        var candidates = moves(engine)
        if (remaining == 1) {
            // A single placement can only capture target groups whose sole liberty is that point.
            val groups = puzzle.targets.filter { engine.stoneAt(it) == GoStone.WHITE }
                .map { goEndgameGroup(engine, it) }.distinct()
            if (groups.isEmpty()) return@restored false
            val liberties = groups.map { goEndgameLiberties(engine, it) }
            if (liberties.any { it.size != 1 }) return@restored false
            val finalPoints = liberties.reduce { a, b -> a.intersect(b) }
            candidates = candidates.filter { it is GoAction.Place && it.point in finalPoints }
        }
        for (move in candidates) {
            val won = restored(bytes) { candidate ->
                check(candidate.apply(move) == ActionResult.Accepted) { "引擎列出的玩家着不合法。" }
                if (goal(candidate)) true else whiteReplies(candidate.serialize(), remaining - 1).result == GoCaptureProofResult.PROVEN
            }
            if (won) return@restored true
        }
        false
    }

    private fun moves(engine: GoRecordEngine): List<GoAction> = engine.legalActions()
        .filter { it is GoAction.Place || it == GoAction.Pass }.distinct().sortedWith(compareBy<GoAction> {
            if (it is GoAction.Place) puzzle.targets.minOf { target ->
                kotlin.math.abs(it.point.x - target.x) + kotlin.math.abs(it.point.y - target.y)
            } else Int.MAX_VALUE
        }.thenBy { if (it is GoAction.Place) it.point.y * puzzle.boardSize + it.point.x else Int.MAX_VALUE })

    private fun <T> restored(bytes: ByteArray, block: (GoRecordEngine) -> T): T {
        if (nodes >= nodeLimit) throw LimitReached()
        nodes++
        require(bytes.isNotEmpty() && bytes.size <= 1_000_000)
        return engineFactory(puzzle.boardSize, 15, 0).use { engine ->
            check(engine.restore(bytes) == RestoreResult.Restored) { "多步残局记录无法恢复。" }
            check(engine.boardSize == puzzle.boardSize && engine.blackKomiQuarters == 15 && engine.handicapStones == 0)
            block(engine)
        }
    }

    internal fun goal(engine: GoRuleEngine) = puzzle.targets.all { engine.stoneAt(it) != GoStone.WHITE }
}

class GoTacticalCapturePlan internal constructor(val puzzle: GoTacticalCapturePuzzle, bytes: ByteArray,
    val firstMoveProof: GoCaptureAnalysis,
) {
    private val initial = bytes.copyOf()
    fun copyInitialBytes() = initial.copyOf()
}

object GoTacticalCaptureLibrary {
    val twoCorners = GoTacticalCapturePuzzle("two-corners-two-moves", "两角取子", 9,
        listOf(1 to 0, 0 to 0, 0 to 2, 8 to 8, 1 to 1, 4 to 4,
            7 to 8, 5 to 4, 8 to 6, 4 to 5, 7 to 7, 5 to 5).map { BoardPosition(it.first, it.second) },
        setOf(BoardPosition(0, 0), BoardPosition(8, 8)), BoardPosition(0, 1), 2)

    fun open(puzzle: GoTacticalCapturePuzzle = twoCorners,
        engineFactory: (Int, Int, Int) -> GoRecordEngine = { size, komi, handicap -> NativeGoEngine(size, komi, handicap) },
        nodeLimit: Int = 10_000,
    ): GoTacticalCapturePlan {
        val solver = GoCaptureSolver(puzzle, engineFactory, nodeLimit)
        var afterFirst: ByteArray? = null
        val initial = engineFactory(puzzle.boardSize, 15, 0).use { engine ->
            require(engine.boardSize == puzzle.boardSize && engine.blackKomiQuarters == 15 && engine.handicapStones == 0)
            require(engine.eventHistory().isEmpty() && engine.phase == GoPhase.PLAYING)
            puzzle.setup.forEach { require(engine.apply(GoAction.Place(it)) == ActionResult.Accepted) { "多步残局题面不合法。" } }
            require(engine.eventHistory() == puzzle.setup.map { GoAction.Place(it) })
            require(engine.currentPlayer.value == GoPlayer.BLACK.code && engine.phase == GoPhase.PLAYING)
            require(puzzle.targets.all { engine.stoneAt(it) == GoStone.WHITE })
            val bytes = engine.serialize()
            require(engine.apply(GoAction.Place(puzzle.firstMove)) == ActionResult.Accepted)
            afterFirst = engine.serialize()
            bytes
        }
        val proof = solver.afterBlackMove(checkNotNull(afterFirst), puzzle.maxBlackMoves - 1)
        require(proof.result == GoCaptureProofResult.PROVEN) { "多步残局尚未通过全部合法防守验证。" }
        return GoTacticalCapturePlan(puzzle, initial, proof)
    }
}

data class GoTacticalCaptureState(
    val puzzle: GoTacticalCapturePuzzle,
    val stones: List<Int>,
    val blackMoves: Int = 0,
    val attempts: Int = 0,
    val completed: Boolean = false,
    val lastMoveIndex: Int? = null,
    val defense: GoAction? = null,
    val feedback: String = "黑先：在${puzzle.maxBlackMoves}手内提走目标白子，对手按合法着防守。",
)

/** Accepted positions are installed only after the whole bounded defense proof and chosen reply succeed. */
class GoTacticalCaptureSession(
    private val plan: GoTacticalCapturePlan,
    private val engineFactory: (Int, Int, Int) -> GoRecordEngine = { size, komi, handicap -> NativeGoEngine(size, komi, handicap) },
    private val nodeLimit: Int = 10_000,
) {
    private var checkpoint = plan.copyInitialBytes()
    private var current = snapshot(checkpoint)
    fun state() = current.copy(stones = current.stones.toList())
    fun copyCheckpointBytes() = checkpoint.copyOf()
    fun reset(): GoTacticalCaptureState {
        val initial = plan.copyInitialBytes()
        val next = snapshot(initial)
        checkpoint = initial; current = next
        return state()
    }

    fun answer(point: BoardPosition): GoTacticalCaptureState {
        if (current.completed) return state()
        val puzzle = plan.puzzle
        val solver = GoCaptureSolver(puzzle, engineFactory, nodeLimit)
        var accepted = false
        var proof: GoCaptureAnalysis? = null
        engineFactory(puzzle.boardSize, 15, 0).use { engine ->
            check(engine.restore(checkpoint) == RestoreResult.Restored)
            check(engine.phase == GoPhase.PLAYING && engine.currentPlayer.value == GoPlayer.BLACK.code)
            accepted = engine.apply(GoAction.Place(point)) == ActionResult.Accepted
            if (accepted) proof = solver.afterBlackMove(engine.serialize(), puzzle.maxBlackMoves - current.blackMoves - 1)
        }
        val result = proof?.result
        if (!accepted || result != GoCaptureProofResult.PROVEN) {
            current = current.copy(attempts = current.attempts + 1, feedback = when {
                !accepted -> "这手不符合围棋规则，局面保持不变。"
                result == GoCaptureProofResult.UNRESOLVED -> "变化验证超过限额，本次未推进，请重试或重置。"
                proof?.defense == null -> "这手没有在剩余步数内完成目标，局面保持不变。"
                else -> "对手有防守使目标无法在剩余步数内完成，局面保持不变。"
            })
            return state()
        }
        val validated = checkNotNull(proof)
        val bytes = checkNotNull(validated.copyContinuationBytes())
        val next = snapshot(bytes).copy(blackMoves = current.blackMoves + 1, attempts = current.attempts + 1,
            defense = validated.defense)
        current = next.copy(feedback = if (next.completed) "目标白子已全部提走。" else "对手已完成合法防守，继续用黑棋完成目标。")
        checkpoint = bytes
        return state()
    }

    private fun snapshot(bytes: ByteArray): GoTacticalCaptureState =
        engineFactory(plan.puzzle.boardSize, 15, 0).use { engine ->
            check(engine.restore(bytes) == RestoreResult.Restored)
            val size = plan.puzzle.boardSize
            val completed = GoCaptureSolver(plan.puzzle, engineFactory, nodeLimit).goal(engine)
            check(completed || engine.phase == GoPhase.PLAYING && engine.currentPlayer.value == GoPlayer.BLACK.code)
            GoTacticalCaptureState(plan.puzzle, List(size * size) { engine.stoneAt(BoardPosition(it % size, it / size)).code },
                completed = completed, lastMoveIndex = engine.eventHistory().filterIsInstance<GoAction.Place>().lastOrNull()?.point
                    ?.let { it.y * size + it.x })
        }
}
