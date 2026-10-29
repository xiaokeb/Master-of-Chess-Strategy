package com.masterofchessstrategy.games.lanzhou_mahjong

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.NativeLanzhouMahjongSession
import com.masterofchessstrategy.games.lanzhou_mahjong.data.LanzhouMahjongEntryFacts
import com.masterofchessstrategy.games.lanzhou_mahjong.data.LanzhouMahjongLearningSnapshot
import com.masterofchessstrategy.games.lanzhou_mahjong.data.LanzhouMahjongStoredSession
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

    @Test fun completedLearningOffersOnlyEasyAndDoesNotInventWinCounts() {
        var creations = 0
        show(state(10), onNewEasy = { creations++ })
        compose.onNodeWithTag("lanzhou_mahjong_entry_new_easy").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, creations) }
        compose.onNodeWithText("中等：简单累计胜10场", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("困难：中等累计胜15场", substring = true).assertExists()
        compose.onNodeWithText("大师：困难累计胜20场", substring = true).assertExists()
        compose.onNodeWithTag("lanzhou_mahjong_entry_trusted_wins_pending").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("0胜", substring = true).assertDoesNotExist()
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
        compose.onNodeWithTag("lanzhou_mahjong_entry_trusted_wins_pending").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("lanzhou_mahjong_entry_refresh").performScrollTo().assertIsDisplayed().assertIsEnabled()
    }

    private fun show(state: LanzhouMahjongEntryState, onLearning: () -> Unit = {}, onResume: () -> Unit = {},
        onNewEasy: () -> Unit = {}, onBack: () -> Unit = {}, onRefresh: () -> Unit = {},
    ) = compose.setContent {
        MaterialTheme { LanzhouMahjongEntryScreen(state, onLearning, onResume, onNewEasy, onBack, onRefresh) }
    }

    private fun state(count: Int) = LanzhouMahjongEntryState(loading = false,
        facts = LanzhouMahjongEntryFacts(LanzhouMahjongLearningSnapshot(2, count, 30), null))
}
