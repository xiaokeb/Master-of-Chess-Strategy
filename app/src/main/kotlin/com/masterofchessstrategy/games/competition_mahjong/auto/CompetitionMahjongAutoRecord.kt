package com.masterofchessstrategy.games.competition_mahjong.auto

import com.masterofchessstrategy.engine.CompetitionMahjongAutoConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/** Mechanical private-envelope checks. No tile or action fields are decoded; Native replays legality. */
internal object CompetitionMahjongAutoRecord {
    private const val ROUND_OFFSET = 24
    private const val ORIGIN_BYTES = 151 // MOCM header and opaque initial wall.
    private const val COUNT_OFFSET = ROUND_OFFSET + ORIGIN_BYTES
    private const val EVENTS_OFFSET = COUNT_OFFSET + 4
    const val MAX_EVENTS = 4096
    const val MAX_BYTES = 187 + 4 * MAX_EVENTS

    fun eventCount(bytes: ByteArray, config: CompetitionMahjongAutoConfig): Int {
        require(bytes.size in 187..MAX_BYTES) { "自动演局私有记录长度无效" }
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(input.int == 0x414d434d && input.get().toInt() == 1 && input.get().toInt() == config.viewerSeat)
        config.botLevels.forEach { require(input.get().toInt() == it) }
        require(input.short.toInt() == 0 && input.long == config.decisionSeed)
        require(input.int == bytes.size - 28)
        require(CRC32().apply { update(bytes, 0, bytes.size - 4) }.value.toInt() == input.getInt(bytes.size - 4))
        require(input.getInt(ROUND_OFFSET) == 0x4d434f4d && bytes[ROUND_OFFSET + 4].toInt() == 1)
        require(bytes[ROUND_OFFSET + 5].toInt() == config.dealerSeat && bytes[ROUND_OFFSET + 6].toInt() == config.roundWind)
        val count = input.getInt(COUNT_OFFSET)
        require(count in 0..MAX_EVENTS && bytes.size == 187 + 4 * count)
        var hash = 2166136261u
        for (index in ROUND_OFFSET until bytes.size - 8) hash = (hash xor bytes[index].toUByte().toUInt()) * 16777619u
        require(hash.toInt() == input.getInt(bytes.size - 8))
        return count
    }

    fun eventsAfter(baseline: ByteArray, current: ByteArray, config: CompetitionMahjongAutoConfig): Int {
        val first = eventCount(baseline, config)
        val last = eventCount(current, config)
        require(last >= first)
        for (index in ROUND_OFFSET until COUNT_OFFSET) require(baseline[index] == current[index]) { "自动演局起始牌局不一致" }
        for (index in EVENTS_OFFSET until EVENTS_OFFSET + first * 4)
            require(baseline[index] == current[index]) { "自动演局指定起点历史不一致" }
        return last - first
    }
}
