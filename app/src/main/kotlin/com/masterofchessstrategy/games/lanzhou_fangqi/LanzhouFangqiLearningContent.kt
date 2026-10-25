package com.masterofchessstrategy.games.lanzhou_fangqi

import com.masterofchessstrategy.engine.games.lanzhou_fangqi.FangqiAction
import com.masterofchessstrategy.engine.games.lanzhou_fangqi.FangqiPoint

internal data class FangqiLesson(val id: String, val title: String, val body: String)

/** Project-authored exercises; a line is an example solution, not a restriction on play. */
internal data class FangqiPractice(
    val id: String,
    val title: String,
    val instruction: String,
    val board: List<Int>,
    val exampleLine: List<FangqiAction>,
)

internal object LanzhouFangqiLearningContent {
    val lessons = listOf(
        FangqiLesson("opening", "棋盘与开局", "棋盘有七行七列交叉点。黑方占上方两整行，红方占下方两整行，每方十四子，中间三行留空；红方先走。"),
        FangqiLesson("step", "沿线走一步", "每回合选己方一子，沿横线或竖线移到相邻空点。不能斜走、跳过棋子或落到占用点。先点击棋子，再点击标出的合法空点。"),
        FangqiLesson("square", "四角成方", "四枚己方棋子占据轴对齐正方形的四角即可成方，边长可以是棋盘内的一至六格。只检查四角，边上和内部有棋子也不影响；斜方不算。开局已有的方不会自动吃子。"),
        FangqiLesson("capture", "新成方与逐枚吃子", "一步新形成几个方，就能指定吃几枚对方棋子，最多吃掉对方现存全部棋子。可选任何对方棋子，包括其方中的棋子；每枚吃完后立即移除，全部选完才换手。"),
        FangqiLesson("history", "重组成方与禁重", "旧方拆开后再组成，可重新获得吃子机会；一直保留的方不重复计。棋盘、行棋方和待吃子数完全重复此前任一局面时，该动作会被拒绝。悔棋同时撤回整个回合及相应历史。"),
        FangqiLesson("finish", "怎样获胜", "吃光对方棋子，或轮到对方时对方没有任何合法走步，即获胜。成方后的待吃子必须按要求选完。人机局悔棋返回玩家上一次作决定的位置。"),
    )

    val practices = listOf(
        FangqiPractice(
            "lfq-practice-square-01", "补齐一个方", "红先。把左上角的小方补齐，再点选唯一黑子，完成吃子获胜。",
            board(red = listOf(0 to 0, 1 to 0, 0 to 1, 1 to 2), black = listOf(5 to 5)),
            listOf(move(1, 2, 1, 1), capture(5, 5)),
        ),
        FangqiPractice(
            "lfq-practice-double-01", "一步形成两个方", "红先。一格方与两格方可以同时出现。找出共同的缺角，随后逐枚点选两枚黑子获胜。",
            board(red = listOf(0 to 0, 2 to 0, 0 to 2, 1 to 1, 2 to 1, 1 to 2, 2 to 3), black = listOf(5 to 5, 6 to 6)),
            listOf(move(2, 3, 2, 2), capture(6, 6), capture(5, 5)),
        ),
        FangqiPractice(
            "lfq-practice-block-01", "封住最后一个出口", "红先。黑子在左上角，右侧已被红子占住。用另一个红子堵住其下方出口即可获胜，无须成方吃子。",
            board(red = listOf(1 to 0, 0 to 2), black = listOf(0 to 0)),
            listOf(move(0, 2, 0, 1)),
        ),
    )

    private fun board(red: List<Pair<Int, Int>>, black: List<Pair<Int, Int>>): List<Int> {
        val points = MutableList(49) { 0 }
        red.forEach { (x, y) -> points[FangqiPoint(x, y).index] = 1 }
        black.forEach { (x, y) -> points[FangqiPoint(x, y).index] = 2 }
        return points.toList()
    }

    private fun move(x: Int, y: Int, toX: Int, toY: Int) =
        FangqiAction.Move(FangqiPoint(x, y), FangqiPoint(toX, toY))
    private fun capture(x: Int, y: Int) = FangqiAction.Capture(FangqiPoint(x, y))
}
