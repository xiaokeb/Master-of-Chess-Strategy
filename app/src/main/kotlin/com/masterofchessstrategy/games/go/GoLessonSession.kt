package com.masterofchessstrategy.games.go

import com.masterofchessstrategy.engine.ActionResult
import com.masterofchessstrategy.engine.BoardPosition
import com.masterofchessstrategy.engine.EngineError
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.GoAction
import com.masterofchessstrategy.engine.GoPhase
import com.masterofchessstrategy.engine.GoPlayer
import com.masterofchessstrategy.engine.GoRuleEngine
import com.masterofchessstrategy.engine.GoStone

sealed interface GoLessonAnswer {
    data class Point(val point: BoardPosition) : GoLessonAnswer
    data object Pass : GoLessonAnswer
    data class Confirm(val actor: GoPlayer) : GoLessonAnswer
    data class Resume(val actor: GoPlayer) : GoLessonAnswer
    data class Result(val result: GameResult) : GoLessonAnswer
}

data class GoLessonState(
    val lesson: GoLesson,
    val stones: List<Int>,
    val phase: GoPhase,
    val currentPlayer: GoPlayer,
    val selectedPoints: Set<Int>,
    val deadStoneIndices: Set<Int>,
    val completed: Boolean,
    val feedback: String,
)

/** A lesson owns a disposable rules session; it never mutates a saved game. */
class GoLessonSession(val lesson: GoLesson, private val engine: GoRuleEngine) : AutoCloseable {
    private val found = mutableSetOf<BoardPosition>()
    private var completed = false
    private var feedback = lesson.instruction

    init {
        try {
            require(engine.boardSize == lesson.boardSize && engine.handicapStones == lesson.handicap)
            for (entry in lesson.setup) {
                val action = when (entry) {
                    is GoLessonSetup.Action -> entry.action
                    is GoLessonSetup.Confirm -> GoAction.ConfirmScore(
                        entry.actor, engine.scoringState().proposalRevision,
                    )
                }
                check(engine.apply(action) == ActionResult.Accepted) { "教程 ${lesson.id} 初始局面无效" }
            }
            if (lesson.goal is GoLessonGoal.PredictResult) {
                check(engine.previewScore()?.result == lesson.goal.result) { "教程预览与答案不一致" }
            }
            if (lesson.goal is GoLessonGoal.FindLiberties) {
                check(groupLiberties(lesson.goal.groupAt) == lesson.goal.points) { "教程气的集合不一致" }
            }
        } catch (error: Exception) {
            engine.close()
            throw error
        }
    }

    fun state(): GoLessonState {
        val size = lesson.boardSize
        val scoring = engine.scoringState()
        return GoLessonState(
            lesson = lesson,
            stones = List(size * size) { index -> engine.stoneAt(
                BoardPosition(index % size, index / size),
            ).code },
            phase = engine.phase,
            currentPlayer = GoPlayer.entries[engine.currentPlayer.value],
            selectedPoints = found.map { it.y * size + it.x }.toSet(),
            deadStoneIndices = scoring.deadStones.map { it.y * size + it.x }.toSet(),
            completed = completed,
            feedback = feedback,
        )
    }

    fun answer(answer: GoLessonAnswer): GoLessonState {
        if (completed) return state()
        val goal = lesson.goal
        when {
            goal is GoLessonGoal.FindLiberties && answer is GoLessonAnswer.Point -> {
                if (answer.point !in goal.points) return retry("这里不是中央黑子的气，观察正交相邻的空点。")
                if (!found.add(answer.point)) return retry("这个点已经找过了，再找另一口气。")
                completed = found == goal.points
                feedback = "已找到 ${found.size}/${goal.points.size} 口气"
            }
            goal is GoLessonGoal.Place && answer is GoLessonAnswer.Point -> {
                if (answer.point != goal.point) return retry("先完成本步骤指定的落点。")
                accept(GoAction.Place(answer.point))
                check(goal.captured.all { engine.stoneAt(it) == GoStone.EMPTY }) { "教程提子结果不一致" }
                completed = true
            }
            goal is GoLessonGoal.ForbiddenPoint && answer is GoLessonAnswer.Point -> {
                if (answer.point != goal.point) return retry("这个点不是题目要求辨认的禁着或立即回提点。")
                val before = engine.serialize()
                check(engine.apply(GoAction.Place(answer.point)) == ActionResult.Rejected(EngineError.ILLEGAL_ACTION)) {
                    "教程禁着点被规则引擎接受"
                }
                check(engine.serialize().contentEquals(before)) { "拒绝的着手改变了教程局面" }
                completed = true
            }
            goal == GoLessonGoal.SecondPass && answer == GoLessonAnswer.Pass -> {
                accept(GoAction.Pass)
                check(engine.phase == GoPhase.SCORING && engine.gameResult() == GameResult.ONGOING)
                completed = true
            }
            goal is GoLessonGoal.Confirm && answer is GoLessonAnswer.Confirm -> {
                if (answer.actor != goal.actor) return retry("请由尚未确认的一方核对当前集合。")
                accept(GoAction.ConfirmScore(answer.actor, engine.scoringState().proposalRevision))
                check(engine.phase == GoPhase.FINISHED && engine.finalScore() != null)
                completed = true
            }
            goal is GoLessonGoal.Resume && answer is GoLessonAnswer.Resume -> {
                if (answer.actor != goal.actor) return retry("主张死子的一方先下，本题是黑方。")
                accept(GoAction.ResumePlay(answer.actor))
                check(engine.phase == GoPhase.PLAYING && engine.currentPlayer.value == answer.actor.code)
                check(engine.scoringState().deadStones.isEmpty())
                completed = true
            }
            goal is GoLessonGoal.PredictResult && answer is GoLessonAnswer.Result -> {
                if (answer.result != goal.result) return retry("两方评估相同，继续把黑贴3¾子计入比较。")
                completed = true
            }
            else -> return retry("按当前步骤的提示操作。")
        }
        if (completed) feedback = lesson.success
        return state()
    }

    private fun retry(message: String): GoLessonState {
        feedback = message
        return state()
    }

    private fun accept(action: GoAction) {
        check(engine.apply(action) == ActionResult.Accepted) { "教程动作被规则引擎拒绝" }
    }

    private fun groupLiberties(start: BoardPosition): Set<BoardPosition> {
        val color = engine.stoneAt(start)
        check(color != GoStone.EMPTY)
        val size = lesson.boardSize
        val group = ArrayDeque<BoardPosition>()
        val visited = mutableSetOf(start)
        val liberties = mutableSetOf<BoardPosition>()
        group.add(start)
        while (group.isNotEmpty()) {
            val point = group.removeFirst()
            for ((x, y) in listOf(point.x - 1 to point.y, point.x + 1 to point.y,
                point.x to point.y - 1, point.x to point.y + 1)) {
                if (x !in 0 until size || y !in 0 until size) continue
                val neighbor = BoardPosition(x, y)
                when (engine.stoneAt(neighbor)) {
                    GoStone.EMPTY -> liberties.add(neighbor)
                    color -> if (visited.add(neighbor)) group.add(neighbor)
                    else -> Unit
                }
            }
        }
        return liberties
    }

    override fun close() = engine.close()
}
