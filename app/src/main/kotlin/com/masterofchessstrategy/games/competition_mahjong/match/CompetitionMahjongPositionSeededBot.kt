package com.masterofchessstrategy.games.competition_mahjong.match

import com.masterofchessstrategy.engine.CompetitionMahjongRuleSession
import com.masterofchessstrategy.games.competition_mahjong.competitionMahjongPublicBotSeed

/** Each action can be regenerated after undo or restart, independently of batch boundaries. */
internal fun CompetitionMahjongRuleSession.advancePositionSeededBot(level: Int): Int =
    advanceBots(level, competitionMahjongPublicBotSeed(snapshot()), maxActions = 1)
