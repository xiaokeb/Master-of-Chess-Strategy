package com.masterofchessstrategy.games.competition_mahjong.history

import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.games.competition_mahjong.match.*
import com.masterofchessstrategy.games.competition_mahjong.match.policy.*
import org.junit.Assert.*
import org.junit.Test

class CompetitionMahjongMatchHistoryTest {
    @Test fun completeFormalSeriesProducesSixteenHandPublicResultSheet() {
        val checkpoint = checkpoint()
        val entry = CompetitionMahjongMatchHistoryEntry.from(checkpoint, policy(), 1234)
        assertEquals(checkpoint.config.matchId, entry.matchId)
        assertEquals(Difficulty.HARD, entry.difficulty)
        assertEquals(16, entry.hands.size)
        assertEquals(1, entry.viewerStanding.rank)
        assertEquals(512, entry.viewerStanding.score)
        assertFalse(entry.favorite)
    }

    @Test fun incompleteLegacyOrUnauthorizedSeriesIsRejected() {
        val config = matchConfig()
        assertThrows(IllegalArgumentException::class.java) {
            CompetitionMahjongMatchHistoryEntry.from(CompetitionMahjongMatchCheckpoint(
                config, matchLedger(config, 15), 15, matchRecord(config.handConfig(15).viewerSeat),
                CompetitionMahjongAssistanceUsage(Difficulty.HARD)), policy(), 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CompetitionMahjongMatchHistoryEntry.from(CompetitionMahjongMatchCheckpoint(
                config, matchLedger(config, 16), 15, matchRecord(config.handConfig(15).viewerSeat, true)), policy(), 1)
        }
        val unauthorized = CompetitionMahjongPlayPolicy.fromProgress(
            CompetitionMahjongUnlockProgress(true, 10, 15, 20), mapOf(
                Difficulty.EASY to 3, Difficulty.MEDIUM to 2, Difficulty.HARD to 1, Difficulty.MASTER to 0))
        assertThrows(IllegalArgumentException::class.java) {
            CompetitionMahjongMatchHistoryEntry.from(checkpoint(), unauthorized, 1)
        }
    }

    @Test fun codecRoundTripsFavoriteFirstCanonicalOrder() {
        val newer = entry(1, 300, favorite = false)
        val olderFavorite = entry(2, 100, favorite = true)
        val bytes = CompetitionMahjongMatchHistoryCodec.encode(0, listOf(newer, olderFavorite))
        val restored = CompetitionMahjongMatchHistoryCodec.decode(bytes, 0)!!
        assertEquals(listOf(olderFavorite.matchId, newer.matchId), restored.entries.map { it.matchId })
        assertArrayEquals(bytes, CompetitionMahjongMatchHistoryCodec.encode(0, restored.entries))
    }

    @Test fun codecRejectsCorruptionOwnershipReservedAndOversize() {
        val bytes = CompetitionMahjongMatchHistoryCodec.encode(0, listOf(entry(1, 1)))
        assertNull(CompetitionMahjongMatchHistoryCodec.decode(bytes, 1))
        assertNull(CompetitionMahjongMatchHistoryCodec.decode(bytes.copyOf().also { it[6] = 1 }, 0))
        assertNull(CompetitionMahjongMatchHistoryCodec.decode(bytes.copyOf().also {
            it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
        }, 0))
        assertNull(CompetitionMahjongMatchHistoryCodec.decode(ByteArray(
            CompetitionMahjongMatchHistoryCodec.MAX_BYTES + 1), 0))
    }

    @Test fun historyRejectsDuplicateIdsAndMixedViewers() {
        val first = entry(1, 1)
        assertThrows(IllegalArgumentException::class.java) {
            CompetitionMahjongMatchHistory.from(listOf(first, first.withFavorite(true)))
        }
        val other = CompetitionMahjongMatchHistoryEntry(
            "93000000-0000-4000-8000-000000000002", 1, first.difficulty, first.botLevel,
            2, false, first.hands)
        assertThrows(IllegalArgumentException::class.java) {
            CompetitionMahjongMatchHistory.from(listOf(first, other))
        }
    }

    private fun checkpoint(): CompetitionMahjongMatchCheckpoint {
        val config = matchConfig()
        return CompetitionMahjongMatchCheckpoint(config, matchLedger(config, 16), 15,
            matchRecord(config.handConfig(15).viewerSeat, true), CompetitionMahjongAssistanceUsage(Difficulty.HARD))
    }

    private fun entry(id: Int, time: Long, favorite: Boolean = false): CompetitionMahjongMatchHistoryEntry {
        val base = CompetitionMahjongMatchHistoryEntry.from(checkpoint(), policy(), time)
        return CompetitionMahjongMatchHistoryEntry(
            "93000000-0000-4000-8000-${id.toString().padStart(12, '0')}", base.viewerPlayerId,
            base.difficulty, base.botLevel, time, favorite, base.hands)
    }

    private fun policy() = CompetitionMahjongPlayPolicy.fromProgress(
        CompetitionMahjongUnlockProgress(true, 10, 15, 20), Difficulty.entries.associateWith { it.code })
}
