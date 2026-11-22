package com.masterofchessstrategy.games.go

import com.masterofchessstrategy.engine.GoPlayer

enum class GoClockMode { MAIN, PER_MOVE }

/** The active deadline is durable; per-move time resets only on a change of turn. */
data class GoTurnClock(
    val limitMillis: Long,
    val blackMillis: Long,
    val whiteMillis: Long,
    val active: GoPlayer?,
    val startedAtMillis: Long,
    val mode: GoClockMode = GoClockMode.MAIN,
) {
    init {
        require(limitMillis in 1..MAX_LIMIT && blackMillis in 0..limitMillis && whiteMillis in 0..limitMillis)
        require(mode != GoClockMode.PER_MOVE || limitMillis in 1_000L..600_000L && limitMillis % 1_000L == 0L)
        require(startedAtMillis >= 0 && (active != null || startedAtMillis == 0L))
        if (active != null) require(stored(active) > 0L)
    }

    fun remaining(player: GoPlayer, nowMillis: Long): Long {
        require(nowMillis >= 0)
        val millis = stored(player)
        if (active != player || nowMillis <= startedAtMillis) return millis
        return (millis - (nowMillis - startedAtMillis)).coerceAtLeast(0L)
    }

    /** Commit the previous turn and optionally start the next at the same instant. */
    fun advance(nowMillis: Long, next: GoPlayer?): GoTurnClock {
        val newTurn = mode == GoClockMode.PER_MOVE && next != null && next != active
        val black = if (newTurn && next == GoPlayer.BLACK) limitMillis else remaining(GoPlayer.BLACK, nowMillis)
        val white = if (newTurn && next == GoPlayer.WHITE) limitMillis else remaining(GoPlayer.WHITE, nowMillis)
        return copy(blackMillis = black, whiteMillis = white, active = next,
            startedAtMillis = if (next == null) 0 else nowMillis)
    }

    fun expired(nowMillis: Long): GoPlayer? = active?.takeIf { remaining(it, nowMillis) == 0L }

    private fun stored(player: GoPlayer) = if (player == GoPlayer.BLACK) blackMillis else whiteMillis

    companion object {
        const val MAX_LIMIT = 180 * 60_000L

        fun start(limitMillis: Long, first: GoPlayer, nowMillis: Long, mode: GoClockMode = GoClockMode.MAIN) =
            GoTurnClock(limitMillis, limitMillis, limitMillis, first, nowMillis, mode)
    }
}

internal fun goClockText(millis: Long): String {
    val seconds = (millis + 999L) / 1_000L
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}
