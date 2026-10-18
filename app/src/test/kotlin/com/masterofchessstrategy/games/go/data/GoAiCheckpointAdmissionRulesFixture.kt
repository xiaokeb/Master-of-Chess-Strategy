package com.masterofchessstrategy.games.go.data

import com.masterofchessstrategy.engine.*

/** Pure test fixture extracted unchanged in behavior from GoAiMatchSessionTest.Rules.
 * Not a Native engine or a replacement production session; admits no real Go histories.
 */
internal class GoAiCheckpointAdmissionRulesFixture(override val boardSize: Int, override var handicapStones: Int) : GoRecordEngine {
    private val events = mutableListOf<GoAction>()
    var closed = false
    override val gameType = GameType.GO
    override val blackKomiQuarters get() = if (handicapStones == 0) 15 else 0
    override val currentPlayer: PlayerId get() = PlayerId((if (handicapStones > 0) 1 else 0) xor (events.count { it is GoAction.Place || it == GoAction.Pass } % 2))
    override val phase: GoPhase get() = when {
        events.count { it is GoAction.ConfirmScore } == 2 -> GoPhase.FINISHED
        events.takeLast(2) == listOf(GoAction.Pass, GoAction.Pass) || events.lastOrNull() is GoAction.ConfirmScore -> GoPhase.SCORING
        else -> GoPhase.PLAYING
    }
    override fun apply(action: GoAction): ActionResult {
        if (action is GoAction.Place && stoneAt(action.point) != GoStone.EMPTY) return ActionResult.Rejected(EngineError.ILLEGAL_ACTION)
        events += action
        return ActionResult.Accepted
    }
    override fun undo(): Boolean = if (events.isEmpty()) false else { events.removeAt(events.lastIndex); true }
    override fun stoneAt(point: BoardPosition): GoStone {
        val index = events.indexOfFirst { it is GoAction.Place && it.point == point }
        if (index < 0) return GoStone.EMPTY
        val turn = events.take(index).count { it is GoAction.Place || it == GoAction.Pass }
        return if (turn % 2 == 0) GoStone.BLACK else GoStone.WHITE
    }
    override fun serialize() = byteArrayOf(boardSize.toByte(), handicapStones.toByte()) + events.map {
        when (it) {
            is GoAction.Place -> (it.point.y * boardSize + it.point.x).toByte()
            GoAction.Pass -> 81.toByte()
            is GoAction.ConfirmScore -> (82 + it.actor.code).toByte()
            else -> error("Unsupported transaction fixture")
        }
    }.toByteArray()
    override fun restore(data: ByteArray): RestoreResult {
        if (data.size < 2 || data[0].toInt() != boardSize) return RestoreResult.Rejected(EngineError.CORRUPTED_DATA)
        handicapStones = data[1].toInt()
        events.clear()
        data.drop(2).forEach { events += when (val code = it.toInt()) {
            81 -> GoAction.Pass
            82, 83 -> GoAction.ConfirmScore(GoPlayer.entries[code - 82], 1)
            else -> GoAction.Place(BoardPosition(code % boardSize, code / boardSize))
        } }
        return RestoreResult.Restored
    }
    override fun scoringState() = GoScoringState(phase, 1, events.any { it is GoAction.ConfirmScore && it.actor == GoPlayer.BLACK },
        events.any { it is GoAction.ConfirmScore && it.actor == GoPlayer.WHITE }, emptyList())
    override fun eventHistory() = events.toList()
    override fun gameResult() = if (phase == GoPhase.FINISHED) GameResult.FIRST_PLAYER_WIN else GameResult.ONGOING
    override fun reset() { events.clear() }
    override fun legalActions(): List<GoAction> = (0 until boardSize * boardSize).map {
        GoAction.Place(BoardPosition(it % boardSize, it / boardSize))
    }.filter { stoneAt(it.point) == GoStone.EMPTY } + GoAction.Pass
    override fun previewScore(): GoAreaScore? = null
    override fun finalScore(): GoAreaScore? = null
    override fun exportSgf() = "(;GM[1]SZ[$boardSize])"
    override fun restoreSgf(text: String) = RestoreResult.Rejected(EngineError.UNSUPPORTED)
    override fun close() { check(!closed); closed = true }
}
