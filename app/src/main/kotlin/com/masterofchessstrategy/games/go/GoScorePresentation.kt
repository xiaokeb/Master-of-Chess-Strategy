package com.masterofchessstrategy.games.go

import com.masterofchessstrategy.engine.GoAreaScore

internal fun goScoreText(quarters: Int, mode: GoScoreDisplayMode): String {
    val units = if (mode == GoScoreDisplayMode.STONES) 4 else 2
    val scaled = quarters.toDouble() / units
    val number = if (scaled % 1.0 == 0.0) scaled.toInt().toString() else scaled.toString()
    return number + if (mode == GoScoreDisplayMode.STONES) " 子" else " 目"
}

/** Chinese half-board margin: positive means Black leads; 1 stone equals 2 points. */
internal fun GoAreaScore.blackMarginQuarters(): Int =
    (blackAreaQuarters - whiteAreaQuarters) / 2 - blackKomiQuarters -
        handicapAdjustmentQuarters / 2
