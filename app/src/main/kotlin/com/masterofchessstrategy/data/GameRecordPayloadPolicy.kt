package com.masterofchessstrategy.data

import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.games.go.GO_SETUP_BOARD_OFFSET
import com.masterofchessstrategy.games.go.GO_SETUP_FORMAT_VERSION
import com.masterofchessstrategy.games.lanzhou_fangqi.data.FangqiSessionAdmission
import java.io.IOException

/** Envelope checks only. Go rule replay and terminal metadata still require Native admission. */
internal object GameRecordPayloadPolicy {
    const val XIANGQI_MAX_BYTES = 64 * 1024
    const val GO_MAX_BYTES = GO_SETUP_BOARD_OFFSET + 19 * 19 + 21 * 16_386
    const val FANGQI_MAX_BYTES = 1_048_576

    fun currentVersion(gameType: GameType): Int = when (gameType) {
        GameType.GO -> 3
        GameType.LANZHOU_SQUARE_CHESS -> 1
        else -> 2
    }

    fun maximumBytes(gameType: GameType): Int = when (gameType) {
        GameType.GO -> GO_MAX_BYTES
        GameType.LANZHOU_SQUARE_CHESS -> FANGQI_MAX_BYTES
        else -> XIANGQI_MAX_BYTES
    }

    fun accepts(gameType: GameType, version: Int, state: ByteArray): Boolean = when (gameType) {
        GameType.GO -> acceptsGo(version, state)
        GameType.LANZHOU_SQUARE_CHESS -> when (version) {
            1 -> fangqiActions(state) != null
            // Preserve old generic envelopes without granting ranked participation.
            2 -> state.size in 1..XIANGQI_MAX_BYTES
            else -> false
        }
        // Do not tighten other game families while introducing the Go-specific contract.
        else -> version == 2 && state.size in 1..XIANGQI_MAX_BYTES
    }

    /** A full Fangqi record must keep every action and the exact native history count. */
    fun acceptsMoveCount(gameType: GameType, version: Int, state: ByteArray, moveCount: Int): Boolean =
        if (gameType == GameType.LANZHOU_SQUARE_CHESS && version == 1) {
            moveCount in 0..FangqiSessionAdmission.MAX_ACTIONS && fangqiActions(state) == moveCount
        } else moveCount in 0..GameRecord.MAX_MOVE_COUNT

    private fun fangqiActions(state: ByteArray): Int? = try {
        FangqiSessionAdmission.inspectHistory(state)
    } catch (_: IOException) { null }

    private fun acceptsGo(version: Int, state: ByteArray): Boolean {
        val baseHeader = when (version) { 1 -> 10; 2 -> 11; in 3..GO_SETUP_FORMAT_VERSION -> 16; else -> return false }
        val width = when (version) { 1 -> 3; 2 -> 17; else -> 21 }
        if (state.size !in baseHeader..GO_MAX_BYTES || state[0] != 'M'.code.toByte() ||
            state[1] != 'O'.code.toByte() || state[2] != 'C'.code.toByte() ||
            state[3] != 'G'.code.toByte() || unsigned(state[4]) != version ||
            unsigned(state[5]) !in setOf(9, 13, 19)
        ) return false
        if (version != 1 && unsigned(state[6]) !in setOf(0, 15)) return false
        if (version >= 3) {
            val handicap = unsigned(state[7])
            if (handicap != 0 && (handicap !in 2..9 || state[6] != 0.toByte())) return false
            if (readUnsigned32(state, 8) > Int.MAX_VALUE) return false
        }
        val header = if (version == GO_SETUP_FORMAT_VERSION) {
            val size = unsigned(state[5])
            val end = GO_SETUP_BOARD_OFFSET + size * size
            if (state.size < end || state[7] != 0.toByte() || unsigned(state[16]) > 1 ||
                (GO_SETUP_BOARD_OFFSET until end).any { unsigned(state[it]) > 2 }) return false
            end
        } else baseHeader
        val events = readUnsigned32(state, baseHeader - 4)
        val maximumEvents = if (version == 1) 4_096 else 16_386
        return events <= maximumEvents && state.size.toLong() == header + events * width
    }

    private fun unsigned(value: Byte): Int = value.toInt() and 0xff

    private fun readUnsigned32(bytes: ByteArray, offset: Int): Long =
        (0..3).fold(0L) { value, index -> value or (unsigned(bytes[offset + index]).toLong() shl (8 * index)) }
}
