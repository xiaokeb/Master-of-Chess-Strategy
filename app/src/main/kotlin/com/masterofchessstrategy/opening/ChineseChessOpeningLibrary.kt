package com.masterofchessstrategy.opening

import com.masterofchessstrategy.engine.BoardMove
import com.masterofchessstrategy.engine.BoardPosition

internal data class ChineseChessOpeningStep(
    val move: BoardMove,
    val title: String,
    val explanation: String,
)

internal data class ChineseChessOpeningLine(
    val id: String,
    val title: String,
    val summary: String,
    val steps: List<ChineseChessOpeningStep>,
) {
    init {
        require(id.matches(Regex("[a-z0-9-]{1,48}")))
        require(title.isNotBlank() && summary.isNotBlank())
        require(steps.isNotEmpty() && steps.size <= MAX_STEPS)
        require(steps.all { it.title.isNotBlank() && it.explanation.isNotBlank() })
    }

    companion object {
        const val MAX_STEPS = 32
    }
}

/** Project-authored seed library; every line is revalidated by the live rule engine. */
internal object ChineseChessOpeningLibrary {
    const val CONTENT_VERSION = 1

    val lines: List<ChineseChessOpeningLine> = listOf(
        ChineseChessOpeningLine(
            id = "central-cannon-screen-horse",
            title = "中炮对屏风马起步",
            summary = "用中炮控制中路，黑方以正马稳固中央。",
            steps = listOf(
                step(7, 7, 4, 7, "炮二平五", "红方右炮横移中路，直接形成中炮架势。"),
                step(7, 0, 6, 2, "马8进7", "黑方右马发展到河口方向，保护中卒并控制要点。"),
                step(8, 9, 7, 7, "马二进三", "炮已离位，红方右马顺势发展并协同中炮。"),
                step(8, 0, 7, 0, "车9平8", "黑车沿底线占据开放肋道，为后续出车做准备。"),
            ),
        ),
        ChineseChessOpeningLine(
            id = "flying-elephant-balanced",
            title = "飞相局稳健出子",
            summary = "先补中相，再从左翼发展马力，强调阵形完整。",
            steps = listOf(
                step(6, 9, 4, 7, "相三进五", "红相进入中路，先巩固将门再组织子力。"),
                step(2, 0, 4, 2, "象3进5", "黑方对称补象，保持中央防线完整。"),
                step(1, 9, 2, 7, "马八进七", "红方左马自然发展，控制河口与中兵前方。"),
                step(1, 0, 2, 2, "马2进3", "黑方左马对应发展，形成均衡布局。"),
            ),
        ),
        ChineseChessOpeningLine(
            id = "central-pawn-horse",
            title = "挺中兵双马布局",
            summary = "先争夺中线空间，再发展马力观察双方结构。",
            steps = listOf(
                step(4, 6, 4, 5, "兵五进一", "红方挺中兵，提前争夺河口和中央空间。"),
                step(4, 3, 4, 4, "卒5进1", "黑方以中卒回应，明确中央对抗。"),
                step(1, 9, 2, 7, "马八进七", "红方发展左马，准备支援中央。"),
                step(7, 0, 6, 2, "马8进7", "黑方发展右马，形成对中路的持续控制。"),
            ),
        ),
    ).also { entries ->
        require(entries.map { it.id }.distinct().size == entries.size)
    }

    fun find(id: String): ChineseChessOpeningLine? = lines.firstOrNull { it.id == id }

    private fun step(
        fromX: Int,
        fromY: Int,
        toX: Int,
        toY: Int,
        title: String,
        explanation: String,
    ) = ChineseChessOpeningStep(
        move = BoardMove(BoardPosition(fromX, fromY), BoardPosition(toX, toY)),
        title = title,
        explanation = explanation,
    )
}
