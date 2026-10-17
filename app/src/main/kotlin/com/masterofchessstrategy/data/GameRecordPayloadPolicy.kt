package com.masterofchessstrategy.data

import com.masterofchessstrategy.engine.GameType

/** Envelope checks only. Go rule replay and terminal metadata still require Native admission. */
internal object GameRecordPayloadPolicy {
    const val XIANGQI_MAX_BYTES = 64 * 1024
    const val GO_MAX_BYTES = 16 + 21 * 16_386

    // All non-Go rows keep the existing envelope marker; this is not Native rule admission.
    fun currentVersion(gameType: GameType): Int = if (gameType == GameType.GO) 3 else 2

    fun maximumBytes(gameType: GameType): Int = when (gameType) {
        GameType.GO -> GO_MAX_BYTES
        else -> XIANGQI_MAX_BYTES
    }

    fun accepts(gameType: GameType, version: Int, state: ByteArray): Boolean = when (gameType) {
        GameType.GO -> acceptsGo(version, state)
        // Do not tighten other game families while introducing the Go-specific contract.
        else -> version == 2 && state.size in 1..XIANGQI_MAX_BYTES
    }

    private fun acceptsGo(version: Int, state: ByteArray): Boolean {
        val header = when (version) { 1 -> 10; 2 -> 11; 3 -> 16; else -> return false }
        val width = when (version) { 1 -> 3; 2 -> 17; else -> 21 }
        if (state.size !in header..GO_MAX_BYTES || state[0] != 'M'.code.toByte() ||
            state[1] != 'O'.code.toByte() || state[2] != 'C'.code.toByte() ||
            state[3] != 'G'.code.toByte() || unsigned(state[4]) != version ||
            unsigned(state[5]) !in setOf(9, 13, 19)
        ) return false
        if (version != 1 && unsigned(state[6]) !in setOf(0, 15)) return false
        if (version == 3) {
            val handicap = unsigned(state[7])
            if (handicap != 0 && (handicap !in 2..9 || state[6] != 0.toByte())) return false
            if (readUnsigned32(state, 8) > Int.MAX_VALUE) return false
        }
        val events = readUnsigned32(state, header - 4)
        val maximumEvents = if (version == 1) 4_096 else 16_386
        return events <= maximumEvents && state.size.toLong() == header + events * width
    }

    private fun unsigned(value: Byte): Int = value.toInt() and 0xff

    private fun readUnsigned32(bytes: ByteArray, offset: Int): Long =
        (0..3).fold(0L) { value, index -> value or (unsigned(bytes[offset + index]).toLong() shl (8 * index)) }
}
