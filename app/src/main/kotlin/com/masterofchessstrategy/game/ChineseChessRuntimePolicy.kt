package com.masterofchessstrategy.game

/** Background pacing reduces idle work, never the selected AI's search budget. */
internal object ChineseChessRuntimePolicy {
    const val BACKGROUND_CLOCK_INTERVAL_MILLIS = 1_000L
    const val BACKGROUND_AUTO_PLAY_DELAY_MILLIS = 2_000L

    fun clockInterval(foregroundInterval: Long, isForeground: Boolean): Long =
        if (isForeground) foregroundInterval else foregroundInterval.coerceAtLeast(BACKGROUND_CLOCK_INTERVAL_MILLIS)

    fun autoPlayDelay(viewingDelay: Long, isForeground: Boolean): Long =
        if (isForeground) viewingDelay else viewingDelay.coerceAtLeast(BACKGROUND_AUTO_PLAY_DELAY_MILLIS)
}
