package com.masterofchessstrategy.games.competition_mahjong.match

/** User preferences only; a background lifecycle stop is not a request to persist a pause. */
data class CompetitionMahjongMatchPlayback(
    val paused: Boolean = false,
    val speed: Float = 1f,
) {
    init { require(speed == 0.5f || speed == 1f || speed == 2f || speed == 4f) }
    internal val speedPermille: Int get() = (speed * 1000).toInt()
}
