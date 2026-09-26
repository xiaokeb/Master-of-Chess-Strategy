package com.masterofchessstrategy.ui

import android.Manifest
import android.os.Build
import android.os.Process
import android.util.Base64
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.MainActivity
import com.masterofchessstrategy.data.AppSettings
import com.masterofchessstrategy.data.GameSessionSnapshot
import com.masterofchessstrategy.data.LoadGameSessionResult
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.data.RoomAppSettingsRepository
import com.masterofchessstrategy.data.RoomGameSessionRepository
import com.masterofchessstrategy.data.TutorialProgressEntity
import com.masterofchessstrategy.data.CompletedGameSession
import com.masterofchessstrategy.data.RoomCompletedGameSessionRepository
import com.masterofchessstrategy.data.GameRecord
import com.masterofchessstrategy.data.MatchOutcome
import com.masterofchessstrategy.data.StoredGameMode
import com.masterofchessstrategy.endgame.ChineseChessEndgamePackParser
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.BoardPosition
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.engine.NativeChineseChessEngine
import com.masterofchessstrategy.engine.RestoreResult
import com.masterofchessstrategy.game.ChineseChessBackgroundController
import com.masterofchessstrategy.game.ChineseChessGameViewModel
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** Opt-in host-coordinated test: prepare/resume are deliberately killed, not successful JUnit runs. */
@RunWith(AndroidJUnit4::class)
class ChineseChessProcessRestartTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val phase get() = requireNotNull(arguments.getString("restartPhase"))
    private val scenario get() = requireNotNull(arguments.getString("restartScenario"))
    private val runId get() = requireNotNull(arguments.getString("restartRunId"))
    private val composeRule = createAndroidComposeRule<MainActivity>()
    private lateinit var database: MocsDatabase
    private var preceding: JSONObject? = null
    private var beforeLaunch: GameSessionSnapshot? = null
    private val seed = object : ExternalResource() {
        override fun before() = runBlocking {
            assumeTrue("Run with tools/android/test-process-restart.ps1", arguments.getString("restartHarness") == "true")
            require(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Emulator-only fixture" }
            require(phase in setOf("prepare", "resume", "verify"))
            require(scenario in setOf("auto", "human", "timed", "transaction"))
            require(runId.matches(Regex("[a-f0-9]{32}")))
            database = MocsDatabase.getInstance(context)
            if (phase == "prepare") {
                // The host must explicitly reset the dedicated emulator's app data first.
                check(database.activeGameDao().find(GameType.CHINESE_CHESS.code) == null)
                check(database.gameRecordDao().listAll().isEmpty())
                check(database.matchOutcomeDao().listAll().isEmpty())
                RoomAppSettingsRepository(database.appSettingsDao()).save(
                    AppSettings.DEFAULT.copy(gameDurationMinutes = 5, soundEnabled = false),
                )
                database.tutorialProgressDao().upsert(TutorialProgressEntity(
                    GameType.CHINESE_CHESS.code, 1, 4, true, 1L,
                ))
            } else {
                preceding = JSONObject(receiptFile(if (phase == "resume") "prepare" else "resume").readText())
                assertEquals(runId, preceding!!.getString("runId"))
                assertEquals(scenario, preceding!!.getString("scenario"))
                assertNotEquals("A different Linux process is required", preceding!!.getInt("pid"), Process.myPid())
                beforeLaunch = saved()
                assertSameHistoryPrefix(requireNotNull(beforeLaunch), preceding!!)
                if (phase == "resume" && scenario == "timed") {
                    assertNull("Must have died before the live ticker expired", beforeLaunch!!.resultOverride)
                    assertTrue(System.currentTimeMillis() - requireNotNull(beforeLaunch!!.turnStartedAtEpochMillis) >= 10_000L)
                }
            }
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(seed).around(composeRule)

    @Test fun processRestartScenario() = runBlocking {
        if (scenario == "transaction") {
            atomicTransactionScenario()
            return@runBlocking
        }
        openGame()
        val controller = ChineseChessBackgroundController.get(context)
        composeRule.waitUntil(30_000L) { controller.game?.uiState?.let { !it.isRestoring && !it.isPersisting } == true }
        val game = requireNotNull(controller.game)
        when (phase) {
            "prepare" -> {
                when (scenario) {
                    "auto" -> composeRule.waitUntil(30_000L) { saved().acceptedMoveCount >= 2 && controller.isServiceRunning }
                    "human" -> {
                        composeRule.runOnIdle {
                            game.onSquareTap(BoardPosition(0, 6))
                            game.onSquareTap(BoardPosition(0, 5))
                        }
                        composeRule.waitUntil(30_000L) { saved().acceptedMoveCount == 2 && !game.uiState.isAiThinking && !game.uiState.isPersisting }
                    }
                    "timed" -> assertEquals(10, game.uiState.perMoveTimeLimitSeconds)
                }
                assertEquals(GameResult.ONGOING, game.uiState.result)
                writeReceipt()
                holdForHostKill()
            }
            "resume" -> {
                assertEquals(preceding!!.getString("sessionId"), saved().sessionId)
                when (scenario) {
                    "auto" -> {
                        composeRule.waitUntil(30_000L) { saved().acceptedMoveCount > beforeLaunch!!.acceptedMoveCount }
                        composeRule.runOnIdle { game.setAutoPlaySpeed(1.75f); game.stopBackgroundAutomation() }
                        composeRule.waitUntil(30_000L) { game.uiState.isAutoPlayPaused && !game.uiState.isPersisting && !game.uiState.isAiThinking }
                    }
                    "human" -> {
                        assertClockIncludesProcessAbsence(game, requireNotNull(beforeLaunch))
                        composeRule.runOnIdle { game.resign() }
                        awaitTerminal(game, expectOutcome = true)
                    }
                    "timed" -> awaitTerminal(game, expectOutcome = false)
                }
                writeReceipt()
                holdForHostKill()
            }
            "verify" -> {
                if (scenario == "auto") {
                    assertTrue(game.uiState.isAutoPlayPaused)
                    assertEquals(1.75f, game.uiState.autoPlaySpeed)
                    assertEquals(beforeLaunch!!.redRemainingMillis, game.uiState.redRemainingMillis)
                    assertEquals(beforeLaunch!!.blackRemainingMillis, game.uiState.blackRemainingMillis)
                    delay(2_500L)
                    assertEquals(beforeLaunch!!.acceptedMoveCount, saved().acceptedMoveCount)
                    assertFalse(controller.isServiceRunning)
                } else {
                    awaitTerminal(game, expectOutcome = scenario == "human")
                    val old = requireNotNull(preceding)
                    val record = database.gameRecordDao().listAll().single()
                    assertEquals(old.getLong("recordTime"), record.completedAtEpochMillis)
                    assertEquals(old.getString("sessionId"), record.recordId)
                    assertEquals(old.getString("engineState"), encode(record.engineState))
                    if (scenario == "human") {
                        assertEquals(old.getLong("outcomeTime"), database.matchOutcomeDao().listAll().single().settledAtEpochMillis)
                    }
                }
                assertSameHistoryPrefix(saved(), requireNotNull(preceding))
                writeReceipt()
                composeRule.onNodeWithTag(GAME_BACK_BUTTON_TAG).performClick()
                composeRule.waitUntil(10_000L) { controller.game == null && !controller.isServiceRunning && !controller.isWakeLockHeld }
            }
        }
    }

    private fun openGame() {
        composeRule.onNodeWithTag(HOME_CHINESE_CHESS_TAG).performClick()
        when (scenario) {
            "auto", "human" -> {
                composeRule.onNodeWithTag(if (scenario == "auto") MODE_AUTO_PLAY_TAG else MODE_AI_GAME_TAG).performScrollTo().performClick()
                composeRule.onNodeWithTag(DIFFICULTY_EASY_TAG).assertIsEnabled().performClick()
            }
            "timed" -> {
                composeRule.onNodeWithTag(MODE_EXTENSIONS_TAG).performScrollTo().performClick()
                composeRule.onNodeWithTag(TIMED_CHALLENGE_ENTRY_TAG).performClick()
                composeRule.onNodeWithText("10 秒").performScrollTo().performClick()
                composeRule.onNodeWithTag(TIMED_CHALLENGE_START_TAG).performScrollTo().assertIsEnabled().performClick()
            }
        }
        composeRule.waitForIdle()
    }

    private fun awaitTerminal(game: ChineseChessGameViewModel, expectOutcome: Boolean) {
        composeRule.waitUntil(15_000L) {
            !game.uiState.isPersisting && game.uiState.result == GameResult.SECOND_PLAYER_WIN && runBlocking {
                database.gameRecordDao().listAll().size == 1 && database.matchOutcomeDao().listAll().size == if (expectOutcome) 1 else 0
            }
        }
        assertEquals(GameResult.SECOND_PLAYER_WIN, saved().resultOverride)
        if (scenario == "timed") assertEquals(0L, game.uiState.redRemainingMillis)
    }

    private fun assertClockIncludesProcessAbsence(game: ChineseChessGameViewModel, snapshot: GameSessionSnapshot) {
        composeRule.runOnIdle {
            game.synchronizeClock()
            val expected = requireNotNull(snapshot.redRemainingMillis) -
                (System.currentTimeMillis() - requireNotNull(snapshot.turnStartedAtEpochMillis))
            val actual = requireNotNull(game.uiState.redRemainingMillis)
            assertTrue("Clock reset or drift: expected=$expected actual=$actual", kotlin.math.abs(actual - expected) < 1_000L)
            assertTrue(actual < requireNotNull(snapshot.redRemainingMillis) - 2_000L)
        }
    }

    private fun assertSameHistoryPrefix(snapshot: GameSessionSnapshot, receipt: JSONObject) {
        assertEquals(receipt.getString("sessionId"), snapshot.sessionId)
        val priorMoves = receipt.getInt("moves")
        assertTrue(snapshot.acceptedMoveCount >= priorMoves)
        NativeChineseChessEngine().use { engine ->
            assertEquals(RestoreResult.Restored, engine.restore(snapshot.engineState))
            repeat(snapshot.acceptedMoveCount - priorMoves) { assertTrue(engine.undo()) }
            assertArrayEquals(Base64.decode(receipt.getString("engineState"), Base64.NO_WRAP), engine.serialize())
        }
    }

    private fun saved(): GameSessionSnapshot = runBlocking {
        (RoomGameSessionRepository(database.activeGameDao()).load(GameType.CHINESE_CHESS) as LoadGameSessionResult.Loaded).snapshot
    }

    /** Hold an outer real SQLite transaction open after the production terminal writes. */
    private suspend fun atomicTransactionScenario() {
        val repository = RoomCompletedGameSessionRepository(database, ChineseChessEndgamePackParser.loadBundled(context.assets))
        if (phase == "prepare") {
            val state = NativeChineseChessEngine().use { it.serialize() }
            val initial = GameSessionSnapshot(GameType.CHINESE_CHESS, StoredGameMode.HUMAN_VS_AI,
                Difficulty.EASY, state, System.currentTimeMillis(), sessionId = "transaction-$runId")
            repository.save(initial)
            val terminal = terminalFixture(initial)
            database.withTransaction {
                repository.complete(terminal)
                // This receipt describes visible uncommitted rows, not durable success.
                writeReceipt(terminal.snapshot)
                holdForHostKill()
            }
        } else if (phase == "resume") {
            assertNull("Uncommitted terminal session survived SIGKILL", requireNotNull(beforeLaunch).resultOverride)
            assertTrue("Uncommitted record survived SIGKILL", database.gameRecordDao().listAll().isEmpty())
            assertTrue("Uncommitted award survived SIGKILL", database.matchOutcomeDao().listAll().isEmpty())
            repository.complete(terminalFixture(requireNotNull(beforeLaunch)))
            writeReceipt()
            holdForHostKill()
        } else {
            val old = requireNotNull(preceding)
            assertEquals(GameResult.SECOND_PLAYER_WIN, saved().resultOverride)
            repository.complete(terminalFixture(saved()))
            assertEquals(1, database.gameRecordDao().listAll().size)
            assertEquals(1, database.matchOutcomeDao().listAll().size)
            assertEquals(old.getLong("recordTime"), database.gameRecordDao().listAll().single().completedAtEpochMillis)
            assertEquals(old.getLong("outcomeTime"), database.matchOutcomeDao().listAll().single().settledAtEpochMillis)
            writeReceipt()
        }
    }

    private fun terminalFixture(snapshot: GameSessionSnapshot): CompletedGameSession {
        val terminal = snapshot.copy(resultOverride = GameResult.SECOND_PLAYER_WIN, turnStartedAtEpochMillis = null)
        val now = System.currentTimeMillis()
        return CompletedGameSession(terminal, GameRecord(snapshot.sessionId, snapshot.gameType, snapshot.mode,
            snapshot.difficulty, GameResult.SECOND_PLAYER_WIN, snapshot.engineState, snapshot.acceptedMoveCount,
            completedAtEpochMillis = now), MatchOutcome(snapshot.sessionId, snapshot.gameType, snapshot.mode,
            requireNotNull(snapshot.difficulty), 0, GameResult.SECOND_PLAYER_WIN, now))
    }

    private suspend fun writeReceipt(snapshot: GameSessionSnapshot = saved()) {
        val records = database.gameRecordDao().listAll()
        val outcomes = database.matchOutcomeDao().listAll()
        val receipt = JSONObject().put("runId", runId).put("scenario", scenario).put("phase", phase)
            .put("pid", Process.myPid()).put("sessionId", snapshot.sessionId).put("moves", snapshot.acceptedMoveCount)
            .put("engineState", encode(snapshot.engineState)).put("paused", snapshot.autoPlayPaused)
            .put("recordCount", records.size).put("outcomeCount", outcomes.size)
            .put("recordTime", records.singleOrNull()?.completedAtEpochMillis ?: 0L)
            .put("outcomeTime", outcomes.singleOrNull()?.settledAtEpochMillis ?: 0L)
        val destination = receiptFile(phase)
        val temporary = File(destination.parentFile, "${destination.name}.tmp")
        temporary.writeText(receipt.toString())
        check(temporary.renameTo(destination)) { "Cannot publish checkpoint receipt" }
    }

    private fun receiptFile(stage: String) = File(context.filesDir, "restart-$stage.json")
    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private suspend fun holdForHostKill(): Nothing {
        delay(90_000L)
        error("Host did not terminate this prepared process; no process-death evidence")
    }
}
