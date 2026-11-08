package com.masterofchessstrategy.games.competition_mahjong.match.play

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.masterofchessstrategy.engine.NativeCompetitionMahjongSession
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.games.competition_mahjong.CompetitionMahjongActionKind
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongCompetitionConfig
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongMatchCheckpoint
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongMatchCheckpointCodec
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongMatchStore
import com.masterofchessstrategy.games.competition_mahjong.match.policy.CompetitionMahjongPlayPolicy
import com.masterofchessstrategy.games.competition_mahjong.match.policy.CompetitionMahjongUnlockProgress
import com.masterofchessstrategy.games.competition_mahjong.match.hint.*
import com.masterofchessstrategy.engine.CompetitionMahjongAction
import com.masterofchessstrategy.engine.CompetitionMahjongRoundActionKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Production series content plus real JNI; the byte store and Activity isolate all App data. */
@RunWith(AndroidJUnit4::class)
class CompetitionMahjongMatchPlayContentTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun discardUpdatesThePrivateRecordThroughTheSeriesPage() = runBlocking {
        withGame { game, store, natives, _ ->
            val original = store.load(0)!!.engineRecord
            chooseDiscard(game)
            waitReady(game)
            val saved = store.load(0)!!
            assertFalse(original.contentEquals(saved.engineRecord))
            assertArrayEquals(natives.last().serialize(), saved.engineRecord)
            assertEquals(0, saved.activeHandIndex)
            compose.onNodeWithTag("competition_mahjong_match_play").assertExists()
            compose.onNodeWithTag("competition_mahjong_match_progress").assertTextContains("已完成 0 / 16 盘")
        }
    }

    @Test fun declarationCancellationPreservesRecordAndConfirmationPersistsNativePenalty() = runBlocking {
        withGame { game, store, natives, _ ->
            val original = natives.last().serialize()
            compose.onNodeWithTag("competition_mahjong_match_declare").performClick()
            compose.onNodeWithText("取消").performClick()
            assertArrayEquals(original, natives.last().serialize())
            val table = game.state.value.table!!
            val tile = game.state.value.declarationTiles.first { candidate ->
                table.availableActions.none { it.kind == CompetitionMahjongActionKind.WIN && it.tile == candidate }
            }
            compose.onNodeWithTag("competition_mahjong_match_declare").performClick()
            compose.onNodeWithTag("competition_mahjong_match_declaration_tile_$tile").performScrollTo().performClick()
            compose.onNodeWithTag("competition_mahjong_match_confirm_declaration").performClick()
            waitReady(game)
            assertTrue(natives.last().snapshot().seats[0].winDisabled)
            compose.onNodeWithTag("competition_mahjong_feedback").assertTextContains("本盘停和", substring = true)
            compose.onNodeWithTag("competition_mahjong_match_declare").assertIsNotEnabled()
            assertArrayEquals(natives.last().serialize(), store.load(0)!!.engineRecord)
        }
    }

    @Test fun newSeriesNeedsConfirmationAndPersistsTheSelectedStrategy() = runBlocking {
        withGame { game, store, natives, _ ->
            val oldId = store.load(0)!!.config.matchId
            val original = store.load(0)!!.engineRecord
            compose.onNodeWithText("设置").performClick()
            compose.onNodeWithText("返回牌桌").performClick()
            assertArrayEquals(original, store.load(0)!!.engineRecord)
            compose.onNodeWithText("设置").performClick()
            compose.onNodeWithTag("competition_mahjong_match_level_3").performScrollTo().performClick()
            compose.onNodeWithTag("competition_mahjong_match_confirm_new").performClick()
            waitReady(game)
            val fresh = store.load(0)!!
            assertFalse(oldId == fresh.config.matchId)
            assertEquals(3, fresh.config.botLevel)
            assertEquals(3, game.state.value.botLevel)
            assertEquals(0, fresh.activeHandIndex)
            assertEquals(0, fresh.ledger.completedHandCount)
            assertArrayEquals(natives.last().serialize(), fresh.engineRecord)
        }
    }

    @Test fun saveFailurePreventsLeavingUntilRetryOrAnExplicitUnsavedChoice() = runBlocking {
        withGame { game, store, natives, backCalls ->
            compose.runOnUiThread { game.toggleBots() }
            waitReady(game)
            store.fail = true
            chooseDiscard(game)
            compose.waitUntil(15_000) { !game.state.value.busy && game.state.value.series.needsSave }
            compose.onNodeWithText("返回").performClick()
            compose.onNodeWithTag("competition_mahjong_match_leave").assertExists()
            compose.runOnIdle { assertEquals(0, backCalls()) }
            store.fail = false
            compose.onNodeWithTag("competition_mahjong_match_leave_confirm").performClick()
            waitReady(game)
            assertArrayEquals(natives.last().serialize(), store.load(0)!!.engineRecord)
            compose.onNodeWithText("返回").performClick()
            compose.runOnIdle { assertEquals(1, backCalls()) }
        }
    }

    @Test fun lockedDifficultyChipsAndDirectCommandCannotReplaceTheSavedSeries() = runBlocking {
        withGame(botLevel = 0, policy = CompetitionMahjongPlayPolicy.fromProgress(
            CompetitionMahjongUnlockProgress(true), Difficulty.entries.associateWith { it.code })) { game, store, natives, _ ->
            val old = store.load(0)!!
            val record = old.engineRecord
            compose.onNodeWithText("设置").performClick()
            (1..3).forEach { level ->
                compose.onNodeWithTag("competition_mahjong_match_level_$level").performScrollTo().assertIsNotEnabled()
            }
            compose.runOnUiThread { game.newMatch(3) }
            waitReady(game)
            assertEquals(1, natives.size)
            assertEquals(old.config.matchId, store.load(0)!!.config.matchId)
            assertArrayEquals(record, store.load(0)!!.engineRecord)
            assertEquals(0, store.load(0)!!.config.botLevel)
        }
    }

    @Test fun hardUndoUsesOneSeriesAllowanceAndSecondCommandKeepsNativeAndSavedUsage() = runBlocking {
        withGame { game, store, natives, _ ->
            chooseDiscard(game)
            waitReady(game)
            compose.runOnUiThread { game.undo(game.state.value.series.activeHandIndex) }
            waitReady(game)
            assertEquals(1, store.load(0)!!.assistance!!.undoUsed)
            assertEquals(0, game.state.value.assistance!!.undoRemaining)
            assertFalse(game.state.value.table!!.canUndo)
            val record = natives.last().serialize()
            compose.runOnUiThread { game.undo(game.state.value.series.activeHandIndex) }
            waitReady(game)
            assertArrayEquals(record, natives.last().serialize())
            assertArrayEquals(record, store.load(0)!!.engineRecord)
            assertEquals(1, store.load(0)!!.assistance!!.undoUsed)
        }
    }

    @Test fun settingsPauseAndSpeedPersistWithoutChangingTheNativePosition() = runBlocking {
        withGame { game, store, natives, _ ->
            val before = natives.last().serialize()
            compose.onNodeWithText("设置").performClick()
            compose.onNodeWithTag("competition_mahjong_match_pause").performScrollTo().performClick()
            waitReady(game)
            compose.onNodeWithTag("competition_mahjong_match_speed_4000").performScrollTo().performClick()
            waitReady(game)
            assertTrue(store.load(0)!!.playback!!.paused)
            assertEquals(4f, store.load(0)!!.playback!!.speed)
            assertArrayEquals(before, natives.last().serialize())
            compose.onNodeWithText("返回牌桌").performClick()
            compose.runOnUiThread { game.setForeground(false); game.setForeground(true) }
            waitReady(game)
            assertTrue(game.state.value.paused)
            assertEquals(4f, game.state.value.speed)
            assertArrayEquals(before, store.load(0)!!.engineRecord)
        }
    }

    @Test fun injectedHintShowsOnlyAfterSaveAndRepeatedViewKeepsTheSameCharge() = runBlocking {
        var calls = 0
        val advisor = object : CompetitionMahjongHintAdvisor {
            override val identity = "instrumented-fixture-v1"
            override suspend fun recommend(input: CompetitionMahjongHintInput, decisionSeed: Long): CompetitionMahjongAction {
                calls++
                assertTrue(input.view.seats.filter { it.seat != input.view.viewerSeat }.all { it.concealedTiles.isEmpty() })
                return input.actions.first { it.kind == CompetitionMahjongRoundActionKind.DISCARD }
            }
        }
        withGame(hintAdvisor = advisor) { game, store, natives, _ ->
            val before = natives.last().serialize()
            compose.onNodeWithTag("competition_mahjong_match_hint").performClick()
            waitReady(game)
            compose.onNodeWithTag("competition_mahjong_match_hint_advice").assertTextContains("建议：", substring = true)
            assertEquals(1, store.load(0)!!.assistance!!.hintUsed)
            assertArrayEquals(before, natives.last().serialize())
            compose.onNodeWithTag("competition_mahjong_match_hint").performClick()
            waitReady(game)
            assertEquals(1, calls)
            assertEquals(1, store.load(0)!!.assistance!!.hintUsed)
        }
    }

    @Test fun productionPublicAdviceIsVisibleWithoutApplyingIt() = runBlocking {
        withGame(hintAdvisor = CompetitionMahjongPublicShapeHintAdvisor) { game, store, natives, _ ->
            val before = natives.last().serialize()
            compose.onNodeWithTag("competition_mahjong_match_hint").performClick()
            waitReady(game)
            compose.onNodeWithTag("competition_mahjong_match_hint_advice").assertTextContains("建议：", substring = true)
            assertEquals(CompetitionMahjongPublicShapeHintAdvisor.identity, game.state.value.hint!!.advisorIdentity)
            assertEquals(1, store.load(0)!!.assistance!!.hintUsed)
            assertArrayEquals(before, natives.last().serialize())
        }
    }

    private suspend fun chooseDiscard(game: CompetitionMahjongMatchPlayViewModel) {
        val action = game.state.value.table!!.availableActions.first { it.kind == CompetitionMahjongActionKind.DISCARD }
        compose.onNodeWithTag("competition_mahjong_action_${action.id}").performScrollTo().performClick()
    }

    private suspend fun waitReady(game: CompetitionMahjongMatchPlayViewModel) {
        withTimeout(15_000) { game.state.first { !it.loading && !it.busy } }
        assertFalse(game.state.value.series.needsSave)
    }

    private suspend fun withGame(
        botLevel: Int = 2,
        policy: CompetitionMahjongPlayPolicy = CompetitionMahjongPlayPolicy.fromProgress(
            CompetitionMahjongUnlockProgress(true, 10, 15, 20), Difficulty.entries.associateWith { it.code }),
        hintAdvisor: CompetitionMahjongHintAdvisor? = null,
        block: suspend (CompetitionMahjongMatchPlayViewModel, ByteStore, CopyOnWriteArrayList<NativeCompetitionMahjongSession>, () -> Int) -> Unit,
    ) {
        val store = ByteStore()
        val natives = CopyOnWriteArrayList<NativeCompetitionMahjongSession>()
        val owner = ViewModelStore()
        lateinit var game: CompetitionMahjongMatchPlayViewModel
        var backCalls = 0
        compose.runOnUiThread {
            game = CompetitionMahjongMatchPlayViewModel(store,
                CompetitionMahjongCompetitionConfig(UUID.randomUUID().toString(), 925, botLevel = botLevel),
                { config -> NativeCompetitionMahjongSession(config.seed, config.dealerSeat, config.roundWind, config.viewerSeat)
                    .also(natives::add) }, ownerIsCurrent = { true }, playPolicy = policy, hintAdvisor = hintAdvisor)
            owner.put("match-play-test", game)
        }
        try {
            waitReady(game)
            assertTrue(game.state.value.canOperate)
            compose.setContent { MaterialTheme { CompetitionMahjongMatchPlayContent(game, { backCalls++ }) } }
            block(game, store, natives) { backCalls }
        } finally {
            compose.runOnUiThread { compose.activity.setContent { }; owner.clear() }
        }
    }

    private class ByteStore : CompetitionMahjongMatchStore {
        @Volatile private var bytes: ByteArray? = null
        @Volatile var fail = false
        override suspend fun load(viewerPlayerId: Int): CompetitionMahjongMatchCheckpoint? = bytes?.let {
            CompetitionMahjongMatchCheckpointCodec.decode(it, viewerPlayerId) ?: throw IOException("测试存档损坏")
        }
        override suspend fun save(checkpoint: CompetitionMahjongMatchCheckpoint) {
            if (fail) throw IOException("测试写失败")
            bytes = CompetitionMahjongMatchCheckpointCodec.encode(checkpoint)
        }
    }
}
