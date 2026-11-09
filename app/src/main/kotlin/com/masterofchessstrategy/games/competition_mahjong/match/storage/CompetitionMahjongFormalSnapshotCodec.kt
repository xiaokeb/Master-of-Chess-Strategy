package com.masterofchessstrategy.games.competition_mahjong.match.storage

import com.masterofchessstrategy.games.competition_mahjong.history.CompetitionMahjongMatchHistoryCodec
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongMatchCheckpointCodec
import com.masterofchessstrategy.games.competition_mahjong.statistics.CompetitionMahjongStatisticsCodec
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32

/** MCTX v3 adds compact evidence for completed series. v1 and v2 remain byte-stable. */
internal object CompetitionMahjongFormalSnapshotCodec {
    private const val MAGIC = 0x4d435458
    private const val HEADER_BYTES = 12
    private const val FOOTER_BYTES = 4
    private const val MAX_ACTIVE_BYTES = 1_100_000
    private const val MAX_PREFIX_BYTES = 1 + 15 * (4 + CompetitionMahjongPrivateSeriesReplay.MAX_HAND_BYTES)
    private const val MAX_ARCHIVE_BYTES = 8 * 1024 * 1024
    const val MAX_BYTES = HEADER_BYTES + FOOTER_BYTES + 16 + MAX_ACTIVE_BYTES + MAX_PREFIX_BYTES + MAX_ARCHIVE_BYTES +
        CompetitionMahjongStatisticsCodec.MAX_BYTES + CompetitionMahjongMatchHistoryCodec.MAX_BYTES +
        CompetitionMahjongFormalSnapshot.MAX_DELETIONS * 36

    fun encode(snapshot: CompetitionMahjongFormalSnapshot): ByteArray {
        val active = snapshot.active?.let(CompetitionMahjongMatchCheckpointCodec::encode) ?: byteArrayOf()
        val statistics = CompetitionMahjongStatisticsCodec.encode(snapshot.viewerPlayerId, snapshot.statistics.outcomes)
        val history = CompetitionMahjongMatchHistoryCodec.encode(snapshot.viewerPlayerId, snapshot.history.entries)
        val records = snapshot.settledHandRecords
        val archive = snapshot.archivedSeries
        val archiveSize = if (archive.isEmpty()) 0 else 4 + archive.sumOf { it.encodedSize }
        require(archiveSize <= MAX_ARCHIVE_BYTES) { "国标跨场证据已达到存档容量上限" }
        val version = if (archive.isNotEmpty()) 3 else if (records != null) 2 else 1
        val size = HEADER_BYTES + FOOTER_BYTES + 16 + active.size + statistics.size + history.size +
            snapshot.deletedMatchIds.size * 36 + (if (version >= 2) 1 else 0) +
            (records?.sumOf { record -> 4 + record.size } ?: 0) + archiveSize
        require(active.size <= MAX_ACTIVE_BYTES && size <= MAX_BYTES)
        val output = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
        output.putInt(MAGIC).put(version.toByte())
            .put(snapshot.viewerPlayerId.toByte()).putShort(0)
            .putInt(size - HEADER_BYTES - FOOTER_BYTES)
        for (section in listOf(active, statistics, history)) output.putInt(section.size).put(section)
        output.putInt(snapshot.deletedMatchIds.size)
        snapshot.deletedMatchIds.sorted().forEach { output.put(it.toByteArray(StandardCharsets.US_ASCII)) }
        if (version >= 2) {
            output.put((records?.size ?: -1).toByte())
            records?.forEach { record -> output.putInt(record.size).put(record) }
        }
        if (version == 3) {
            output.putInt(archive.size)
            archive.forEach { it.writeTo(output) }
        }
        output.putInt(CRC32().apply { update(output.array(), 0, output.position()) }.value.toInt())
        return output.array()
    }

    fun decode(bytes: ByteArray, viewerPlayerId: Int): CompetitionMahjongFormalSnapshot? {
        require(viewerPlayerId in 0..3)
        if (bytes.size !in HEADER_BYTES + FOOTER_BYTES + 16..MAX_BYTES) return null
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        if (input.int != MAGIC) return null
        val version = input.get().toInt()
        if (version !in 1..3 || input.get().toInt() != viewerPlayerId ||
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
            if (count !in 0..CompetitionMahjongFormalSnapshot.MAX_DELETIONS || input.remaining() < count * 36) return null
            val deleted = List(count) {
                ByteArray(36).also(input::get).toString(StandardCharsets.US_ASCII)
            }
            if (deleted.distinct().size != deleted.size) return null
            val records = if (version >= 2) {
                if (!input.hasRemaining()) return null
                val prefixCount = input.get().toInt()
                if (version == 3 && prefixCount == -1) null else {
                    if (prefixCount != active?.activeHandIndex) return null
                    List(prefixCount) { section(CompetitionMahjongPrivateSeriesReplay.MAX_HAND_BYTES) ?: return null }
                }
            } else null
            val archive = if (version == 3) {
                if (input.remaining() !in 4..MAX_ARCHIVE_BYTES) return null
                val archiveCount = input.int
                if (archiveCount !in 1..statistics.seriesCount ||
                    archiveCount > input.remaining() / CompetitionMahjongArchivedSeries.MIN_BYTES) return null
                List(archiveCount) { CompetitionMahjongArchivedSeries.read(input, viewerPlayerId) ?: return null }
            } else emptyList()
            if (input.hasRemaining()) return null
            val result = CompetitionMahjongFormalSnapshot(viewerPlayerId, active, statistics, history, deleted.toSet(), records, archive)
            result.takeIf { encode(it).contentEquals(bytes) }
        } catch (_: IllegalArgumentException) { null }
        catch (_: ArithmeticException) { null }
    }
}
