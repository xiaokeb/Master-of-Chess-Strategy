package com.masterofchessstrategy.games.competition_mahjong.match

import com.masterofchessstrategy.engine.CompetitionMahjongPublicView
import com.masterofchessstrategy.engine.CompetitionMahjongRuleSession
import com.masterofchessstrategy.games.competition_mahjong.competitionMahjongPublicBotSeed

/** Each action can be regenerated after undo or restart, independently of batch boundaries. */
internal fun CompetitionMahjongRuleSession.advancePositionSeededBot(
    level: Int,
    strategy: CompetitionMahjongBotStrategy = CompetitionMahjongBotStrategy.POSITION_SEEDED_V1,
    frame: CompetitionMahjongPublicView = snapshot(),
): Int {
    val seed = competitionMahjongPublicBotSeed(frame)
    return when (strategy) {
        CompetitionMahjongBotStrategy.POSITION_SEEDED_V1 -> advanceBots(level, seed, maxActions = 1)
        CompetitionMahjongBotStrategy.POSITION_SEEDED_V2 -> advanceCalibratedBots(level, seed, maxActions = 1)
        CompetitionMahjongBotStrategy.LEGACY_BATCH -> error("Legacy bot strategy requires a batch seed")
    }
}
