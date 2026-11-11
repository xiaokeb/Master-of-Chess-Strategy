package com.masterofchessstrategy.games.competition_mahjong.auto

import com.masterofchessstrategy.engine.CompetitionMahjongAutoConfig
import com.masterofchessstrategy.engine.CompetitionMahjongAutoSession
import com.masterofchessstrategy.engine.CompetitionMahjongAutoAdvance
import com.masterofchessstrategy.engine.CompetitionMahjongPublicView
import com.masterofchessstrategy.engine.RestoreResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class CompetitionMahjongAutoPlayViewModelTest {
    private class Harness(
        policy: com.masterofchessstrategy.games.competition_mahjong.match.policy.CompetitionMahjongPlayPolicy? = autoPolicy(),
        config: CompetitionMahjongAutoRunConfig = autoConfig(2, listOf(0, 1, 2, 3)),
        openings: List<CompetitionMahjongAutoOpening> = emptyList(),
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store = AutoMemoryStore()
        val sessions = mutableListOf<ViewSession>()
        val game = CompetitionMahjongAutoPlayViewModel(store, config, policy, openings = openings,
            sessionFactory = { ViewSession(it).also(sessions::add) }, dispatcher = Dispatchers.Unconfined,
            parentScope = scope, waitMillis = { awaitCancellation() })
        suspend fun until(predicate: (CompetitionMahjongAutoPlayUiState) -> Boolean) =
            withTimeout(2000) { game.state.first(predicate) }
        fun close() { scope.cancel() }
    }

    /** Presentation double adds only the viewer hand guaranteed by real public v2 frames. */
    private class ViewSession(override val config: CompetitionMahjongAutoConfig) : CompetitionMahjongAutoSession {
        private val delegate = AutoSessionDouble(config)
        val advanceCalls get() = delegate.advanceCalls
        override fun snapshot(): CompetitionMahjongPublicView = delegate.snapshot().let { frame ->
            frame.copy(seats = frame.seats.map { seat ->
                if (seat.seat == frame.viewerSeat) seat.copy(concealedTiles = List(seat.concealedCount) { it % 9 }) else seat
            })
        }
        override fun advance(maxActions: Int): CompetitionMahjongAutoAdvance = delegate.advance(maxActions)
        override fun undo(): Boolean = delegate.undo()
        override fun serialize(): ByteArray = delegate.serialize()
        override fun restore(data: ByteArray): RestoreResult = delegate.restore(data)
        override fun close() = delegate.close()
    }

    @Test fun openPublishesOnlyPublicObservationAndNonSensitiveConfiguration() = runBlocking {
        val harness = Harness()
        try {
            val state = harness.until { it.table != null }
            val table = checkNotNull(state.table)
            assertEquals(listOf("玩家1AI", "玩家2AI", "玩家3AI", "玩家4AI"), table.seats.map { it.name })
            assertTrue(table.availableActions.isEmpty())
            assertEquals(13, table.seats[table.localSeat].visibleHand!!.size)
            assertTrue(table.seats.filter { it.seat != table.localSeat }.all { it.visibleHand == null })
            assertEquals(listOf(0, 1, 2, 3), state.configuration!!.playerBotLevels)
            assertFalse(state.configuration.toString().contains("109"))
            assertFalse(state.configuration.toString().contains("73"))
            assertEquals(4, state.difficultyOptions.size)
        } finally { harness.close() }
    }

    @Test fun missingPolicyFailsClosedBeforeSessionAndKeepsRetryVisible() = runBlocking {
        val harness = Harness(policy = null)
        try {
            val state = harness.until { it.playback.error != null }
            assertNull(state.table)
            assertNull(state.configuration)
            assertTrue(harness.sessions.isEmpty())
            assertTrue(state.difficultyOptions.isEmpty())
            assertTrue(state.playback.error!!.contains("权限"))
        } finally { harness.close() }
    }

    @Test fun tableStatusUsesAiNamesAndManualStepPausesContinuousPlayback() = runBlocking {
        val harness = Harness()
        try {
            var state = harness.until { it.table != null }
            assertTrue(state.table!!.status.contains("暂停"))
            harness.game.updateSettings(state.playback.activity.settings.copy(paused = false))
            state = harness.until { !it.playback.activity.settings.paused }
            harness.game.step()
            state = harness.until { it.playback.activity.eventsAfterOpening == 1 }
            assertTrue(state.playback.activity.settings.paused)
            assertTrue(checkNotNull(state.table).status.contains("暂停"))
            assertEquals(1, harness.sessions.single().advanceCalls)
            assertTrue(checkNotNull(state.table).availableActions.isEmpty())
        } finally { harness.close() }
    }

    @Test fun savedConfigurationTracksHandAndFinalStandingsWithoutPrivateRecords() = runBlocking {
        val harness = Harness(config = autoConfig())
        try {
            harness.until { it.table != null }
            repeat(63) { harness.game.step().join() }
            val state = harness.until { it.playback.activity.finished && it.configuration?.standings?.size == 4 }
            val display = checkNotNull(state.configuration)
            assertEquals(15, display.handIndex)
            assertEquals(0, display.seriesIndex)
            assertEquals(listOf(1, 1, 1, 1), display.standings.map { it.rank })
            assertEquals(listOf(0, 1, 2, 3), display.standings.map { it.playerId }.sorted())
        } finally { harness.close() }
    }

    @Test fun openingOptionsExposeNoBytesAndUnavailableRecordCannotReplaceActivity() = runBlocking {
        val config = autoConfig()
        val openingConfig = config.copyForTest(levels = listOf(0, 3, 0, 0))
        val record = autoRecord(openingConfig.handConfig(0, 0, true), 1)
        val opening = CompetitionMahjongAutoOpening("fixed-1", "固定一巡", openingConfig, record)
        record.fill(0)
        val harness = Harness(policy = autoPolicy(false), config = config, openings = listOf(opening))
        try {
            val state = harness.until { it.table != null }
            assertEquals(listOf(CompetitionMahjongAutoOpeningOption("fixed-1", "固定一巡", false, listOf(0, 3, 0, 0))),
                state.openings)
            val runId = state.playback.activity.runId
            harness.game.newRun(listOf(0, 0, 0, 0), state.playback.activity.settings, "fixed-1")
            yield()
            assertEquals(runId, harness.game.state.value.playback.activity.runId)
            assertEquals(1, harness.sessions.size)
        } finally { harness.close() }
    }

    @Test fun unknownOpeningAndLockedLevelRequestsAreIgnoredBeforeCreatingNative() = runBlocking {
        val harness = Harness(policy = autoPolicy(false), config = autoConfig())
        try {
            val state = harness.until { it.table != null }
            val runId = state.playback.activity.runId
            harness.game.newRun(listOf(0, 0, 0, 0), state.playback.activity.settings, "missing")
            harness.game.newRun(listOf(0, 1, 0, 0), state.playback.activity.settings)
            yield()
            assertEquals(runId, harness.game.state.value.playback.activity.runId)
            assertEquals(1, harness.sessions.size)
        } finally { harness.close() }
    }

    @Test fun leavingWaitsForExecutionStateSaveAndFailureKeepsPageActive() = runBlocking {
        val harness = Harness()
        try {
            harness.until { it.table != null }
            harness.game.setHost(true).join()
            harness.until { it.playback.activity.executionActive }
            harness.store.failNext = true
            var left = false
            harness.game.leave { left = true }.join()
            assertFalse(left)
            assertTrue(harness.game.state.value.playback.activity.needsSave)
            harness.game.retrySave().join()
            harness.until { !it.playback.activity.needsSave }
            harness.game.leave { left = true }.join()
            assertTrue(left)
            assertFalse(harness.game.state.value.playback.activity.executionActive)
        } finally { harness.close() }
    }

    private fun CompetitionMahjongAutoRunConfig.copyForTest(levels: List<Int>) =
        CompetitionMahjongAutoRunConfig(runId, seed, viewerPlayerId, levels, decisionSeed)
}
