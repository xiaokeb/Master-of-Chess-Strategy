package com.masterofchessstrategy.records

import com.masterofchessstrategy.data.GameRecord
import com.masterofchessstrategy.data.HighlightCondition
import com.masterofchessstrategy.data.StoredGameMode
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.GameType
import java.util.zip.CRC32

/** Marks finished AI auto-play games for the existing favorites collection. */
internal object ChineseChessHighlightPolicy {
    const val LONG_GAME_PLIES = 160
    private const val COMEBACK_DEFICIT = 500 // One chariot of captured material.
    private const val HEADER_BYTES = 12
    private const val BOARD_BYTES = 90
    private const val MOVE_BYTES = 11
    private const val CRC_BYTES = 4
    private const val MAX_HISTORY = 4_096

    fun matches(record: GameRecord, conditionsMask: Int): Boolean {
        if (
            record.gameType != GameType.CHINESE_CHESS ||
            record.mode !in setOf(StoredGameMode.AI_AUTO_PLAY, StoredGameMode.OPENING_AUTO_PLAY) ||
            conditionsMask == 0
        ) {
            return false
        }
        if (
            conditionsMask and HighlightCondition.MASTER.bit != 0 &&
            record.difficulty == Difficulty.MASTER
        ) {
            return true
        }
        if (
            conditionsMask and HighlightCondition.LONG_GAME.bit != 0 &&
            record.moveCount >= LONG_GAME_PLIES
        ) {
            return true
        }
        return conditionsMask and HighlightCondition.COMEBACK.bit != 0 &&
            materialComeback(record)
    }

    private fun materialComeback(record: GameRecord): Boolean {
        val winner = when (record.result) {
            GameResult.FIRST_PLAYER_WIN -> 0
            GameResult.SECOND_PLAYER_WIN -> 1
            GameResult.DRAW, GameResult.ONGOING -> return false
        }
        val state = record.engineState
        if (state.size < HEADER_BYTES + BOARD_BYTES + CRC_BYTES ||
            !state.copyOfRange(0, 4).contentEquals("MOCX".encodeToByteArray()) ||
            state[4].toInt() != 2 || state[5].toInt() != GameType.CHINESE_CHESS.code
        ) {
            return false
        }
        val count = (state[10].toInt() and 0xff) or ((state[11].toInt() and 0xff) shl 8)
        if (count > MAX_HISTORY ||
            state.size != HEADER_BYTES + BOARD_BYTES + count * MOVE_BYTES + CRC_BYTES
        ) {
            return false
        }
        val checksum = CRC32().apply { update(state, 0, state.size - CRC_BYTES) }.value
        val storedChecksum = (0 until CRC_BYTES).fold(0L) { value, index ->
            value or ((state[state.size - CRC_BYTES + index].toLong() and 0xffL) shl (8 * index))
        }
        if (checksum != storedChecksum) return false

        var finalDifference = 0
        for (square in 0 until BOARD_BYTES) {
            val code = state[HEADER_BYTES + square].toInt() and 0xff
            val value = pieceValue(code) ?: return false
            finalDifference += signedValue(code, value, winner)
        }
        val captured = IntArray(count)
        var initialDifference = finalDifference
        for (index in count - 1 downTo 0) {
            val code = state[HEADER_BYTES + BOARD_BYTES + index * MOVE_BYTES + 5].toInt() and 0xff
            val value = pieceValue(code) ?: return false
            captured[index] = code
            initialDifference += signedValue(code, value, winner)
        }

        var difference = initialDifference
        captured.forEach { code ->
            if (code != 0) {
                val value = requireNotNull(pieceValue(code))
                difference -= signedValue(code, value, winner)
                if (sideOf(code) == winner && difference <= -COMEBACK_DEFICIT) {
                    return true
                }
            }
        }
        return false
    }

    private fun signedValue(code: Int, value: Int, winner: Int): Int = when {
        code == 0 -> 0
        sideOf(code) == winner -> value
        else -> -value
    }

    private fun sideOf(code: Int): Int = if (code and 0x80 != 0) 1 else 0

    private fun pieceValue(code: Int): Int? {
        if (code == 0) return 0
        if (code and 0x78 != 0) return null
        return when (code and 0x07) {
            1 -> 0 // General capture ends play; it is not material swing.
            2, 3 -> 200
            4 -> 300
            5 -> 500
            6 -> 350
            7 -> 100
            else -> null
        }
    }
}
