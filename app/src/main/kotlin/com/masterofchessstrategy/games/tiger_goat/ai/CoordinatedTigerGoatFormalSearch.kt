package com.masterofchessstrategy.games.tiger_goat.ai

import com.masterofchessstrategy.games.tiger_goat.TigerGoatAction
import com.masterofchessstrategy.games.tiger_goat.TigerGoatActionKind
import com.masterofchessstrategy.games.tiger_goat.TigerGoatAiPositionSnapshot
import com.masterofchessstrategy.games.tiger_goat.TigerGoatAiPositionStamp
import com.masterofchessstrategy.games.tiger_goat.TigerGoatAiSearchCoordinator
import com.masterofchessstrategy.games.tiger_goat.TigerGoatAiSearchState

/** Integration adapter depends on the separately reviewed App AI coordinator node. */
internal class CoordinatedTigerGoatFormalSearch(
    private val coordinator: TigerGoatAiSearchCoordinator,
) : TigerGoatFormalSearch {
    override val failed: Boolean get() = coordinator.state is TigerGoatAiSearchState.Failed
    override fun replace(checkpoint: TigerGoatFormalCheckpoint) {
        check(coordinator.setPosition(checkpoint.position())) { "虎羊棋 AI 已关闭" }
    }
    override fun start() = coordinator.start()
    override fun consume(checkpoint: TigerGoatFormalCheckpoint): TigerGoatAction? =
        coordinator.consume(checkpoint.position())?.let { suggestion ->
            val action = suggestion.action ?: error("AI 未返回有效动作")
            TigerGoatAction(TigerGoatActionKind.valueOf(action.kind.name), action.from, action.to)
        }
    override fun cancel() = coordinator.cancel()
    override fun seal() = coordinator.seal()
    override fun closeAndJoinBlocking() = coordinator.closeAndJoinBlocking()

    private fun TigerGoatFormalCheckpoint.position() = TigerGoatAiPositionSnapshot(
        TigerGoatAiPositionStamp(sessionId, revision), copyBytes(),
    )
}
