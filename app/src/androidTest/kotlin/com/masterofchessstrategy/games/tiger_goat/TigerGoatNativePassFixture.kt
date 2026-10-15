package com.masterofchessstrategy.games.tiger_goat

/** Valid analysis seed: twelve goats occupy the mountains and both apexes hold tigers. */
internal fun tigerGoatBlockedGoatsSave(version: Int = 2): ByteArray = ByteArray(53).also { bytes ->
    byteArrayOf(77, 79, 67, 84, version.toByte(), 1, 1, 2, 0, 10).copyInto(bytes)
    bytes[10 + 10] = 1
    bytes[10 + 14] = 1
    for (point in 25..36) bytes[10 + point] = 2
    var hash = 2166136261L
    for (index in 0 until bytes.size - 4) {
        hash = ((hash xor (bytes[index].toLong() and 255)) * 16777619L) and 0xffff_ffffL
    }
    for (offset in 0..3) bytes[bytes.size - 4 + offset] = (hash ushr (offset * 8)).toByte()
}
