package com.masterofchessstrategy.games.go.data

import com.masterofchessstrategy.data.GameRecord
import com.masterofchessstrategy.data.GameRecordPayloadPolicy
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.engine.GoAction
import com.masterofchessstrategy.engine.GoPhase
import com.masterofchessstrategy.engine.NativeGoEngine
import com.masterofchessstrategy.engine.RestoreResult

/** Worker-only admission. SGF's final passes or one confirmation are not a completed record. */
internal fun requireCompletedGoRecord(record: GameRecord) {
    val state = record.engineState.copyOf()
    require(record.gameType == GameType.GO &&
        GameRecordPayloadPolicy.accepts(GameType.GO, record.engineFormatVersion, state))
    NativeGoEngine(boardSize = state[5].toInt() and 0xff).use { engine ->
        require(engine.restore(state) == RestoreResult.Restored) { "Invalid Go record history" }
        require(engine.phase == GoPhase.FINISHED && engine.finalScore() != null) {
            "Go record has no agreed terminal score"
        }
        require(engine.gameResult() == record.result) { "Go record result differs from its history" }
        val moves = engine.eventHistory().count { it is GoAction.Place || it == GoAction.Pass }
        require(moves == record.moveCount) { "Go record move count differs from its history" }
    }
}
