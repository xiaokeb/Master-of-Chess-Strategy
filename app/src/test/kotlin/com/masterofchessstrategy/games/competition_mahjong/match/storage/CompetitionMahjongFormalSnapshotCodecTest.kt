package com.masterofchessstrategy.games.competition_mahjong.match.storage

import com.masterofchessstrategy.games.competition_mahjong.history.CompetitionMahjongMatchHistory
import com.masterofchessstrategy.games.competition_mahjong.history.CompetitionMahjongMatchHistoryEntry
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongBotStrategy
import com.masterofchessstrategy.games.competition_mahjong.statistics.CompetitionMahjongSeriesOutcome
import com.masterofchessstrategy.games.competition_mahjong.statistics.CompetitionMahjongStatistics
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

class CompetitionMahjongFormalSnapshotCodecTest {
    @Test fun canonicalRoundTripPreservesActiveResultsFavoriteAndDeletion() {
        val first = formalCheckpoint()
        val second = formalCheckpoint(id = 2)
        val snapshot = CompetitionMahjongFormalSnapshot(0).save(first, formalPolicy(), 100)
            .favorite(first.config.matchId, true).save(second, formalPolicy(), 200).delete(second.config.matchId)
        val bytes = CompetitionMahjongFormalSnapshotCodec.encode(snapshot)
        val restored = CompetitionMahjongFormalSnapshotCodec.decode(bytes, 0)!!
        assertArrayEquals(bytes, CompetitionMahjongFormalSnapshotCodec.encode(restored))
        assertEquals(2, restored.statistics.seriesCount)
        assertTrue(restored.history.entries.single().favorite)
        assertEquals(setOf(second.config.matchId), restored.deletedMatchIds)
        assertArrayEquals(second.engineRecord, restored.active!!.engineRecord)
    }

    @Test fun emptyTransactionRoundTripsWithoutAnActiveGame() {
        val bytes = CompetitionMahjongFormalSnapshotCodec.encode(CompetitionMahjongFormalSnapshot(0))
        val decoded = CompetitionMahjongFormalSnapshotCodec.decode(bytes, 0)!!
        assertNull(decoded.active)
        assertEquals(0, decoded.statistics.seriesCount)
        assertTrue(decoded.history.entries.isEmpty())
    }

    @Test fun truncatedOversizeForeignAndBadChecksumAreRejected() {
        val bytes = CompetitionMahjongFormalSnapshotCodec.encode(CompetitionMahjongFormalSnapshot(0))
        assertNull(CompetitionMahjongFormalSnapshotCodec.decode(bytes, 1))
        for (length in bytes.indices) assertNull(CompetitionMahjongFormalSnapshotCodec.decode(bytes.copyOf(length), 0))
        assertNull(CompetitionMahjongFormalSnapshotCodec.decode(ByteArray(CompetitionMahjongFormalSnapshotCodec.MAX_BYTES + 1), 0))
        assertNull(CompetitionMahjongFormalSnapshotCodec.decode(bytes.copyOf().also { it[it.lastIndex] = 0 }, 0))
    }

    @Test fun validChecksumCannotHideUnknownVersionReservedBitsOrOverflowLength() {
        val base = CompetitionMahjongFormalSnapshotCodec.encode(CompetitionMahjongFormalSnapshot(0))
        assertNull(CompetitionMahjongFormalSnapshotCodec.decode(rechecksum(base.copyOf().also { it[4] = 4 }), 0))
        assertNull(CompetitionMahjongFormalSnapshotCodec.decode(rechecksum(base.copyOf().also { it[6] = 1 }), 0))
        assertNull(CompetitionMahjongFormalSnapshotCodec.decode(rechecksum(base.copyOf().also {
            ByteBuffer.wrap(it).order(ByteOrder.BIG_ENDIAN).putInt(12, Int.MAX_VALUE)
        }), 0))
    }

