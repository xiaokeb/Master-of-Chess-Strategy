package com.masterofchessstrategy.games.xiangqi

import android.os.SystemClock
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.data.CompletedGameCommitResult
import com.masterofchessstrategy.MainActivity
import com.masterofchessstrategy.R
import com.masterofchessstrategy.data.CompletedGameSession
import com.masterofchessstrategy.data.GameRecord
import com.masterofchessstrategy.data.GameSessionSnapshot
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.data.RoomCompletedGameSessionRepository
import com.masterofchessstrategy.data.RoomEndgameProgressRepository
import com.masterofchessstrategy.data.StoredGameMode
import com.masterofchessstrategy.endgame.ChineseChessEndgameLevel
import com.masterofchessstrategy.endgame.ChineseChessEndgamePackParser
import com.masterofchessstrategy.endgame.ChineseChessEndgameViewModel
import com.masterofchessstrategy.endgame.EndgameCatalogFeedback
import com.masterofchessstrategy.engine.ActionResult
import com.masterofchessstrategy.engine.BoardMove
import com.masterofchessstrategy.engine.ChineseChessSide
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.engine.NativeChineseChessEngine
import com.masterofchessstrategy.engine.RestoreResult
import com.masterofchessstrategy.game.ChineseChessFeedback
import com.masterofchessstrategy.game.ChineseChessGameUiState
import com.masterofchessstrategy.game.ChineseChessGameViewModel
import com.masterofchessstrategy.game.PikafishNetworkProvider
import com.masterofchessstrategy.ui.CHINESE_CHESS_BOARD_TAG
import com.masterofchessstrategy.ui.ChineseChessEndgameScreen
import com.masterofchessstrategy.ui.ChineseChessGameScreen
import com.masterofchessstrategy.ui.calculateBoardGeometry
import java.io.Closeable
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith

