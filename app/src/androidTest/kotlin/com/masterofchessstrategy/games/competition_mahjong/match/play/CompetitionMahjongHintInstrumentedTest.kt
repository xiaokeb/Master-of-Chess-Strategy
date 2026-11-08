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

    private suspend fun verifyPaidHint(useProduction: Boolean) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "cm-hint-${UUID.randomUUID()}")
        assertTrue(directory.mkdirs())
        val owners = mutableListOf<ViewModelStore>()
        try {
            val policy = CompetitionMahjongPlayPolicy.fromProgress(
                CompetitionMahjongUnlockProgress(true, 10, 15, 20), Difficulty.entries.associateWith { it.code })
            val config = CompetitionMahjongCompetitionConfig(UUID.randomUUID().toString(), 925, botLevel = 2)
            fun repository() = CompetitionMahjongFormalRepository(
                CompetitionMahjongFormalFileStorage(directory, 0), 0, policy, { saved ->
                    val expected = saved.config.handConfig(saved.activeHandIndex)
                    NativeCompetitionMahjongSession(expected.seed, expected.dealerSeat, expected.roundWind, expected.viewerSeat).use {
                        check(it.restore(saved.engineRecord) == RestoreResult.Restored)
                        saved.validateRestoredFrame(it.snapshot(), it.legalActions())
                    }
                })
            val calls = AtomicInteger()
            val advisor = if (useProduction) CompetitionMahjongPublicShapeHintAdvisor else object : CompetitionMahjongHintAdvisor {
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
