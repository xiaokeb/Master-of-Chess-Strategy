package com.masterofchessstrategy.games.go

import com.masterofchessstrategy.engine.EngineError
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.GoAction
import com.masterofchessstrategy.engine.GoAreaScore
import com.masterofchessstrategy.engine.GoPhase
import com.masterofchessstrategy.engine.GoPlayer

enum class GoScoreDisplayMode { STONES, POINTS }

data class GoGameUiState(
    val boardSize: Int = 19,
    val handicapStones: Int = 0,
    val stones: List<Int> = List(boardSize * boardSize) { 0 },
    val currentPlayer: GoPlayer = GoPlayer.BLACK,
    val phase: GoPhase = GoPhase.PLAYING,
    val result: GameResult = GameResult.ONGOING,
    val legalPoints: Set<Int> = emptySet(),
    val showLegalPoints: Boolean = false,
    val deadStoneIndices: Set<Int> = emptySet(),
    val lastMoveIndex: Int? = null,
    val proposalRevision: Int = 0,
    val blackConfirmed: Boolean = false,
    val whiteConfirmed: Boolean = false,
    val previewScore: GoAreaScore? = null,
    val displayMode: GoScoreDisplayMode = GoScoreDisplayMode.STONES,
    val scoringActor: GoPlayer = GoPlayer.BLACK,
    val sessionReady: Boolean = false,
    val loading: Boolean = true,
    val saving: Boolean = false,
    val needsSave: Boolean = false,
    val recordAvailable: Boolean = false,
    val importing: Boolean = false,
    val error: String? = null,
    val resignedBy: GoPlayer? = null,
    val timedOutBy: GoPlayer? = null,
    val timeRemainingMillis: Pair<Long, Long>? = null,
) {
    val canOperate: Boolean get() = sessionReady && !loading && !saving && !needsSave && !importing
    val canAct: Boolean get() = canOperate && result == GameResult.ONGOING
}

internal fun GoGameUiState.rejectedActionMessage(action: GoAction, error: EngineError): String {
    if (error != EngineError.ILLEGAL_ACTION) return "当前棋局状态不接受这项操作，请核对行棋或结算状态。"
    return when (action) {
        is GoAction.Place -> {
            val (x, y) = action.point
            when {
                x !in 0 until boardSize || y !in 0 until boardSize -> "请选择棋盘内的交叉点。"
                stones[y * boardSize + x] != 0 -> "这个交叉点已有棋子，请选空点。"
                else -> "这个空点现在不能落子。请检查无气禁着或全局同形，或选择其他落点。"
            }
        }
        is GoAction.ConfirmScore -> "当前死子标记无法确认，请核对后再试。"
        is GoAction.ToggleDeadGroup -> "请选择棋盘上已有棋子的交叉点来标记死子。"
        else -> "该操作不符合当前围棋规则，请核对行棋或结算状态。"
    }
}
