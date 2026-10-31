package com.masterofchessstrategy.games.competition_mahjong.match.storage

import com.masterofchessstrategy.games.competition_mahjong.history.CompetitionMahjongMatchHistory
import com.masterofchessstrategy.games.competition_mahjong.history.CompetitionMahjongMatchHistoryEntry
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
        assertNull(CompetitionMahjongFormalSnapshotCodec.decode(rechecksum(base.copyOf().also { it[4] = 2 }), 0))
        assertNull(CompetitionMahjongFormalSnapshotCodec.decode(rechecksum(base.copyOf().also { it[6] = 1 }), 0))
        assertNull(CompetitionMahjongFormalSnapshotCodec.decode(rechecksum(base.copyOf().also {
            ByteBuffer.wrap(it).order(ByteOrder.BIG_ENDIAN).putInt(12, Int.MAX_VALUE)
        }), 0))
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
