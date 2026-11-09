package com.masterofchessstrategy.games.competition_mahjong.data

import com.masterofchessstrategy.engine.NativeCompetitionMahjongSession
import com.masterofchessstrategy.engine.RestoreResult
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongBotStrategy
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongCompetitionConfig
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongMatchCheckpoint
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongMatchController
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongMatchLedger
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongMatchStore
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongPlayerScores
import com.masterofchessstrategy.games.competition_mahjong.match.storage.CompetitionMahjongPrivateSeriesReplay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class CompetitionMahjongPrivateSeriesReplayInstrumentedTest {
    @Test fun sixteenNativeJournalsMustMatchEveryHandAndItsPublicResult() = runBlocking {
        val config = CompetitionMahjongCompetitionConfig(UUID.randomUUID().toString(), 20261009, 0, 0)
        val (records, ledger) = playHands(config, 16)
        val replayed = CompetitionMahjongPrivateSeriesReplay.verifySeries(config, records, ledger)
        assertEquals(ledger.finalStandings(), replayed.finalStandings())
        assertThrows(IllegalArgumentException::class.java) {
            CompetitionMahjongPrivateSeriesReplay.verifySeries(config, records.dropLast(1), ledger)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CompetitionMahjongPrivateSeriesReplay.verifySeries(config, records.reversed(), ledger)
        }
        val differentScore = ledger.results.first().let {
            it.copy(scores = it.scores + CompetitionMahjongPlayerScores(10, -10, 0, 0))
        }
        val falsePublicResult = CompetitionMahjongMatchLedger.restored(listOf(differentScore) + ledger.results.drop(1))
        assertThrows(IllegalArgumentException::class.java) {
            CompetitionMahjongPrivateSeriesReplay.verifySeries(config, records, falsePublicResult)
        }
        Unit
    }

    @Test fun sameWallAndLegalOutcomeDoNotProveTheConfiguredBotStrategy() = runBlocking {
        val canonical = CompetitionMahjongCompetitionConfig(UUID.randomUUID().toString(), 20261009, 0, 0,
            CompetitionMahjongBotStrategy.POSITION_SEEDED_V1)
        val (records, ledger) = playHands(canonical, 1)
        assertEquals(ledger.results.single(), CompetitionMahjongPrivateSeriesReplay.verifyHand(canonical, 0, records.single()))
        val legacy = canonical.copy(botStrategy = CompetitionMahjongBotStrategy.LEGACY_BATCH)
        val (alternative, alternativeLedger) = playHands(legacy, 1)
        assertEquals(alternativeLedger.results.single(),
            CompetitionMahjongPrivateSeriesReplay.verifyHand(legacy, 0, alternative.single()))
        assertThrows(IllegalArgumentException::class.java) {
            CompetitionMahjongPrivateSeriesReplay.verifyHand(canonical, 0, alternative.single())
        }
        assertThrows(IllegalArgumentException::class.java) {
            CompetitionMahjongPrivateSeriesReplay.verifyHand(canonical.copy(botLevel = 3), 0, records.single())
        }
        Unit
    }

    @Test fun legalForeignWallAndIncompleteHandAreRejectedAsEvidence() = runBlocking {
        val config = CompetitionMahjongCompetitionConfig(UUID.randomUUID().toString(), 321, 0, 0)
        val foreign = config.copy(seed = config.seed + 1)
        val record = playHands(foreign, 1).first.single()
        NativeCompetitionMahjongSession(config.seed, 0, 0, 0).use { native ->
            val incomplete = native.serialize()
            // A legally replayable record can still belong to a different shuffled wall.
            assertEquals(RestoreResult.Restored, native.restore(record))
            assertThrows(IllegalArgumentException::class.java) {
                CompetitionMahjongPrivateSeriesReplay.verifyHand(config, 0, record)
            }
            assertThrows(IllegalArgumentException::class.java) {
                CompetitionMahjongPrivateSeriesReplay.verifyHand(config, 0, incomplete)
            }
        }
        Unit
    }

    private suspend fun playHands(
        config: CompetitionMahjongCompetitionConfig,
        count: Int,
    ): Pair<List<ByteArray>, CompetitionMahjongMatchLedger> {
        var saved: CompetitionMahjongMatchCheckpoint? = null
        val store = object : CompetitionMahjongMatchStore {
            override suspend fun load(viewerPlayerId: Int) = saved
            override suspend fun save(checkpoint: CompetitionMahjongMatchCheckpoint) {
                saved = checkpoint
            }
        }
        val controller = CompetitionMahjongMatchController(config, store)
        val records = ArrayList<ByteArray>(count)
        try {
            assertTrue(controller.open())
            repeat(count) { index ->
                finishCompetitionMahjongTestHand(controller, index)
                records.add(checkNotNull(saved).engineRecord)
                if (index < count - 1) assertTrue(controller.nextHand(index))
            }
            return records to checkNotNull(saved).ledger
        } finally {
            controller.close()
        }
    }
}
