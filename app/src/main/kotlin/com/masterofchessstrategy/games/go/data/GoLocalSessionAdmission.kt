package com.masterofchessstrategy.games.go.data

import com.masterofchessstrategy.data.GameRecord
import com.masterofchessstrategy.data.GameRecordPayloadPolicy
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.engine.GoAction
import com.masterofchessstrategy.engine.GoPhase
import com.masterofchessstrategy.engine.NativeGoEngine
import com.masterofchessstrategy.engine.RestoreResult
import com.masterofchessstrategy.games.go.GoSavedSession
import com.masterofchessstrategy.games.go.GoSessionFileCodec

/** Owns canonical bytes; callers and admission test doubles cannot mutate a pending transaction. */
internal class GoAdmittedLocalSession(
    session: GoSavedSession,
    val phase: GoPhase,
    val result: GameResult,
    val moveCount: Int,
) {
    private val owned = session.copy(engineState = session.engineState.copyOf())

    init {
        GoSessionFileCodec.encode(owned)
        require(GameRecordPayloadPolicy.accepts(GameType.GO, 3, owned.engineState))
        require((phase == GoPhase.FINISHED) == (result != GameResult.ONGOING))
        require(moveCount in 0..GameRecord.MAX_MOVE_COUNT)
    }

    fun session(): GoSavedSession = owned.copy(engineState = owned.engineState.copyOf())
}

/** Worker-only: normalizes historical MOCG versions without changing the retained legacy file. */
internal fun admitLocalGoSession(session: GoSavedSession): GoAdmittedLocalSession {
    val state = session.engineState.copyOf()
    require(state.size >= 6 && GameRecordPayloadPolicy.accepts(GameType.GO, state[4].toInt(), state)) {
        "Invalid Go native envelope"
    }
    require((state[5].toInt() and 255) == session.boardSize) { "Go board size differs from its checkpoint" }
    NativeGoEngine(session.boardSize).use { engine ->
        require(engine.restore(state) == RestoreResult.Restored) { "Invalid Go session history" }
        require((engine.phase == GoPhase.FINISHED) == (engine.finalScore() != null))
        val events = engine.eventHistory()
        val last = events.filterIsInstance<GoAction.Place>().lastOrNull()?.point
        return GoAdmittedLocalSession(
            session.copy(engineState = engine.serialize(), lastMoveIndex = last?.let {
                it.y * engine.boardSize + it.x
            }),
            engine.phase,
            engine.gameResult(),
            events.count { it is GoAction.Place || it == GoAction.Pass },
        )
    }
}
