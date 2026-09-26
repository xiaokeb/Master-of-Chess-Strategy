package com.masterofchessstrategy.game

/** Stable service-facing facts; no engine handle or mutable board leaves the session. */
internal data class ChineseChessBackgroundDemand(
    val canRun: Boolean,
    val needsCpu: Boolean,
    val isBusy: Boolean,
    val isForeground: Boolean,
)