/** Live ViewModel input and Pikafish replies, using only UUID-owned Room/cache storage. */
@RunWith(AndroidJUnit4::class)
class XiangqiEndgameGameFlowInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val pack by lazy { ChineseChessEndgamePackParser.loadBundled(context.assets) }

    @Test
    fun addedLevelsRenderAndWinThroughRealBoardTouchesInLandscape() = runBlocking {
        assertEquals(35, pack.levels.size)
        assertEquals(19, NEW_IDS.size)
        var visibleGame by mutableStateOf<ChineseChessGameViewModel?>(null)
        val directory = File(context.getExternalFilesDir(null), "xiangqi-endgame-visual-${UUID.randomUUID()}")
        assertTrue(directory.mkdirs())
        // Production landscape host and screen, but UUID-owned storage: not a navigation acceptance test.
        instrumentation.runOnMainSync {
            compose.activity.setContent {
                visibleGame?.let { game ->
                    ChineseChessGameScreen(
                        state = game.uiState, onSquareTap = game::onSquareTap,
                        onUndo = {}, onHint = {}, onResign = {}, onRestart = {}, onBack = {},
                        modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
                    )
                }
            }
        }
        try {
            Fixture().use { fixture ->
                NEW_IDS.forEach { id ->
                    val selected = level(id)
                    fixture.game(selected) {}.use { handle ->
                        val game = handle.model
                        awaitGame(game) { it.isInteractionEnabled }
                        instrumentation.runOnMainSync {
                            assertFalse("A valid level switch is not a damaged save",
                                game.uiState.feedback == ChineseChessFeedback.RESTORE_REJECTED)
                        }
                        instrumentation.runOnMainSync { visibleGame = game }
                        compose.onNodeWithText(context.getString(R.string.endgame_game_title, selected.title))
                            .assertIsDisplayed()
                        compose.onNodeWithTag(CHINESE_CHESS_BOARD_TAG).assertIsDisplayed()
                        compose.onNodeWithText(context.getString(R.string.endgame_move_goal, selected.maxPlayerMoves, 0))
                            .performScrollTo().assertIsDisplayed()
                        capture(directory, "$id-initial")
                        playToWin(fixture, game, selected) { move ->
                            val board = compose.onNodeWithTag(CHINESE_CHESS_BOARD_TAG)
                            val bounds = board.fetchSemanticsNode().boundsInRoot
                            val geometry = calculateBoardGeometry(bounds.width, bounds.height)
                            board.performTouchInput { click(geometry.center(move.from)) }
                            instrumentation.runOnMainSync {
                                assertEquals(move.from, game.uiState.selectedPosition)
                                assertTrue(move.to in game.uiState.legalDestinations)
                            }
                            board.performTouchInput { click(geometry.center(move.to)) }
                        }
                        awaitGame(game) { it.result == GameResult.FIRST_PLAYER_WIN && !it.hasUncommittedResult }
                        compose.onNodeWithText(context.getString(R.string.red_wins)).assertIsDisplayed()
                        compose.onNodeWithText(context.getString(R.string.endgame_move_goal,
                            selected.maxPlayerMoves, selected.maxPlayerMoves)).performScrollTo().assertIsDisplayed()
                        capture(directory, "$id-won")
                        assertEquals(selected.maxPlayerMoves,
                            fixture.database.endgameProgressDao().listAll().single { it.levelId == id }.bestPlayerMoves)
                        instrumentation.runOnMainSync { visibleGame = null }
                        compose.waitForIdle()
                    }
                }
            }
        } finally {
            instrumentation.runOnMainSync { visibleGame = null }
            compose.waitForIdle()
        }
        println("Xiangqi endgame visual evidence: ${directory.absolutePath}")
    }

    private fun capture(directory: File, name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        try {
            assertTrue("Production host must remain landscape", bitmap.width > bitmap.height)
            File(directory, "$name.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }

    @Test
    fun addedLevelsCommitThroughGameCallbackAndUnlockOnlyTheirNextLevel() = runBlocking {
        Fixture().use { fixture ->
            // Prerequisite fixtures use real Native wins, not inserted reward rows.
            val coveredLevels = pack.levels.filter {
                it.difficulty in setOf(Difficulty.EASY, Difficulty.MEDIUM)
            }
            assertEquals(31, coveredLevels.size)
            assertEquals(19, NEW_IDS.size)
            val prerequisites = coveredLevels.filter { it.id !in NEW_IDS }
            assertEquals(12, prerequisites.size)
            prerequisites.forEach { fixture.repository.complete(terminal(it)) }
            fixture.catalog().use { catalog ->
                awaitCatalog(catalog.model, prerequisites.size)
                NEW_IDS.forEachIndexed { index, id ->
                    val selected = level(id)
                    instrumentation.runOnMainSync {
                        assertTrue(id, catalog.model.uiState.entries.single { it.level.id == id }.isUnlocked)
                        NEW_IDS.drop(index + 1).filter { level(it).difficulty == selected.difficulty }.forEach { later ->
                            assertFalse(later, catalog.model.uiState.entries.single { it.level.id == later }.isUnlocked)
                        }
                    }
                    val commits = mutableListOf<CompletedGameCommitResult>()
                    fixture.game(selected) { result ->
                        commits += result
                        catalog.model.refreshAfterCommit(result.endgame)
                    }.use { game ->
                        playToWin(fixture, game.model, selected)
                        awaitGame(game.model) { it.result == GameResult.FIRST_PLAYER_WIN && !it.hasUncommittedResult }
                        awaitCatalog(catalog.model, prerequisites.size + index + 1)
                        instrumentation.runOnMainSync {
                            assertEquals(1, commits.size)
                            val reward = requireNotNull(commits.single().endgame)
                            assertTrue(reward.firstCompletion)
                            assertEquals(selected.starReward, reward.awardedStars)
                            assertEquals(selected.scoreReward, reward.awardedScore)
                            assertEquals(selected.maxPlayerMoves, game.model.uiState.endgamePlayerMovesUsed)
                            assertTrue(catalog.model.uiState.entries.single { it.level.id == id }.isCompleted)
                        }
                        val record = fixture.database.gameRecordDao().listAll().single {
                            it.recordId == fixture.database.activeGameDao().find(GameType.CHINESE_CHESS.code)!!.sessionId
                        }
                        assertEquals(selected.principalVariation.size, record.moveCount)
                        NativeChineseChessEngine().use { engine ->
                            assertEquals(RestoreResult.Restored, engine.restore(record.engineState))
                            assertEquals(GameResult.FIRST_PLAYER_WIN, engine.gameResult())
                        }
                        // The public retry action is inert after a successful commit.
                        instrumentation.runOnMainSync { game.model.retryTerminalSave() }
                        instrumentation.waitForIdleSync()
                        instrumentation.runOnMainSync { assertEquals(1, commits.size) }
                    }
                }
                assertEquals(31, fixture.database.endgameProgressDao().listAll().size)
                assertEquals(coveredLevels.size, fixture.database.endgameProgressDao().listAll().size)
                assertTrue(fixture.database.matchOutcomeDao().listAll().isEmpty())
            }
        }
    }

    @Test
    fun failedGameCommitKeepsCheckpointThenRetryAndRecreationNeverDuplicateRewards() = runBlocking {
        Fixture().use { fixture ->
            val selected = level("xq-medium-012")
            val commits = mutableListOf<CompletedGameCommitResult>()
            fixture.catalog().use { catalog ->
                awaitCatalog(catalog.model, 0)
                fixture.game(selected) { result ->
                    commits += result
                    catalog.model.refreshAfterCommit(result.endgame)
                }.use { game ->
                    awaitGame(game.model) { it.isInteractionEnabled }
                    fixture.database.openHelper.writableDatabase.execSQL(
                        "CREATE TRIGGER reject_game_reward BEFORE INSERT ON endgame_progress " +
                            "BEGIN SELECT RAISE(ABORT, 'injected game reward failure'); END",
                    )
                    val checkpoint = playToWin(fixture, game.model, selected)
                    awaitGame(game.model) { it.hasUncommittedResult && it.feedback == ChineseChessFeedback.SAVE_FAILED }
                    instrumentation.runOnMainSync {
                        assertEquals(GameResult.FIRST_PLAYER_WIN, game.model.uiState.result)
                        assertFalse(game.model.uiState.isInteractionEnabled)
                        assertEquals(0, commits.size)
                        assertEquals(0, catalog.model.uiState.completedCount)
                    }
                    assertEquals(checkpoint, fixture.database.activeGameDao().find(GameType.CHINESE_CHESS.code))
                    assertTrue(fixture.database.gameRecordDao().listAll().isEmpty())
                    assertTrue(fixture.database.endgameProgressDao().listAll().isEmpty())
                    fixture.database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_game_reward")
                    instrumentation.runOnMainSync { game.model.retryTerminalSave() }
                    awaitGame(game.model) { !it.hasUncommittedResult && it.result == GameResult.FIRST_PLAYER_WIN }
                    awaitCatalog(catalog.model, 1)
                    instrumentation.runOnMainSync {
                        assertEquals(1, commits.size)
                        assertTrue(requireNotNull(commits.single().endgame).firstCompletion)
                    }
                }
                val records = fixture.database.gameRecordDao().listAll()
                val rewards = fixture.database.endgameProgressDao().listAll()
                fixture.game(selected) { commits += it }.use { restored ->
                    awaitGame(restored.model) { it.result == GameResult.FIRST_PLAYER_WIN && !it.hasUncommittedResult }
                    instrumentation.runOnMainSync {
                        assertEquals(2, commits.size)
                        val repeated = requireNotNull(commits.last().endgame)
                        assertFalse(repeated.firstCompletion)
                        assertEquals(0, repeated.awardedStars)
                        assertEquals(0, repeated.awardedScore)
                    }
                }
                assertEquals(records, fixture.database.gameRecordDao().listAll())
                assertEquals(rewards, fixture.database.endgameProgressDao().listAll())
                assertEquals(1, records.size)
                assertEquals(1, rewards.size)
            }
        }
    }

    @Test
    fun catalogShowsFirstAndRepeatCompletionWithoutDuplicatingRewards() = runBlocking {
        var visibleCatalog by mutableStateOf<ChineseChessEndgameViewModel?>(null)
        val directory = File(context.getExternalFilesDir(null), "xiangqi-endgame-reward-${UUID.randomUUID()}")
        assertTrue(directory.mkdirs())
        instrumentation.runOnMainSync {
            compose.activity.setContent {
                visibleCatalog?.let { catalog ->
                    ChineseChessEndgameScreen(
                        state = catalog.uiState,
                        onBack = {},
                        onModeSelected = catalog::selectMode,
                        onDifficultySelected = catalog::selectDifficulty,
                        onThemeSelected = catalog::selectTheme,
                        onRandomChallenge = catalog::randomChallenge,
                        onLevelSelected = {},
                        modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
                    )
                }
            }
        }
        try {
            Fixture().use { fixture ->
                fixture.catalog().use { catalog ->
                    val selected = level("xq-easy-001")
                    awaitCatalog(catalog.model, 0)
                    instrumentation.runOnMainSync { visibleCatalog = catalog.model }

                    fixture.game(selected) { result ->
                        catalog.model.refreshAfterCommit(result.endgame)
                    }.use { game ->
                        playToWin(fixture, game.model, selected)
                        awaitGame(game.model) {
                            it.result == GameResult.FIRST_PLAYER_WIN && !it.hasUncommittedResult
                        }
                    }
                    await {
                        catalog.model.uiState.completedCount == 1 &&
                            catalog.model.uiState.feedback == EndgameCatalogFeedback.FIRST_COMPLETION
                    }
                    compose.onNodeWithText(context.getString(R.string.endgame_first_completion))
                        .performScrollTo().assertIsDisplayed()
                    compose.onNodeWithText(context.getString(
                        R.string.endgame_progress_summary,
                        1,
                        pack.levels.size,
                        selected.starReward,
                        selected.scoreReward,
                    )).performScrollTo().assertIsDisplayed()
                    capture(directory, "first-completion")

                    // Restoring the committed terminal game exercises the production idempotent callback.
                    fixture.game(selected) { result ->
                        catalog.model.refreshAfterCommit(result.endgame)
                    }.use { repeated ->
                        awaitGame(repeated.model) {
                            it.result == GameResult.FIRST_PLAYER_WIN && !it.hasUncommittedResult
                        }
                    }
                    await {
                        catalog.model.uiState.completedCount == 1 &&
                            catalog.model.uiState.feedback == EndgameCatalogFeedback.COMPLETED_AGAIN
                    }
                    compose.onNodeWithText(context.getString(R.string.endgame_completed_again))
                        .performScrollTo().assertIsDisplayed()
                    compose.onNodeWithText(context.getString(
                        R.string.endgame_progress_summary,
                        1,
                        pack.levels.size,
                        selected.starReward,
                        selected.scoreReward,
                    )).performScrollTo().assertIsDisplayed()
                    assertEquals(1, fixture.database.endgameProgressDao().listAll().size)
                    capture(directory, "repeat-completion")
                }
            }
        } finally {
            instrumentation.runOnMainSync { visibleCatalog = null }
            compose.waitForIdle()
        }
        println("Xiangqi endgame reward visual evidence: ${directory.absolutePath}")
    }

    /** Wait for the real AI reply; derive the finisher from that reply, not the sample line. */
    private suspend fun playToWin(fixture: Fixture, game: ChineseChessGameViewModel, selected: ChineseChessEndgameLevel,
        input: (BoardMove) -> Unit = { tap(game, it) }):
        com.masterofchessstrategy.data.ActiveGameEntity {
        awaitGame(game) { it.isInteractionEnabled && it.endgamePlayerMovesUsed == 0 }
        var checkpoint = requireNotNull(fixture.database.activeGameDao().find(GameType.CHINESE_CHESS.code))
        input(selected.principalVariation.first())
        if (selected.maxPlayerMoves == 2) {
            awaitGame(game) {
                it.isInteractionEnabled && it.currentSide == ChineseChessSide.RED && it.endgamePlayerMovesUsed == 1
            }
            checkpoint = requireNotNull(fixture.database.activeGameDao().find(GameType.CHINESE_CHESS.code))
            assertEquals(2, checkpoint.acceptedMoveCount)
            val finisher = NativeChineseChessEngine().use { engine ->
                assertEquals(RestoreResult.Restored, engine.restore(checkpoint.engineState))
                assertEquals(GameResult.ONGOING, engine.gameResult())
                val wins = engine.legalActions().filter { move ->
                    assertEquals(ActionResult.Accepted, engine.apply(move))
                    val won = engine.gameResult() == GameResult.FIRST_PLAYER_WIN
                    assertTrue(engine.undo())
                    won
                }
                // Only the initial forced-win move is unique; later wins need not be.
                assertTrue("${selected.id}: actual AI reply must still allow a win", wins.isNotEmpty())
                wins.first()
            }
            input(finisher)
        }
        return checkpoint
    }

    private fun tap(game: ChineseChessGameViewModel, move: BoardMove) = instrumentation.runOnMainSync {
        assertTrue(game.uiState.isInteractionEnabled)
        game.onSquareTap(move.from)
        assertTrue(move.to in game.uiState.legalDestinations)
        game.onSquareTap(move.to)
    }

    private fun awaitGame(game: ChineseChessGameViewModel, condition: (ChineseChessGameUiState) -> Boolean) = await {
        val state = game.uiState
        assertTrue("Native engine must remain available", state.isEngineAvailable)
        assertFalse("Pikafish must not fall back or fail", state.feedback == ChineseChessFeedback.AI_MOVE_FAILED)
        !state.isRestoring && !state.isPersisting && !state.isAiThinking && condition(state)
    }

    private fun awaitCatalog(catalog: ChineseChessEndgameViewModel, count: Int) = await {
        !catalog.uiState.isLoading && catalog.uiState.completedCount == count
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 30_000
        while (true) {
            var ready = false
            instrumentation.runOnMainSync { ready = condition() }
            if (ready) return
            check(SystemClock.uptimeMillis() < deadline) { "Live endgame flow did not reach the expected checkpoint" }
            SystemClock.sleep(20)
        }
    }

    private fun level(id: String) = pack.levels.single { it.id == id }

    private fun terminal(selected: ChineseChessEndgameLevel): CompletedGameSession {
        val bytes = NativeChineseChessEngine().use { engine ->
            assertEquals(RestoreResult.Restored, engine.restore(selected.initialEngineState))
            selected.principalVariation.forEach { assertEquals(ActionResult.Accepted, engine.apply(it)) }
            assertEquals(GameResult.FIRST_PLAYER_WIN, engine.gameResult())
            engine.serialize()
        }
        val snapshot = GameSessionSnapshot(
            gameType = GameType.CHINESE_CHESS, mode = StoredGameMode.ENDGAME, difficulty = selected.difficulty,
            engineState = bytes, updatedAtEpochMillis = 1_000, sessionId = UUID.randomUUID().toString(),
            acceptedMoveCount = selected.principalVariation.size, resultOverride = GameResult.FIRST_PLAYER_WIN,
            sessionVariantId = pack.sessionVariantId(selected.id),
        )
        val record = GameRecord(
            recordId = snapshot.sessionId, gameType = snapshot.gameType, mode = snapshot.mode,
            difficulty = snapshot.difficulty, result = GameResult.FIRST_PLAYER_WIN, engineState = bytes.copyOf(),
            moveCount = snapshot.acceptedMoveCount, isEndgame = true, completedAtEpochMillis = 1_000,
        )
        return CompletedGameSession(snapshot, record, null)
    }

    private inner class Fixture : Closeable {
        private val suffix = UUID.randomUUID().toString()
        private val databaseName = "xiangqi-live-endgame-$suffix.db"
        private val networkDirectory = File(context.cacheDir, "xiangqi-live-endgame-$suffix")
        private val provider = PikafishNetworkProvider(
            targetDirectory = networkDirectory,
            openNetworkAsset = { context.assets.open("pikafish/pikafish.nnue") },
        )
        private val networkFile = File(provider.requireNetworkPath())
        val database = Room.databaseBuilder(context, MocsDatabase::class.java, databaseName).build()
        val repository = RoomCompletedGameSessionRepository(database, pack)

        fun catalog(): CatalogHandle {
            val store = ViewModelStore()
            lateinit var model: ChineseChessEndgameViewModel
            instrumentation.runOnMainSync {
                model = ViewModelProvider(store, viewModelFactory { initializer {
                    ChineseChessEndgameViewModel(pack, RoomEndgameProgressRepository(database.endgameProgressDao(), pack))
                } })[ChineseChessEndgameViewModel::class.java]
            }
            return CatalogHandle(model, store)
        }

        fun game(selected: ChineseChessEndgameLevel, committed: (CompletedGameCommitResult) -> Unit): GameHandle {
            val store = ViewModelStore()
            lateinit var model: ChineseChessGameViewModel
            instrumentation.runOnMainSync {
                model = ViewModelProvider(store, viewModelFactory { initializer {
                    ChineseChessGameViewModel(
                        sessionRepository = repository, mode = StoredGameMode.ENDGAME, difficulty = selected.difficulty,
                        clockTickIntervalMillis = null, initialPositionState = selected.initialEngineState,
                        sessionVariantId = pack.sessionVariantId(selected.id), endgameTitle = selected.title,
                        endgameMaxPlayerMoves = selected.maxPlayerMoves, onCompletionCommitted = committed,
                        engineFactory = { NativeChineseChessEngine(provider::requireNetworkPath) },
                    )
                } })[ChineseChessGameViewModel::class.java]
            }
            return GameHandle(model, store)
        }

        override fun close() {
            database.close()
            context.deleteDatabase(databaseName)
            // Delete only this fixture's verified file and empty UUID directory.
            check(networkFile.parentFile?.canonicalFile == networkDirectory.canonicalFile)
            check(networkFile.delete())
            check(networkDirectory.delete())
        }
    }

    private inner class GameHandle(val model: ChineseChessGameViewModel, private val store: ViewModelStore) : Closeable {
        override fun close() = instrumentation.runOnMainSync { store.clear() }
    }

    private inner class CatalogHandle(val model: ChineseChessEndgameViewModel, private val store: ViewModelStore) : Closeable {
        override fun close() = instrumentation.runOnMainSync { store.clear() }
    }

    private companion object {
        // All nineteen additions since the published sixteen-level seed pack.
        val NEW_IDS = (11..18).map { "xq-easy-${it.toString().padStart(3, '0')}" } +
            (3..13).map { "xq-medium-${it.toString().padStart(3, '0')}" }
    }
}
