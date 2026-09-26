package com.masterofchessstrategy.ui

import android.Manifest
import android.app.NotificationManager
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.MainActivity
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.data.TutorialProgressEntity
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.game.ChineseChessBackgroundController
import com.masterofchessstrategy.game.ChineseChessBackgroundService
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** Uses the production Activity/onCreate/navigation, including real lock-screen reconstruction. */
@RunWith(AndroidJUnit4::class)
class ChineseChessBackgroundNavigationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val composeRule = createAndroidComposeRule<MainActivity>()
    private lateinit var database: MocsDatabase
    private val seed = object : ExternalResource() {
        override fun before() {
            shell("input keyevent 224")
            shell("wm dismiss-keyguard")
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
            shell("appops set ${context.packageName} POST_NOTIFICATION allow")
            assertTrue("Notification permission must be effective before launching MainActivity",
                context.getSystemService(NotificationManager::class.java).areNotificationsEnabled())
            database = MocsDatabase.getInstance(context)
            runBlocking {
                database.tutorialProgressDao().upsert(TutorialProgressEntity(
                    gameTypeCode = GameType.CHINESE_CHESS.code, contentVersion = 1,
                    completedStepCount = 4, isCompleted = true, updatedAtEpochMillis = 1L,
                ))
            }
        }
        override fun after() {
            shell("input keyevent 224")
            shell("wm dismiss-keyguard")
        }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(seed).around(composeRule)

    @Test fun mainActivityRecreatesAndNotificationReturnsToTheSameGame() = runBlocking {
        composeRule.onNodeWithTag(HOME_CHINESE_CHESS_TAG).performClick()
        composeRule.onNodeWithTag(MODE_AUTO_PLAY_TAG).performScrollTo().performClick()
        composeRule.onNodeWithTag(DIFFICULTY_EASY_TAG).assertIsEnabled().performClick()
        composeRule.waitForIdle()
        val controller = ChineseChessBackgroundController.get(context)
        composeRule.waitUntil(30_000L) { controller.isServiceRunning && (saved()?.acceptedMoveCount ?: 0) >= 1 }
        val originalGame = controller.game
        val before = requireNotNull(saved())
        val notification = context.getSystemService(NotificationManager::class.java).activeNotifications
            .single { it.id == ChineseChessBackgroundService.NOTIFICATION_ID }.notification
        shell("input keyevent 223")
        await { controller.isWakeLockHeld && (saved()?.acceptedMoveCount ?: 0) >= before.acceptedMoveCount + 2 }
        shell("input keyevent 224")
        shell("wm dismiss-keyguard")
        notification.contentIntent.send()
        composeRule.waitUntil(30_000L) { controller.game?.isRuntimeForeground == true }
        composeRule.waitForIdle()
        assertSame(originalGame, controller.game)
        assertEquals(before.sessionId, requireNotNull(saved()).sessionId)
        composeRule.onNodeWithTag(AUTO_PLAY_TOGGLE_TAG).performScrollTo().performClick()
        await { controller.game?.uiState?.isAiThinking == false && controller.game?.uiState?.isPersisting == false }
        composeRule.onNodeWithTag("background_toggle").performScrollTo().performClick()
        await { !controller.isServiceRunning && !controller.isWakeLockHeld }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("background_status").assertTextContains("后台挂机已停止", substring = true)
        shell("screencap -p /data/local/tmp/mocs-background-controls.png")
        assertTrue(requireNotNull(saved()).autoPlayPaused)
        composeRule.onNodeWithTag(GAME_BACK_BUTTON_TAG).performClick()
        composeRule.waitUntil(10_000L) { controller.game == null }
    }

    private fun saved() = runBlocking { database.activeGameDao().find(GameType.CHINESE_CHESS.code) }
    private suspend fun await(predicate: () -> Boolean) {
        try {
            withTimeout(30_000L) { while (!predicate()) delay(100L) }
        } catch (failure: TimeoutCancellationException) {
            var details = ""
            instrumentation.runOnMainSync {
                val runtime = ChineseChessBackgroundController.get(context)
                details = "status=${runtime.status}, enabled=${runtime.enabled}, running=${runtime.isServiceRunning}, " +
                    "held=${runtime.isWakeLockHeld}, allowed=${runtime.notificationsAllowed()}, demand=${runtime.game?.backgroundDemand}"
            }
            throw AssertionError("Background navigation wait failed: $details, savedMoves=${saved()?.acceptedMoveCount}", failure)
        }
    }
    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    }
}
