package com.masterofchessstrategy.games.competition_mahjong.match.storage

import com.masterofchessstrategy.games.competition_mahjong.history.CompetitionMahjongMatchHistoryCodec
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongMatchCheckpointCodec
import com.masterofchessstrategy.games.competition_mahjong.statistics.CompetitionMahjongStatisticsCodec
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32

/** MCTX v1 owns MCMT v2, MCST v1, MCHR v1 and durable deletion IDs in one atomic value. */
internal object CompetitionMahjongFormalSnapshotCodec {
    private const val MAGIC = 0x4d435458
    private const val HEADER_BYTES = 12
    private const val FOOTER_BYTES = 4
    private const val MAX_ACTIVE_BYTES = 1_100_000
    const val MAX_BYTES = HEADER_BYTES + FOOTER_BYTES + 16 + MAX_ACTIVE_BYTES +
        CompetitionMahjongStatisticsCodec.MAX_BYTES + CompetitionMahjongMatchHistoryCodec.MAX_BYTES +
        CompetitionMahjongFormalSnapshot.MAX_DELETIONS * 36

    fun encode(snapshot: CompetitionMahjongFormalSnapshot): ByteArray {
        val active = snapshot.active?.let(CompetitionMahjongMatchCheckpointCodec::encode) ?: byteArrayOf()
        val statistics = CompetitionMahjongStatisticsCodec.encode(snapshot.viewerPlayerId, snapshot.statistics.outcomes)
        val history = CompetitionMahjongMatchHistoryCodec.encode(snapshot.viewerPlayerId, snapshot.history.entries)
        val size = HEADER_BYTES + FOOTER_BYTES + 16 + active.size + statistics.size + history.size +
            snapshot.deletedMatchIds.size * 36
        require(active.size <= MAX_ACTIVE_BYTES && size <= MAX_BYTES)
        val output = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
        output.putInt(MAGIC).put(1).put(snapshot.viewerPlayerId.toByte()).putShort(0)
            .putInt(size - HEADER_BYTES - FOOTER_BYTES)
        for (section in listOf(active, statistics, history)) output.putInt(section.size).put(section)
        output.putInt(snapshot.deletedMatchIds.size)
        snapshot.deletedMatchIds.sorted().forEach { output.put(it.toByteArray(StandardCharsets.US_ASCII)) }
        output.putInt(CRC32().apply { update(output.array(), 0, output.position()) }.value.toInt())
        return output.array()
    }

    fun decode(bytes: ByteArray, viewerPlayerId: Int): CompetitionMahjongFormalSnapshot? {
        require(viewerPlayerId in 0..3)
        if (bytes.size !in HEADER_BYTES + FOOTER_BYTES + 16..MAX_BYTES) return null
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        if (input.int != MAGIC || input.get().toInt() != 1 || input.get().toInt() != viewerPlayerId ||
            input.short.toInt() != 0 || input.int != bytes.size - HEADER_BYTES - FOOTER_BYTES ||
            input.getInt(bytes.size - FOOTER_BYTES) != CRC32().apply {
                update(bytes, 0, bytes.size - FOOTER_BYTES)
            }.value.toInt()) return null
        input.limit(bytes.size - FOOTER_BYTES)
        fun section(maximum: Int): ByteArray? {
            if (input.remaining() < 4) return null
            val size = input.int
            if (size !in 0..maximum || size > input.remaining()) return null
            return ByteArray(size).also(input::get)
        }
        return try {
            val activeBytes = section(MAX_ACTIVE_BYTES) ?: return null
            val active = if (activeBytes.isEmpty()) null else
                CompetitionMahjongMatchCheckpointCodec.decode(activeBytes, viewerPlayerId) ?: return null
            val statistics = CompetitionMahjongStatisticsCodec.decode(
                section(CompetitionMahjongStatisticsCodec.MAX_BYTES) ?: return null, viewerPlayerId) ?: return null
            val history = CompetitionMahjongMatchHistoryCodec.decode(
                section(CompetitionMahjongMatchHistoryCodec.MAX_BYTES) ?: return null, viewerPlayerId) ?: return null
            if (input.remaining() < 4) return null
            val count = input.int
            if (count !in 0..CompetitionMahjongFormalSnapshot.MAX_DELETIONS || input.remaining() != count * 36) return null
            val deleted = List(count) {
                ByteArray(36).also(input::get).toString(StandardCharsets.US_ASCII)
            }
            if (deleted.distinct().size != deleted.size) return null
            val result = CompetitionMahjongFormalSnapshot(viewerPlayerId, active, statistics, history, deleted.toSet())
            result.takeIf { encode(it).contentEquals(bytes) }
        } catch (_: IllegalArgumentException) { null }
        catch (_: ArithmeticException) { null }
    }
}
