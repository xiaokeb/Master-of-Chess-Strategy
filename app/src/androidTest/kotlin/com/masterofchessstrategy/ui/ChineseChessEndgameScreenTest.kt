package com.masterofchessstrategy.ui

import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.R
import com.masterofchessstrategy.endgame.ChineseChessEndgameLevel
import com.masterofchessstrategy.endgame.ChineseChessEndgamePackParser
import com.masterofchessstrategy.endgame.ChineseChessEndgameTrack
import com.masterofchessstrategy.endgame.ChineseChessEndgameUiState
import com.masterofchessstrategy.endgame.EndgameLevelEntry
import com.masterofchessstrategy.engine.BoardMove
import com.masterofchessstrategy.engine.BoardPosition
import com.masterofchessstrategy.engine.ChineseChessSide
import com.masterofchessstrategy.engine.ChineseChessPiece
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.game.ChineseChessGameUiState
import org.junit.Rule
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChineseChessEndgameScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun bundledTwoMoveLevelDisplaysItsGoalAndOpensTheCorrectLevel() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val level = ChineseChessEndgamePackParser.loadBundled(context.assets).levels
            .single { it.id == "xq-easy-009" }
        val entry = EndgameLevelEntry(level, true, true, null)
        var selected: String? = null
        composeRule.setContent {
            ChineseChessEndgameScreen(
                state = ChineseChessEndgameUiState(
                    entries = listOf(entry), visibleEntries = listOf(entry),
                    themes = listOf(level.theme), isLoading = false,
                ),
                onBack = {}, onModeSelected = {}, onDifficultySelected = {},
                onThemeSelected = {}, onRandomChallenge = {},
                onLevelSelected = { selected = it },
            )
        }
        composeRule.onNodeWithText("两步强制胜", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText(
            context.getString(R.string.endgame_goal, 2, 1, 15), useUnmergedTree = true,
        ).assertIsDisplayed()
        composeRule.onNodeWithTag(ENDGAME_LEVEL_PREFIX + level.id).performClick()
        composeRule.runOnIdle { assertEquals(level.id, selected) }
    }

    @Test
    fun newMediumMixedPieceLevelIsVisibleAndSelectable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val level = ChineseChessEndgamePackParser.loadBundled(context.assets).levels
            .single { it.id == "xq-medium-004" }
        val entry = EndgameLevelEntry(level, true, true, null)
        var selected: String? = null
        composeRule.setContent {
            ChineseChessEndgameScreen(
                state = ChineseChessEndgameUiState(
                    selectedDifficulty = Difficulty.MEDIUM,
                    entries = listOf(entry), visibleEntries = listOf(entry),
                    themes = listOf(level.theme), isLoading = false,
                ),
                onBack = {}, onModeSelected = {}, onDifficultySelected = {},
                onThemeSelected = {}, onRandomChallenge = {},
                onLevelSelected = { selected = it },
            )
        }
        composeRule.onNodeWithText(
            "马跃宫口", substring = true, useUnmergedTree = true,
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            context.getString(R.string.endgame_goal, 2, 1, 20), useUnmergedTree = true,
        ).assertIsDisplayed()
        composeRule.waitForIdle()
        ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                "screencap -p /data/local/tmp/mocs-xiangqi-medium-004.png",
            ),
        ).use { it.readBytes() }
        composeRule.onNodeWithTag(ENDGAME_LEVEL_PREFIX + level.id).performClick()
        composeRule.runOnIdle { assertEquals(level.id, selected) }
    }

    @Test
    fun newMediumMixedPiecePositionRendersOnTheGameBoard() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val level = ChineseChessEndgamePackParser.loadBundled(context.assets).levels
            .single { it.id == "xq-medium-004" }
        val board = MutableList<ChineseChessPiece?>(90) { null }
        level.pieces.forEach { positioned ->
            board[positioned.position.y * 9 + positioned.position.x] = positioned.piece
        }
        composeRule.setContent {
            ChineseChessGameScreen(
                state = ChineseChessGameUiState(
                    board = board, isEndgame = true, endgameTitle = level.title,
                    endgameMaxPlayerMoves = level.maxPlayerMoves,
                ),
                onSquareTap = {}, onUndo = {}, onHint = {}, onResign = {},
                onRestart = {}, onBack = {},
            )
        }
        composeRule.onNodeWithText(
            context.getString(R.string.endgame_game_title, level.title),
        ).assertIsDisplayed()
        composeRule.onNodeWithTag(CHINESE_CHESS_BOARD_TAG).assertIsDisplayed()
        composeRule.waitForIdle()
        ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                "screencap -p /data/local/tmp/mocs-xiangqi-medium-004-board.png",
            ),
        ).use { it.readBytes() }
    }

    @Test
    fun classicTwoMoveLevelShowsItsGoalAndPlayableBoard() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val level = ChineseChessEndgamePackParser.loadBundled(context.assets).levels
            .single { it.id == "xq-medium-007" }
        val board = MutableList<ChineseChessPiece?>(90) { null }
        level.pieces.forEach { positioned ->
            board[positioned.position.y * 9 + positioned.position.x] = positioned.piece
        }
        composeRule.setContent {
            ChineseChessGameScreen(
                state = ChineseChessGameUiState(
                    board = board, isEndgame = true, endgameTitle = level.title,
                    endgameMaxPlayerMoves = level.maxPlayerMoves,
                ),
                onSquareTap = {}, onUndo = {}, onHint = {}, onResign = {},
                onRestart = {}, onBack = {},
            )
        }
        composeRule.onNodeWithText(context.getString(R.string.endgame_game_title, "中车夺士"))
            .assertIsDisplayed()
        composeRule.onNodeWithTag(CHINESE_CHESS_BOARD_TAG).assertIsDisplayed()
        composeRule.waitForIdle()
        ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(
                "screencap -p /data/local/tmp/mocs-xiangqi-medium-007-board.png",
            ),
        ).use { it.readBytes() }
    }

    @Test
    fun newHorseCannonTwoMoveLevelRendersItsPlayableBoard() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val level = ChineseChessEndgamePackParser.loadBundled(context.assets).levels
            .single { it.id == "xq-medium-009" }
        val board = MutableList<ChineseChessPiece?>(90) { null }
        level.pieces.forEach { positioned ->
            board[positioned.position.y * 9 + positioned.position.x] = positioned.piece
        }
        composeRule.setContent {
            ChineseChessGameScreen(
                state = ChineseChessGameUiState(
                    board = board, isEndgame = true, endgameTitle = level.title,
                    endgameMaxPlayerMoves = level.maxPlayerMoves,
                ),
                onSquareTap = {}, onUndo = {}, onHint = {}, onResign = {},
                onRestart = {}, onBack = {},
            )
        }
        composeRule.onNodeWithText(context.getString(R.string.endgame_game_title, level.title))
            .assertIsDisplayed()
        composeRule.onNodeWithTag(CHINESE_CHESS_BOARD_TAG).assertIsDisplayed()
        composeRule.waitForIdle()
        ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(
                "screencap -p /data/local/tmp/mocs-xiangqi-medium-009-board.png",
            ),
        ).use { it.readBytes() }
    }

    @Test
    fun lockedBonusLevelExplainsItsSeparateUnlockPath() {
        val level = ChineseChessEndgameLevel(
            id = "xq-easy-003",
            difficulty = Difficulty.EASY,
            chapterOrder = 3,
            title = "奖励验收",
            theme = "唯一一步杀",
            sideToMove = ChineseChessSide.RED,
            maxPlayerMoves = 1,
            starReward = 1,
            scoreReward = 10,
            pieces = emptyList(),
            principalVariation = listOf(
                BoardMove(BoardPosition(0, 2), BoardPosition(4, 2)),
            ),
            track = ChineseChessEndgameTrack.BONUS,
        )
        val entry = EndgameLevelEntry(
            level = level,
            isChapterUnlocked = true,
            isUnlocked = false,
            completion = null,
        )

        composeRule.setContent {
            ChineseChessEndgameScreen(
                state = ChineseChessEndgameUiState(
                    entries = listOf(entry),
                    visibleEntries = listOf(entry),
                    themes = listOf(level.theme),
                    isLoading = false,
                ),
                onBack = {},
                onModeSelected = {},
                onDifficultySelected = {},
                onThemeSelected = {},
                onRandomChallenge = {},
                onLevelSelected = {},
            )
        }

        composeRule.onNodeWithText("奖励支线", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText(
            "完成本档主线和上一奖励关后解锁",
            useUnmergedTree = true,
        ).assertIsDisplayed()
    }
}
