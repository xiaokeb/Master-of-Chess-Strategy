package com.masterofchessstrategy.challenge

/** Stable route and persistence encoding for Chinese-chess per-move clocks. */
internal object TimedChallengeConfig {
    const val PREFIX = "timed:"
    const val DEFAULT_SECONDS = 30
    const val BACKING_CLOCK_MINUTES = 1
    val ALLOWED_SECONDS = listOf(10, 30, 60)

    fun sessionVariant(secondsPerMove: Int): String {
        require(secondsPerMove in ALLOWED_SECONDS)
        return "$PREFIX$secondsPerMove"
    }

    fun decodeSessionVariant(value: String): Int {
        require(value.startsWith(PREFIX))
        val seconds = value.removePrefix(PREFIX).toIntOrNull()
        require(seconds != null && seconds in ALLOWED_SECONDS)
        require(value == sessionVariant(seconds))
        return seconds
    }
}
