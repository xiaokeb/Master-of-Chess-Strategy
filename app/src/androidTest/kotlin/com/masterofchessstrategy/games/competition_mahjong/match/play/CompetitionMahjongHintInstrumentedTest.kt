package com.masterofchessstrategy.games.competition_mahjong.match.play

import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.engine.*
import com.masterofchessstrategy.games.competition_mahjong.match.*
import com.masterofchessstrategy.games.competition_mahjong.match.hint.*
import com.masterofchessstrategy.games.competition_mahjong.match.policy.*
import com.masterofchessstrategy.games.competition_mahjong.match.storage.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Actual Native/AtomicFile persistence with a deterministic suggestion fixture, not playing-strength evidence. */
@RunWith(AndroidJUnit4::class)
class CompetitionMahjongHintInstrumentedTest {
    @Test fun paidHintRestoresWithoutAdvisorAndWithoutASecondChargeOrNativeEvent() = runBlocking {
        verifyPaidHint(false)
    }

    @Test fun productionPublicAdvisorRestoresReceiptWithoutASecondChargeOrNativeEvent() = runBlocking {
        verifyPaidHint(true)
    }

    @Test fun readyAdviceUsesCurrentScoringAndOnlyPubliclyUnaccountedWinningTiles() = runBlocking {
        val frame = NativeCompetitionMahjongSession(19, scoring = CompetitionMahjongScoring.WMO_2021_KONGS)
            .use { it.snapshot() }
        val ownHand = (0..8).toList() + listOf(18, 19, 20, 27, 31)
        val view = frame.copy(seats = frame.seats.map { seat ->
            seat.copy(concealedTiles = if (seat.seat == 0) ownHand else emptyList(),
                discards = if (seat.seat == 1) List(3) { 31 } else emptyList())
        })
        val discards = listOf(0, 27, 31).map { CompetitionMahjongAction(CompetitionMahjongRoundActionKind.DISCARD, it) }
        val input = CompetitionMahjongHintInput(view, discards, CompetitionMahjongScoring.WMO_2021_KONGS)
        val ownCounts = IntArray(34).also { counts -> ownHand.forEach { counts[it]++ } }
        val known = competitionMahjongKnownCopies(input.view, ownCounts)
        assertEquals(listOf(
            NativeCompetitionMahjongDiscardWaits.Discard(0, 0, 0),
            NativeCompetitionMahjongDiscardWaits.Discard(27, 0, 0),
            NativeCompetitionMahjongDiscardWaits.Discard(31, 3, 3),
        ), NativeCompetitionMahjongDiscardWaits.evaluate(input.view, known, listOf(0, 27, 31), input.scoring))
        assertEquals(discards.last(), CompetitionMahjongReadyHintAdvisor.recommend(input, 0))
        val hostileChange = view.copy(seats = view.seats.map { seat ->
            if (seat.seat != 1) seat else seat.copy(concealedTiles = List(4) { 27 }, melds = listOf(
                CompetitionMahjongPublicMeld(CompetitionMahjongMeldKind.KONG, 27, 1, 0, false),
            ))
        })
        assertEquals(discards.last(), CompetitionMahjongReadyHintAdvisor.recommend(
            CompetitionMahjongHintInput(hostileChange, discards, input.scoring), 0,
        ))
    }

    @Test fun anOpenHandBelowEightDoesNotReceiveAQualifyingWait() {
        val frame = NativeCompetitionMahjongSession(19).use { it.snapshot() }
        val openChows = listOf(1, 4, 11, 23).map { tile ->
            CompetitionMahjongPublicMeld(CompetitionMahjongMeldKind.CHOW, tile, 3, 2, false)
        }
        val view = frame.copy(seats = frame.seats.map { seat ->
            if (seat.seat != 0) seat else seat.copy(concealedCount = 2, concealedTiles = listOf(27, 31), melds = openChows)
        })
        val known = IntArray(34).also { it[27] = 1; it[31] = 1 }
        openChows.forEach { meld -> repeat(3) { known[checkNotNull(meld.tile) + it]++ } }
        assertEquals(listOf(NativeCompetitionMahjongDiscardWaits.Discard(31, 0, 0)),
            NativeCompetitionMahjongDiscardWaits.evaluate(view, known, listOf(31), CompetitionMahjongScoring.WMO_2021_KONGS))
    }

