package com.masterofchessstrategy.ui

import android.content.pm.ActivityInfo
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.custom.ChineseChessHandicapConfig
import com.masterofchessstrategy.data.ActiveGameEntity
import com.masterofchessstrategy.data.AppSettings
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.data.RoomAppSettingsRepository
import com.masterofchessstrategy.data.RoomLocalDataBackupRepository
import com.masterofchessstrategy.data.StoredGameMode
import com.masterofchessstrategy.data.TutorialProgressEntity
import com.masterofchessstrategy.endgame.ChineseChessEndgamePackParser
import com.masterofchessstrategy.engine.BoardPosition
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.engine.NativeChineseChessEngine
import com.masterofchessstrategy.engine.RestoreResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChineseChessHandicapNavigationTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var database: MocsDatabase

    @Before fun prepare() = runBlocking {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            composeRule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        composeRule.waitForIdle()
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext, MocsDatabase::class.java,
        ).build()
        database.tutorialProgressDao().upsert(TutorialProgressEntity(
            gameTypeCode = GameType.CHINESE_CHESS.code, contentVersion = 1,
            completedStepCount = 4, isCompleted = true, updatedAtEpochMillis = 1L,
        ))
    }

    @After fun close() { database.close() }

    @Test fun everySinglePieceHandicapIsNativeLegalAndPreservesTheSelectedRemoval() {
        for (square in ChineseChessHandicapConfig.removableSquares) {
            val variant = ChineseChessHandicapConfig.sessionVariant(setOf(square), "0".repeat(32))
            NativeChineseChessEngine().use { engine ->
                assertTrue(engine.restore(ChineseChessHandicapConfig.initialState(variant)) is RestoreResult.Restored)
                assertEquals(GameResult.ONGOING, engine.gameResult())
                assertTrue(engine.legalActions().isNotEmpty())
                assertNull(engine.pieceAt(BoardPosition(square % 9, square / 9)))
                assertEquals(0, engine.currentPlayer.value)
            }
        }
    }

    @Test fun customRemovalsPlayUndoRecordWithoutRankAndNewGameDoesNotResumeOldResult() {
        composeRule.setContent { MasterOfChessStrategyApp(database) }
        composeRule.onNodeWithTag(HOME_CHINESE_CHESS_TAG).performClick()
        composeRule.onNodeWithTag(HANDICAP_ENTRY_TAG).performScrollTo().performClick()
        composeRule.onNodeWithTag(HANDICAP_START_TAG).performScrollTo().assertIsNotEnabled()
        tapSquare(BoardPosition(4, 0))
        composeRule.onNodeWithText("双方将帅必须保留。").assertExists()
        tapSquare(BoardPosition(0, 0))
        tapSquare(BoardPosition(0, 0)) // A removed square can restore the exact original piece.
        composeRule.onNodeWithTag(HANDICAP_START_TAG).assertIsNotEnabled()
        tapSquare(BoardPosition(1, 0))
        tapSquare(BoardPosition(1, 9))
        composeRule.onNodeWithText("已让子：红方 1 · 黑方 1").assertExists()
        screenshot("mocs-handicap-setup.png")
        composeRule.onNodeWithTag(HANDICAP_START_TAG).performScrollTo().performClick()
        waitForMoves(0)
        val start = requireNotNull(saved())
        assertEquals(StoredGameMode.HANDICAP.code, start.modeCode)
        assertEquals(setOf(1, 82), ChineseChessHandicapConfig.removedSquares(start.sessionVariantId))
        playNativeLegalMove()
        waitForMoves(2)
        composeRule.onNodeWithTag(UNDO_BUTTON_TAG).assertIsEnabled().performClick()
        waitForMoves(0)
        assertTrue(start.engineState.contentEquals(requireNotNull(saved()).engineState))
        composeRule.onNodeWithTag(RESIGN_BUTTON_TAG).performScrollTo().performClick()
        composeRule.onNodeWithText("确认认输").performClick()
        composeRule.waitUntil(10_000) { runBlocking { database.gameRecordDao().listAll().size == 1 } }
        assertTrue(runBlocking { database.matchOutcomeDao().listAll().isEmpty() })
        assertEquals(StoredGameMode.HANDICAP.code, runBlocking { database.gameRecordDao().listAll().single().modeCode })
        composeRule.onNodeWithTag(GAME_BACK_BUTTON_TAG).performClick()
        waitForTag(HANDICAP_CONTINUE_TAG)
        composeRule.onNodeWithTag(HANDICAP_CONTINUE_TAG).performScrollTo().performClick()
        composeRule.onNodeWithText("黑方胜").assertExists()
        assertEquals(start.sessionId, requireNotNull(saved()).sessionId)
        composeRule.onNodeWithTag(GAME_BACK_BUTTON_TAG).performClick()
        waitForTag(HANDICAP_CONTINUE_TAG)
        composeRule.onNodeWithTag(HANDICAP_START_TAG).performScrollTo().performClick()
        composeRule.waitUntil(10_000) { saved()?.sessionVariantId != start.sessionVariantId }
        waitForMoves(0)
        assertNotEquals(start.sessionId, requireNotNull(saved()).sessionId)
        assertTrue(start.engineState.contentEquals(requireNotNull(saved()).engineState))
        assertEquals(1, runBlocking { database.gameRecordDao().listAll().size })
        composeRule.onNodeWithTag(GAME_BACK_BUTTON_TAG).performClick()
    }

    @Test fun settingsEntrySupportsAiFirstAndBackupPreservesHandicapIdentity() {
        runBlocking { RoomAppSettingsRepository(database.appSettingsDao()).save(AppSettings.DEFAULT.copy(aiFirstEnabled = true)) }
        composeRule.setContent { MasterOfChessStrategyApp(database) }
        composeRule.onNodeWithTag(HOME_SETTINGS_TAG).performScrollTo().performClick()
        composeRule.onNodeWithTag(HANDICAP_ENTRY_TAG).performScrollTo().performClick()
        tapSquare(BoardPosition(0, 0))
        composeRule.onNodeWithTag(HANDICAP_START_TAG).performScrollTo().performClick()
        waitForMoves(1)
        composeRule.onNodeWithText("玩家执黑 · AI 执红").assertExists()
        val opening = requireNotNull(saved())
        assertEquals(1, opening.playerIndex)
        playNativeLegalMove()
        waitForMoves(3)
        composeRule.onNodeWithTag(UNDO_BUTTON_TAG).performClick()
        waitForMoves(1)
        assertTrue(opening.engineState.contentEquals(requireNotNull(saved()).engineState))
        val current = requireNotNull(saved())
        screenshot("mocs-handicap-game.png")
        verifyBackup(current)

        // Starting the setup from an active game's settings removes the old game VM.
        composeRule.onNodeWithTag(GAME_SETTINGS_BUTTON_TAG).performClick()
        composeRule.onNodeWithTag(SETTINGS_AI_FIRST_TAG).performScrollTo().performClick()
        composeRule.waitUntil(10_000) { runBlocking { database.appSettingsDao().find()?.aiFirstEnabled == false } }
        composeRule.onNodeWithTag(HANDICAP_ENTRY_TAG).performScrollTo().performClick()
        waitForTag(HANDICAP_CONTINUE_TAG)
        composeRule.onNodeWithTag(HANDICAP_CONTINUE_TAG).performScrollTo().performClick()
        waitForMoves(1)
        composeRule.onNodeWithText("玩家执黑 · AI 执红").assertExists()
        assertEquals(current.sessionId, requireNotNull(saved()).sessionId)
        assertEquals(current.sessionVariantId, requireNotNull(saved()).sessionVariantId)
        assertTrue(current.engineState.contentEquals(requireNotNull(saved()).engineState))
        composeRule.onNodeWithTag(GAME_BACK_BUTTON_TAG).performClick()
    }

    private fun verifyBackup(expected: ActiveGameEntity) = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pack = ChineseChessEndgamePackParser.loadBundled(context.assets)
        val validator: (ByteArray) -> Boolean = { bytes ->
            NativeChineseChessEngine().use { it.restore(bytes) is RestoreResult.Restored }
        }
        val bytes = RoomLocalDataBackupRepository(database, pack, validator).export(100L)
        val restored = Room.inMemoryDatabaseBuilder(context, MocsDatabase::class.java).build()
        try {
            RoomLocalDataBackupRepository(restored, pack, validator).restore(bytes)
            assertEquals(expected, restored.activeGameDao().find(GameType.CHINESE_CHESS.code))
            assertEquals(true, restored.appSettingsDao().find()?.aiFirstEnabled)
        } finally { restored.close() }
    }

    private fun playNativeLegalMove() {
        val move = NativeChineseChessEngine().use {
            assertTrue(it.restore(requireNotNull(saved()).engineState) is RestoreResult.Restored)
            it.legalActions().first()
        }
        tapSquare(move.from)
        tapSquare(move.to)
    }

    private fun saved(): ActiveGameEntity? = runBlocking { database.activeGameDao().find(GameType.CHINESE_CHESS.code) }
    private fun waitForMoves(count: Int) {
        composeRule.waitUntil(30_000) { saved()?.acceptedMoveCount == count }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(GAME_BACK_BUTTON_TAG).assertIsEnabled()
    }
    private fun waitForTag(tag: String) {
        composeRule.waitUntil(10_000) { composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun tapSquare(position: BoardPosition) {
        val board = composeRule.onNodeWithTag(CHINESE_CHESS_BOARD_TAG)
        val node = board.fetchSemanticsNode()
        val size = Size(node.boundsInRoot.width, node.boundsInRoot.height)
        val geometry = calculateBoardGeometry(size.width, size.height).copy(reversed = node.config[BoardReversedKey])
        board.performTouchInput { click(node.config[BoardViewportKey].screenPoint(geometry.center(position), size)) }
    }
    private fun screenshot(name: String) {
        ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "screencap -p /data/local/tmp/$name",
        )).use { it.readBytes() }
    }
}
