package com.masterofchessstrategy.games.go.data

import com.masterofchessstrategy.data.GameRecord
import com.masterofchessstrategy.data.HighlightCondition
import com.masterofchessstrategy.data.StoredGameMode
import com.masterofchessstrategy.engine.BoardPosition
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.engine.GoAction
import com.masterofchessstrategy.engine.GoPlayer
import com.masterofchessstrategy.engine.GoRecordEngine
import com.masterofchessstrategy.engine.GoStone
import com.masterofchessstrategy.engine.NativeGoEngine
import com.masterofchessstrategy.engine.RestoreResult

/** Only runs at first projection of an already admitted completed record. */
internal object GoAiHighlightPolicy {
    const val LONG_GAME_MOVES = 160
    const val COMEBACK_CAPTURE_DEFICIT = 5

    fun matches(record: GameRecord, boardSize: Int, openingEventCount: Int, conditions: Int,
        engineFactory: (Int) -> GoRecordEngine = { NativeGoEngine(it) },
    ): Boolean {
        if (record.gameType != GameType.GO ||
            (record.mode != StoredGameMode.AI_AUTO_PLAY && record.mode != StoredGameMode.OPENING_AUTO_PLAY) ||
            conditions == 0) return false
        if (conditions and HighlightCondition.MASTER.bit != 0 && record.difficulty == Difficulty.MASTER) return true
        if (conditions and HighlightCondition.LONG_GAME.bit != 0 && record.moveCount >= LONG_GAME_MOVES) return true
        if (conditions and HighlightCondition.COMEBACK.bit == 0 || record.result == GameResult.DRAW) return false
        val winner = if (record.result == GameResult.FIRST_PLAYER_WIN) GoPlayer.BLACK else GoPlayer.WHITE
        return engineFactory(boardSize).use { rules ->
            check(rules.restore(record.engineState) == RestoreResult.Restored)
            val events = rules.eventHistory()
            require(openingEventCount in 0..events.size)
            var balance = 0
            var maxReverseBalance = 0
            // Rewind legal Native history once. For each live placement, compare the
            // opponent's stone count on the two sides of the move to count captures.
            var black = rules.count(GoStone.BLACK)
            var white = rules.count(GoStone.WHITE)
            for (index in events.lastIndex downTo openingEventCount) {
                check(rules.undo())
                if (events[index] !is GoAction.Place) continue
                val mover = GoPlayer.entries[rules.currentPlayer.value]
                val captured = if (mover == GoPlayer.BLACK) {
                    val before = rules.count(GoStone.WHITE)
                    (before - white).also {
                        white = before
                        black--
                    }
                } else {
                    val before = rules.count(GoStone.BLACK)
                    (before - black).also {
                        black = before
                        white--
                    }
                }
                check(captured >= 0)
                balance += if (mover == winner) captured else -captured
                maxReverseBalance = maxOf(maxReverseBalance, balance)
            }
            // Forward balance at a point is the total minus its reverse suffix.
            maxReverseBalance - balance >= COMEBACK_CAPTURE_DEFICIT
        }
    }

    private fun GoRecordEngine.count(stone: GoStone): Int = (0 until boardSize * boardSize).count { index ->
        stoneAt(BoardPosition(index % boardSize, index / boardSize)) == stone
    }
}
