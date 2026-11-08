package com.masterofchessstrategy.games.competition_mahjong.match.policy

import com.masterofchessstrategy.engine.Difficulty
import java.util.Collections

/** Strategy identities used by both play and backup admission. Strength is evaluated separately. */
internal val competitionMahjongProductionStrategies: Map<Difficulty, Int> = Collections.unmodifiableMap(
    mapOf(Difficulty.EASY to 0, Difficulty.MEDIUM to 1, Difficulty.HARD to 2, Difficulty.MASTER to 3),
)
