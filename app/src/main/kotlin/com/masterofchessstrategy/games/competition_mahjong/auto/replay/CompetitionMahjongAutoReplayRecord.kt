package com.masterofchessstrategy.games.competition_mahjong.auto.replay

import com.masterofchessstrategy.engine.CompetitionMahjongAutoConfig
import com.masterofchessstrategy.games.competition_mahjong.auto.CompetitionMahjongAutoRecord
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/** Creates an opaque recorded-journal prefix without decoding its action or tile fields. */
internal object CompetitionMahjongAutoReplayRecord {
    private const val ROUND_OFFSET = 24
    private const val COUNT_OFFSET = 175
    private const val EVENTS_OFFSET = 179
    private const val BASE_BYTES = 187

    fun prefix(baseline: ByteArray, terminal: ByteArray, eventOffset: Int,
               config: CompetitionMahjongAutoConfig): ByteArray {
        val baselineCount = CompetitionMahjongAutoRecord.eventCount(baseline, config)
        val replayEvents = CompetitionMahjongAutoRecord.eventsAfter(baseline, terminal, config)
        require(eventOffset in 0..replayEvents)
        val targetCount = baselineCount + eventOffset
        val result = ByteArray(BASE_BYTES + targetCount * 4)
        terminal.copyInto(result, endIndex = EVENTS_OFFSET + targetCount * 4)
        val output = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN)
        output.putInt(20, result.size - 28)
        output.putInt(COUNT_OFFSET, targetCount)
        var hash = 2166136261u
        for (index in ROUND_OFFSET until result.size - 8)
            hash = (hash xor result[index].toUByte().toUInt()) * 16777619u
        output.putInt(result.size - 8, hash.toInt())
        output.putInt(result.size - 4, CRC32().apply { update(result, 0, result.size - 4) }.value.toInt())
        require(CompetitionMahjongAutoRecord.eventCount(result, config) == targetCount)
        return result
    }
}
