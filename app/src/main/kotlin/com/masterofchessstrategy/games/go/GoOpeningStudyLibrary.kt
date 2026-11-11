package com.masterofchessstrategy.games.go

import com.masterofchessstrategy.engine.*

data class GoOpeningStudyStep(val point: BoardPosition, val explanation: String) {
    init { require(explanation.isNotBlank() && explanation.length <= 160) }
}

class GoOpeningStudyLine(
    val id: String,
    val title: String,
    val focus: String,
    val boardSize: Int,
    steps: List<GoOpeningStudyStep>,
) {
    val steps = steps.toList()
    init {
        require(id.matches(Regex("[a-z0-9-]{1,48}")) && title.isNotBlank() && focus.isNotBlank())
        require(boardSize in setOf(9, 13, 19) && steps.size in 4..128)
        require(steps.all { it.point.x in 0 until boardSize && it.point.y in 0 until boardSize })
    }
}

/** A rules-verified, immutable endpoint for replay or an AI-versus-AI training continuation. */
class GoOpeningStudyPlan internal constructor(val line: GoOpeningStudyLine, bytes: ByteArray, val opening: GoAiOpening) {
    private val snapshot = bytes.copyOf()
    fun copySnapshotBytes() = snapshot.copyOf()
    fun automaticConfig(difficulty: GoAiDifficulty) = opening.config(humanPlayer = null, difficulty = difficulty)
}

/** Project-authored teaching lines. They illustrate opening direction and make no professional-joseki claim. */
object GoOpeningStudyLibrary {
    val lines: List<GoOpeningStudyLine> = listOf(
        line("9-corners-center", "9路：四角与中央", "先占宽处，再观察中央联络。", 9,
            step(2, 2, "黑先占左上角，兼顾上边与左边。"),
            step(6, 6, "白占右下角，与黑方保持距离。"),
            step(2, 6, "黑占左下角，观察左侧两角的呼应。"),
            step(6, 2, "白占右上角，两方分别在一侧有两个支点。"),
            step(4, 4, "黑进入中央，观察中央棋子能向几侧发展。"),
            step(4, 3, "白紧贴中央黑子，比较接触后双方向上边的出路。")),
        line("9-approach-extension", "9路：靠近与展开", "一方靠近角部后，双方向边上展开。", 9,
            step(2, 2, "黑先在左上角落子。"),
            step(6, 6, "白取对角，保留右边和下边的空间。"),
            step(3, 2, "黑沿上边紧邻原子，观察紧密联络的代价。"),
            step(6, 5, "白沿右侧向中央延伸。"),
            step(2, 4, "黑在左侧隔一格展开，比较它与紧贴走法的范围。"),
            step(4, 6, "白在下方落子，兼顾原有右下角和中央方向。")),
        line("13-balanced-corners", "13路：对角均衡", "用对角占位保留两翼选择。", 13,
            step(3, 3, "黑占左上星位附近，保留两个边的选择。"),
            step(9, 9, "白占右下，先在远处分得空间。"),
            step(9, 3, "黑占右上，与左上棋子同向上边。"),
            step(3, 9, "白占左下，形成对应的下边布局。"),
            step(6, 3, "黑在上边两角之间加子，观察两侧的连接距离。"),
            step(6, 9, "白在下边作对应选择，比较两条边的开阔程度。")),
        line("13-side-development", "13路：边上展开", "从角部向边上延伸，并留意对方夹击方向。", 13,
            step(3, 3, "黑先占左上角。"),
            step(9, 9, "白先占右下角。"),
            step(5, 3, "黑沿上边隔一格展开，上边仍有可侵入的空间。"),
            step(9, 7, "白沿右侧展开，比较方向不同的两翼。"),
            step(3, 6, "黑转向左侧，观察同一角子如何兼顾两个边。"),
            step(6, 9, "白向下边中段展开，留意对手可从中央接近的位置。")),
        line("19-star-approach", "19路：星位与拆边", "星位开局后比较拆边的方向和间距。", 19,
            step(3, 3, "黑占左上星位。"),
            step(15, 15, "白占右下星位。"),
            step(15, 3, "黑在右上增加支点，上边两端都有黑子。"),
            step(3, 15, "白在左下增加支点，形成对应的下边。"),
            step(5, 3, "黑从左上向上边短拆，观察已有角子的支援。"),
            step(13, 15, "白从右下向下边短拆，与黑方的方向相反。"),
            step(9, 3, "黑把支点延伸到上边中央，右上角之间仍有间隔。"),
            step(9, 15, "白进入下边中央，比较两方尚未连起的位置。")),
        line("19-wide-framework", "19路：宽松布局", "先分占角部，再向两边展开形成全局比较。", 19,
            step(3, 3, "黑占左上星位，保留向左边和上边展开的选择。"),
            step(15, 15, "白占右下星位。"),
            step(3, 15, "黑占左下，左边形成两处远距离支点。"),
            step(15, 3, "白占右上，右边形成对应支点。"),
            step(3, 9, "黑占左边中央，观察大间距留下的侵入点。"),
            step(15, 9, "白占右边中央，比较另一侧相同的间距。"),
            step(9, 3, "黑转到上边中央，布局从一侧向相邻边展开。"),
            step(9, 15, "白占下边中央，比较双方的边上支点和中央空白。")),
    )

    init { require(lines.map { it.id }.toSet().size == lines.size) }

    fun line(id: String): GoOpeningStudyLine? = lines.firstOrNull { it.id == id }

    /** Applies every authored move through the formal rules engine; no diagram-only endpoint is accepted. */
    fun open(
        line: GoOpeningStudyLine,
        engineFactory: (Int, Int, Int) -> GoRecordEngine = { size, komi, handicap -> NativeGoEngine(size, komi, handicap) },
    ): GoOpeningStudyPlan {
        var bytes: ByteArray? = null
        engineFactory(line.boardSize, 15, 0).use { engine ->
            require(engine.boardSize == line.boardSize && engine.handicapStones == 0 && engine.blackKomiQuarters == 15)
            require(engine.phase == GoPhase.PLAYING && engine.gameResult() == GameResult.ONGOING && engine.eventHistory().isEmpty())
            for (step in line.steps) {
                val action = GoAction.Place(step.point)
                require(engine.apply(action) == ActionResult.Accepted) { "开局线路 ${line.id} 含非法着手。" }
            }
            require(engine.phase == GoPhase.PLAYING && engine.gameResult() == GameResult.ONGOING)
            require(engine.eventHistory() == line.steps.map { GoAction.Place(it.point) })
            bytes = engine.serialize()
        }
        val canonical = checkNotNull(bytes)
        val opening = GoAiOpening.fromSnapshot(canonical, engineFactory)
        require(opening.eventCount == line.steps.size && opening.boardSize == line.boardSize && opening.handicapStones == 0)
        return GoOpeningStudyPlan(GoOpeningStudyLine(line.id, line.title, line.focus, line.boardSize, line.steps), canonical, opening)
    }

    fun openAll(
        engineFactory: (Int, Int, Int) -> GoRecordEngine = { size, komi, handicap -> NativeGoEngine(size, komi, handicap) },
    ) = lines.map { open(it, engineFactory) }

    private fun step(x: Int, y: Int, explanation: String) = GoOpeningStudyStep(BoardPosition(x, y), explanation)

    private fun line(id: String, title: String, focus: String, size: Int, vararg steps: GoOpeningStudyStep) =
        GoOpeningStudyLine(id, title, focus, size, steps.asList())
}
