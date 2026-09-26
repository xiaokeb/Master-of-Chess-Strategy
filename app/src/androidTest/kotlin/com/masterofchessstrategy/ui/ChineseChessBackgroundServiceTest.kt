package com.masterofchessstrategy.ui

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.pm.ActivityInfo
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.material3.Text
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.data.RoomGameSessionRepository
import com.masterofchessstrategy.data.StoredGameMode
import com.masterofchessstrategy.data.AppSettings
import com.masterofchessstrategy.data.RoomAppSettingsRepository
import com.masterofchessstrategy.data.RoomLocalDataBackupRepository
import com.masterofchessstrategy.endgame.ChineseChessEndgamePackParser
import com.masterofchessstrategy.settings.AppSettingsViewModel
import com.masterofchessstrategy.settings.LocalDataBackupViewModel
import com.masterofchessstrategy.settings.BackupFeedback
import com.masterofchessstrategy.settings.awaitLocalDataWriters
import java.io.ByteArrayInputStream
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.engine.NativeChineseChessEngine
import com.masterofchessstrategy.engine.RestoreResult
import com.masterofchessstrategy.game.BackgroundRunStatus
import com.masterofchessstrategy.game.ChineseChessBackgroundController
import com.masterofchessstrategy.game.ChineseChessBackgroundService
import com.masterofchessstrategy.game.ChineseChessGameViewModel
import com.masterofchessstrategy.game.PikafishNetworkProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual service/notification/wake-lock tests. Screen-off is not a claim of forced Doze immunity. */
@RunWith(AndroidJUnit4::class)
class ChineseChessBackgroundServiceTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var database: MocsDatabase
    private lateinit var controller: ChineseChessBackgroundController
    private lateinit var game: ChineseChessGameViewModel

    @Before fun prepare() {
        shell("input keyevent 224")
        shell("wm dismiss-keyguard")
        shell("appops set ${context.packageName} POST_NOTIFICATION ignore")
        instrumentation.runOnMainSync {
            composeRule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            controller = ChineseChessBackgroundController.get(context)
        }
        composeRule.waitForIdle()
        database = Room.inMemoryDatabaseBuilder(context, MocsDatabase::class.java).build()
        instrumentation.runOnMainSync { game = createGame("first") }
        composeRule.setContent { GameContent() }
        composeRule.waitUntil(30_000L) { saved()?.acceptedMoveCount?.let { it >= 1 } == true }
    }

    @After fun cleanup() {
        shell("input keyevent 224")
        shell("wm dismiss-keyguard")
        instrumentation.runOnMainSync { composeRule.activity.viewModelStore.clear() }
        runBlocking { await { !controller.isServiceRunning && !controller.isWakeLockHeld } }
        database.close()
        shell("appops set ${context.packageName} POST_NOTIFICATION allow")
    }

    @Test fun notificationGateScreenOffProgressAndStopUseTheSameSession() = runBlocking {
        instrumentation.runOnMainSync {
            assertFalse(controller.notificationsAllowed())
            controller.enable()
            assertEquals(BackgroundRunStatus.NOTIFICATIONS_REQUIRED, controller.status)
            assertFalse(controller.isServiceRunning)
        }
        allowNotifications()
        instrumentation.runOnMainSync { controller.enable() }
        await { controller.isServiceRunning }
        val before = requireNotNull(saved())
        val notification = notification()
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(ChineseChessBackgroundController.CHANNEL_ID, notification.channelId)
        assertEquals(1, notification.actions.size)

        shell("input keyevent 223")
        assertFalse(context.getSystemService(PowerManager::class.java).isInteractive)
        await { !game.isRuntimeForeground && controller.isWakeLockHeld }
        withTimeout(30_000L) {
            while ((saved()?.acceptedMoveCount ?: 0) < before.acceptedMoveCount + 3) delay(100L)
        }
        val background = requireNotNull(saved())
        assertEquals(before.sessionId, background.sessionId)
        NativeChineseChessEngine().use { assertTrue(it.restore(background.engineState) is RestoreResult.Restored) }

        notification.actions.single().actionIntent.send()
        await { !controller.isServiceRunning && !controller.isWakeLockHeld && !game.uiState.isAiThinking && !game.uiState.isPersisting }
        val stopped = requireNotNull(saved())
        assertTrue(stopped.autoPlayPaused)
        delay(2_500L)
        assertEquals(stopped, saved())
        shell("input keyevent 224")
        shell("wm dismiss-keyguard")
        // Unlike MainActivity.onCreate, bare ComponentActivity cannot reinstall a test lambda
        // after the lock screen rotates/recreates it. Reattach only the UI, never a new game.
        instrumentation.runOnMainSync {
            assertSame(game, ViewModelProvider(composeRule.activity)["first", ChineseChessGameViewModel::class.java])
            composeRule.activity.setContent { GameContent() }
        }
        await { game.isRuntimeForeground }
        assertFalse(controller.enabled)
        assertFalse(controller.isServiceRunning)
        assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications.none {
            it.id == ChineseChessBackgroundService.NOTIFICATION_ID
        })
    }

    @Test fun replacementRetiresOldRuntimeAndOldStopTokenCannotStopNewSession() = runBlocking {
        allowNotifications()
        instrumentation.runOnMainSync { controller.enable() }
        await { controller.isServiceRunning }
        val first = game
        val oldToken = requireNotNull(controller.token)
        val oldStop = notification().actions.single().actionIntent
        instrumentation.runOnMainSync {
            game = createGame("replacement")
            game.registerBackgroundRuntime(controller::attach)
            assertFalse(first.uiState.isEngineAvailable)
            assertNotEquals(oldToken, controller.token)
            assertSame(game, controller.game)
        }
        await { controller.isServiceRunning && !game.uiState.isRestoring }
        oldStop.send()
        delay(300L)
        instrumentation.runOnMainSync {
            assertTrue(controller.enabled)
            assertTrue(controller.isServiceRunning)
            assertFalse(game.uiState.isAutoPlayPaused)
            first.retireBackgroundRuntime()
            assertSame(game, controller.game)
            composeRule.activity.viewModelStore.clear()
        }
        await { controller.game == null && !controller.isServiceRunning && !controller.isWakeLockHeld }
    }

    @Test fun cancellingLeaseBeforeServiceStartPublishesThenRemovesNotificationWithoutCrash() = runBlocking {
        allowNotifications()
        await { !game.uiState.isPersisting && !game.uiState.isRestoring }
        instrumentation.runOnMainSync {
            controller.enable()
            // onStartCommand cannot run until this main-thread block returns.
            game.retireBackgroundRuntime()
        }
        delay(6_000L)
        await { controller.game == null && !controller.isServiceRunning && !controller.isWakeLockHeld }
        assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications.none {
            it.id == ChineseChessBackgroundService.NOTIFICATION_ID
        })
    }

    @Test fun restoringBackupStopsOldRuntimeAndReloadsRetainedSettings() = runBlocking {
        allowNotifications()
        instrumentation.runOnMainSync { controller.enable(); game.stopBackgroundAutomation() }
        await { game.uiState.isAutoPlayPaused && !game.uiState.isPersisting && !game.uiState.isAiThinking }
        val checkpoint = requireNotNull(saved())
        val settingsRepository = RoomAppSettingsRepository(database.appSettingsDao())
        settingsRepository.save(AppSettings.DEFAULT.copy(soundEnabled = false))
        val repository = RoomLocalDataBackupRepository(database,
            ChineseChessEndgamePackParser.loadBundled(context.assets),
            validateEngineState = { bytes -> NativeChineseChessEngine().use { it.restore(bytes) == RestoreResult.Restored } })
        val backup = repository.export(System.currentTimeMillis())
        settingsRepository.save(AppSettings.DEFAULT.copy(soundEnabled = true))
        lateinit var settings: AppSettingsViewModel
        lateinit var backupModel: LocalDataBackupViewModel
        instrumentation.runOnMainSync {
            settings = ViewModelProvider(composeRule.activity, AppSettingsViewModel.factory(settingsRepository))[AppSettingsViewModel::class.java]
            backupModel = ViewModelProvider(composeRule.activity, LocalDataBackupViewModel.factory(repository))[LocalDataBackupViewModel::class.java]
            game.toggleAutoPlayPaused()
        }
        await { controller.isServiceRunning && (saved()?.acceptedMoveCount ?: 0) > checkpoint.acceptedMoveCount }
        val oldGame = game
        val oldStop = notification().actions.single().actionIntent
        instrumentation.runOnMainSync {
            composeRule.activity.setContent {
                Text("sound=${settings.uiState.settings.soundEnabled}, feedback=${backupModel.uiState.feedback}")
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("sound=true, feedback=null").assertIsDisplayed()
        instrumentation.runOnMainSync {
            backupModel.restore(
                openInputStream = { ByteArrayInputStream(backup) }, onRestored = {},
                beforeRestore = {
                    awaitLocalDataWriters(listOf(oldGame, settings)) { oldGame.retireBackgroundRuntime() }
                },
                onFinished = settings::loadSettings,
            )
        }
        composeRule.waitUntil(30_000L) { backupModel.uiState.feedback == BackupFeedback.RESTORED && !settings.uiState.isLoading }
        composeRule.onNodeWithText("sound=false, feedback=RESTORED").assertIsDisplayed()
        await { !controller.isServiceRunning && !controller.isWakeLockHeld }
        assertFalse(oldGame.uiState.isEngineAvailable)
        assertEquals(checkpoint, saved())
        oldStop.send()
        delay(2_500L)
        assertEquals(checkpoint, saved())
        instrumentation.runOnMainSync { game = createGame("restored"); game.registerBackgroundRuntime(controller::attach) }
        await { !game.uiState.isRestoring && !game.uiState.isPersisting }
        assertTrue(game.uiState.isEngineAvailable)
        assertTrue(game.uiState.isAutoPlayPaused)
        assertEquals(checkpoint.sessionId, requireNotNull(saved()).sessionId)
        assertEquals(checkpoint.acceptedMoveCount, requireNotNull(saved()).acceptedMoveCount)
    }

    @Composable private fun GameContent() {
        ChineseChessRuntimeEffect(game)
        ChineseChessGameScreen(
            state = game.uiState, onSquareTap = game::onSquareTap, onUndo = game::undo,
            onHint = game::requestHint, onResign = game::resign, onRestart = game::restart,
            onBack = {}, onToggleAutoPlay = game::toggleAutoPlayPaused,
        )
    }

    private fun createGame(key: String): ChineseChessGameViewModel {
        val network = PikafishNetworkProvider(context)
        return ViewModelProvider(composeRule.activity, ChineseChessGameViewModel.factory(
            repository = RoomGameSessionRepository(database.activeGameDao()),
            mode = StoredGameMode.AI_AUTO_PLAY, difficulty = Difficulty.EASY, timeControlMinutes = 5,
            engineFactory = { NativeChineseChessEngine(network::requireNetworkPath) },
        ))[key, ChineseChessGameViewModel::class.java]
    }

    private fun allowNotifications() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        shell("appops set ${context.packageName} POST_NOTIFICATION allow")
    }
    private fun notification() = context.getSystemService(NotificationManager::class.java).activeNotifications
        .single { it.id == ChineseChessBackgroundService.NOTIFICATION_ID }.notification
    private fun saved() = runBlocking { database.activeGameDao().find(GameType.CHINESE_CHESS.code) }
    private suspend fun await(predicate: () -> Boolean) = withTimeout(30_000L) {
        while (true) {
            var done = false
            instrumentation.runOnMainSync { done = predicate() }
            if (done) break
            delay(50L)
        }
    }
    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    }
}
