package com.masterofchessstrategy.games.competition_mahjong.statistics

internal fun competitionMahjongStandardPoints(twelfths: Long): String {
    val whole = twelfths / 12
    val remainder = twelfths % 12
    if (remainder == 0L) return whole.toString()
    val divisor = when {
        remainder % 6 == 0L -> 6
        remainder % 4 == 0L -> 4
        remainder % 3 == 0L -> 3
        remainder % 2 == 0L -> 2
        else -> 1
    }
    return "$whole ${remainder / divisor}/${12 / divisor}"
}
