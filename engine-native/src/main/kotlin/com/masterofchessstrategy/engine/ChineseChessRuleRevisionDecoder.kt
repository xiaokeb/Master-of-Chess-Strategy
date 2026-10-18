package com.masterofchessstrategy.engine

import java.util.zip.CRC32

/** Reads metadata only; the native restore gate verifies board/history legality. */
internal object ChineseChessRuleRevisionDecoder {
    fun decode(data: ByteArray): ChineseChessRuleRevision? {
        if (data.size < 106 || !data.copyOfRange(0, 4).contentEquals("MOCX".encodeToByteArray()) ||
            data[5].toInt() != GameType.CHINESE_CHESS.code
        ) return null
        val history = u16(data, 10)
        if (history > 4_096) return null
        val recordsEnd = 102 + history * 11
        val version = data[4].toInt()
        val revision = when (version) {
            2 -> {
                if (data.size != recordsEnd + 4) return null
                0
            }
            3, 4 -> {
                val ruleBytes = if (version == 4) 1 else 0
                if (data.size < recordsEnd + 2 + ruleBytes + 4) return null
                val claims = u16(data, recordsEnd)
                val claimsEnd = recordsEnd + 2 + claims * 3
                if (claims > 8_192 || data.size != claimsEnd + ruleBytes + 4) return null
                if (version == 4) data[claimsEnd].toInt() else 0
            }
            else -> return null
        }
        if (revision !in 0..1) return null
        val crc = CRC32().apply { update(data, 0, data.size - 4) }.value
        val stored = (0 until 4).fold(0L) { value, index ->
            value or ((data[data.size - 4 + index].toLong() and 0xffL) shl (8 * index))
        }
        if (crc != stored) return null
        return if (revision == 0) ChineseChessRuleRevision.LEGACY_COMPATIBILITY
            else ChineseChessRuleRevision.CONTINUOUS_CHECKING_MATE
    }

    private fun u16(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xff) or ((data[offset + 1].toInt() and 0xff) shl 8)
}
