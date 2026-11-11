package com.masterofchessstrategy.games.competition_mahjong.auto

import androidx.activity.ComponentActivity
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.NativeCompetitionMahjongAutoSession
import com.masterofchessstrategy.games.competition_mahjong.match.policy.CompetitionMahjongPlayPolicy
import com.masterofchessstrategy.games.competition_mahjong.match.policy.CompetitionMahjongUnlockProgress
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CompetitionMahjongAutoPlayContentTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var game: CompetitionMahjongAutoPlayViewModel? = null

    @After fun closeSession() {
        game?.let { runBlocking { it.close().join() } }
        game = null
    }

    @Test fun pausedObservationStepsAndUndoesOneRealNativeEventWithoutManualActions() {
        val store = UiStore()
        val active = createGame(store, fullPolicy())
        setContent(active)
        awaitTable()
        compose.onNodeWithText("国标麻将 · AI自动演局").assertIsDisplayed()
        compose.onNodeWithTag("competition_mahjong_auto_position").assertTextContains("第1盘", substring = true)
        repeat(4) { compose.onNodeWithTag("competition_mahjong_auto_score_$it").performScrollTo().assertIsDisplayed() }
        compose.onNodeWithText("玩家1AI：简单").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("competition_mahjong_auto_step").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(5_000) { store.checkpoint?.eventsAfterOpening == 1 }
        captureCompetitionMahjongAuto("table")
        compose.onNodeWithTag("competition_mahjong_auto_undo").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(5_000) { store.checkpoint?.eventsAfterOpening == 0 }
        assertTrue(store.checkpoint!!.settings.paused)
        compose.onAllNodesWithText("摸牌").assertCountEquals(0)
        compose.onAllNodesWithText("出牌").assertCountEquals(0)
    }

    @Test fun system150PercentSettingsRemainScrollableAndSaveContinuousSpeedAndFiniteContinuation() {
        val store = UiStore()
        val active = createGame(store, fullPolicy())
        setContent(active, 1.5f)
        awaitTable()
        compose.onNodeWithTag("competition_mahjong_auto_settings").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText("播放与保存设置").assertIsDisplayed()
        compose.onNodeWithText("后台执行尚未启用，离开前台将暂停推进。").performScrollTo().assertIsDisplayed()
        captureCompetitionMahjongAuto("settings")
        compose.onNodeWithTag("competition_mahjong_auto_speed").performScrollTo().performTouchInput {
            swipeRight(startX = left + width * .1f, endX = right - width * .1f)
        }
        compose.onNodeWithTag("competition_mahjong_auto_auto_continue").performScrollTo().performClick()
        compose.onNodeWithTag("competition_mahjong_auto_max_series").performScrollTo().performTextClearance()
        compose.onNodeWithTag("competition_mahjong_auto_max_series").performTextInput("2")
        compose.onNodeWithTag("competition_mahjong_auto_settings_save").assertIsDisplayed().assertIsEnabled().performClick()
        compose.waitUntil(5_000) { store.checkpoint?.settings?.maxSeries == 2 }
        val settings = store.checkpoint!!.settings
        assertTrue(settings.autoContinue)
        assertTrue(settings.speed > .5f && settings.speed <= 4f)
        compose.onNodeWithTag("competition_mahjong_auto_settings_dialog").assertDoesNotExist()
    }

    @Test fun lockedOpeningAndDifficultyAreDisabledBeforeConfirmedNewRun() {
        val policy = easyPolicy()
        val initial = config()
        val lockedConfig = CompetitionMahjongAutoRunConfig("83000000-0000-4000-8000-000000000002", 73, 0,
            listOf(0, 3, 0, 0), 109)
        val openingBytes = NativeCompetitionMahjongAutoSession(lockedConfig.handConfig(0, 0, true)).use { it.serialize() }
        val opening = CompetitionMahjongAutoOpening("master-opening", "大师指定开局", lockedConfig, openingBytes)
        val store = UiStore()
        val active = createGame(store, policy, initial, listOf(opening))
        setContent(active)
        awaitTable()
        val previous = store.checkpoint!!.config.runId
        compose.onNodeWithTag("competition_mahjong_auto_new").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText("确认新开演局").assertIsDisplayed()
        compose.onNodeWithTag("competition_mahjong_auto_opening_master-opening").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("competition_mahjong_auto_player_0_level_1").performScrollTo().assertIsNotEnabled()
        captureCompetitionMahjongAuto("new-run")
        compose.onNodeWithTag("competition_mahjong_auto_new_confirm").assertIsDisplayed().assertIsEnabled().performClick()
        compose.waitUntil(5_000) { store.checkpoint?.config?.runId != previous }
        assertEquals(listOf(0, 0, 0, 0), store.checkpoint!!.config.playerBotLevels)
        assertNull(store.checkpoint!!.openingRecord)
        assertTrue(store.checkpoint!!.settings.paused)
    }

    private fun createGame(store: UiStore, policy: CompetitionMahjongPlayPolicy,
        initial: CompetitionMahjongAutoRunConfig = config(), openings: List<CompetitionMahjongAutoOpening> = emptyList()) =
        CompetitionMahjongAutoPlayViewModel(store, initial, policy, openings = openings).also { game = it }

    private fun setContent(active: CompetitionMahjongAutoPlayViewModel, fontScale: Float = 1f) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                MaterialTheme { CompetitionMahjongAutoContent(active, {}, Modifier.safeDrawingPadding()) }
            }
        }
    }

    private fun awaitTable() {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("competition_mahjong_auto_controls").fetchSemanticsNodes().isNotEmpty() &&
                game?.state?.value?.table != null
        }
    }

    private fun config() = CompetitionMahjongAutoRunConfig("83000000-0000-4000-8000-000000000001", 73, 0,
        listOf(0, 0, 0, 0), 109)

    private fun fullPolicy() = CompetitionMahjongPlayPolicy.fromProgress(
        CompetitionMahjongUnlockProgress(true, 10, 15, 20), Difficulty.entries.associateWith { it.code })
    private fun easyPolicy() = CompetitionMahjongPlayPolicy.fromProgress(
        CompetitionMahjongUnlockProgress(true), Difficulty.entries.associateWith { it.code })

    private class UiStore : CompetitionMahjongAutoStore {
        @Volatile var checkpoint: CompetitionMahjongAutoCheckpoint? = null
        val archives = linkedMapOf<String, CompetitionMahjongAutoArchive>()
        override suspend fun load(viewerPlayerId: Int): CompetitionMahjongAutoCheckpoint? =
            checkpoint?.also { require(it.config.viewerPlayerId == viewerPlayerId) }
        override suspend fun save(checkpoint: CompetitionMahjongAutoCheckpoint, archive: CompetitionMahjongAutoArchive?) {
            archive?.let { incoming -> archives[incoming.key]?.let { require(it.sameContent(incoming)) } }
            this.checkpoint = CompetitionMahjongAutoCheckpointCodec.decode(
                CompetitionMahjongAutoCheckpointCodec.encode(checkpoint), checkpoint.config.viewerPlayerId)!!
            archive?.let { archives.putIfAbsent(it.key, it) }
        }
    }
}

internal fun captureCompetitionMahjongAuto(name: String) {
    val token = InstrumentationRegistry.getArguments().getString("mahjong_auto_visual_token") ?: return
    require(UUID.fromString(token).toString() == token)
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val parent = checkNotNull(instrumentation.targetContext.getExternalFilesDir(null))
    val directory = File(parent, "competition-mahjong-auto-qa/$token")
    check(directory.isDirectory || directory.mkdirs())
    // Dialog windows also animate outside the Compose test clock.
    instrumentation.waitForIdleSync()
    SystemClock.sleep(350)
    val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
    try {
        File(directory, "$name.png").outputStream().use { check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    } finally {
        screenshot.recycle()
    }
}
