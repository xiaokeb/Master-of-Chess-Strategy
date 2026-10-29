package com.masterofchessstrategy.games.lanzhou_mahjong

import com.masterofchessstrategy.games.lanzhou_mahjong.data.LanzhouMahjongLearningAdvance
import com.masterofchessstrategy.games.lanzhou_mahjong.data.LanzhouMahjongLearningRepository
import com.masterofchessstrategy.games.lanzhou_mahjong.data.LanzhouMahjongLearningSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LanzhouMahjongLearningScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun correctAnswerUnlocksNextLessonAfterSaving() {
        val store = MemoryStore()
        val controller = showCourse(store)
        composeRule.waitUntil(5_000) { controller.state.value.loaded }
        composeRule.onNodeWithText("下一课").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag("lanzhou_mahjong_answer_0").performScrollTo().performClick()
        composeRule.waitUntil(5_000) { controller.state.value.feedback != null }
        composeRule.onNodeWithText("下一课").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag("lanzhou_mahjong_answer_1").performScrollTo().performClick()
        composeRule.waitUntil(5_000) { controller.state.value.completedCount == 1 }
        composeRule.onNodeWithText("下一课").performScrollTo().assertIsEnabled().performClick()
        composeRule.waitUntil(5_000) { controller.state.value.lessonIndex == 1 }
        composeRule.onNodeWithText("示例手牌", substring = true).assertExists()
        composeRule.runOnIdle { assertEquals(1, store.progress!!.completedCount) }
    }

    @Test
    fun narrowLargeTextLessonAndAnswersRemainScrollable() {
        val state = LanzhouMahjongLearningState(loaded = true, lessonIndex = 9, completedCount = 9)
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                MaterialTheme {
                    Box(Modifier.requiredWidth(240.dp)) {
                        LanzhouMahjongLearningScreen(state, {}, {}, {}, {}, {})
                    }
                }
            }
        }
        composeRule.onNodeWithText("已掌握 9/10 课").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("新局按项目桌规", substring = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("lanzhou_mahjong_answer_2").performScrollTo().assertIsDisplayed().assertIsEnabled()
        composeRule.onNodeWithText("下一课").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun pendingSaveFreezesAnswersAndOffersExplicitRetry() {
        var retries = 0
        val state = LanzhouMahjongLearningState(loaded = true, hasPendingSave = true,
            feedback = "教程进度保存失败，请重试。当前进度尚未确认")
        composeRule.setContent {
            MaterialTheme { LanzhouMahjongLearningScreen(state, {}, {}, {}, {}, { retries++ }) }
        }
        composeRule.onNodeWithTag("lanzhou_mahjong_answer_1").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag("lanzhou_mahjong_learning_retry").performScrollTo().assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals(1, retries) }
    }

    @Test
    fun conflictOffersReloadAndNeverAnAnswerWrite() {
        var reads = 0
        val state = LanzhouMahjongLearningState(loaded = true, hasPendingSave = true, requiresReload = true,
            feedback = "教程进度已变化，请重新读取。原记录已保留")
        composeRule.setContent {
            MaterialTheme { LanzhouMahjongLearningScreen(state, {}, {}, { reads++ }, {}, {}) }
        }
        composeRule.onNodeWithTag("lanzhou_mahjong_learning_retry").assertDoesNotExist()
        composeRule.onNodeWithTag("lanzhou_mahjong_answer_1").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag("lanzhou_mahjong_learning_reload").performScrollTo().assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals(1, reads) }
    }

    @Test
    fun readFailureKeepsAnswersAbsentAndOffersReadRetry() {
        var reads = 0
        val state = LanzhouMahjongLearningState(feedback = "教程进度读取失败，请重试。原记录已保留")
        composeRule.setContent {
            MaterialTheme { LanzhouMahjongLearningScreen(state, {}, {}, { reads++ }, {}, {}) }
        }
        composeRule.onNodeWithTag("lanzhou_mahjong_answer_1").assertDoesNotExist()
        composeRule.onNodeWithText("重新读取").performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(1, reads) }
    }

    @Test
    fun failedSaveCannotUnlockAndSameAnswerCanRetry() {
        val store = MemoryStore().apply { failSave = true }
        val controller = showCourse(store)
        composeRule.waitUntil(5_000) { controller.state.value.loaded }
        composeRule.onNodeWithTag("lanzhou_mahjong_answer_1").performScrollTo().performClick()
        composeRule.waitUntil(5_000) { controller.state.value.hasPendingSave && !controller.state.value.isBusy }
        composeRule.onNodeWithText("下一课").performScrollTo().assertIsNotEnabled()
        composeRule.runOnIdle { assertEquals(0, controller.state.value.completedCount); store.failSave = false }
        composeRule.onNodeWithTag("lanzhou_mahjong_learning_retry").performScrollTo().performClick()
        composeRule.waitUntil(5_000) { controller.state.value.completedCount == 1 }
        composeRule.onNodeWithText("下一课").performScrollTo().assertIsEnabled()
    }

    private fun showCourse(store: MemoryStore): LanzhouMahjongLearningController {
        val controller = LanzhouMahjongLearningController(store)
        composeRule.setContent {
            val state by controller.state.collectAsState()
            val scope = rememberCoroutineScope()
            LaunchedEffect(Unit) { controller.load() }
            MaterialTheme {
                LanzhouMahjongLearningScreen(state,
                    onSelectLesson = { scope.launch { controller.selectLesson(it) } },
                    onAnswer = { scope.launch { controller.submit(it) } },
                    onReload = { scope.launch { controller.load() } }, onBack = {},
                    onRetrySave = { scope.launch { controller.retrySave() } })
            }
        }
        return controller
    }

    private class MemoryStore : LanzhouMahjongLearningRepository {
        override val ownerEpoch = 0L
        override val epoch = MutableStateFlow(0L)
        @Volatile var progress: LanzhouMahjongLearningSnapshot? = null
        @Volatile var failSave = false
        override suspend fun load() = progress
        override suspend fun advance(request: LanzhouMahjongLearningAdvance): LanzhouMahjongLearningSnapshot {
            if (failSave) error("write failed")
            check(progress == request.before || progress == request.after)
            progress = request.after
            return request.after
        }
    }
}
