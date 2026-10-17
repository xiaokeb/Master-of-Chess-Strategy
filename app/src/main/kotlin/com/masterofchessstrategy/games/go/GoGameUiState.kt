package com.masterofchessstrategy.games.go

import com.masterofchessstrategy.engine.GameResult
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
) {
    val canOperate: Boolean get() = sessionReady && !loading && !saving && !needsSave && !importing
    val canAct: Boolean get() = canOperate && result == GameResult.ONGOING
}
