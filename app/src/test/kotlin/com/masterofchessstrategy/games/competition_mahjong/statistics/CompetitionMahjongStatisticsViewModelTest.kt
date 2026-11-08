package com.masterofchessstrategy.games.competition_mahjong.statistics

import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.games.competition_mahjong.match.*
import com.masterofchessstrategy.games.competition_mahjong.match.policy.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class CompetitionMahjongStatisticsViewModelTest {
    private class MemoryStore : CompetitionMahjongStatisticsStore {
        var outcomes = emptyList<CompetitionMahjongSeriesOutcome>()
        var failLoad = false
        var failRecord = false
        var recordCalls = 0
        override suspend fun withdraw(viewerPlayerId: Int, matchId: String): CompetitionMahjongStatistics {
            outcomes = outcomes.filterNot { it.viewerPlayerId == viewerPlayerId && it.matchId == matchId }
            return CompetitionMahjongStatistics.from(outcomes)
        }
        override suspend fun load(viewerPlayerId: Int): CompetitionMahjongStatistics {
            if (failLoad) throw IOException("load")
            return CompetitionMahjongStatistics.from(outcomes.filter { it.viewerPlayerId == viewerPlayerId })
        }
        override suspend fun record(outcome: CompetitionMahjongSeriesOutcome): CompetitionMahjongRecordStatisticsResult {
            recordCalls++
            if (failRecord) throw IOException("record")
            val duplicate = outcomes.firstOrNull { it.matchId == outcome.matchId }
            if (duplicate != null) {
                require(duplicate.sameResult(outcome))
                return CompetitionMahjongRecordStatisticsResult(false, CompetitionMahjongStatistics.from(outcomes))
            }
            outcomes = outcomes + outcome
            return CompetitionMahjongRecordStatisticsResult(true, CompetitionMahjongStatistics.from(outcomes))
        }
    }

    private class Harness(val store: MemoryStore = MemoryStore()) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val game = CompetitionMahjongStatisticsViewModel(store, 0, scope, Dispatchers.Unconfined)
        suspend fun ready() = withTimeout(2_000) { game.state.first { !it.loading } }
        fun close() = scope.cancel()
    }

    @Test fun loadAndRefreshFailurePreserveLastGoodStatistics() = runBlocking {
        val store = MemoryStore().apply { outcomes = listOf(outcome()) }
        val harness = Harness(store)
        try {
            assertEquals(1, harness.ready().statistics.seriesCount)
            store.failLoad = true
            harness.game.refresh().join()
            val state = harness.game.state.value
            assertEquals(1, state.statistics.seriesCount)
            assertNotNull(state.error)
        } finally { harness.close() }
    }

    @Test fun formalCheckpointRecordsOnceAndDuplicateDeliveryIsIdempotent() = runBlocking {
        val harness = Harness()
        try {
            harness.ready()
            var now = 1234L
            val recorder = CompetitionMahjongStatisticsRecorder(harness.store, policy(), clockMillis = { now })
            val checkpoint = completeCheckpoint()
            harness.game.record(checkpoint, recorder).join()
            assertEquals(1, harness.game.state.value.statistics.seriesCount)
            assertEquals("赛事统计已记录", harness.game.state.value.notice)

            now = 9999
            harness.game.record(checkpoint, recorder).join()
            assertEquals(1, harness.game.state.value.statistics.seriesCount)
            assertEquals("该赛事已经记录", harness.game.state.value.notice)
            assertEquals(2, harness.store.recordCalls)
        } finally { harness.close() }
    }

    @Test fun unauthorizedDifficultyFailsBeforeStoreMutationAndCanRetryWithCorrectPolicy() = runBlocking {
        val harness = Harness()
        try {
            harness.ready()
            val wrong = CompetitionMahjongStatisticsRecorder(harness.store,
                CompetitionMahjongPlayPolicy.fromProgress(CompetitionMahjongUnlockProgress(true, 10, 15, 20),
                    mapOf(Difficulty.EASY to 3, Difficulty.MEDIUM to 2,
                        Difficulty.HARD to 1, Difficulty.MASTER to 0)))
            harness.game.record(completeCheckpoint(), wrong).join()
            assertEquals(0, harness.store.recordCalls)
            assertNotNull(harness.game.state.value.error)

            harness.game.record(completeCheckpoint(), CompetitionMahjongStatisticsRecorder(
                harness.store, policy(), clockMillis = { 55 })).join()
            assertEquals(1, harness.game.state.value.statistics.seriesCount)
        } finally { harness.close() }
    }

    @Test fun parentCancellationMarksStateClosed() = runBlocking {
        val harness = Harness()
        harness.ready()
        harness.close()
        withTimeout(2_000) { harness.game.state.first { it.closed } }
        assertTrue(harness.game.state.value.closed)
    }

    @Test fun standardPointsFormatsExactTwelfthsWithoutFloatingPoint() {
        assertEquals("0", competitionMahjongStandardPoints(0))
        assertEquals("1", competitionMahjongStandardPoints(12))
        assertEquals("1 1/2", competitionMahjongStandardPoints(18))
        assertEquals("2 1/3", competitionMahjongStandardPoints(28))
        assertEquals("2 1/4", competitionMahjongStandardPoints(27))
    }

    private fun completeCheckpoint(): CompetitionMahjongMatchCheckpoint {
        val config = matchConfig()
        return CompetitionMahjongMatchCheckpoint(config, matchLedger(config, 16), 15,
            matchRecord(config.handConfig(15).viewerSeat, terminal = true),
            CompetitionMahjongAssistanceUsage(Difficulty.HARD))
    }

    private fun policy() = CompetitionMahjongPlayPolicy.fromProgress(
        CompetitionMahjongUnlockProgress(true, 10, 15, 20), Difficulty.entries.associateWith { it.code })

    private fun outcome() = CompetitionMahjongSeriesOutcome(
        "91000000-0000-4000-8000-000000000001", 0, Difficulty.EASY, 1, 1,
        20, 4, 1, 1, 1, 0, 8, 8)
}
