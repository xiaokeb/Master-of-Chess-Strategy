package com.masterofchessstrategy.ui

import android.Manifest
import android.os.Build
import android.os.Debug
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Base64
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.MainActivity
import com.masterofchessstrategy.data.AppSettings
import com.masterofchessstrategy.data.GameSessionSnapshot
import com.masterofchessstrategy.data.LoadGameSessionResult
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.data.RoomAppSettingsRepository
import com.masterofchessstrategy.data.RoomGameSessionRepository
import com.masterofchessstrategy.data.StoredGameMode
import com.masterofchessstrategy.data.TutorialProgressEntity
import com.masterofchessstrategy.data.toGameRecord
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.engine.NativeChineseChessEngine
import com.masterofchessstrategy.engine.RestoreResult
import com.masterofchessstrategy.game.ChineseChessBackgroundController
import com.masterofchessstrategy.game.ChineseChessFeedback
import com.masterofchessstrategy.game.ChineseChessGameViewModel
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain

/** Opt-in, real UI/Room/Pikafish run. No fabricated terminal position, clock expiry or resignation. */
class ChineseChessContinuousPlayTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val composeRule = createAndroidComposeRule<MainActivity>()
    private lateinit var database: MocsDatabase
    private lateinit var game: ChineseChessGameViewModel
    private lateinit var controller: ChineseChessBackgroundController
    private val samples = JSONArray()
    private val records = JSONArray()
    private val observedIds = mutableSetOf<String>()
    private var startedAt = 0L
    private var lastSample = 0L
    private var backgroundMoves = 0
    private var runId = ""
    private val seed = object : ExternalResource() {
        override fun before() = runBlocking {
            assumeTrue("Use tools/android/test-continuous-play.ps1", arguments.getString("continuousHarness") == "true")
            require(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Emulator-only fixture" }
            runId = requireNotNull(arguments.getString("continuousRunId"))
            require(runId.matches(Regex("[a-f0-9]{32}")))
            database = MocsDatabase.getInstance(context)
            check(database.activeGameDao().find(GameType.CHINESE_CHESS.code) == null)
            check(database.gameRecordDao().listAll().isEmpty())
            check(database.matchOutcomeDao().listAll().isEmpty())
            RoomAppSettingsRepository(database.appSettingsDao()).save(AppSettings.DEFAULT.copy(
                autoContinueEnabled = true, autoContinueGameLimit = GAME_LIMIT,
                gameDurationMinutes = null, soundEnabled = true,
            ))
            database.tutorialProgressDao().upsert(TutorialProgressEntity(GameType.CHINESE_CHESS.code, 1, 4, true, 1L))
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
            startedAt = SystemClock.elapsedRealtime()
        }

        override fun after() {
            // A failed test must not leave a dark screen or keep playing indefinitely.
            if (runId.isNotEmpty()) {
                shell("input keyevent 224")
                shell("wm dismiss-keyguard")
            }
        }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(seed).around(composeRule)

    @Test fun threeNaturalGamesPreserveHistoryAndStopAtTheConfiguredLimit() = runBlocking {
        try {
            composeRule.onNodeWithTag(HOME_CHINESE_CHESS_TAG).performClick()
            composeRule.onNodeWithTag(MODE_AUTO_PLAY_TAG).performScrollTo().performClick()
            composeRule.onNodeWithTag(DIFFICULTY_EASY_TAG).assertIsEnabled().performClick()
            controller = ChineseChessBackgroundController.get(context)
            composeRule.waitUntil(30_000L) { controller.game?.uiState?.isRestoring == false }
            game = requireNotNull(controller.game)
            composeRule.runOnIdle { game.setAutoPlaySpeed(4f); game.persistAutoPlaySpeed() }
            await { saved().acceptedMoveCount >= 8 && controller.isServiceRunning }
            sample("foreground")

            // Exercise the actual control, then wait for the in-flight search/checkpoint safe point.
            composeRule.onNodeWithTag(AUTO_PLAY_TOGGLE_TAG).performScrollTo().performClick()
            await { game.uiState.isAutoPlayPaused && !game.uiState.isAiThinking && !game.uiState.isPersisting }
            val paused = saved()
            assertEquals(GameResult.ONGOING, game.uiState.result)
            validateHistory(paused.engineState, paused.acceptedMoveCount)
            delay(3_000L)
            assertSnapshotEquals(paused, saved())
            assertFalse(controller.isServiceRunning)
            assertFalse(controller.isWakeLockHeld)
            composeRule.onNodeWithTag(AUTO_PLAY_TOGGLE_TAG).performScrollTo().performClick()
            await { controller.isServiceRunning && saved().acceptedMoveCount > paused.acceptedMoveCount }

            val beforeDark = saved()
            val priorTotal = beforeDark.acceptedMoveCount + recordsMovesBefore(beforeDark.sessionId)
            shell("input keyevent 223")
            assertFalse(context.getSystemService(PowerManager::class.java).isInteractive)
            await { !game.isRuntimeForeground && controller.isWakeLockHeld }
            val darkStart = SystemClock.elapsedRealtime()
            while (SystemClock.elapsedRealtime() - darkStart < 90_000L) {
                checkHealth()
                delay(1_000L)
            }
            val afterDark = saved()
            // The session may naturally end during this interval; count all committed games too.
            val afterTotal = afterDark.acceptedMoveCount + recordsMovesBefore(afterDark.sessionId)
            backgroundMoves = afterTotal - priorTotal
            assertTrue("No screen-off progress", backgroundMoves > 0)
            validateHistory(afterDark.engineState, afterDark.acceptedMoveCount)
            shell("input keyevent 224")
            shell("wm dismiss-keyguard")
            await { game.isRuntimeForeground && !controller.isWakeLockHeld }
            assertSame(game, controller.game)
            sample("returned")

            // Bounded safety timeout is a failure, never a shortcut that declares a drawn game.
            withTimeout(20 * 60_000L) {
                while (database.gameRecordDao().listAll().size < GAME_LIMIT || game.uiState.isPersisting) {
                    checkHealth()
                    delay(1_000L)
                }
            }
            checkHealth()
            await { !controller.isServiceRunning && !controller.isWakeLockHeld && !game.uiState.isAiThinking }
            assertEquals(GAME_LIMIT, game.uiState.completedAutoGames)
            assertTrue(game.uiState.isAutoPlayPaused)
            assertFalse(game.uiState.hasUncommittedResult)
            assertNotEquals(GameResult.ONGOING, game.uiState.result)
            val finished = saved()
            assertEquals(GAME_LIMIT, finished.completedAutoGames)
            val committed = database.gameRecordDao().listAll()
            assertEquals(GAME_LIMIT, committed.size)
            assertEquals(GAME_LIMIT, committed.map { it.recordId }.toSet().size)
            val last = committed.single { it.recordId == finished.sessionId }
            assertArrayEquals(last.engineState, finished.engineState)
            assertEquals(requireNotNull(last.toGameRecord()).result, finished.resultOverride)
            assertTrue(database.matchOutcomeDao().listAll().isEmpty())
            delay(5_000L)
            assertSnapshotEquals(finished, saved())
            assertEquals(committed, database.gameRecordDao().listAll())
            sample("limit_reached")

            composeRule.onNodeWithTag(GAME_BACK_BUTTON_TAG).performClick()
            composeRule.waitUntil(15_000L) { controller.game == null && !controller.isServiceRunning && !controller.isWakeLockHeld }
            instrumentation.runOnMainSync {
                // onCleared closes the engine without rewriting the detached UI snapshot.
                // Registration is gated on the actual native owner, not that stale display flag.
                var registeredAfterExit = false
                game.registerBackgroundRuntime { registeredAfterExit = true; AutoCloseable {} }
                assertFalse("Exited game still owns an engine", registeredAfterExit)
                game.restart()
            }
            delay(1_000L)
            assertSnapshotEquals(finished, saved())
            sample("exited")
            writeReport("passed")
        } catch (failure: Throwable) {
            writeReport("failed", failure.toString())
            throw failure
        }
    }

    private suspend fun recordsMovesBefore(activeId: String): Int =
        database.gameRecordDao().listAll().filter { it.recordId != activeId }.sumOf { it.moveCount }

    private suspend fun checkHealth() {
        instrumentation.runOnMainSync {
            assertTrue(game.uiState.isEngineAvailable)
            assertFalse("Terminal save failed", game.uiState.hasUncommittedResult && !game.uiState.isPersisting)
            assertTrue("Runtime failure: ${game.uiState.feedback}", game.uiState.feedback !in setOf(
                ChineseChessFeedback.AI_MOVE_FAILED, ChineseChessFeedback.SAVE_FAILED, ChineseChessFeedback.ENGINE_UNAVAILABLE,
            ))
        }
        for (record in database.gameRecordDao().listAll().sortedBy { it.completedAtEpochMillis }) {
            if (!observedIds.add(record.recordId)) continue
            assertEquals(StoredGameMode.AI_AUTO_PLAY.code, record.modeCode)
            val result = validateHistory(record.engineState, record.moveCount)
            assertNotEquals("No artificially terminated games", GameResult.ONGOING, result)
            assertEquals(result, requireNotNull(record.toGameRecord()).result)
            records.put(JSONObject().put("id", record.recordId).put("moves", record.moveCount)
                .put("result", result.name).put("engineState", Base64.encodeToString(record.engineState, Base64.NO_WRAP)))
        }
        assertTrue("Auto-continue exceeded limit", observedIds.size <= GAME_LIMIT)
        if (SystemClock.elapsedRealtime() - lastSample >= 15_000L) sample("playing")
        writeReport("running")
    }

    /** restore replays all moves legally; undo to the canonical start also checks exact history length. */
    private fun validateHistory(bytes: ByteArray, moves: Int): GameResult = NativeChineseChessEngine().use { verifier ->
        val initial = verifier.serialize()
        assertEquals(RestoreResult.Restored, verifier.restore(bytes))
        val result = verifier.gameResult()
        repeat(moves) { assertTrue(verifier.undo()) }
        assertFalse(verifier.undo())
        assertArrayEquals(initial, verifier.serialize())
        result
    }

    private suspend fun saved(): GameSessionSnapshot =
        (RoomGameSessionRepository(database.activeGameDao()).load(GameType.CHINESE_CHESS) as LoadGameSessionResult.Loaded).snapshot

    private fun assertSnapshotEquals(expected: GameSessionSnapshot, actual: GameSessionSnapshot) {
        assertArrayEquals(expected.engineState, actual.engineState)
        assertEquals(expected.copy(engineState = actual.engineState), actual)
    }

    private suspend fun await(predicate: suspend () -> Boolean) = withTimeout(30_000L) {
        while (!predicate()) delay(100L)
    }

    private suspend fun sample(label: String) {
        val memory = Debug.MemoryInfo().also(Debug::getMemoryInfo)
        val snapshot = saved()
        lastSample = SystemClock.elapsedRealtime()
        samples.put(JSONObject().put("label", label).put("elapsedMillis", lastSample - startedAt)
            .put("sessionId", snapshot.sessionId).put("moves", snapshot.acceptedMoveCount)
            .put("completedGames", snapshot.completedAutoGames).put("pssKiB", memory.totalPss)
            .put("nativeAllocatedBytes", Debug.getNativeHeapAllocatedSize())
            .put("threads", File("/proc/self/task").list()?.size ?: -1)
            .put("fileDescriptors", File("/proc/self/fd").list()?.size ?: -1)
            .put("foreground", game.isRuntimeForeground).put("service", controller.isServiceRunning)
            .put("wakeLock", controller.isWakeLockHeld))
    }

    private fun writeReport(status: String, error: String? = null) {
        val report = JSONObject().put("runId", runId).put("status", status).put("pid", Process.myPid())
            .put("elapsedMillis", SystemClock.elapsedRealtime() - startedAt).put("gameLimit", GAME_LIMIT)
            .put("backgroundMoves", backgroundMoves).put("records", records).put("samples", samples)
            .put("error", error ?: JSONObject.NULL)
        File(context.filesDir, "continuous-play.json").writeText(report.toString(2))
    }

    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    }

    companion object { private const val GAME_LIMIT = 3 }
}
