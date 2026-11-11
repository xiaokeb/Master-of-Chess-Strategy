package com.masterofchessstrategy.games.competition_mahjong.auto.replay

import com.masterofchessstrategy.games.competition_mahjong.auto.CompetitionMahjongAutoArchive
import com.masterofchessstrategy.games.competition_mahjong.auto.CompetitionMahjongAutoCheckpointCodec
import com.masterofchessstrategy.games.competition_mahjong.auto.autoCheckpoint
import com.masterofchessstrategy.games.competition_mahjong.auto.autoConfig
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

class CompetitionMahjongAutoArchiveLibraryTest {
    @Test fun publicSummaryContainsNoRecordAndDefensivelyCopiesStrategies() {
        val archive = CompetitionMahjongAutoArchive(autoCheckpoint(hand = 15, completed = true))
        val summary = archive.summary(1234, true)
        assertEquals(archive.key, summary.key)
        assertEquals(0, summary.seriesIndex)
        assertEquals(0, summary.viewerPlayerId)
        assertEquals(1234L, summary.savedAtEpochMillis)
        assertTrue(summary.favorite)
        assertEquals(listOf(0, 0, 0, 0), summary.playerBotLevels)
        assertEquals(listOf(1, 1, 1, 1), summary.standings.map { it.rank })
        assertFalse(summary.toString().contains("MCMA"))
        assertFalse(summary.toString().contains("decision"))
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (summary.playerBotLevels as MutableList<Int>)[0] = 3
        }
    }

    @Test fun privateExportRoundTripsCompleteArchiveAndCopiesBytes() {
        val archive = CompetitionMahjongAutoArchive(autoCheckpoint(
            config = autoConfig(viewer = 2, levels = listOf(0, 1, 2, 3)), hand = 15, completed = true))
        val export = archive.export()
        assertEquals(CompetitionMahjongAutoExport.MIME_TYPE, export.mimeType)
        assertEquals("81000000-0000-4000-8000-000000000001-series-1.mcae", export.suggestedFileName)
        val original = export.bytes
        export.bytes.fill(0)
        assertArrayEquals(original, export.bytes)
        val restored = checkNotNull(CompetitionMahjongAutoExportCodec.decode(original, 2))
        assertTrue(archive.sameContent(restored))
        assertFalse(export.toString().contains("73"))
        assertFalse(export.toString().contains("109"))
    }

    @Test fun corruptionUnknownFieldsWrongOwnerAndSizeAreRejected() {
        val bytes = CompetitionMahjongAutoArchive(autoCheckpoint(hand = 15, completed = true)).export().bytes
        assertNull(CompetitionMahjongAutoExportCodec.decode(bytes, 1))
        listOf(0, 15, bytes.size - 1).forEach { size ->
            assertNull(CompetitionMahjongAutoExportCodec.decode(bytes.copyOf(size), 0))
        }
        assertNull(CompetitionMahjongAutoExportCodec.decode(
            ByteArray(CompetitionMahjongAutoExportCodec.MAX_BYTES + 1), 0))
        for (offset in listOf(0, 4, 5, 6, 12, bytes.lastIndex)) {
            val changed = bytes.copyOf().also { it[offset] = (it[offset].toInt() xor 1).toByte() }
            if (offset < 8) fixExportCrc(changed)
            assertNull("offset=" + offset, CompetitionMahjongAutoExportCodec.decode(changed, 0))
        }
    }

    @Test fun validOuterEnvelopeCannotTurnIncompleteActivityIntoArchive() {
        val activity = CompetitionMahjongAutoCheckpointCodec.encode(autoCheckpoint(events = 1))
        val bytes = wrap(activity)
        assertNull(CompetitionMahjongAutoExportCodec.decode(bytes, 0))
    }

    @Test fun invalidSummaryMetadataIsRejected() {
        val archive = CompetitionMahjongAutoArchive(autoCheckpoint(hand = 15, completed = true))
        assertThrows(IllegalArgumentException::class.java) { archive.summary(-1) }
        val valid = archive.summary(1)
        assertThrows(IllegalArgumentException::class.java) {
            CompetitionMahjongAutoArchiveSummary("wrong", valid.runId, valid.seriesIndex, valid.viewerPlayerId,
                valid.playerBotLevels, valid.elapsedMillis, valid.savedAtEpochMillis, false, valid.scores, valid.standings)
        }
    }

    private fun wrap(payload: ByteArray): ByteArray =
        ByteBuffer.allocate(payload.size + 16).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(0x4d434145).put(1).put(0).putShort(0).putInt(payload.size).put(payload)
            putInt(CRC32().apply { update(array(), 0, position()) }.value.toInt())
        }.array()

    private fun fixExportCrc(bytes: ByteArray) {
        ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).putInt(bytes.size - 4,
            CRC32().apply { update(bytes, 0, bytes.size - 4) }.value.toInt())
    }
}
