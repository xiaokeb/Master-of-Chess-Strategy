package com.masterofchessstrategy.games.go

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import java.io.File

class GoLearningFlowInstrumentedTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferences = context.getSharedPreferences("go-learning-flow-test-${UUID.randomUUID()}", Context.MODE_PRIVATE)
    private val repository = GoLearningPreferencesRepository(preferences)
    private val runId = UUID.randomUUID().toString()

    @After fun clearOnlyTestProgress() { assertTrue(preferences.edit().clear().commit()) }

    @Test fun completeEveryTopicThroughRealRulesAndPersistTheTutorialCondition() {
        composeRule.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        composeRule.waitUntil(10_000) {
            composeRule.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        }
        composeRule.setContent { MaterialTheme { GoLearningRoute(onBack = {}, repository = repository) } }
        for ((index, lesson) in GoLearningLibrary.lessons.withIndex()) {
            if (index > 0) {
                composeRule.onNodeWithText("知识点目录").performScrollTo().performClick()
                composeRule.onNodeWithText(lesson.title).performScrollTo().performClick()
            }
            composeRule.waitUntil(10_000) {
                composeRule.onAllNodesWithText("${index + 1}. ${lesson.title}").fetchSemanticsNodes().isNotEmpty() &&
                    composeRule.onAllNodesWithText("正在保存教程进度…").fetchSemanticsNodes().isEmpty()
            }
            when (val goal = lesson.goal) {
                is GoLessonGoal.FindLiberties -> goal.points.forEach { point ->
                    composeRule.onNodeWithTag("go_intersection_${point.x}_${point.y}").performClick()
                }
                is GoLessonGoal.Place -> composeRule.onNodeWithTag("go_intersection_${goal.point.x}_${goal.point.y}").performClick()
                is GoLessonGoal.ForbiddenPoint -> composeRule.onNodeWithTag("go_intersection_${goal.point.x}_${goal.point.y}").performClick()
                GoLessonGoal.SecondPass -> composeRule.onNodeWithText("虚着").performScrollTo().performClick()
                is GoLessonGoal.Confirm -> composeRule.onNodeWithText("白方确认").performScrollTo().performClick()
                is GoLessonGoal.Resume -> composeRule.onNodeWithText("由黑方先行续弈").performScrollTo().performClick()
                is GoLessonGoal.PredictResult -> {
                    composeRule.onNodeWithText("黑方领先").performScrollTo().performClick()
                    composeRule.onNodeWithText("两方评估相同，继续把黑贴3¾子计入比较。").assertExists()
                    assertFalse(runBlocking { repository.load() }.completedLessonIds.contains(lesson.id))
                    composeRule.onNodeWithText("白方领先").performScrollTo().performClick()
                }
            }
            composeRule.waitUntil(10_000) {
                composeRule.onAllNodesWithText("本项完成").fetchSemanticsNodes().isNotEmpty() &&
                    composeRule.onAllNodesWithText("正在保存教程进度…").fetchSemanticsNodes().isEmpty()
            }
            assertTrue(runBlocking { repository.load() }.completedLessonIds.contains(lesson.id))
            if (index in setOf(0, 6, 7, 9)) saveVisual(lesson.id)
        }
        composeRule.onNodeWithText("入门教程完成，简单难度的教程条件已满足。").performScrollTo().assertExists()
        assertTrue(runBlocking { repository.load() }.isCompleted)
        saveVisual("complete", scrollToTitle = false)
    }

    private fun saveVisual(name: String, scrollToTitle: Boolean = true) {
        if (scrollToTitle) composeRule.onNodeWithTag("go_lesson_title").performScrollTo()
        val directory = File(checkNotNull(context.getExternalFilesDir("go-learning-qa")), runId)
        assertTrue(directory.mkdirs() || directory.isDirectory)
        val output = File(directory, "$name.png")
        output.outputStream().use {
            assertTrue(composeRule.onRoot().captureToImage().asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        println("GO_LEARNING_VISUAL=${output.absolutePath}")
    }
}
