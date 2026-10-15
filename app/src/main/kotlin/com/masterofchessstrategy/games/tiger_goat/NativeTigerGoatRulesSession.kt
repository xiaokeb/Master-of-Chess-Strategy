package com.masterofchessstrategy.games.tiger_goat

import com.masterofchessstrategy.engine.games.tiger_goat.TigerGoatSession
import com.masterofchessstrategy.engine.games.tiger_goat.TigerGoatAction as NativeAction
import com.masterofchessstrategy.engine.games.tiger_goat.TigerGoatActionKind as NativeActionKind
import com.masterofchessstrategy.engine.games.tiger_goat.TigerGoatOutcome

/** The application consumes point ids and derives geometry from the native topology. */
class NativeTigerGoatRulesSession(private val session: TigerGoatSession) : TigerGoatRulesSession {
    override val spec = TigerGoatBoardSpec(
        session.geometry.points.mapIndexed { id, point -> TigerGoatPoint(id, point.x, point.y) },
        session.geometry.edges.map { TigerGoatEdge(it.from, it.to) },
    )
    override val actionCount: Int get() = session.snapshot().actionCount
    override fun snapshot(): TigerGoatSnapshot = session.snapshot().let {
        TigerGoatSnapshot(
            pieces = it.board,
            phase = TigerGoatPhase.valueOf(it.phase.name),
            toMove = it.side,
            reserve = it.reserve,
            captured = it.captured,
            winner = when (it.outcome) {
                TigerGoatOutcome.ONGOING -> TigerGoatWinner.NONE
                TigerGoatOutcome.TIGER_WINS -> TigerGoatWinner.TIGER
                TigerGoatOutcome.GOAT_WINS -> TigerGoatWinner.GOAT
            },
            chainTiger = it.chainTiger,
            chainVisited = it.visited,
        )
    }
    override fun legalActions() = session.legalActions().map {
        TigerGoatAction(TigerGoatActionKind.valueOf(it.kind.name), it.from, it.to)
    }
    override fun apply(action: TigerGoatAction) = session.apply(
        NativeAction(NativeActionKind.valueOf(action.kind.name), action.from, action.to),
    )
    override fun undo() = session.undo()
    override fun reset() = session.reset()
    override fun serialize() = session.serialize()
    override fun restore(bytes: ByteArray) = session.restore(bytes)
    override fun close() = session.close()
}
