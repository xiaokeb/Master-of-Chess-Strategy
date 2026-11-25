package com.masterofchessstrategy.games.lanzhou_mahjong

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.NativeLanzhouMahjongSession
import com.masterofchessstrategy.games.lanzhou_mahjong.data.LanzhouMahjongEntryFacts
import com.masterofchessstrategy.games.lanzhou_mahjong.data.LanzhouMahjongLearningSnapshot
import com.masterofchessstrategy.games.lanzhou_mahjong.data.LanzhouMahjongStoredSession
import com.masterofchessstrategy.games.lanzhou_mahjong.settlement.LanzhouMahjongSeatStatistics
import com.masterofchessstrategy.games.lanzhou_mahjong.settlement.LanzhouMahjongStatistics
import java.io.File
import java.math.BigInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Pure menu callbacks; only the active-session case obtains real Native bytes, without opening a DB. */
@RunWith(AndroidJUnit4::class)
class LanzhouMahjongEntryScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun incompleteLearningShowsActualCountWithoutNewGame() {
        var lessons = 0
        show(state(3), onLearning = { lessons++ })
        compose.onNodeWithText("已掌握 3/10 课").assertIsDisplayed()
        compose.onNodeWithTag("lanzhou_mahjong_entry_new_easy").assertDoesNotExist()
        compose.onNodeWithTag("lanzhou_mahjong_entry_resume").assertDoesNotExist()
        compose.onNodeWithTag("lanzhou_mahjong_entry_learning").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, lessons) }
    }

    @Test fun completedLearningShowsRealProgressAndOpensOnlyEligibleDifficulty() {
        val creations = mutableListOf<Difficulty>()
        show(state(10), onNew = { creations += it })
        compose.onNodeWithTag("lanzhou_mahjong_entry_new_easy").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf(Difficulty.EASY), creations) }
        compose.onNodeWithTag("lanzhou_mahjong_entry_new_medium").performScrollTo().assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("中等 · 简单可信胜场 0/10").assertExists()
        compose.onNodeWithText("困难 · 中等可信胜场 0/15").assertExists()
        compose.onNodeWithText("大师 · 困难可信胜场 0/20").assertExists()
        capture("locked")
    }

    @Test fun unlockedButtonsSendTheirOwnDifficulty() {
        val creations = mutableListOf<Difficulty>()
        val progress = state(10)
        show(progress.copy(facts = requireNotNull(progress.facts).copy(trustedWins = mapOf(
            Difficulty.EASY to 10, Difficulty.MEDIUM to 15, Difficulty.HARD to 20,
        ))), onNew = { creations += it })
        for (difficulty in listOf(Difficulty.MEDIUM, Difficulty.HARD, Difficulty.MASTER)) {
            compose.onNodeWithTag("lanzhou_mahjong_entry_new_${difficulty.name.lowercase()}")
                .performScrollTo().assertIsEnabled().performClick()
        }
        compose.runOnIdle { assertEquals(listOf(Difficulty.MEDIUM, Difficulty.HARD, Difficulty.MASTER), creations) }
        capture("unlocked")
    }

    @Test fun completedRoundsShowFourSeatsOnDemand() {
        val source = state(10)
        show(source.copy(facts = requireNotNull(source.facts).copy(statistics = LanzhouMahjongStatistics(
            completedRounds = 2,
            exhaustiveDraws = 1,
            seats = listOf(-72L, 24L, 24L, 24L).mapIndexed { index, score ->
                LanzhouMahjongSeatStatistics(falseDeclarations = if (index == 0) 1 else 0,
                    netScore = BigInteger.valueOf(score))
            },
        ))))
        compose.onNodeWithTag("lanzhou_mahjong_statistics_seat_0").assertDoesNotExist()
        compose.onNodeWithTag("lanzhou_mahjong_entry_statistics").performScrollTo().performClick()
        compose.onNodeWithText("已结算 2 局 · 黄庄 1 局").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("1 号席 · 胡牌 0 次 · 炸胡 1 次 · 净分 -72").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("4 号席 · 胡牌 0 次 · 炸胡 0 次 · 净分 +24").performScrollTo().assertIsDisplayed()
        capture("statistics")
        compose.onNodeWithTag("lanzhou_mahjong_entry_statistics").performScrollTo().performClick()
        compose.onNodeWithTag("lanzhou_mahjong_statistics_seat_0").assertDoesNotExist()
    }

    @Test fun existingHardSessionOffersExplicitResumeWithoutReplacingIt() {
        val active = runBlocking(Dispatchers.Default) {
            NativeLanzhouMahjongSession(321, 0, 1, 2).use { native ->
                LanzhouMahjongStoredSession(LanzhouMahjongSessionRecord(Difficulty.HARD, 2, 321, 0, 1, 321,
                    native.serialize()), 51)
            }
        }
        var resumes = 0
        show(state(2).copy(facts = LanzhouMahjongEntryFacts(LanzhouMahjongLearningSnapshot(2, 2, 30), active)),
            onResume = { resumes++ })
        compose.onNodeWithTag("lanzhou_mahjong_entry_resume").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText("继续困难对局").assertIsDisplayed()
        compose.onNodeWithTag("lanzhou_mahjong_entry_new_easy").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, resumes) }
    }

    @Test fun loadingCannotExposeStalePlayableFacts() {
        show(state(10).copy(loading = true))
        compose.onNodeWithText("正在读取学习与对局进度…").assertIsDisplayed()
        compose.onNodeWithTag("lanzhou_mahjong_entry_new_easy").assertDoesNotExist()
        compose.onNodeWithTag("lanzhou_mahjong_entry_refresh").assertIsNotEnabled()
    }

    @Test fun readFailureOffersRefreshWithoutAnUnlock() {
        var refreshes = 0
        show(LanzhouMahjongEntryState(loading = false, error = "菜单进度读取失败，请重试。原记录已保留"),
            onRefresh = { refreshes++ })
        compose.onNodeWithTag("lanzhou_mahjong_entry_error").assertIsDisplayed()
        compose.onNodeWithTag("lanzhou_mahjong_entry_new_easy").assertDoesNotExist()
        compose.onNodeWithTag("lanzhou_mahjong_entry_refresh").performClick()
        compose.runOnIdle { assertEquals(1, refreshes) }
    }

    @Test fun retiredPageOffersOnlyBack() {
        var backs = 0
        show(state(10).copy(retired = true, error = "本地数据已更换或页面已关闭，请返回后重新进入"),
            onBack = { backs++ })
        compose.onNodeWithTag("lanzhou_mahjong_entry_new_easy").assertDoesNotExist()
        compose.onNodeWithTag("lanzhou_mahjong_entry_learning").assertDoesNotExist()
        compose.onNodeWithTag("lanzhou_mahjong_entry_refresh").assertDoesNotExist()
        compose.onNodeWithTag("lanzhou_mahjong_entry_back").performClick()
        compose.runOnIdle { assertEquals(1, backs) }
    }

    @Test fun narrowLargeTextCanReachThresholdsAndRefresh() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                MaterialTheme {
                    Box(Modifier.requiredWidth(240.dp)) {
                        LanzhouMahjongEntryScreen(state(10), {}, {}, {}, {}, {})
                    }
                }
            }
        }
        compose.onNodeWithTag("lanzhou_mahjong_entry_new_easy").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("lanzhou_mahjong_entry_new_master").performScrollTo().assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithTag("lanzhou_mahjong_entry_refresh").performScrollTo().assertIsDisplayed().assertIsEnabled()
        capture("large-text")
    }

    private fun capture(label: String) {
        val token = InstrumentationRegistry.getArguments().getString("mocsVisualToken") ?: return
        require(token.matches(Regex("qa-[a-f0-9]{32}")))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(requireNotNull(context.getExternalFilesDir(null)), "mocs-visual/$token")
        check(directory.isDirectory || directory.mkdirs())
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        try {
            File(directory, "$label.png").outputStream().use {
                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun show(state: LanzhouMahjongEntryState, onLearning: () -> Unit = {}, onResume: () -> Unit = {},
        onNew: (Difficulty) -> Unit = {}, onBack: () -> Unit = {}, onRefresh: () -> Unit = {},
    ) = compose.setContent {
        MaterialTheme { LanzhouMahjongEntryScreen(state, onLearning, onResume, onNew, onBack, onRefresh) }
    }

    private fun state(count: Int) = LanzhouMahjongEntryState(loading = false,
        facts = LanzhouMahjongEntryFacts(LanzhouMahjongLearningSnapshot(2, count, 30), null))
}