    private suspend fun verifyPaidHint(useProduction: Boolean) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "cm-hint-${UUID.randomUUID()}")
        assertTrue(directory.mkdirs())
        val owners = mutableListOf<ViewModelStore>()
        try {
            val policy = CompetitionMahjongPlayPolicy.fromProgress(
                CompetitionMahjongUnlockProgress(true, 10, 15, 20), Difficulty.entries.associateWith { it.code })
            val strategy = if (useProduction) CompetitionMahjongBotStrategy.POSITION_SEEDED_WMO_KONGS
                else CompetitionMahjongBotStrategy.LEGACY_BATCH
            val config = CompetitionMahjongCompetitionConfig(UUID.randomUUID().toString(), 925, botLevel = 2,
                botStrategy = strategy)
            fun repository() = CompetitionMahjongFormalRepository(
                CompetitionMahjongFormalFileStorage(directory, 0), 0, policy, { saved ->
                    val expected = saved.config.handConfig(saved.activeHandIndex)
                    NativeCompetitionMahjongSession(expected.seed, expected.dealerSeat, expected.roundWind,
                        expected.viewerSeat, expected.scoring).use {
                        check(it.restore(saved.engineRecord) == RestoreResult.Restored)
                        saved.validateRestoredFrame(it.snapshot(), it.legalActions())
                    }
                })
            val calls = AtomicInteger()
            val advisor = if (useProduction) CompetitionMahjongReadyHintAdvisor else object : CompetitionMahjongHintAdvisor {
                override val identity = "instrumented-fixture-v1"
                override suspend fun recommend(input: CompetitionMahjongHintInput, decisionSeed: Long): CompetitionMahjongAction {
                    calls.incrementAndGet()
                    return input.actions.first { it.kind == CompetitionMahjongRoundActionKind.DISCARD }
                }
            }
            fun game(advisor: CompetitionMahjongHintAdvisor?): CompetitionMahjongMatchPlayViewModel {
                lateinit var result: CompetitionMahjongMatchPlayViewModel
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    result = CompetitionMahjongMatchPlayViewModel(repository().matchStore, config,
                        ownerIsCurrent = { true }, playPolicy = policy, hintAdvisor = advisor)
                    ViewModelStore().also { owners += it; it.put("hint-test", result) }
                }
                return result
            }
            val first = game(advisor)
            withTimeout(15_000) { first.state.first { !it.loading && !it.busy } }
            val before = repository().matchStore.load(0)!!.engineRecord
            InstrumentationRegistry.getInstrumentation().runOnMainSync { first.requestHint(0) }
            withTimeout(15_000) { first.state.first { !it.busy && it.hint != null } }
            val receipt = first.state.value.hint
            assertEquals(1, repository().matchStore.load(0)!!.assistance!!.hintUsed)
            assertArrayEquals(before, repository().matchStore.load(0)!!.engineRecord)
            InstrumentationRegistry.getInstrumentation().runOnMainSync { owners.first().clear() }
            val restored = game(null)
            withTimeout(15_000) { restored.state.first { !it.loading && !it.busy } }
            assertEquals(receipt, restored.state.value.hint)
            assertTrue(restored.state.value.canHint)
            InstrumentationRegistry.getInstrumentation().runOnMainSync { restored.requestHint(0) }
            withTimeout(15_000) { restored.state.first { !it.busy } }
            assertEquals(if (useProduction) 0 else 1, calls.get())
            assertEquals(1, repository().matchStore.load(0)!!.assistance!!.hintUsed)
            assertArrayEquals(before, repository().matchStore.load(0)!!.engineRecord)
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { owners.forEach(ViewModelStore::clear) }
            directory.deleteRecursively()
        }
    }
}
