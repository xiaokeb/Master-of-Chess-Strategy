package com.masterofchessstrategy.engine

/** Black moves first. Player codes are shared with the native rules. */
enum class GoPlayer(val code: Int) { BLACK(0), WHITE(1) }

enum class GoStone(val code: Int) { EMPTY(0), BLACK(1), WHITE(2) }

enum class GoPhase(val code: Int) { PLAYING(0), SCORING(1), FINISHED(2) }

/** Agreement actions are part of the game record and can be undone. */
sealed interface GoAction : GameAction {
    data class Place(val point: BoardPosition) : GoAction
    data object Pass : GoAction
    data class ToggleDeadGroup(val point: BoardPosition, val actor: GoPlayer) : GoAction
    data class ConfirmScore(val actor: GoPlayer, val proposalRevision: Int) : GoAction
    data class ResumePlay(val firstPlayer: GoPlayer) : GoAction
}

data class GoScoringState(
    val phase: GoPhase,
    val proposalRevision: Int,
    val blackConfirmed: Boolean,
    val whiteConfirmed: Boolean,
    val deadStones: List<BoardPosition>,
)

/** Values use quarter-stone units, preserving exact 3¾-stone komi. */
data class GoAreaScore(
    val blackAreaQuarters: Int,
    val whiteAreaQuarters: Int,
    val blackKomiQuarters: Int,
    val result: GameResult,
    val handicapAdjustmentQuarters: Int,
)

interface GoRuleEngine : RuleEngine<GoAction> {
    val boardSize: Int
    val blackKomiQuarters: Int
    val handicapStones: Int
    val phase: GoPhase
    fun stoneAt(point: BoardPosition): GoStone
    fun scoringState(): GoScoringState

    /** A preview is never a final adjudication. */
    fun previewScore(): GoAreaScore?
    fun finalScore(): GoAreaScore?
}
