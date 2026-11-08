package com.masterofchessstrategy.games.competition_mahjong.match.play

import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.NativeCompetitionMahjongSession
import com.masterofchessstrategy.engine.RestoreResult
import com.masterofchessstrategy.games.competition_mahjong.match.*
import com.masterofchessstrategy.games.competition_mahjong.match.policy.*
import com.masterofchessstrategy.games.competition_mahjong.match.storage.CompetitionMahjongFormalFileStorage
import com.masterofchessstrategy.games.competition_mahjong.match.storage.CompetitionMahjongFormalRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CompetitionMahjongPlaybackInstrumentedTest {
    @Test fun pausedSpeedRestoresThroughRealAtomicFileNativeAndRecreatedViewModel() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "cm-playback-${UUID.randomUUID()}")
        assertTrue(directory.mkdirs())
        val owner = ViewModelStore()
        try {
            val policy = CompetitionMahjongPlayPolicy.fromProgress(
                CompetitionMahjongUnlockProgress(true, 10, 15, 20), Difficulty.entries.associateWith { it.code })
            val config = CompetitionMahjongCompetitionConfig(UUID.randomUUID().toString(), 925, botLevel = 0)
            val hand = config.handConfig(0)
            val record = NativeCompetitionMahjongSession(hand.seed, hand.dealerSeat, hand.roundWind, hand.viewerSeat)
                .use { it.serialize() }
            fun repository() = CompetitionMahjongFormalRepository(
                CompetitionMahjongFormalFileStorage(directory, 0), 0, policy, { saved ->
                    val expected = saved.config.handConfig(saved.activeHandIndex)
                    NativeCompetitionMahjongSession(expected.seed, expected.dealerSeat, expected.roundWind, expected.viewerSeat).use {
                        check(it.restore(saved.engineRecord) == RestoreResult.Restored)
                        saved.validateRestoredFrame(it.snapshot())
                    }
                })
            repository().matchStore.save(CompetitionMahjongMatchCheckpoint(config, CompetitionMahjongMatchLedger.empty(), 0,
                record, CompetitionMahjongAssistanceUsage(Difficulty.EASY), CompetitionMahjongMatchPlayback(true, 4f)))
            lateinit var game: CompetitionMahjongMatchPlayViewModel
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                game = CompetitionMahjongMatchPlayViewModel(repository().matchStore, config, ownerIsCurrent = { true }, playPolicy = policy)
                owner.put("restored-playback", game)
            }
            withTimeout(15_000) { game.state.first { !it.loading && !it.busy } }
            assertTrue(game.state.value.paused)
            assertEquals(4f, game.state.value.speed)
            assertArrayEquals(record, repository().matchStore.load(0)!!.engineRecord)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                game.setForeground(false)
                game.setForeground(true)
            }
            withTimeout(15_000) { game.state.first { !it.busy } }
            assertTrue(game.state.value.paused)
            assertArrayEquals(record, repository().matchStore.load(0)!!.engineRecord)
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { owner.clear() }
            directory.deleteRecursively()
        }
    }
}
