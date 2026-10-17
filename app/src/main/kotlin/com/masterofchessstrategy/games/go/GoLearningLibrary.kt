package com.masterofchessstrategy.games.go

import com.masterofchessstrategy.engine.BoardPosition
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.GoAction
import com.masterofchessstrategy.engine.GoPlayer

sealed interface GoLessonGoal {
    data class FindLiberties(val groupAt: BoardPosition, val points: Set<BoardPosition>) : GoLessonGoal
    data class Place(val point: BoardPosition, val captured: Set<BoardPosition> = emptySet()) : GoLessonGoal
    data class ForbiddenPoint(val point: BoardPosition) : GoLessonGoal
    data object SecondPass : GoLessonGoal
    data class Confirm(val actor: GoPlayer) : GoLessonGoal
    data class Resume(val actor: GoPlayer) : GoLessonGoal
    data class PredictResult(val result: GameResult) : GoLessonGoal
}

sealed interface GoLessonSetup {
    data class Action(val action: GoAction) : GoLessonSetup
    data class Confirm(val actor: GoPlayer) : GoLessonSetup
}

data class GoLesson(
    val id: String,
    val title: String,
    val explanation: String,
    val instruction: String,
    val success: String,
    val setup: List<GoLessonSetup>,
    val goal: GoLessonGoal,
    val boardSize: Int = 9,
    val handicap: Int = 0,
)

/** Project-authored teaching fixtures executed through the production Chinese Go rules. */
object GoLearningLibrary {
    const val CONTENT_VERSION = 1

    private fun p(x: Int, y: Int) = BoardPosition(x, y)
    private fun move(x: Int, y: Int) = GoLessonSetup.Action(GoAction.Place(p(x, y)))
    private val pass = GoLessonSetup.Action(GoAction.Pass)
    private val twoStones = listOf(move(0, 0), move(8, 8))
    private val pending = twoStones + listOf(pass, pass)
    private val markedWhite = pending + GoLessonSetup.Action(
        GoAction.ToggleDeadGroup(p(8, 8), GoPlayer.BLACK),
    )

    val lessons: List<GoLesson> = listOf(
        GoLesson(
            "liberties", "寻找棋子的气",
            "观察中央黑子：正交相邻的空点让棋子保持在盘上。斜角空点没有与它直接相接。",
            "点出中央黑子的四口气，先不要落子。",
            "四个点都找到了。判断一组棋的气时，重复相邻的空点只算一次。",
            listOf(move(4, 4), move(8, 8)),
            GoLessonGoal.FindLiberties(p(4, 4), setOf(p(3, 4), p(5, 4), p(4, 3), p(4, 5))),
        ),
        GoLesson(
            "connection", "连成一个棋组",
            "让新黑子与中央黑子正交相邻，便能连成一组。斜着靠近仍是两个棋组。",
            "在第5行第6列落一颗黑子。",
            "两颗黑子已经相连。今后判断提子时，会一起检查这一组的气。",
            listOf(move(4, 4), move(8, 8)), GoLessonGoal.Place(p(5, 4)),
        ),
        GoLesson(
            "capture", "提走最后一口气",
            "左上角白子只剩一个出口。填上它后，盘面会立即清除这颗白子。",
            "黑棋在第2行第1列落子，提走角上的白子。",
            "白子已被提走，角点重新成为空点。新落黑子还能从这个空点获得气。",
            listOf(move(1, 0), move(0, 0)), GoLessonGoal.Place(p(0, 1), setOf(p(0, 0))),
        ),
        GoLesson(
            "suicide", "辨认禁着点",
            "左上角的两个邻点都是黑子。白棋下在角点既没有气，也提不走这些黑子。",
            "选择白棋此刻不能落子的角点。",
            "规则引擎拒绝了这手，棋盘和行棋方保持原状。能立即提走对手时需要重新判断。",
            listOf(move(1, 0), pass, move(0, 1)), GoLessonGoal.ForbiddenPoint(p(0, 0)),
        ),
        GoLesson(
            "ko", "不能立即回提",
            "左上局部刚发生提劫。白棋马上回提，会回到此前出现过的整个盘面。",
            "选择白棋不能立即回提的点：第2行第2列。",
            "这次回提被拒绝。实际对局须在别处进行有效交换，还要继续满足全局同形限制。",
            listOf(move(0, 1), move(1, 1), move(1, 0), move(0, 2), move(2, 1),
                move(2, 2), move(8, 8), move(1, 3), move(1, 2)),
            GoLessonGoal.ForbiddenPoint(p(1, 1)),
        ),
        GoLesson(
            "passes", "双虚进入结算",
            "黑方已经虚着，白方也认为盘上无需继续争取。虚着本身不代表认输。",
            "让白方虚着，观察状态变化。",
            "现在进入待确认结算，正式胜负还没有提交；双方要先核对死子。",
            twoStones + pass, GoLessonGoal.SecondPass,
        ),
        GoLesson(
            "counting", "数子与贴子",
            "本练习两方评估相同。把共有空点均分后，还需要在半盘胜负比较中计入黑贴3¾子。",
            "按当前预览，选择领先的一方。",
            "白方因贴子领先3¾子，等于7.5目。切换显示单位不会改变中国规则结果。",
            pending, GoLessonGoal.PredictResult(GameResult.SECOND_PLAYER_WIN),
        ),
        GoLesson(
            "agreement", "双方确认同一集合",
            "这只是一个协商示范：白子被双方约定标为死子，黑方已确认该集合。教程不自动判定死活。",
            "由白方确认当前集合，完成结算。",
            "同一版本的集合获得双方确认，正式结果才提交。更改标记会清除已有确认。",
            markedWhite + GoLessonSetup.Confirm(GoPlayer.BLACK), GoLessonGoal.Confirm(GoPlayer.WHITE),
        ),
        GoLesson(
            "dispute", "有争议就继续下",
            "黑方提议把右下白子标为死子，白方不同意。不要提交这份结算。",
            "恢复行棋；主张死子的黑方先下。",
            "实盘没有删除白子，旧标记与确认已清空，黑方现在可以在盘上继续行棋。",
            markedWhite, GoLessonGoal.Resume(GoPlayer.BLACK),
        ),
        GoLesson(
            "handicap", "让子局由白先行",
            "五颗黑色让子已摆在固定星位，它们是开局布置。白方先下，普通贴子为零，还子另计。",
            "白棋在第4行第5列开始行棋。",
            "第一手是白子。悔棋只撤销实际动作，不会撤销开局让子。",
            emptyList(), GoLessonGoal.Place(p(4, 3)), handicap = 5,
        ),
    )

    fun lesson(id: String): GoLesson? = lessons.firstOrNull { it.id == id }
}
