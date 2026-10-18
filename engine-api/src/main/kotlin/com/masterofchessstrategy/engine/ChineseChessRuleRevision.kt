package com.masterofchessstrategy.engine

/** Describes the judge pinned to a game; it is not an AI strength setting. */
enum class ChineseChessRuleRevision {
    LEGACY_COMPATIBILITY,
    CONTINUOUS_CHECKING_MATE,
}

/** Optional metadata; absence must not be interpreted as the current revision. */
interface ChineseChessRuleRevisionEngine : ChineseChessRuleEngine {
    val ruleRevision: ChineseChessRuleRevision?
}