    @Test fun v3KeepsPrivateArchiveWithAReplacedActiveSeriesAndRejectsDuplicateProofs() {
        val finished = formalCheckpoint()
        val next = formalCheckpoint(id = 2, count = 0)
        val policy = formalPolicy()
        val original = CompetitionMahjongFormalSnapshot(0).save(finished, policy, 100).save(next, policy, 200)
        val config = finished.config.copy(botStrategy = CompetitionMahjongBotStrategy.POSITION_SEEDED_V1)
        val actions = shortArrayOf(1)
        val proof = CompetitionMahjongArchivedSeries(config, List(16) { actions })
        val archived = original.withPrivateEvidence(null, listOf(proof))
        actions[0] = 8
        val codec = CompetitionMahjongFormalSnapshotCodec
        val bytes = codec.encode(archived)
        assertEquals(3, bytes[4].toInt())
        val restored = checkNotNull(codec.decode(bytes, 0))
        assertEquals(next.config, restored.active!!.config)
        assertNull(restored.settledHandRecords)
        assertArrayEquals(shortArrayOf(1), restored.archivedSeries.single().decisions(0))
        restored.archivedSeries.single().decisions(0)[0] = 5
        assertArrayEquals(bytes, codec.encode(restored))
        assertThrows(IllegalArgumentException::class.java) { original.withPrivateEvidence(null, listOf(proof, proof)) }
        val countOffset = bytes.size - 4 - proof.encodedSize - 4
        val invalidCount = bytes.copyOf().also { ByteBuffer.wrap(it).putInt(countOffset, Int.MAX_VALUE) }
        assertNull(codec.decode(rechecksum(invalidCount), 0))
    }

    @Test fun v2PrefixIsBoundedAndOwnedWithoutChangingV1Bytes() {
        val checkpoint = formalCheckpoint(count = 2)
        val snapshot = CompetitionMahjongFormalSnapshot(0, checkpoint)
        val codec = CompetitionMahjongFormalSnapshotCodec
        val legacy = codec.encode(snapshot)
        assertEquals(1, legacy[4].toInt())
        assertNull(codec.decode(legacy, 0)!!.settledHandRecords)
        val record = ByteArray(CompetitionMahjongPrivateSeriesReplay.MAX_HAND_BYTES) { 37 }
        val tracked = snapshot.withSettledHandRecords(List(checkpoint.activeHandIndex) { record })
        record.fill(0)
        val bytes = codec.encode(tracked)
        assertEquals(2, bytes[4].toInt())
        val restored = codec.decode(bytes, 0)!!
        assertTrue(restored.settledHandRecords!!.all { it.all { byte -> byte.toInt() == 37 } })
        restored.settledHandRecords!!.first().fill(0)
        assertArrayEquals(bytes, codec.encode(restored))
        assertArrayEquals(legacy, codec.encode(codec.decode(legacy, 0)!!))
        val prefixOffset = legacy.size - 4
        assertNull(codec.decode(rechecksum(bytes.copyOf().also { it[prefixOffset] = 0 }), 0))
        assertNull(codec.decode(rechecksum(bytes.copyOf().also {
            ByteBuffer.wrap(it).putInt(prefixOffset + 1, Int.MAX_VALUE)
        }), 0))
        assertThrows(IllegalArgumentException::class.java) {
            snapshot.withSettledHandRecords(List(checkpoint.activeHandIndex) { ByteArray(1) })
        }
    }

    @Test fun snapshotRejectsPartialCommitAndInconsistentResultSheets() {
        val checkpoint = formalCheckpoint()
        val policy = formalPolicy()
        val outcome = CompetitionMahjongSeriesOutcome.from(checkpoint, policy, 100)
        val stats = CompetitionMahjongStatistics.from(listOf(outcome))
        assertThrows(IllegalArgumentException::class.java) { CompetitionMahjongFormalSnapshot(0, checkpoint) }
        assertThrows(IllegalArgumentException::class.java) { CompetitionMahjongFormalSnapshot(0, statistics = stats) }
        val mismatched = CompetitionMahjongMatchHistoryEntry.from(formalCheckpoint(drawLast = true), policy, 100)
        assertThrows(IllegalArgumentException::class.java) {
            CompetitionMahjongFormalSnapshot(0, statistics = stats,
                history = CompetitionMahjongMatchHistory.from(listOf(mismatched)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CompetitionMahjongFormalSnapshot(0, formalCheckpoint(count = 15), stats,
                CompetitionMahjongMatchHistory.from(listOf(CompetitionMahjongMatchHistoryEntry.from(checkpoint, policy, 100))))
        }
    }

    @Test fun invalidOrExcessiveDeletionIdsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            CompetitionMahjongFormalSnapshot(0, deletedMatchIds = setOf("not-a-uuid"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CompetitionMahjongFormalSnapshot(0, deletedMatchIds = (0..CompetitionMahjongFormalSnapshot.MAX_DELETIONS)
                .map { "98000000-0000-4000-8000-${it.toString().padStart(12, '0')}" }.toSet())
        }
    }

    private fun rechecksum(bytes: ByteArray): ByteArray = bytes.also {
        ByteBuffer.wrap(it).order(ByteOrder.BIG_ENDIAN).putInt(it.size - 4,
            CRC32().apply { update(it, 0, it.size - 4) }.value.toInt())
    }
}
