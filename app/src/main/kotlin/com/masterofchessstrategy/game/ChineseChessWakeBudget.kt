package com.masterofchessstrategy.game

/** A stalled operation cannot extend its CPU lease; only a new move/session counts as progress. */
internal class ChineseChessWakeBudget(initialCheckpoint: String?, nowMillis: Long) {
    private var checkpoint = initialCheckpoint
    private var lastProgressAt = nowMillis

    fun remainingMillis(currentCheckpoint: String?, nowMillis: Long): Long {
        if (currentCheckpoint != checkpoint) {
            checkpoint = currentCheckpoint
            lastProgressAt = nowMillis
        }
        return (TIMEOUT_MILLIS - (nowMillis - lastProgressAt).coerceAtLeast(0L)).coerceAtLeast(0L)
    }

    companion object { const val TIMEOUT_MILLIS = 60_000L }
}
